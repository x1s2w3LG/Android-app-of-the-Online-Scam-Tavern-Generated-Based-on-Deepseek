import com.liarsbar.core.LiarbarServer;
import com.liarsbar.core.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 机器人功能测试：验证"单人 + 机器人"能开局、机器人会自己行动、整局能打完。
 *
 * <p>用法: java BotGameTest</p>
 */
public final class BotGameTest {

    private static int failures = 0;
    private static int checks = 0;
    private static final int PORT = 18100;

    private static void check(boolean cond, String msg) {
        checks++;
        if (cond) {
            System.out.println("  [ok]   " + msg);
        } else {
            failures++;
            System.out.println("  [FAIL] " + msg);
        }
    }

    // ------------------------------------------------------------ 客户端

    static final class Client implements WebSocket.Listener {
        final BlockingQueue<Map<String, Object>> inbox = new LinkedBlockingQueue<Map<String, Object>>();
        final List<Map<String, Object>> errors = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        final StringBuilder partial = new StringBuilder();
        volatile WebSocket ws;
        volatile Map<String, Object> lastState;
        volatile String playerId;

        void send(String json) {
            ws.sendText(json, true);
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
                    if ("welcome".equals(t)) {
                        playerId = Json.str(m, "playerId");
                    } else if ("state".equals(t)) {
                        lastState = m;
                    } else if ("error".equals(t)) {
                        errors.add(m);
                    }
                    inbox.add(m);
                } catch (Exception ignored) {
                    // 忽略
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

    private static Client connect() throws Exception {
        Client c = new Client();
        HttpClient http = HttpClient.newHttpClient();
        c.ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + PORT + "/ws"), c)
                .get(10, TimeUnit.SECONDS);
        return c;
    }

    private static boolean waitFor(long ms, Cond cond) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.ok()) {
                return true;
            }
            Thread.sleep(30);
        }
        return false;
    }

    interface Cond {
        boolean ok();
    }

    private static Map<String, Object> awaitError(Client c, String code, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            Map<String, Object> found = null;
            synchronized (c.errors) {
                for (Map<String, Object> e : c.errors) {
                    if (code.equals(Json.str(e, "code"))) {
                        found = e;
                        break;
                    }
                }
            }
            if (found != null) {
                return found;
            }
            Thread.sleep(30);
        }
        return null;
    }

    private static int playerCount(Map<String, Object> st) {
        if (st == null) {
            return -1;
        }
        List<Object> l = Json.list(st, "players");
        return l == null ? 0 : l.size();
    }

    private static int botCount(Map<String, Object> st) {
        if (st == null) {
            return -1;
        }
        List<Object> l = Json.list(st, "players");
        if (l == null) {
            return 0;
        }
        int n = 0;
        for (Object o : l) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(p.get("bot"))) {
                n++;
            }
        }
        return n;
    }

    private static int aliveCountOf(Map<String, Object> st) {
        List<Object> l = Json.list(st, "players");
        if (l == null) {
            return -1;
        }
        int n = 0;
        for (Object o : l) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(p.get("alive"))) {
                n++;
            }
        }
        return n;
    }

    private static String nameOf(Map<String, Object> st, String id) {
        if (id == null) {
            return "—";
        }
        List<Object> l = Json.list(st, "players");
        if (l == null) {
            return "?";
        }
        for (Object o : l) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (id.equals(Json.str(p, "id"))) {
                return Json.str(p, "name") + (Boolean.TRUE.equals(p.get("bot")) ? "(机器人)" : "");
            }
        }
        return "?";
    }

    // ------------------------------------------------------------ 主流程

    public static void main(String[] args) throws Exception {
        /* 机器人整局模拟：把判定演出锁调短，否则 7.4 秒一次会跑到超时
           （判定锁本身由 ChatRenameTest 用真实时长验证） */
        com.liarsbar.core.service.GameService.setJudgePauseMs(120);
        // 每个用例单独起一个服务器，避免 60 秒重连宽限期里的座位互相干扰
        runOne("添加机器人", new Body() {
            public void run() throws Exception {
                testAddBots();
            }
        });
        runOne("单人 + 3 机器人", new Body() {
            public void run() throws Exception {
                testSoloVersusBots();
            }
        });
        runOne("单人 + 1 机器人", new Body() {
            public void run() throws Exception {
                testTwoPlayerWithBot();
            }
        });

        System.out.println();
        System.out.println("BotGameTest: " + checks + " 项检查, " + failures + " 项失败");
        if (failures > 0) {
            System.exit(1);
        }
    }

    interface Body {
        void run() throws Exception;
    }

    private static void runOne(String label, Body body) throws Exception {
        LiarbarServer server = new LiarbarServer(PORT);
        server.start();
        try {
            body.run();
        } finally {
            server.stop();
            Thread.sleep(600);
        }
    }

    /** 加机器人 / 权限校验 / 重名与计数。 */
    private static void testAddBots() throws Exception {
        System.out.println("· 添加机器人");
        Client host = connect();
        host.send("{\"t\":\"join\",\"name\":\"阿一\"}");
        waitFor(5000, new Cond() {
            public boolean ok() {
                return playerCount(host.lastState) == 1 && host.lastState != null;
            }
        });
        check(host.lastState != null, "房主入座成功");

        // 非房主不能加（先造一个第二个玩家）
        Client other = connect();
        other.send("{\"t\":\"join\",\"name\":\"阿二\"}");
        waitFor(5000, new Cond() {
            public boolean ok() {
                return playerCount(other.lastState) == 2;
            }
        });

        other.send("{\"t\":\"add-bot\"}");
        check(awaitError(other, "not-host", 4000) != null, "非房主加机器人应被拒绝（not-host）");

        // 2 真人 + 2 机器人 = 4 人（上限）
        host.send("{\"t\":\"add-bot\"}");
        waitFor(4000, new Cond() {
            public boolean ok() {
                return playerCount(host.lastState) == 3;
            }
        });
        host.send("{\"t\":\"add-bot\"}");
        waitFor(4000, new Cond() {
            public boolean ok() {
                return playerCount(host.lastState) == 4;
            }
        });
        check(playerCount(host.lastState) == 4, "2 真人 + 2 机器人 = 4 人，实际 "
                + playerCount(host.lastState));
        check(botCount(host.lastState) == 2, "应识别出 2 个机器人，实际 " + botCount(host.lastState));

        host.send("{\"t\":\"add-bot\"}");
        check(awaitError(host, "room-full", 4000) != null, "满员后加机器人应被拒绝（room-full）");

        host.send("{\"t\":\"remove-bot\"}");
        waitFor(4000, new Cond() {
            public boolean ok() {
                return botCount(host.lastState) == 1;
            }
        });
        check(botCount(host.lastState) == 1, "移除后应剩 1 个机器人，实际 " + botCount(host.lastState));

        other.ws.abort();
        host.ws.abort();
        Thread.sleep(500);
    }

    /** 核心场景：一个真人 + 3 个机器人，真人只做最简单的操作，机器人要自己把牌局推下去。 */
    private static void testSoloVersusBots() throws Exception {
        System.out.println("· 单人 + 3 机器人 打完整局");
        Client me = connect();
        me.send("{\"t\":\"join\",\"name\":\"单人玩家\"}");
        waitFor(5000, new Cond() {
            public boolean ok() {
                return playerCount(me.lastState) == 1;
            }
        });

        for (int i = 0; i < 3; i++) {
            me.send("{\"t\":\"add-bot\"}");
            Thread.sleep(120);
        }
        boolean four = waitFor(5000, new Cond() {
            public boolean ok() {
                return playerCount(me.lastState) == 4;
            }
        });
        check(four, "1 真人 + 3 机器人 = 4 人");
        check(Boolean.TRUE.equals(me.lastState.get("canStart")), "只有 1 个真人时也能开始（人数算上机器人）");

        me.send("{\"t\":\"start\"}");
        boolean playing = waitFor(6000, new Cond() {
            public boolean ok() {
                return "playing".equals(Json.str(me.lastState, "phase"));
            }
        });
        check(playing, "开局成功进入 playing");

        // 真人这边做最保守的动作（能出就出，出不了就质疑），把注意力放在"机器人会不会自己动"
        long end = System.currentTimeMillis() + 180_000;
        long startAt = System.currentTimeMillis();
        int myActs = 0;
        int lastAlive = 4;
        int lastRound = 0;
        long lastLogAt = 0;
        String lastSig = "";
        long lastActAt = 0;
        boolean over = false;
        while (System.currentTimeMillis() < end) {
            Map<String, Object> st = me.lastState;
            if (st == null) {
                Thread.sleep(50);
                continue;
            }
            if ("over".equals(Json.str(st, "phase"))) {
                over = true;
                break;
            }
            // 每 5 秒打一次进度，方便判断是"卡住"还是"只是慢"
            long now0 = System.currentTimeMillis();
            if (now0 - lastLogAt > 5000) {
                lastLogAt = now0;
                int alive = aliveCountOf(st);
                int round = Json.intValue(st, "roundNumber", 0);
                System.out.println("       [" + ((now0 - startAt) / 1000) + "s] 第 " + round
                        + " 轮 存活 " + alive + " 当前 "
                        + nameOf(st, Json.str(st, "currentPlayerId"))
                        + (alive != lastAlive || round != lastRound ? "  <==" : ""));
                lastAlive = alive;
                lastRound = round;
            }
            boolean canPlay = Boolean.TRUE.equals(st.get("canPlay"));
            boolean canDoubt = Boolean.TRUE.equals(st.get("canDoubt"));
            if (canPlay || canDoubt) {
                long now = System.currentTimeMillis();
                String sig = Json.str(st, "roundNumber") + "|" + Json.str(st, "currentPlayerId")
                        + "|" + Json.str(st, "hand");
                if (!sig.equals(lastSig) || now - lastActAt > 800) {
                    lastSig = sig;
                    lastActAt = now;
                    myActs++;
                    if (canPlay) {
                        me.send("{\"t\":\"play\",\"cards\":[0]}");
                    } else {
                        me.send("{\"t\":\"doubt\"}");
                    }
                }
            }
            Thread.sleep(40);
        }

        check(over, "整局在 180 秒内结束（真人只出手 " + myActs + " 次，其余回合都是机器人在动）");
        if (over) {
            String winnerId = Json.str(me.lastState, "winnerId");
            check(winnerId != null, "结算给出了 winnerId");
            List<Object> ps = Json.list(me.lastState, "players");
            int alive = 0;
            String winnerName = null;
            for (Object o : ps) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) o;
                if (Boolean.TRUE.equals(p.get("alive"))) {
                    alive++;
                    winnerName = Json.str(p, "name");
                }
            }
            check(alive == 1, "结算时只剩 1 名存活玩家，实际 " + alive);
            System.out.println("       （本局赢家：" + winnerName + "）");
        }

        me.ws.abort();
        Thread.sleep(500);
    }

    /** 2 人局（1 真人 + 1 机器人）也要能打完——这是"缺人时补齐"的最小场景。 */
    private static void testTwoPlayerWithBot() throws Exception {
        System.out.println("· 单人 + 1 机器人（2 人局）");
        Client me = connect();
        me.send("{\"t\":\"join\",\"name\":\"双人测试\"}");
        waitFor(5000, new Cond() {
            public boolean ok() {
                return playerCount(me.lastState) == 1;
            }
        });
        me.send("{\"t\":\"add-bot\"}");
        boolean two = waitFor(5000, new Cond() {
            public boolean ok() {
                return playerCount(me.lastState) == 2;
            }
        });
        check(two, "1 真人 + 1 机器人 = 2 人");

        me.send("{\"t\":\"start\"}");
        boolean playing = waitFor(6000, new Cond() {
            public boolean ok() {
                return "playing".equals(Json.str(me.lastState, "phase"));
            }
        });
        check(playing, "2 人局开局成功");

        long end = System.currentTimeMillis() + 150_000;
        String lastSig = "";
        long lastActAt = 0;
        boolean over = false;
        while (System.currentTimeMillis() < end) {
            Map<String, Object> st = me.lastState;
            if (st == null) {
                Thread.sleep(50);
                continue;
            }
            if ("over".equals(Json.str(st, "phase"))) {
                over = true;
                break;
            }
            boolean canPlay = Boolean.TRUE.equals(st.get("canPlay"));
            boolean canDoubt = Boolean.TRUE.equals(st.get("canDoubt"));
            if (canPlay || canDoubt) {
                long now = System.currentTimeMillis();
                String sig = Json.str(st, "roundNumber") + "|" + Json.str(st, "currentPlayerId")
                        + "|" + Json.str(st, "hand");
                if (!sig.equals(lastSig) || now - lastActAt > 800) {
                    lastSig = sig;
                    lastActAt = now;
                    if (canPlay) {
                        me.send("{\"t\":\"play\",\"cards\":[0]}");
                    } else {
                        me.send("{\"t\":\"doubt\"}");
                    }
                }
            }
            Thread.sleep(40);
        }
        check(over, "2 人局也能打到结算");
        me.ws.abort();
    }
}
