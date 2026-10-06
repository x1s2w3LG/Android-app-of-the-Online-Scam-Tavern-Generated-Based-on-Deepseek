package com.liarsbar.core.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手写 RFC6455 WebSocket 服务端连接。
 *
 * <p>零第三方依赖：握手只用 SHA-1 + Base64（自带实现），帧收发自己解析。
 * 发送走独立线程 + 有界队列，避免某个卡死的客户端阻塞整个游戏线程。</p>
 */
public final class WebSocketConn {

    /** 上层业务回调。 */
    public interface Handler {
        /** 握手成功、连接可用时回调。 */
        void onOpen(WebSocketConn conn);

        void onText(WebSocketConn conn, String text);

        void onClose(WebSocketConn conn);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 1 << 20;      // 单帧上限 1MB
    private static final int OUT_QUEUE = 512;          // 发送队列上限

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Handler handler;
    private final String id;

    private final BlockingQueue<String> outQueue = new ArrayBlockingQueue<String>(OUT_QUEUE);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Object writeLock = new Object();

    private volatile long lastActivity = System.currentTimeMillis();
    private Thread reader;
    private Thread writer;

    private WebSocketConn(Socket socket, InputStream in, OutputStream out, Handler handler,
                          String id, Map<String, String> headers) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.handler = handler;
        this.id = id;
        this.headers = headers;
    }

    private final Map<String, String> headers;

    public String getId() {
        return id;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getRemoteAddress() {
        return socket.getRemoteSocketAddress() == null ? "?" : socket.getRemoteSocketAddress().toString();
    }

    public boolean isOpen() {
        return open.get();
    }

    public long getLastActivity() {
        return lastActivity;
    }

    /** 供服务器内部刷新活跃时间（收到任何数据时调用）。 */
    void touch() {
        lastActivity = System.currentTimeMillis();
    }

    /**
     * 执行 WebSocket 握手；失败会抛 IOException（调用方负责关连接）。
     *
     * @param headers 已解析好的 HTTP 请求头（key 全部小写）
     */
    public static WebSocketConn handshake(Socket socket, InputStream in, OutputStream out,
                                          Map<String, String> headers, Handler handler,
                                          String id) throws IOException {
        String key = headers.get("sec-websocket-key");
        if (key == null || key.trim().isEmpty()) {
            throw new IOException("缺少 Sec-WebSocket-Key");
        }
        String accept = base64(sha1((key.trim() + GUID).getBytes("UTF-8")));
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        synchronized (out) {
            out.write(response.getBytes("UTF-8"));
            out.flush();
        }
        WebSocketConn conn = new WebSocketConn(socket, in, out, handler, id, headers);
        conn.start();
        return conn;
    }

    private void start() {
        reader = new Thread(new Runnable() {
            public void run() {
                readLoop();
            }
        }, "ws-reader-" + id);
        reader.setDaemon(true);

        writer = new Thread(new Runnable() {
            public void run() {
                writeLoop();
            }
        }, "ws-writer-" + id);
        writer.setDaemon(true);

        writer.start();
        reader.start();
    }

    /** 异步发送文本消息；队列满说明对端已经不健康，直接断开。 */
    public void send(String text) {
        if (!open.get()) {
            return;
        }
        if (!outQueue.offer(text)) {
            close(1008, "send queue overflow");
        }
    }

    /** 发送 WebSocket ping 帧（保活）。 */
    public void ping() {
        if (!open.get()) {
            return;
        }
        try {
            writeFrame(0x9, new byte[0]);
        } catch (IOException e) {
            closeQuietly();
        }
    }

    public void close(int code, String reason) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try {
            byte[] payload;
            if (reason == null || reason.isEmpty()) {
                payload = new byte[]{(byte) (code >>> 8), (byte) (code & 0xFF)};
            } else {
                byte[] r = reason.getBytes("UTF-8");
                payload = new byte[2 + r.length];
                payload[0] = (byte) (code >>> 8);
                payload[1] = (byte) (code & 0xFF);
                System.arraycopy(r, 0, payload, 2, r.length);
            }
            writeFrame(0x8, payload);
        } catch (IOException ignored) {
            // 已经断了，忽略
        }
        closeQuietly();
    }

    private void closeQuietly() {
        open.set(false);
        try {
            socket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------ 写

    private void writeLoop() {
        try {
            while (open.get()) {
                String msg = outQueue.poll(500, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    continue;
                }
                writeFrame(0x1, msg.getBytes("UTF-8"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 对端断开
        } finally {
            closeQuietly();
        }
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            out.write(0x80 | opcode);
            int len = payload.length;
            if (len < 126) {
                out.write(len);
            } else if (len < 65536) {
                out.write(126);
                out.write((len >>> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) (((long) len >>> (8 * i)) & 0xFF));
                }
            }
            out.write(payload);
            out.flush();
        }
    }

    // ------------------------------------------------------------ 读

    private void readLoop() {
        ByteArrayOutputStream frag = null;
        int fragOpcode = 0;
        try {
            while (open.get()) {
                Frame f = readFrame();
                if (f == null) {
                    break;
                }
                touch();
                switch (f.opcode) {
                    case 0x8: // close
                        closeQuietly();
                        break;
                    case 0x9: // ping -> pong
                        try {
                            writeFrame(0xA, f.payload);
                        } catch (IOException e) {
                            closeQuietly();
                        }
                        break;
                    case 0xA: // pong
                        break;
                    case 0x1:
                    case 0x2:
                        if (f.fin) {
                            deliver(f.opcode, f.payload);
                        } else {
                            frag = new ByteArrayOutputStream();
                            frag.write(f.payload);
                            fragOpcode = f.opcode;
                        }
                        break;
                    case 0x0:
                        if (frag != null) {
                            frag.write(f.payload);
                            if (f.fin) {
                                deliver(fragOpcode, frag.toByteArray());
                                frag = null;
                            }
                        }
                        break;
                    default:
                        close(1002, "unsupported opcode");
                }
            }
        } catch (IOException e) {
            // 连接异常，走统一的关闭流程
        } finally {
            boolean wasOpen = open.getAndSet(false);
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
            if (wasOpen && handler != null) {
                handler.onClose(this);
            }
        }
    }

    private void deliver(int opcode, byte[] payload) {
        if (opcode != 0x1) {
            return; // 只处理文本帧
        }
        String text = new String(payload, utf8());
        if (handler != null) {
            handler.onText(this, text);
        }
    }

    private static java.nio.charset.Charset utf8() {
        return java.nio.charset.Charset.forName("UTF-8");
    }

    private static final class Frame {
        boolean fin;
        int opcode;
        byte[] payload;
    }

    private Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = readByte();
        Frame f = new Frame();
        f.fin = (b0 & 0x80) != 0;
        f.opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) readByte() << 8) | readByte();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte();
            }
        }
        if (len < 0 || len > MAX_FRAME) {
            throw new IOException("WebSocket 帧过大: " + len);
        }
        byte[] mask = null;
        if (masked) {
            mask = readN(4);
        }
        byte[] payload = readN((int) len);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i & 3]);
            }
        }
        f.payload = payload;
        return f;
    }

    private int readByte() throws IOException {
        int v = in.read();
        if (v < 0) {
            throw new IOException("连接已关闭");
        }
        return v;
    }

    private byte[] readN(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new IOException("连接已关闭");
            }
            off += r;
        }
        return buf;
    }

    // ------------------------------------------------------------ 编解码工具

    private static byte[] sha1(byte[] data) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(data);
        } catch (Exception e) {
            throw new IOException("SHA-1 不可用", e);
        }
    }

    /** 自带 Base64 编码，避免依赖 java.util.Base64（Android API 26 以下没有）。 */
    static String base64(byte[] data) {
        final char[] T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(T[(n >>> 18) & 63]).append(T[(n >>> 12) & 63]).append(T[(n >>> 6) & 63]).append(T[n & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(T[(n >>> 18) & 63]).append(T[(n >>> 12) & 63]).append("==");
        } else if (rest == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(T[(n >>> 18) & 63]).append(T[(n >>> 12) & 63]).append(T[(n >>> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    /** 解析 HTTP 请求头，key 统一转小写。 */
    static Map<String, String> parseHeaders(java.io.BufferedReader reader) throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                break;
            }
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
            }
        }
        return headers;
    }
}
