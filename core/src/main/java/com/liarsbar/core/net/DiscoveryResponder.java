package com.liarsbar.core.net;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * 局域网自动发现的服务端应答器。
 *
 * <p>客户端（安卓 App 的"搜索附近房间"）向广播地址发一个 {@code LIARBAR?} 探测包，
 * 本机就回一条 {@code LIARBAR|端口|人数|阶段}。客户端从 UDP 包的来源地址就知道房主 IP，
 * 所以回复里不用带 IP。</p>
 *
 * <p>绑定失败（端口被占、没有网络等）不会影响游戏，静默降级即可。</p>
 */
public final class DiscoveryResponder {

    /** 发现用的 UDP 端口（与游戏端口无关，固定一个不常见值即可）。 */
    public static final int DISCOVERY_PORT = 18923;
    /** 探测包内容。 */
    public static final String PROBE = "LIARBAR?";
    /** 回复前缀。 */
    public static final String REPLY_PREFIX = "LIARBAR|";

    /** 供应答器读取当前房间状态。 */
    public interface Info {
        int httpPort();

        int players();

        String phase();
    }

    private final Info info;
    private DatagramSocket socket;
    private Thread thread;
    private volatile boolean running;

    public DiscoveryResponder(Info info) {
        this.info = info;
    }

    /** 启动应答线程；失败就静默放弃（返回 false）。 */
    public boolean start() {
        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
            socket.setBroadcast(true);
        } catch (Exception e) {
            socket = null;
            return false;
        }
        running = true;
        thread = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "liarbar-discovery");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void loop() {
        byte[] buf = new byte[256];
        while (running && socket != null) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8").trim();
                if (!PROBE.equals(msg)) {
                    continue;
                }
                String reply = REPLY_PREFIX + info.httpPort() + "|" + info.players()
                        + "|" + (info.phase() == null ? "lobby" : info.phase());
                byte[] out = reply.getBytes("UTF-8");
                socket.send(new DatagramPacket(out, out.length,
                        packet.getAddress(), packet.getPort()));
            } catch (Exception e) {
                if (running && socket != null && !socket.isClosed()) {
                    // 单个包出错不影响后续
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    public void stop() {
        running = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // ignore
            }
            socket = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /** 客户端侧：把探测包广播出去（安卓的"搜索附近房间"也可以直接用它）。 */
    public static void sendProbe(DatagramSocket clientSocket, InetAddress target, int port)
            throws Exception {
        byte[] data = PROBE.getBytes("UTF-8");
        clientSocket.send(new DatagramPacket(data, data.length, target, port));
    }
}
