package com.liarsbar.core.net;

import com.liarsbar.core.json.Json;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 局域网房间扫描（客户端侧）：广播探测包，收集附近房间的应答。
 *
 * <p>安卓 App 的「搜索附近房间」按钮会调它；PC 浏览器上没法用，所以那边保留手动填地址。</p>
 */
public final class DiscoveryScanner {

    /** 一个被发现的房间。 */
    public static final class Room {
        public final String ip;
        public final int port;
        public final int players;
        public final String phase;

        Room(String ip, int port, int players, String phase) {
            this.ip = ip;
            this.port = port;
            this.players = players;
            this.phase = phase;
        }
    }

    private DiscoveryScanner() {
    }

    /** 扫描附近房间，最多等 timeoutMs 毫秒。 */
    public static List<Room> scan(int timeoutMs) {
        List<Room> rooms = new ArrayList<Room>();
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            sock.setSoTimeout(200);

            Set<InetAddress> targets = new LinkedHashSet<InetAddress>();
            try {
                targets.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception ignored) {
                // 忽略
            }
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress b = ia.getBroadcast();
                        if (b != null) {
                            targets.add(b);
                        }
                    }
                }
            } catch (Exception ignored) {
                // 忽略
            }

            for (InetAddress t : targets) {
                try {
                    DiscoveryResponder.sendProbe(sock, t, DiscoveryResponder.DISCOVERY_PORT);
                } catch (Exception ignored) {
                    // 单个目标失败不影响其它
                }
            }

            long end = System.currentTimeMillis() + Math.max(200, timeoutMs);
            byte[] buf = new byte[256];
            while (System.currentTimeMillis() < end) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                    if (!msg.startsWith(DiscoveryResponder.REPLY_PREFIX)) {
                        continue;
                    }
                    String[] parts = msg.substring(DiscoveryResponder.REPLY_PREFIX.length()).split("\\|");
                    if (parts.length < 3) {
                        continue;
                    }
                    String ip = p.getAddress().getHostAddress();
                    int port = Integer.parseInt(parts[0].trim());
                    int players = Integer.parseInt(parts[1].trim());
                    String phase = parts[2].trim();
                    boolean dup = false;
                    for (Room r : rooms) {
                        if (r.ip.equals(ip) && r.port == port) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        rooms.add(new Room(ip, port, players, phase));
                    }
                } catch (SocketTimeoutException te) {
                    // 继续等剩下的时间
                } catch (Exception e) {
                    break;
                }
            }
        } catch (Exception e) {
            // 没有网络权限 / 广播被禁 → 返回空列表，前端会提示手动填地址
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
        return rooms;
    }

    /** 扫描并直接返回前端的 JSON 数组字符串。 */
    public static String scanJson(int timeoutMs) {
        List<Object> arr = Json.arr();
        for (Room r : scan(timeoutMs)) {
            Map<String, Object> m = Json.obj();
            m.put("ip", r.ip);
            m.put("port", Integer.valueOf(r.port));
            m.put("players", Integer.valueOf(r.players));
            m.put("phase", r.phase);
            arr.add(m);
        }
        return Json.write(arr);
    }
}
