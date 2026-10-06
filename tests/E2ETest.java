import com.liarsbar.core.LiarbarServer;
import com.liarsbar.core.json.Json;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 端到端测试：真的起服务器、真的用 WebSocket 连上去，走完整套协议。
 *
 * <p>覆盖：HTTP 静态服务、WS 升级同端口、join/welcome/state、满员拒绝、
 * 非房主开局拒绝、非法操作错误码、4 人完整对局跑到结算、断线用 token 重连。</p>
 */
public final class E2ETest {

    private static int failures = 0;
    private static int checks = 0;
    private static int PORT = 18099;
    /** true = 打外部已有的服务器（例如手机 APK 里的那个），不自己起服务。 */
    private static boolean EXTERNAL = false;

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
        final String label;
        final BlockingQueue<Map<String, Object>> inbox = new LinkedBlockingQueue<Map<String, Object>>();
        final List<Map<String, Object>> errors = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        final StringBuilder partial = new StringBuilder();
        volatile WebSocket ws;
        volatile Map<String, Object> lastState;
        volatile String playerId;
        volatile String token;
        volatile boolean closed;

        Client(String label) {
            this.label = label;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.ws = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String s = partial.toString();
                partial.setLength(0);
                try {
                    Map<String, Object> m = Json.parseObject(s);
                    String t = Json.str(m, "t");
                    if ("welcome".equals(t)) {
                        playerId = Json.str(m, "playerId");
                        token = Json.str(m, "token");
                    } else if ("state".equals(t)) {
                        lastState = m;
                    } else if ("error".equals(t)) {
                        errors.add(m);
                    }
                    inbox.add(m);
                } catch (Exception e) {
                    System.out.println("  (解析消息失败: " + s + ")");
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
            closed = true;
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
        }

        void send(String json) {
            ws.sendText(json, true);
        }

        void sendJoin(String name, String withToken) {
            Map<String, Object> m = Json.obj();
            m.put("t", "join");
            m.put("name", name);
            if (withToken != null) {
                m.put("token", withToken);
            }
            send(Json.write(m));
        }

        void sendPlay(int... indices) {
            Map<String, Object> m = Json.obj();
            m.put("t", "play");
            List<Object> cards = new ArrayList<Object>();
            for (int i : indices) {
                cards.add(Integer.valueOf(i));
            }
            m.put("cards", cards);
            send(Json.write(m));
        }

        void sendSimple(String t) {
            Map<String, Object> m = Json.obj();
            m.put("t", t);
            send(Json.write(m));
        }

        int statePlayers() {
            Map<String, Object> st = lastState;
            if (st == null) {
                return -1;
            }
            List<Object> ps = Json.list(st, "players");
            return ps == null ? -1 : ps.size();
        }

        boolean phase(String p) {
            Map<String, Object> st = lastState;
            return st != null && p.equals(Json.str(st, "phase"));
        }
    }

    private static Client connect(String label) throws Exception {
        Client c = new Client(label);
        HttpClient http = HttpClient.newHttpClient();
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + PORT + "/ws"), c)
                .get(10, TimeUnit.SECONDS);
        c.ws = ws;
        return c;
    }

    // ------------------------------------------------------------ 断言工具

    private static boolean waitFor(long timeoutMs, Condition cond) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.ok()) {
                return true;
            }
            Thread.sleep(30);
        }
        return false;
    }

    interface Condition {
        boolean ok();
    }

    private static Map<String, Object> awaitError(Client c, String code, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
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

    private static void clearErrors(Client c) {
        synchronized (c.errors) {
            c.errors.clear();
        }
    }

    private static int listSize(Map<String, Object> m, String key) {
        List<Object> l = Json.list(m, key);
        return l == null ? 0 : l.size();
    }

    // ------------------------------------------------------------ 主流程

    public static void main(String[] args) throws Exception {
        /*
         * 这个测试是"把整局快速跑完"来验证流程的，所以把判定演出锁调短
         * （生产是 7.4 秒，那会让一局跑好几分钟）。
         * 判定锁本身由 ChatRenameTest 用真实时长单独验证。
         */
        com.liarsbar.core.service.GameService.setJudgePauseMs(120);

        if (args.length >= 2 && "external".equals(args[0])) {
            EXTERNAL = true;
            PORT = Integer.parseInt(args[1]);
        }

        LiarbarServer server = null;
        if (!EXTERNAL) {
            server = new LiarbarServer(PORT);
            server.start();
            System.out.println("服务器已启动: 端口 " + server.getPort() + ", 局域网 " + server.getLanUrl());
            check(server.getPort() == PORT, "端口应为 " + PORT + "，实际 " + server.getPort());
        } else {
            System.out.println("外部服务器模式: 127.0.0.1:" + PORT + "（不自己起服务）");
        }

        try {
            testHttp();
            Client c1 = connect("c1");
            Client c2 = connect("c2");
            Client c3 = connect("c3");
            Client c4 = connect("c4");

            testLobby(c1, c2, c3, c4);
            testRulesOverWire(c1, c2, c3, c4);
            testFullGame(new Client[]{c1, c2, c3, c4});
            testReconnect(c3);
        } finally {
            if (server != null) {
                server.stop();
            }
        }

        System.out.println();
        System.out.println("E2ETest: " + checks + " 项检查, " + failures + " 项失败");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void testHttp() throws Exception {
        System.out.println("· HTTP 静态服务");
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int code = conn.getResponseCode();
        // 前端资源是另一个模块产出的，这里只要求服务器能正确响应 HTTP
        check(code == 200 || code == 404, "GET / 应返回 200/404，实际 " + code);
        String ct = conn.getContentType();
        System.out.println("  (GET / -> " + code + ", Content-Type=" + ct + ")");
        if (code == 200) {
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[512];
            int n = in.read(buf);
            in.close();
            String head = new String(buf, 0, Math.max(n, 0), "UTF-8");
            check(head.toLowerCase().contains("<html") || head.toLowerCase().contains("<!doctype"),
                    "首页应返回 HTML");
        }
        conn.disconnect();

        HttpURLConnection c404 = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/nope.js").openConnection();
        c404.setConnectTimeout(5000);
        check(c404.getResponseCode() == 404, "不存在的资源应返回 404");
        c404.disconnect();

        HttpURLConnection cg = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/gamepage").openConnection();
        cg.setConnectTimeout(5000);
        int gc = cg.getResponseCode();
        check(gc == 200 || gc == 404, "/gamepage 应返回 200/404，实际 " + gc);
        cg.disconnect();
    }

    private static void joinAndWait(Client c, String name, int expectPlayers) throws Exception {
        c.sendJoin(name, null);
        final int expect = expectPlayers;
        boolean ok = waitFor(8000, new Condition() {
            public boolean ok() {
                return c.statePlayers() == expect;
            }
        });
        check(ok, name + " 加入后应看到 " + expect + " 人，实际 " + c.statePlayers());
    }

    private static void testLobby(Client c1, Client c2, Client c3, Client c4) throws Exception {
        System.out.println("· 大厅与人数限制");
        // 依次加入（真实场景也是一个个进），保证房主归属确定
        joinAndWait(c1, "阿一", 1);
        joinAndWait(c2, "阿二", 2);
        joinAndWait(c3, "阿三", 3);
        joinAndWait(c4, "阿四", 4);

        boolean seen4 = waitFor(6000, new Condition() {
            public boolean ok() {
                return c1.statePlayers() == 4 && c2.statePlayers() == 4
                        && c3.statePlayers() == 4 && c4.statePlayers() == 4;
            }
        });
        check(seen4, "4 名玩家最终都应看到 4 人");
        check("lobby".equals(Json.str(c1.lastState, "phase")), "此时应处于大厅阶段");

        Map<String, Object> st = c1.lastState;
        check(Boolean.TRUE.equals(st.get("isHost")), "依次加入时第一个加入的应是房主");
        check(Boolean.TRUE.equals(st.get("canStart")), "4 人时房主应能开始");
        check(!Boolean.TRUE.equals(c2.lastState.get("isHost")), "第二个加入的不应是房主");
        check(Json.str(st, "lanUrl") != null, "应下发局域网地址");
        System.out.println("  (lanUrl = " + Json.str(st, "lanUrl") + ")");

        // 第 5 个人
        Client c5 = connect("c5");
        c5.sendJoin("阿五", null);
        Map<String, Object> err = awaitError(c5, "room-full", 5000);
        check(err != null, "第 5 人应被拒绝（room-full）");
        c5.ws.abort();

        // 非房主开局
        c2.sendSimple("start");
        Map<String, Object> notHost = awaitError(c2, "not-host", 5000);
        check(notHost != null, "非房主开始游戏应被拒绝（not-host）");
    }

    private static void testRulesOverWire(Client c1, Client c2, Client c3, Client c4) throws Exception {
        System.out.println("· 开局与非法操作");
        clearErrors(c1);
        clearErrors(c2);
        c1.sendSimple("start");

        boolean started = waitFor(8000, new Condition() {
            public boolean ok() {
                return c1.phase("playing") && c2.phase("playing");
            }
        });
        check(started, "房主开始后应进入 playing");
        check(listSize(c1.lastState, "hand") == 5, "开局每人应发 5 张牌，实际 "
                + listSize(c1.lastState, "hand"));
        check(Json.str(c1.lastState, "targetCard") != null, "应下发目标牌");
        String target = Json.str(c1.lastState, "targetCard");
        check("Q".equals(target) || "K".equals(target) || "A".equals(target),
                "目标牌应为 Q/K/A，实际 " + target);
        String cur = Json.str(c1.lastState, "currentPlayerId");
        check(cur != null, "应下发当前回合玩家");

        // 局中再加入
        Client late = connect("late");
        late.sendJoin("迟到的", null);
        Map<String, Object> ge = awaitError(late, "game-in-progress", 5000);
        check(ge != null, "对局中加入应被拒绝（game-in-progress）");
        late.ws.abort();

        // 找一个不是当前回合的玩家
        Client[] all = {c1, c2, c3, c4};
        Client notCurrent = null;
        Client current = null;
        for (Client c : all) {
            if (c.playerId != null && c.playerId.equals(cur)) {
                current = c;
            } else if (notCurrent == null) {
                notCurrent = c;
            }
        }
        check(current != null, "应能定位当前回合玩家客户端");
        check(notCurrent != null, "应能找到一个非当前回合玩家");

        if (current != null && notCurrent != null) {
            clearErrors(notCurrent);
            notCurrent.sendPlay(0);
            check(awaitError(notCurrent, "not-your-turn", 5000) != null,
                    "越位出牌应被拒绝（not-your-turn）");

            clearErrors(current);
            /* 上限已经从 3 张放宽到 5 张：出 4 张现在是合法的，
               超过 5 张（手里也没有那么多牌）才会被数量校验拒绝 */
            current.sendPlay(0, 1, 2, 3, 4, 5);
            check(awaitError(current, "bad-card-count", 5000) != null,
                    "出 6 张应被拒绝（bad-card-count）");

            clearErrors(current);
            current.sendPlay(99);
            check(awaitError(current, "card-not-owned", 5000) != null,
                    "下标越界应被拒绝（card-not-owned）");

            clearErrors(current);
            current.sendSimple("doubt");
            check(awaitError(current, "nothing-to-doubt", 5000) != null,
                    "本轮无人出牌时质疑应被拒绝（nothing-to-doubt）");
        }
    }

    private static void testFullGame(Client[] clients) throws Exception {
        System.out.println("· 4 人完整对局");
        long start = System.currentTimeMillis();
        long end = start + 90_000;
        long lastActAt = 0;
        String lastSig = "";
        int actions = 0;
        int rejections = 0;
        boolean over = false;

        while (System.currentTimeMillis() < end) {
            for (Client c : clients) {
                if (c.phase("over")) {
                    over = true;
                }
            }
            if (over) {
                break;
            }
            long now = System.currentTimeMillis();
            for (Client c : clients) {
                Map<String, Object> st = c.lastState;
                if (st == null || Json.str(st, "you") == null) {
                    continue;
                }
                boolean canPlay = Boolean.TRUE.equals(st.get("canPlay"));
                boolean canDoubt = Boolean.TRUE.equals(st.get("canDoubt"));
                if (!canPlay && !canDoubt) {
                    continue;
                }
                String sig = Json.str(st, "roundNumber") + "|" + Json.str(st, "currentPlayerId")
                        + "|" + listSize(st, "table") + "|" + listSize(st, "hand");
                if (sig.equals(lastSig) && now - lastActAt < 600) {
                    continue;
                }
                lastSig = sig;
                lastActAt = now;
                actions++;
                if (canDoubt && (actions % 3 == 0)) {
                    c.sendSimple("doubt");
                } else if (canPlay) {
                    int hand = listSize(st, "hand");
                    if (hand >= 2 && actions % 2 == 0) {
                        c.sendPlay(0, 1);
                    } else {
                        c.sendPlay(0);
                    }
                } else {
                    c.sendSimple("doubt");
                }
                break;
            }
            Thread.sleep(20);
        }

        for (Client c : clients) {
            synchronized (c.errors) {
                rejections += c.errors.size();
            }
        }
        System.out.println("  (驱动动作 " + actions + " 次，用时 "
                + (System.currentTimeMillis() - start) + "ms，错误消息 " + rejections + " 条)");

        check(over, "4 人局应在 90 秒内结束");
        if (over) {
            Client finisher = null;
            for (Client c : clients) {
                if (c.phase("over")) {
                    finisher = c;
                    break;
                }
            }
            Map<String, Object> st = finisher.lastState;
            String winnerId = Json.str(st, "winnerId");
            check(winnerId != null, "结算时应下发 winnerId");
            int alive = 0;
            List<Object> ps = Json.list(st, "players");
            for (Object o : ps) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pm = (Map<String, Object>) o;
                if (Boolean.TRUE.equals(pm.get("alive"))) {
                    alive++;
                }
            }
            check(alive == 1, "结算时应只剩 1 名存活玩家，实际 " + alive);
            check(winnerId != null && winnerId.equals(String.valueOf(
                    ((Map<?, ?>) findAlive(ps)).get("id"))), "winnerId 应与存活玩家一致");
        }
    }

    private static Map<?, ?> findAlive(List<Object> players) {
        for (Object o : players) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (Boolean.TRUE.equals(m.get("alive"))) {
                return m;
            }
        }
        return new HashMap<Object, Object>();
    }

    private static void testReconnect(Client c3) throws Exception {
        System.out.println("· 断线重连");
        String token = c3.token;
        String id = c3.playerId;
        check(token != null && id != null, "客户端应拿到 token 与 playerId");

        c3.ws.abort();
        Thread.sleep(800);

        Client again = connect("c3-reconnect");
        again.sendJoin("阿三", token);
        boolean back = waitFor(8000, new Condition() {
            public boolean ok() {
                return again.playerId != null;
            }
        });
        check(back, "重连应收到 welcome");
        check(id.equals(again.playerId), "重连后应拿回同一个 playerId（" + id + " vs " + again.playerId + "）");
        boolean gotState = waitFor(5000, new Condition() {
            public boolean ok() {
                return again.lastState != null;
            }
        });
        check(gotState, "重连后应立刻收到状态快照");
        again.ws.abort();
    }
}
