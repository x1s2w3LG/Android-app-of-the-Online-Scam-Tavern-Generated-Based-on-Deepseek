import com.liarsbar.core.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * 自己当房主，用「添加机器人」接口把服务端机器人补齐，人到齐了自动开局。
 * 用来验证机器人的头像/对局表现（机器人由服务端 BotBrain 托管，不需要额外客户端）。
 *
 * <p>用法: java HostBots &lt;端口&gt; &lt;机器人个数&gt; &lt;运行秒数&gt; [等几个人再开局] [机器人思考间隔ms]</p>
 */
public final class HostBots {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int botCount = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 60;
        int waitPlayers = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        int thinkMs = args.length > 4 ? Integer.parseInt(args[4]) : 1200;
        boolean alwaysDoubt = args.length > 5 && "1".equals(args[5]);

        final StringBuilder part = new StringBuilder();
        final Map<String, Object>[] state = newMapHolder();
        final boolean[] botSent = {false};
        final boolean[] restartSent = {false};
        final long[] lastStart = {0};
        final String[] lastSig = {""};
        final long[] lastAct = {0};
        final int[] acts = {0};

        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), new WebSocket.Listener() {
                    public void onOpen(WebSocket w) {
                        w.request(1);
                    }

                    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean fin) {
                        part.append(data);
                        if (fin) {
                            String s = part.toString();
                            part.setLength(0);
                            try {
                                Map<String, Object> m = Json.parseObject(s);
                                if ("state".equals(Json.str(m, "t"))) {
                                    synchronized (state) {
                                        state[0] = m;
                                    }
                                }
                            } catch (Exception ignored) {
                                // 忽略
                            }
                        }
                        w.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        ws.sendText("{\"t\":\"join\",\"name\":\"房主\",\"uid\":\""
                + System.getProperty("liarbar.uid", "uhostbots") + "\"}", true);
        Thread.sleep(700);

        long end = System.currentTimeMillis() + seconds * 1000L;
        int lastPlayers = -1;
        while (System.currentTimeMillis() < end) {
            Map<String, Object> st;
            synchronized (state) {
                st = state[0];
            }
            if (st != null) {
                int n = size(st, "players");
                boolean isHost = Boolean.TRUE.equals(st.get("isHost"));
                String phase = Json.str(st, "phase");
                if (n != lastPlayers) {
                    System.out.println("  场上 " + n + " 人，阶段 " + phase);
                    lastPlayers = n;
                }
                if (isHost && !botSent[0] && "lobby".equals(phase)) {
                    for (int i = 0; i < botCount; i++) {
                        ws.sendText("{\"t\":\"add-bot\"}", true);
                        Thread.sleep(250);
                    }
                    botSent[0] = true;
                    System.out.println("  已添加 " + botCount + " 个服务端机器人");
                }
                if (isHost && "lobby".equals(phase) && n >= waitPlayers
                        && System.currentTimeMillis() - lastStart[0] > 4000) {
                    ws.sendText("{\"t\":\"start\"}", true);
                    lastStart[0] = System.currentTimeMillis();
                    System.out.println("  开局（场上 " + n + " 人）");
                }
                if (isHost && "over".equals(phase) && !restartSent[0]) {
                    restartSent[0] = true;
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                Thread.sleep(4000);
                            } catch (InterruptedException ignored) {
                                // 忽略
                            }
                            ws.sendText("{\"t\":\"restart\"}", true);
                            restartSent[0] = false;
                        }
                    }).start();
                }

                /* 轮到自己时要行动，否则整局会卡在这里 */
                boolean canPlay = Boolean.TRUE.equals(st.get("canPlay"));
                boolean canDoubt = Boolean.TRUE.equals(st.get("canDoubt"));
                if (canPlay || canDoubt) {
                    String sig = Json.str(st, "currentPlayerId") + "|" + size(st, "table")
                            + "|" + size(st, "hand");
                    if (!sig.equals(lastSig[0])
                            || System.currentTimeMillis() - lastAct[0] > 4000) {
                        lastSig[0] = sig;
                        lastAct[0] = System.currentTimeMillis();
                        acts[0]++;
                        if (canDoubt && (alwaysDoubt || acts[0] % 3 == 0)) {
                            ws.sendText("{\"t\":\"doubt\"}", true);
                            System.out.println("  房主质疑");
                        } else if (canPlay) {
                            List<Object> hand = Json.list(st, "hand");
                            int cnt = (hand != null && hand.size() >= 2 && acts[0] % 2 == 0) ? 2 : 1;
                            StringBuilder sb = new StringBuilder("{\"t\":\"play\",\"cards\":[");
                            for (int i = 0; i < cnt; i++) {
                                if (i > 0) {
                                    sb.append(',');
                                }
                                sb.append(i);
                            }
                            sb.append("]}");
                            ws.sendText(sb.toString(), true);
                            System.out.println("  房主出牌 " + cnt + " 张");
                        }
                    }
                }
            }
            Thread.sleep(thinkMs / 3);
        }
        ws.abort();
        System.out.println("结束");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object>[] newMapHolder() {
        return new Map[1];
    }

    private static int size(Map<String, Object> st, String key) {
        List<Object> l = Json.list(st, key);
        return l == null ? 0 : l.size();
    }
}
