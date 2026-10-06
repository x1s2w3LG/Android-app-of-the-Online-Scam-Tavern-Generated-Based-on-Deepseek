package com.liarsbar.core.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简 HTTP + WebSocket 同端口服务器。
 *
 * <p>普通请求按静态资源返回；带 {@code Upgrade: websocket} 的请求在同一条连接上升级为
 * WebSocket。关键点：HTTP 头是**逐字节**读到 {@code \r\n\r\n} 为止的，
 * 不会把紧随其后的 WebSocket 帧一起吞掉。</p>
 */
public final class HttpWsServer {

    /**
     * 玩家头像来源（由业务层实现）。头像不进 state 广播，前端按需拉取
     * {@code /avatar/<playerId>}，所以这里单独开一个口子。
     */
    public interface AvatarSource {
        byte[] avatar(String playerId);

        String avatarMime(String playerId);
    }

    private static final int MAX_HEAD = 16 * 1024;

    private final int requestedPort;
    private final StaticProvider provider;
    private final WebSocketConn.Handler wsHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger connSeq = new AtomicInteger(1);
    private volatile AvatarSource avatarSource;

    private ServerSocket serverSocket;
    /** 监听地址：null/空/0.0.0.0 = 所有网卡。 */
    private String bindAddress;
    private Thread acceptThread;

    public HttpWsServer(int port, StaticProvider provider, WebSocketConn.Handler wsHandler) {
        this.requestedPort = port;
        this.provider = provider;
        this.wsHandler = wsHandler;
    }

    public void setAvatarSource(AvatarSource source) {
        this.avatarSource = source;
    }

    public int getPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    /** 指定只监听哪块网卡（默认所有网卡）。必须在 {@link #start()} 之前调用。 */
    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    /** 绑定端口并开始接受连接；会从 requestedPort 起最多尝试 20 个端口。 */
    public void start() throws IOException {
        IOException last = null;
        for (int i = 0; i < 20; i++) {
            int p = requestedPort + i;
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                /* bindAddress 为空 = 监听所有网卡（0.0.0.0），也可以指定只监听某块网卡 */
                java.net.InetAddress addr = (bindAddress == null || bindAddress.isEmpty()
                        || "0.0.0.0".equals(bindAddress) || "*".equals(bindAddress))
                        ? null : java.net.InetAddress.getByName(bindAddress);
                serverSocket.bind(new InetSocketAddress(addr, p), 64);
                break;
            } catch (IOException e) {
                last = e;
                serverSocket = null;
            }
        }
        if (serverSocket == null) {
            throw last != null ? last : new IOException("无法绑定端口");
        }
        running.set(true);
        acceptThread = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "liarbar-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        handle(socket);
                    }
                }, "liarbar-conn-" + connSeq.getAndIncrement());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) {
                    // 单次 accept 失败不应终止整个服务器
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void handle(Socket socket) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = socket.getInputStream();
            out = socket.getOutputStream();
            Head head = readHead(in);
            if (head == null) {
                closeQuietly(socket);
                return;
            }
            String upgrade = head.headers.get("upgrade");
            if (upgrade != null && upgrade.toLowerCase().contains("websocket")) {
                try {
                    WebSocketConn conn = WebSocketConn.handshake(socket, in, out, head.headers,
                            wsHandler, "c" + connSeq.getAndIncrement());
                    if (wsHandler != null) {
                        wsHandler.onOpen(conn);
                    }
                } catch (IOException e) {
                    closeQuietly(socket);
                }
                return; // 连接已交给 WebSocketConn 管理
            }
            serveStatic(socket, out, head);
        } catch (IOException e) {
            closeQuietly(socket);
        } finally {
            // WebSocket 分支由 WebSocketConn 自己关闭；静态分支在上面已关
        }
    }

    // ------------------------------------------------------------ 静态资源

    private void serveStatic(Socket socket, OutputStream out, Head head) throws IOException {
        String method = head.method;
        String path = head.path;
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            writeResponse(out, 405, "text/plain; charset=utf-8",
                    "Method Not Allowed".getBytes("UTF-8"), "GET, HEAD");
            closeQuietly(socket);
            return;
        }

        String rel = normalize(path);
        if (rel == null) {
            writeResponse(out, 403, "text/plain; charset=utf-8",
                    "Forbidden".getBytes("UTF-8"), null);
            closeQuietly(socket);
            return;
        }

        /* 玩家头像：/avatar/<playerId>（URL 里带 ?v=N，所以可以长缓存） */
        if (rel.startsWith("avatar/")) {
            String pid = rel.substring("avatar/".length());
            AvatarSource src = avatarSource;
            byte[] av = (src == null || pid.isEmpty()) ? null : src.avatar(pid);
            if (av == null || av.length == 0) {
                writeResponse(out, 404, "text/plain; charset=utf-8",
                        "no avatar".getBytes("UTF-8"), null);
            } else {
                String mime = src.avatarMime(pid);
                writeResponse(out, 200,
                        (mime == null || mime.isEmpty()) ? "image/jpeg" : mime,
                        "HEAD".equals(method) ? new byte[0] : av, null, true);
            }
            closeQuietly(socket);
            return;
        }

        byte[] body = provider == null ? null : provider.read(rel);
        if (body == null && (rel.isEmpty() || "index.html".equals(rel) || "gamepage".equals(rel))) {
            body = provider == null ? null : provider.read("index.html");
            rel = "index.html";
        }
        if (body == null) {
            byte[] msg = ("404 Not Found: /" + rel).getBytes("UTF-8");
            writeResponse(out, 404, "text/plain; charset=utf-8", msg, null);
            closeQuietly(socket);
            return;
        }
        byte[] payload = "HEAD".equals(method) ? new byte[0] : body;
        writeResponse(out, 200, contentType(rel), payload, null);
        // HEAD 也要带上正确的 Content-Length
        if ("HEAD".equals(method)) {
            // 已经写了 0 长度，这里直接关闭即可（浏览器不会对 HEAD 做缓存判断）
        }
        closeQuietly(socket);
    }

    /** 把 URL 路径规整成 provider 认识的相对路径；非法路径返回 null。 */
    private static String normalize(String path) {
        if (path == null) {
            return null;
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
        }
        path = urlDecode(path);
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || "gamepage".equals(path) || "index.htm".equals(path)) {
            return "index.html";
        }
        if (path.contains("..")) {
            return null;
        }
        return path;
    }

    private static String urlDecode(String s) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()) {
                    bos.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                } else {
                    bos.write(String.valueOf(c).getBytes("UTF-8"));
                }
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String contentType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (p.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (p.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (p.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (p.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (p.endsWith(".png")) {
            return "image/png";
        }
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (p.endsWith(".gif")) {
            return "image/gif";
        }
        if (p.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (p.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "application/octet-stream";
    }

    private static void writeResponse(OutputStream out, int code, String type, byte[] body,
                                      String allow) throws IOException {
        writeResponse(out, code, type, body, allow, false);
    }

    private static void writeResponse(OutputStream out, int code, String type, byte[] body,
                                      String allow, boolean cacheable) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        if (allow != null) {
            sb.append("Allow: ").append(allow).append("\r\n");
        }
        if (cacheable) {
            // 头像 URL 带版本号，可以放心长缓存
            sb.append("Cache-Control: public, max-age=31536000, immutable\r\n");
        } else {
            // 游戏前端迭代频繁，禁用缓存避免手机 WebView 拿到旧 JS
            sb.append("Cache-Control: no-cache, no-store, must-revalidate\r\n");
        }
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        synchronized (out) {
            out.write(sb.toString().getBytes("UTF-8"));
            if (body.length > 0) {
                out.write(body);
            }
            out.flush();
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            default:
                return "Error";
        }
    }

    // ------------------------------------------------------------ 请求头解析

    private static final class Head {
        String method;
        String path;
        Map<String, String> headers;
    }

    /** 逐字节读到 \r\n\r\n，绝不越过请求头边界。 */
    private static Head readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0; // 匹配 \r\n\r\n
        while (buf.size() < MAX_HEAD) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            buf.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) {
                    break;
                }
            } else {
                state = (b == '\r') ? 1 : 0;
            }
        }
        String head = new String(buf.toByteArray(), "ISO-8859-1");
        String[] lines = head.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            return null;
        }
        Head h = new Head();
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) {
            return null;
        }
        h.method = parts[0].toUpperCase();
        h.path = parts[1];
        h.headers = new LinkedHashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx > 0) {
                h.headers.put(lines[i].substring(0, idx).trim().toLowerCase(),
                        lines[i].substring(idx + 1).trim());
            }
        }
        return h;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------ 网络工具

    /** 取本机局域网 IPv4 地址，取不到返回 null。 */
    public static String findLanIp() {
        java.util.List<String> all = findAllLanIps();
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 取本机所有可用的局域网 IPv4 地址（物理网卡排前面）。
     * 房主可以从中挑一个，或者自己填（例如端口映射工具映射出来的公网地址）。
     */
    public static java.util.List<String> findAllLanIps() {
        java.util.List<String> preferred = new ArrayList<String>();
        java.util.List<String> others = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                boolean phys = name.startsWith("wlan") || name.startsWith("eth")
                        || name.startsWith("en") || name.startsWith("wlp")
                        || name.startsWith("ap") || name.startsWith("rndis");
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr.isLoopbackAddress() || !(addr instanceof java.net.Inet4Address)) {
                        continue;
                    }
                    if (!addr.isSiteLocalAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (phys) {
                        if (!preferred.contains(ip)) {
                            preferred.add(ip);
                        }
                    } else if (!others.contains(ip)) {
                        others.add(ip);
                    }
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        java.util.List<String> out = new ArrayList<String>(preferred);
        for (String ip : others) {
            if (!out.contains(ip)) {
                out.add(ip);
            }
        }
        return out;
    }
}
