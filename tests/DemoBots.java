import com.liarsbar.core.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * 演示用机器人客户端：连上服务器、入座、必要时替房主开局，然后自动出牌/质疑。
 * 用来在模拟器上跑出"正在对局"的画面，也可以当压力测试用。
 *
 * 用法: java DemoBots <port> <botCount> [seconds]
 */
public final class DemoBots {

    static final class Bot implements WebSocket.Listener {
        final String name;
        volatile WebSocket ws;
        volatile Map<String, Object> state;
        volatile String me;
        volatile String lastSig = "";
        volatile long lastAct = 0;
        int acts = 0;
        private final StringBuilder partial = new StringBuilder();

        Bot(String name) {
            this.name = name;
        }

        void send(String json) {
            WebSocket w = ws;
            if (w != null) {
                w.sendText(json, true);
            }
        }

        public void onOpen(WebSocket w) {
            ws = w;
            w.request(1);
        }

        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String s = partial.toString();
                partial.setLength(0);
                try {
                    Map<String, Object> m = Json.parseObject(s);
                    String t = Json.str(m, "t");
                    if ("state".equals(t)) {
                        state = m;
                    } else if ("welcome".equals(t)) {
                        me = Json.str(m, "playerId");
                    } else if ("gameover".equals(t)) {
                        System.out.println("[gameover] winner=" + Json.str(m, "winnerName"));
                    }
                } catch (Exception ignored) {
                    // 忽略解析失败
                }
            }
            w.request(1);
            return null;
        }

        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            return null;
        }

        public void onError(WebSocket w, Throwable e) {
        }
    }

    private static int size(Map<String, Object> m, String key) {
        List<Object> l = Json.list(m, key);
        return l == null ? 0 : l.size();
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 60;
        // 动作节流：调大可以让对局慢下来，方便截图/观察
        int actDelayMs = args.length > 3 ? Integer.parseInt(args[3]) : 700;
        // 等到场上至少有这么多人再开局（方便先把真机/模拟器拉进房间）
        int waitPlayers = args.length > 4 ? Integer.parseInt(args[4]) : 2;
        // 1 = 能质疑就一定质疑（演示"异议あり"用）
        boolean alwaysDoubt = args.length > 5 && "1".equals(args[5]);
        // 可选：给第 2 个机器人上传这张图片当头像（验证头像显示用）
        String avatarPath = args.length > 6 ? args[6] : null;

        System.out.println("连接 ws://127.0.0.1:" + port + "/ws ，机器人 " + count + " 个，运行 " + seconds + " 秒");
        List<Bot> bots = new ArrayList<Bot>();
        HttpClient http = HttpClient.newHttpClient();

        for (int i = 0; i < count; i++) {
            Bot b = new Bot("机器人" + (i + 1));
            b.ws = http.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), b)
                    .get(10, TimeUnit.SECONDS);
            bots.add(b);
            Thread.sleep(250);
            b.send("{\"t\":\"join\",\"name\":\"" + b.name + "\"}");
            System.out.println("已加入: " + b.name + " -> " + b.me);

            // 第一个机器人（如果给了图片）上传头像
            if (i == 0 && avatarPath != null) {
                java.io.File f = new java.io.File(avatarPath);
                if (f.isFile()) {
                    byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                    String mime = avatarPath.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
                    String b64 = java.util.Base64.getEncoder().encodeToString(data);
                    Thread.sleep(300);
                    b.send("{\"t\":\"avatar\",\"data\":\"" + b64 + "\",\"mime\":\"" + mime + "\"}");
                    System.out.println("  " + b.name + " 上传了头像 " + data.length + " 字节");
                } else {
                    System.out.println("  (头像文件不存在: " + avatarPath + ")");
                }
            }
        }

        long end = System.currentTimeMillis() + seconds * 1000L;
        boolean started = false;
        while (System.currentTimeMillis() < end) {
            for (Bot b : bots) {
                Map<String, Object> st = b.state;
                if (st == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(st.get("canStart")) && Boolean.TRUE.equals(st.get("isHost"))) {
                    if (size(st, "players") >= waitPlayers) {
                        b.send("{\"t\":\"start\"}");
                        started = true;
                        System.out.println("房主 " + b.name + " 开始游戏（场上 "
                                + size(st, "players") + " 人）");
                    }
                    continue;
                }
                // 结算后由房主自动重开，方便长时间演示
                if ("over".equals(Json.str(st, "phase")) && Boolean.TRUE.equals(st.get("isHost"))) {
                    if (System.currentTimeMillis() - b.lastAct > 3000) {
                        b.lastAct = System.currentTimeMillis();
                        b.lastSig = "";
                        b.send("{\"t\":\"restart\"}");
                        System.out.println("房主 " + b.name + " 重开一局");
                    }
                    continue;
                }
                boolean canPlay = Boolean.TRUE.equals(st.get("canPlay"));
                boolean canDoubt = Boolean.TRUE.equals(st.get("canDoubt"));
                if (!canPlay && !canDoubt) {
                    continue;
                }
                String sig = Json.str(st, "roundNumber") + "|" + Json.str(st, "currentPlayerId")
                        + "|" + size(st, "table") + "|" + size(st, "hand");
                if (sig.equals(b.lastSig) && System.currentTimeMillis() - b.lastAct < actDelayMs) {
                    continue;
                }
                b.lastSig = sig;
                b.lastAct = System.currentTimeMillis();
                b.acts++;
                if (canDoubt && (alwaysDoubt || b.acts % 3 == 0)) {
                    b.send("{\"t\":\"doubt\"}");
                } else if (canPlay) {
                    List<Object> hand = Json.list(st, "hand");
                    int cnt = (hand != null && hand.size() >= 2 && b.acts % 2 == 0) ? 2 : 1;
                    StringBuilder sb = new StringBuilder("{\"t\":\"play\",\"cards\":[");
                    for (int i = 0; i < cnt; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(i);
                    }
                    sb.append("]}");
                    b.send(sb.toString());
                }
            }
            Thread.sleep(40);
        }

        for (Bot b : bots) {
            System.out.println(b.name + " 动作次数=" + b.acts);
        }
        System.out.println("演示结束");
        System.exit(0);
    }
}
