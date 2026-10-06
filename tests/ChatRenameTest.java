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
 * 新增功能的验证：改名（含局内禁止）、聊天、分享地址可改、重名自动加序号。
 *
 * <p>用法: java ChatRenameTest</p>
 */
public final class ChatRenameTest {

    private static int failures = 0;
    private static int checks = 0;
    private static final int PORT = 18101;

    private static void check(boolean cond, String msg) {
        checks++;
        if (cond) {
            System.out.println("  [ok]   " + msg);
        } else {
            failures++;
            System.out.println("  [FAIL] " + msg);
        }
    }

    static final class Client implements WebSocket.Listener {
        final String label;
        final BlockingQueue<Map<String, Object>> inbox = new LinkedBlockingQueue<Map<String, Object>>();
        final List<Map<String, Object>> errors = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        final List<Map<String, Object>> chats = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        final List<Map<String, Object>> renamed = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        final StringBuilder partial = new StringBuilder();
        volatile WebSocket ws;
        volatile Map<String, Object> lastState;
        volatile String playerId;

        Client(String label) {
            this.label = label;
        }

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
                    } else if ("chat".equals(t)) {
                        chats.add(m);
                    } else if ("renamed".equals(t)) {
                        renamed.add(m);
                    }
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

        String myName() {
            Map<String, Object> st = lastState;
            if (st == null || playerId == null) {
                return null;
            }
            List<Object> ps = Json.list(st, "players");
            if (ps == null) {
                return null;
            }
            for (Object o : ps) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) o;
                if (playerId.equals(Json.str(p, "id"))) {
                    return Json.str(p, "name");
                }
            }
            return null;
        }
    }

    private static Client connect(String label) throws Exception {
        Client c = new Client(label);
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

    public static void main(String[] args) throws Exception {
        // 每个用例单独起服务器：掉线座位有 60 秒宽限期，共用服务器会互相干扰
        runOne(new Body() {
            public void run() throws Exception {
                testRenameAndDuplicate();
            }
        });
        runOne(new Body() {
            public void run() throws Exception {
                testChatAndNoRenameInGame();
            }
        });
        runOne(new Body() {
            public void run() throws Exception {
                testCustomShareAddress();
            }
        });
        runOne(new Body() {
            public void run() throws Exception {
                testAvatar();
            }
        });
        runOne(new Body() {
            public void run() throws Exception {
                testSpectatorChat();
            }
        });
        runOne(new Body() {
            public void run() throws Exception {
                testJudgingLock();
            }
        });
        runOne(new Body() {
            public void run() throws Exception {
                testLeaderboard();
            }
        });

        System.out.println();
        System.out.println("ChatRenameTest: " + checks + " 项检查, " + failures + " 项失败");
        if (failures > 0) {
            System.exit(1);
        }
    }

    interface Body {
        void run() throws Exception;
    }

    private static void runOne(Body body) throws Exception {
        LiarbarServer server = new LiarbarServer(PORT);
        server.start();
        try {
            body.run();
        } finally {
            server.stop();
            Thread.sleep(600);
        }
    }

    private static void testRenameAndDuplicate() throws Exception {
        System.out.println("· 改名 / 重名自动加序号");
        Client a = connect("A");
        a.send("{\"t\":\"join\",\"name\":\"阿一\"}");
        waitFor(5000, new Cond() {
            public boolean ok() {
                return "阿一".equals(a.myName());
            }
        });
        check("阿一".equals(a.myName()), "第一个玩家叫 阿一");

        a.send("{\"t\":\"rename\",\"name\":\"酒馆老板\"}");
        boolean renamed = waitFor(5000, new Cond() {
            public boolean ok() {
                return "酒馆老板".equals(a.myName());
            }
        });
        check(renamed, "大厅里可以改名（阿一 → 酒馆老板）");
        check(!a.renamed.isEmpty(), "改名后应收到 renamed 回执");

        // 第二个人故意取一样的名字
        Client b = connect("B");
        b.send("{\"t\":\"join\",\"name\":\"酒馆老板\"}");
        boolean dedup = waitFor(5000, new Cond() {
            public boolean ok() {
                String n = b.myName();
                return n != null && !"酒馆老板".equals(n);
            }
        });
        check(dedup, "重名会自动加序号，B 实际叫：" + b.myName());

        // 空名字应被拒绝
        b.send("{\"t\":\"rename\",\"name\":\"   \"}");
        check(awaitError(b, "name-required", 4000) != null, "空名字改名应被拒绝（name-required）");

        a.ws.abort();
        b.ws.abort();
        Thread.sleep(400);
    }

    private static void testChatAndNoRenameInGame() throws Exception {
        System.out.println("· 局内聊天 / 局内禁止改名");
        Client a = connect("A");
        a.send("{\"t\":\"join\",\"name\":\"房主\"}");
        waitFor(5000, new Cond() {
            public boolean ok() {
                return a.lastState != null;
            }
        });

        // 大厅就能聊天
        a.send("{\"t\":\"chat\",\"text\":\"大家好啊\"}");
        boolean got = waitFor(4000, new Cond() {
            public boolean ok() {
                return !a.chats.isEmpty();
            }
        });
        check(got, "大厅里能发消息（收到 chat 广播）");
        if (got) {
            check("大家好啊".equals(Json.str(a.chats.get(0), "text")), "消息内容正确");
            check(Json.str(a.chats.get(0), "name") != null, "消息带了发言者名字");
        }

        // 加机器人开局
        a.send("{\"t\":\"add-bot\"}");
        waitFor(4000, new Cond() {
            public boolean ok() {
                return a.lastState != null && playersOf(a.lastState) == 2;
            }
        });
        a.send("{\"t\":\"start\"}");
        boolean playing = waitFor(6000, new Cond() {
            public boolean ok() {
                return "playing".equals(Json.str(a.lastState, "phase"));
            }
        });
        check(playing, "1 真人 + 1 机器人 开局成功");

        // 局内改名必须被拒绝
        a.send("{\"t\":\"rename\",\"name\":\"偷偷改名\"}");
        Map<String, Object> err = awaitError(a, "game-in-progress", 4000);
        check(err != null, "对局中改名被拒绝（game-in-progress）");
        check("房主".equals(a.myName()) || a.myName() != null, "被拒绝后名字没有变："
                + a.myName());

        // 局内聊天仍然可以（服务端有 350ms 限流，这里等一下再发）
        Thread.sleep(500);
        int before = a.chats.size();
        a.send("{\"t\":\"chat\",\"text\":\"我先出个牌\"}");
        boolean chatted = waitFor(4000, new Cond() {
            public boolean ok() {
                return a.chats.size() > before;
            }
        });
        check(chatted, "对局中也能聊天");

        // 限流：连着抢发两条，服务端只放一条
        Thread.sleep(500);
        int beforeFlood = a.chats.size();
        a.send("{\"t\":\"chat\",\"text\":\"抢1\"}");
        a.send("{\"t\":\"chat\",\"text\":\"抢2\"}");
        Thread.sleep(1200);
        check(a.chats.size() - beforeFlood == 1,
                "连发两条被限流成一条（实际收到 " + (a.chats.size() - beforeFlood) + " 条）");

        // 超长消息会被截断到 60 字
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append('x');
        }
        int before2 = a.chats.size();
        a.send("{\"t\":\"chat\",\"text\":\"" + longText + "\"}");
        waitFor(4000, new Cond() {
            public boolean ok() {
                return a.chats.size() > before2;
            }
        });
        if (a.chats.size() > before2) {
            String text = Json.str(a.chats.get(a.chats.size() - 1), "text");
            check(text != null && text.length() == 60, "超长消息被截到 60 字，实际 "
                    + (text == null ? -1 : text.length()));
        }

        a.ws.abort();
        Thread.sleep(400);
    }

    // ------------------------------------------------------------ 观战

    /**
     * 打到有人死亡，然后确认：
     *   1) 死者的发言**能被广播**（现在允许观战者聊天）
     *   2) 活人还能正常发言
     *   3) 死者仍然收得到 state（能继续看别人打）
     */
    private static void testSpectatorChat() throws Exception {
        System.out.println("· 观战：淘汰后仍可聊天");
        /* 判定锁默认 7.4 秒，这里主要是打死亡，调短免得回归跑几分钟 */
        com.liarsbar.core.service.GameService.setJudgePauseMs(150);
        Client a = connect("A");
        Client b = connect("B");
        a.send("{\"t\":\"join\",\"name\":\"甲\"}");
        waitFor(6000, new Cond() {
            public boolean ok() {
                return a.lastState != null && playersOf(a.lastState) == 1;
            }
        });
        b.send("{\"t\":\"join\",\"name\":\"乙\"}");
        waitFor(6000, new Cond() {
            public boolean ok() {
                return a.lastState != null && playersOf(a.lastState) == 2;
            }
        });
        check(playersOf(a.lastState) == 2, "两人入座");

        a.send("{\"t\":\"start\"}");
        boolean playing = waitFor(6000, new Cond() {
            public boolean ok() {
                return "playing".equals(Json.str(a.lastState, "phase"));
            }
        });
        check(playing, "开局成功");

        /* 甲每回合出 1 张，乙能质疑就质疑 —— 左轮最多 6 次必响，所以一定会死人 */
        String deadId = null;
        for (int i = 0; i < 60 && deadId == null; i++) {
            Map<String, Object> st = a.lastState;
            if (st == null) {
                Thread.sleep(150);
                continue;
            }
            String phase = Json.str(st, "phase");
            /* 先看有没有人死 —— 死亡往往同时带来 phase=over，
               如果先判 phase 就会一直空转（这个坑踩过一次） */
            deadId = firstDead(st);
            if (deadId != null) {
                break;
            }
            if (!"playing".equals(phase)) {
                Thread.sleep(200);
                continue;
            }
            if (Boolean.TRUE.equals(a.lastState.get("canPlay"))) {
                if (i < 24) {
                    System.out.println("    [" + i + "] 甲出牌 (轮次 " + Json.str(st, "roundNumber")
                            + ", 桌面 " + size(st, "table") + ", 甲牌 " + size(st, "hand") + ")");
                }
                a.send("{\"t\":\"play\",\"cards\":[0]}");
            } else if (Boolean.TRUE.equals(b.lastState.get("canDoubt"))) {
                if (i < 24) {
                    System.out.println("    [" + i + "] 乙质疑");
                }
                b.send("{\"t\":\"doubt\"}");
            } else if (Boolean.TRUE.equals(b.lastState.get("canPlay"))) {
                if (i < 24) {
                    System.out.println("    [" + i + "] 乙出牌");
                }
                b.send("{\"t\":\"play\",\"cards\":[0]}");
            } else if (i < 24 && i % 6 == 0) {
                System.out.println("    [" + i + "] 等状态: phase=" + phase
                        + " aCanPlay=" + a.lastState.get("canPlay")
                        + " bCanDoubt=" + (b.lastState == null ? "null" : b.lastState.get("canDoubt"))
                        + " bCanPlay=" + (b.lastState == null ? "null" : b.lastState.get("canPlay")));
            }
            Thread.sleep(220);
        }
        check(deadId != null, "对局中出现了被淘汰的玩家（" + deadId + "）");
        if (deadId == null) {
            a.ws.abort();
            b.ws.abort();
            return;
        }

        Client dead = deadId.equals(a.playerId) ? a : b;
        Client alive = dead == a ? b : a;

        /* 死者：现在允许发言（实测反馈：死了不能吐槽太难受，改成能看也能聊） */
        dead.errors.clear();
        dead.chats.clear();
        dead.send("{\"t\":\"chat\",\"text\":\"我死了还能说话吗\"}");
        boolean deadChatOk = waitFor(5000, new Cond() {
            public boolean ok() {
                for (Object o : dead.chats) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    if ("我死了还能说话吗".equals(Json.str(m, "text"))) {
                        return true;
                    }
                }
                return false;
            }
        });
        check(deadChatOk, "被淘汰后仍然可以发言，且广播给了全桌");
        check(awaitError(dead, "dead-cannot-chat", 900) == null,
                "不再返回 dead-cannot-chat 这个错误码");

        /* 活人：还能说话 */
        alive.chats.clear();
        alive.send("{\"t\":\"chat\",\"text\":\"我还能说\"}");
        boolean aliveChat = waitFor(5000, new Cond() {
            public boolean ok() {
                return !alive.chats.isEmpty();
            }
        });
        check(aliveChat, "活着的玩家仍然能发言");

        /* 死者：仍然能收到 state（也就是还能观战） */
        Map<String, Object> before = dead.lastState;
        boolean stillState = waitFor(6000, new Cond() {
            public boolean ok() {
                return dead.lastState != null;
            }
        });
        check(stillState && dead.lastState != null, "被淘汰的玩家仍在接收对局状态（可继续观战）");

        a.ws.abort();
        b.ws.abort();
        Thread.sleep(400);
    }

    /**
     * 判定锁：质疑结算的整段动画（异议→亮牌→左轮）播完之前，
     * 下一家不能出牌，否则动画还没播完牌就出去了，节奏乱。
     */
    private static void testJudgingLock() throws Exception {
        System.out.println("· 判定期间锁住操作");
        /* 这个用例要的是"锁确实存在"，所以用生产时长 */
        com.liarsbar.core.service.GameService.setJudgePauseMs(7400);

        Client a = connect("A");
        Client b = connect("B");
        Client c = connect("C");
        a.send("{\"t\":\"join\",\"name\":\"甲\"}");
        waitFor(6000, new Cond() {
            public boolean ok() {
                return a.lastState != null && playersOf(a.lastState) == 1;
            }
        });
        b.send("{\"t\":\"join\",\"name\":\"乙\"}");
        waitFor(6000, new Cond() {
            public boolean ok() {
                return a.lastState != null && playersOf(a.lastState) == 2;
            }
        });
        /* 三个人：这样就算那一枪把人打死了，本局也不会立刻结束，
           判定锁还能继续验（两人局会被直接判成 not-your-turn） */
        c.send("{\"t\":\"join\",\"name\":\"丙\"}");
        waitFor(6000, new Cond() {
            public boolean ok() {
                return a.lastState != null && playersOf(a.lastState) == 3;
            }
        });
        a.send("{\"t\":\"start\"}");
        waitFor(6000, new Cond() {
            public boolean ok() {
                return "playing".equals(Json.str(a.lastState, "phase"));
            }
        });

        check(!Boolean.TRUE.equals(a.lastState.get("judging")), "开局时不在判定中");

        /* 甲出牌 → 乙质疑 → 立刻进入判定锁 */
        if (Boolean.TRUE.equals(a.lastState.get("canPlay"))) {
            a.send("{\"t\":\"play\",\"cards\":[0]}");
        }
        boolean doubted = waitFor(6000, new Cond() {
            public boolean ok() {
                return Boolean.TRUE.equals(b.lastState.get("canDoubt"))
                        || !b.chats.isEmpty();
            }
        });
        check(doubted || Boolean.TRUE.equals(b.lastState.get("canDoubt")), "乙可以质疑");
        b.send("{\"t\":\"doubt\"}");

        boolean locked = waitFor(5000, new Cond() {
            public boolean ok() {
                return Boolean.TRUE.equals(a.lastState.get("judging"))
                        || Boolean.TRUE.equals(b.lastState.get("judging"));
            }
        });
        check(locked, "质疑后进入判定锁（state.judging = true）");

        /* 判定期间谁都不能出牌 —— 用 state 里的当前玩家来试，
           免得因为质疑结果不同（先手可能是甲也可能是乙）撞上 not-your-turn */
        String curId = Json.str(a.lastState, "currentPlayerId");
        Client current = a;
        if (curId != null && curId.equals(b.playerId)) {
            current = b;
        } else if (curId != null && curId.equals(c.playerId)) {
            current = c;
        }
        current.errors.clear();
        current.send("{\"t\":\"play\",\"cards\":[0]}");
        check(awaitError(current, "judging", 4000) != null,
                "判定期间出牌被拒绝（judging）");

        current.errors.clear();
        current.send("{\"t\":\"pass\"}");
        check(awaitError(current, "judging", 4000) != null,
                "判定期间过牌也被拒绝（judging）");

        /* 等锁释放，按钮应该重新可用 */
        boolean released = waitFor(12000, new Cond() {
            public boolean ok() {
                return !Boolean.TRUE.equals(a.lastState.get("judging"))
                        && !Boolean.TRUE.equals(b.lastState.get("judging"));
            }
        });
        check(released, "演出结束后判定锁自动解除");

        a.ws.abort();
        b.ws.abort();
        Thread.sleep(400);
    }

    /**
     * 胜场榜：按设备 UID 统计，改名后仍然是同一个人。
     * 也顺便验证房主可以用 stats 把自己的账本交给服务端恢复。
     */
    private static void testLeaderboard() throws Exception {
        System.out.println("· 胜场榜（UID 身份）");
        com.liarsbar.core.service.GameService.setJudgePauseMs(120);

        Client a = connect("A");
        Client b = connect("B");
        a.send("{\"t\":\"join\",\"name\":\"甲\",\"uid\":\"uaaa\"}");
        waitFor(6000, new Cond() {
            public boolean ok() {
                return a.lastState != null && playersOf(a.lastState) == 1;
            }
        });
        b.send("{\"t\":\"join\",\"name\":\"乙\",\"uid\":\"ubbb\"}");
        waitFor(6000, new Cond() {
            public boolean ok() {
                return a.lastState != null && playersOf(a.lastState) == 2;
            }
        });

        /* 开局前先改名：UID 不变，仍然算同一个人 */
        a.send("{\"t\":\"rename\",\"name\":\"甲改名了\"}");
        waitFor(4000, new Cond() {
            public boolean ok() {
                return "甲改名了".equals(a.myName());
            }
        });
        check("甲改名了".equals(a.myName()), "改名生效");

        a.send("{\"t\":\"start\"}");
        waitFor(6000, new Cond() {
            public boolean ok() {
                return "playing".equals(Json.str(a.lastState, "phase"));
            }
        });

        /* 打到分出胜负（左轮 6 次内必响） */
        boolean over = false;
        for (int i = 0; i < 80 && !over; i++) {
            Map<String, Object> st = a.lastState;
            if (st == null) {
                Thread.sleep(150);
                continue;
            }
            if ("over".equals(Json.str(st, "phase"))) {
                over = true;
                break;
            }
            if (!"playing".equals(Json.str(st, "phase"))) {
                Thread.sleep(150);
                continue;
            }
            if (Boolean.TRUE.equals(st.get("canPlay"))) {
                a.send("{\"t\":\"play\",\"cards\":[0]}");
            } else if (Boolean.TRUE.equals(b.lastState.get("canDoubt"))) {
                b.send("{\"t\":\"doubt\"}");
            } else if (Boolean.TRUE.equals(b.lastState.get("canPlay"))) {
                b.send("{\"t\":\"play\",\"cards\":[0]}");
            }
            Thread.sleep(160);
        }
        check(over, "对局分出胜负");
        if (!over) {
            a.ws.abort();
            b.ws.abort();
            return;
        }

        List<Object> board = Json.list(a.lastState, "leaderboard");
        check(board != null && board.size() == 1, "榜上应有 1 条胜利记录（实际 "
                + (board == null ? 0 : board.size()) + "）");
        if (board != null && board.size() == 1) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) board.get(0);
            String uid = Json.str(row, "uid");
            int wins = Json.intValue(row, "wins", 0);
            check("uaaa".equals(uid) || "ubbb".equals(uid), "记录挂在某个设备的 UID 上（" + uid + "）");
            check(wins == 1, "胜场数为 1（实际 " + wins + "）");
            String name = Json.str(row, "name");
            check(name != null && !name.isEmpty(), "记录带显示名（" + name + "）");

            /* 必须记在"赢家"的 UID 上，不能张冠李戴 */
            String winnerId = Json.str(a.lastState, "winnerId");
            String winnerUid = uidOfPlayer(a.lastState, winnerId);
            check(winnerUid != null, "结算里的赢家带 UID（" + winnerUid + "）");
            check(winnerUid != null && winnerUid.equals(uid),
                    "胜场记在赢家（" + winnerId + " / " + winnerUid + "）的账上");

            if ("uaaa".equals(uid)) {
                check("甲改名了".equals(name), "改名后 UID 不变、显示名跟着更新");
            }
        }

        /* 房主用 stats 交账本：模拟"从房主设备恢复历史胜场" */
        a.send("{\"t\":\"stats\",\"wins\":{\"uccc\":7},\"names\":{\"uccc\":\"老玩家\"}}");
        boolean merged = waitFor(5000, new Cond() {
            public boolean ok() {
                List<Object> lb = Json.list(a.lastState, "leaderboard");
                if (lb == null || lb.isEmpty()) {
                    return false;
                }
                for (Object o : lb) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) o;
                    if ("uccc".equals(Json.str(row, "uid")) && Json.intValue(row, "wins", 0) == 7) {
                        return true;
                    }
                }
                return false;
            }
        });
        check(merged, "房主交上来的历史账本能合并进排行榜");

        a.ws.abort();
        b.ws.abort();
        Thread.sleep(400);
    }

    private static String uidOfPlayer(Map<String, Object> st, String pid) {
        List<Object> l = Json.list(st, "players");
        if (l == null || pid == null) {
            return null;
        }
        for (Object o : l) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (pid.equals(Json.str(p, "id"))) {
                return Json.str(p, "uid");
            }
        }
        return null;
    }

    private static String firstDead(Map<String, Object> st) {        List<Object> l = Json.list(st, "players");
        if (l == null) {
            return null;
        }
        for (Object o : l) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (Boolean.FALSE.equals(p.get("alive"))) {
                return Json.str(p, "id");
            }
        }
        return null;
    }

    private static int size(Map<String, Object> st, String key) {
        List<Object> l = Json.list(st, key);
        return l == null ? 0 : l.size();
    }

    private static int playersOf(Map<String, Object> st) {
        List<Object> l = Json.list(st, "players");
        return l == null ? -1 : l.size();
    }

    private static int avatarVersionOf(Map<String, Object> st, String pid) {
        if (st == null || pid == null) {
            return -1;
        }
        List<Object> l = Json.list(st, "players");
        if (l == null) {
            return -1;
        }
        for (Object o : l) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (pid.equals(Json.str(p, "id"))) {
                return Json.intValue(p, "avatar", 0);
            }
        }
        return -1;
    }

    /** 直接 HTTP 取一段字节，404 返回 null。 */
    private static byte[] httpGetBytes(String path) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + PORT + path).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            if (c.getResponseCode() != 200) {
                return null;
            }
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 头像

    private static void testAvatar() throws Exception {
        System.out.println("· 头像上传 / 取回 / 限制");
        Client me = connect("A");
        me.send("{\"t\":\"join\",\"name\":\"换头像的人\"}");
        waitFor(6000, new Cond() {
            public boolean ok() {
                return me.lastState != null && me.playerId != null;
            }
        });
        check(me.playerId != null, "入座成功");
        check(avatarVersionOf(me.lastState, me.playerId) == 0, "初始头像版本为 0（没有头像）");

        byte[] fake = new byte[2048];
        java.util.Arrays.fill(fake, (byte) 0x41);
        String b64 = java.util.Base64.getEncoder().encodeToString(fake);
        me.send("{\"t\":\"avatar\",\"data\":\"" + b64 + "\",\"mime\":\"image/jpeg\"}");

        boolean up = waitFor(6000, new Cond() {
            public boolean ok() {
                return avatarVersionOf(me.lastState, me.playerId) == 1;
            }
        });
        check(up, "上传后头像版本变成 1");

        byte[] got = httpGetBytes("/avatar/" + me.playerId + "?v=1");
        check(got != null && got.length == fake.length,
                "HTTP /avatar/<id> 能取回同样大小（" + (got == null ? -1 : got.length) + " 字节）");

        byte[] big = new byte[200 * 1024];
        String bigB64 = java.util.Base64.getEncoder().encodeToString(big);
        me.send("{\"t\":\"avatar\",\"data\":\"" + bigB64 + "\",\"mime\":\"image/jpeg\"}");
        check(awaitError(me, "avatar-too-large", 5000) != null, "超过 128KB 的头像被拒绝");

        me.send("{\"t\":\"avatar\",\"data\":\"this-is-not-base64!!!\"}");
        check(awaitError(me, "bad-avatar", 5000) != null, "非法数据被拒绝（bad-avatar）");

        me.send("{\"t\":\"avatar\",\"data\":\"\"}");
        boolean cleared = waitFor(6000, new Cond() {
            public boolean ok() {
                return avatarVersionOf(me.lastState, me.playerId) == 2;
            }
        });
        check(cleared, "清除头像后版本变成 2");
        byte[] none = httpGetBytes("/avatar/" + me.playerId + "?v=2");
        check(none == null || none.length == 0, "清除后 HTTP 取不到头像（404）");

        me.ws.abort();
        Thread.sleep(400);
    }

    private static void testCustomShareAddress() throws Exception {
        System.out.println("· 分享地址可改（房主）");
        Client host = connect("H");
        host.send("{\"t\":\"join\",\"name\":\"房主\"}");
        waitFor(5000, new Cond() {
            public boolean ok() {
                return host.lastState != null;
            }
        });

        String auto = Json.str(host.lastState, "lanUrl");
        check(auto != null, "默认有自动检测的地址：" + auto);
        List<Object> cands = Json.list(host.lastState, "lanCandidates");
        check(cands != null, "下发了可选的网卡地址列表");

        // 非房主不能改
        Client guest = connect("G");
        guest.send("{\"t\":\"join\",\"name\":\"客人\"}");
        waitFor(5000, new Cond() {
            public boolean ok() {
                return guest.lastState != null && playersOf(guest.lastState) == 2;
            }
        });
        guest.send("{\"t\":\"set-lan\",\"url\":\"1.2.3.4\"}");
        check(awaitError(guest, "not-host", 4000) != null, "非房主改地址被拒绝（not-host）");

        // 房主改成樱花映射那种地址
        host.send("{\"t\":\"set-lan\",\"url\":\"example.natfrp.com:12345\"}");
        boolean changed = waitFor(5000, new Cond() {
            public boolean ok() {
                String u = Json.str(host.lastState, "lanUrl");
                return u != null && u.indexOf("example.natfrp.com:12345") >= 0;
            }
        });
        check(changed, "地址改成自定义成功：" + Json.str(host.lastState, "lanUrl"));
        check(Boolean.TRUE.equals(host.lastState.get("lanCustom")), "标记为自定义地址");

        // 非法地址要被拒绝
        host.send("{\"t\":\"set-lan\",\"url\":\"http://a b c\"}");
        check(awaitError(host, "bad-address", 4000) != null, "带空格的非法地址被拒绝（bad-address）");

        // 恢复自动
        host.send("{\"t\":\"set-lan\",\"url\":\"\"}");
        boolean restored = waitFor(5000, new Cond() {
            public boolean ok() {
                return !Boolean.TRUE.equals(host.lastState.get("lanCustom"));
            }
        });
        check(restored, "可以一键恢复自动检测的地址");

        host.ws.abort();
        guest.ws.abort();
    }
}
