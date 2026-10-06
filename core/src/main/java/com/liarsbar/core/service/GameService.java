package com.liarsbar.core.service;

import com.liarsbar.core.json.Json;
import com.liarsbar.core.model.Card;
import com.liarsbar.core.model.Game;
import com.liarsbar.core.model.Play;
import com.liarsbar.core.model.Player;
import com.liarsbar.core.model.RuleException;
import com.liarsbar.core.net.HttpWsServer;
import com.liarsbar.core.net.WebSocketConn;
import com.liarsbar.core.util.Base64;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 房间服务：单房间、2~4 人，负责玩家生命周期、消息分发与状态广播。
 *
 * <p>所有游戏状态变更都在 {@link #lock} 内完成；给客户端发消息走的是
 * {@link WebSocketConn} 的异步队列，不会因为某个慢客户端阻塞游戏线程。</p>
 */
public final class GameService implements WebSocketConn.Handler, HttpWsServer.AvatarSource {

    /** 掉线后保留座位的时长。 */
    public static final long RECONNECT_GRACE_MS = 60_000L;
    /** 连上后必须在此时限内发送 join。 */
    public static final long JOIN_TIMEOUT_MS = 5_000L;
    private static final int MAX_NAME_LEN = 12;

    public static final String PHASE_LOBBY = "lobby";
    public static final String PHASE_PLAYING = "playing";
    public static final String PHASE_OVER = "over";

    private final Object lock = new Object();
    private volatile String lanUrl;
    /** 公网入口（对外地址），由启动器传入；写进 state.publicUrl 供大厅分享。 */
    private volatile String publicUrl;
    /** 自动检测出来的地址（用户没手工改时用它）。 */
    private volatile String autoLanUrl;
    /** 用户是否手工改过分享地址。 */
    private volatile boolean lanCustom;
    private int httpPort = 8080;
    private final long startedAt = System.currentTimeMillis();

    private final List<Player> players = new ArrayList<Player>();
    private final Map<String, Player> byId = new HashMap<String, Player>();
    private final Map<String, Player> byToken = new HashMap<String, Player>();
    private final Map<String, WebSocketConn> connsByPlayer = new HashMap<String, WebSocketConn>();
    private final Map<WebSocketConn, Player> playersByConn = new HashMap<WebSocketConn, Player>();
    private final Map<WebSocketConn, Long> pendingConns = new HashMap<WebSocketConn, Long>();

    private Game game;
    private String phase = PHASE_LOBBY;
    private int idSeq = 1;
    private int botSeq = 0;
    private Map<String, Object> lastReveal;
    /** 机器人下一次行动的时间戳（0 = 还没排期）。 */
    private long botActAt;
    /**
     * 判定演出的结束时间戳。质疑结算会播一长串动画
     * （異議あり！ 2.1s → 亮牌 2.0s → 左轮 3.0s ≈ 7.1s），
     * 这段时间内谁都不能出牌/质疑，否则下一家会在动画里偷偷出牌，
     * 节奏会乱、玩家也看不清判定结果。
     */
    private long judgeUntil;
    /** 已经为当前这次判定广播过"解锁"状态了没。 */
    private boolean judgeReleased = true;

    /**
     * 胜场榜：UID → 胜场数 / 最近用过的名字。
     * 服务端只在内存里维护，**持久化由房主的设备负责**（客户端把 leaderboard
     * 存进本机 localStorage，下次房主进来时用 stats 消息回填）。
     */
    private final Map<String, Integer> winsByUid = new HashMap<String, Integer>();
    private final Map<String, String> namesByUid = new HashMap<String, String>();

    public GameService(String lanUrl) {
        this.lanUrl = lanUrl;
    }

    // ------------------------------------------------------------ 连接生命周期

    @Override
    public void onOpen(WebSocketConn conn) {
        synchronized (lock) {
            pendingConns.put(conn, Long.valueOf(System.currentTimeMillis()));
        }
    }

    @Override
    public void onText(WebSocketConn conn, String text) {
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(text);
        } catch (Exception e) {
            sendError(conn, "bad-message", "消息不是合法的 JSON");
            return;
        }
        String t = Json.str(msg, "t");

        synchronized (lock) {
            Player me = playersByConn.get(conn);

            if (me == null) {
                if ("join".equals(t)) {
                    pendingConns.remove(conn);
                    handleJoin(conn, msg);
                } else {
                    sendError(conn, "not-in-room", "请先发送 join 加入房间");
                }
                return;
            }

            if ("join".equals(t)) {
                // 同一连接重复 join：只在大厅/结算后用来改名
                String name = cleanName(Json.str(msg, "name"));
                if (name != null) {
                    if (PHASE_PLAYING.equals(phase)) {
                        sendError(conn, "game-in-progress", "对局中不能改名");
                    } else {
                        me.setName(uniqueName(name, me));
                        broadcastState();
                    }
                }
                return;
            }

            switch (t == null ? "" : t) {
                case "ping":
                    conn.send(Json.write(simple("pong")));
                    break;
                case "rename":
                    handleRename(conn, me, msg);
                    break;
                case "add-bot":
                    handleAddBot(conn, me);
                    break;
                case "remove-bot":
                    handleRemoveBot(conn, me);
                    break;
                case "set-lan":
                    handleSetLan(conn, me, msg);
                    break;
                case "chat":
                    handleChat(conn, me, msg);
                    break;
                case "avatar":
                    handleAvatar(conn, me, msg);
                    break;
                case "play":
                    handlePlay(conn, me, msg);
                    break;
                case "doubt":
                    handleDoubt(conn, me);
                    break;
                case "pass":
                    handlePass(conn, me);
                    break;
                case "stats":
                    handleStats(conn, me, msg);
                    break;
                case "start":
                    handleStart(conn, me);
                    break;
                case "restart":
                    handleRestart(conn, me);
                    break;
                default:
                    sendError(conn, "bad-message", "未知消息类型: " + t);
            }
        }
    }

    @Override
    public void onClose(WebSocketConn conn) {
        synchronized (lock) {
            pendingConns.remove(conn);
            Player p = playersByConn.remove(conn);
            if (p == null) {
                return;
            }
            // 如果该玩家已经用新连接重连，就不要把新连接顶掉
            if (connsByPlayer.get(p.getId()) != conn) {
                return;
            }
            connsByPlayer.remove(p.getId());
            p.setConnected(false);
            broadcastState();
        }
    }

    /** 由服务器定时调用（建议 1 秒一次）。 */
    public void tick() {
        synchronized (lock) {
            long now = System.currentTimeMillis();

            /* 判定演出结束 → 广播一次，把大家的按钮重新点亮 */
            if (!judgeReleased && now >= judgeUntil) {
                judgeReleased = true;
                broadcastState();
            }

            // 1. 迟迟不 join 的连接直接踢掉
            List<WebSocketConn> stale = new ArrayList<WebSocketConn>();
            for (Map.Entry<WebSocketConn, Long> e : pendingConns.entrySet()) {
                if (now - e.getValue().longValue() > JOIN_TIMEOUT_MS) {
                    stale.add(e.getKey());
                }
            }
            for (WebSocketConn c : stale) {
                pendingConns.remove(c);
                c.close(1008, "join timeout");
            }

            // 2. 掉线超时处理
            List<Player> expired = new ArrayList<Player>();
            for (Player p : players) {
                if (!p.isConnected() && p.getDisconnectedAt() > 0
                        && now - p.getDisconnectedAt() > RECONNECT_GRACE_MS) {
                    expired.add(p);
                }
            }
            if (expired.isEmpty()) {
                driveBots(now);
                return;
            }
            boolean changed = false;
            for (Player p : expired) {
                if (PHASE_LOBBY.equals(phase) || game == null) {
                    removePlayer(p);
                    changed = true;
                } else {
                    Game.TurnFix fix = game.eliminate(p);
                    changed = true;
                    if (fix.over) {
                        phase = PHASE_OVER;
                    } else if (fix.roundEnded) {
                        game.startRound(fix.nextFirst);
                    }
                }
            }
            if (changed) {
                ensureHost();               // 房主跑路了就把房主交给最早进来的那位
                broadcastState();
            }
            driveBots(now);
        }
    }

    /**
     * 保证房间里始终有房主。
     * 设计上"房主 = 第一个连进来的设备"：如果房主掉线被清掉，
     * 就把房主身份顺延给剩下最早进来的玩家（否则没人能开始游戏）。
     */
    private void ensureHost() {
        boolean hasHost = false;
        for (Player p : players) {
            if (p.isHost()) {
                hasHost = true;
                break;
            }
        }
        if (hasHost || players.isEmpty()) {
            return;
        }
        Player next = null;
        for (Player p : players) {
            if (!p.isConnected() && !p.isBot()) {
                continue;
            }
            if (next == null || p.getSeat() < next.getSeat()) {
                next = p;
            }
        }
        if (next == null) {
            next = players.get(0);
        }
        next.setHost(true);
        System.out.println("  [host] 房主顺延给 " + next.getName());
    }

    /**
     * 轮到机器人时，等一小会儿（像在思考）再让它行动。
     * 机器人走的是和人类完全相同的 {@link #doPlay}/{@link #doDoubt} 路径，
     * 所以它同样受规则约束，不会"作弊"。
     */
    private void driveBots(long now) {
        if (!PHASE_PLAYING.equals(phase) || game == null) {
            botActAt = 0;
            return;
        }
        /* 判定演出期间机器人也按兵不动，等动画播完再出手 */
        if (now < judgeUntil) {
            botActAt = 0;
            return;
        }
        Player cur = game.getCurrentPlayer();
        if (cur == null || !cur.isBot()) {
            botActAt = 0;
            return;
        }
        if (botActAt == 0) {
            botActAt = now + 600L + (long) (Math.random() * 700L);   // 0.6~1.3 秒，像在思考
            return;
        }
        if (now < botActAt) {
            return;
        }
        botActAt = 0;

        BotBrain.Action action;
        try {
            action = BotBrain.decide(game, cur);
        } catch (Throwable t) {
            action = null;
        }
        if (action == null) {
            return;
        }
        try {
            if (action.doubt) {
                doDoubt(cur);
            } else if (action.pass) {
                doPass(cur);
            } else {
                doPlay(cur, action.cards);
            }
        } catch (RuleException e) {
            // 机器人不该犯规；真犯规了就让它随便出一张兜底，避免整局卡住
            try {
                if (!cur.getHand().isEmpty()) {
                    List<Integer> one = new ArrayList<Integer>();
                    one.add(Integer.valueOf(0));
                    doPlay(cur, one);
                }
            } catch (RuleException ignored) {
                // 实在动不了就等下一次 tick
            }
        }
    }

    // ------------------------------------------------------------ 消息处理

    private void handleJoin(WebSocketConn conn, Map<String, Object> msg) {
        String name = cleanName(Json.str(msg, "name"));
        if (name == null) {
            name = "玩家" + idSeq;
        }
        String token = Json.str(msg, "token");

        // 1. 断线重连
        if (token != null && !token.isEmpty()) {
            Player existing = byToken.get(token);
            if (existing != null) {
                WebSocketConn old = connsByPlayer.get(existing.getId());
                if (old != null && old != conn) {
                    playersByConn.remove(old);
                    old.close(1000, "replaced by new connection");
                }
                connsByPlayer.put(existing.getId(), conn);
                playersByConn.put(conn, existing);
                existing.setConnected(true);
                String reUid = cleanUid(Json.str(msg, "uid"));
                if (reUid != null && !reUid.isEmpty()) {
                    existing.setUid(reUid);          // 同一台设备重连，UID 保持一致
                }
                // 对局中不接受改名，只恢复座位
                if (!PHASE_PLAYING.equals(phase) && !name.startsWith("玩家")) {
                    existing.setName(name);
                }
                sendWelcome(conn, existing);
                broadcastState();
                return;
            }
        }

        // 2. 对局进行中不接受新玩家（否则房间玩家列表会与 Game 内部的列表不一致）
        if (PHASE_PLAYING.equals(phase)) {
            sendError(conn, "game-in-progress", "本局正在进行，请等这一局结束后再加入");
            return;
        }

        // 3. 满员
        if (players.size() >= Game.MAX_PLAYERS) {
            sendError(conn, "room-full", "房间已满（最多 " + Game.MAX_PLAYERS + " 人）");
            return;
        }

        // 4. 新玩家
        String id = "p" + (idSeq++);
        Player p = new Player(id, UUID.randomUUID().toString().replace("-", ""), players.size(),
                uniqueName(name, null));
        p.setUid(cleanUid(Json.str(msg, "uid")));
        p.setHost(players.isEmpty());
        players.add(p);
        byId.put(id, p);
        byToken.put(p.getToken(), p);
        connsByPlayer.put(id, conn);
        playersByConn.put(conn, p);

        sendWelcome(conn, p);
        broadcastState();
    }

    // ------------------------------------------------------------ 胜场榜

    /** UID 只允许字母数字和短横线，最长 40 字符（客户端生成，别塞奇怪东西）。 */
    private static String cleanUid(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[^A-Za-z0-9_-]", "");
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** 记一次胜场（按 UID 累加，改名字不影响）。 */
    private void recordWin(Player winner) {
        if (winner == null) {
            return;
        }
        String uid = winner.getUid();
        if (uid == null || uid.isEmpty()) {
            return;
        }
        Integer cur = winsByUid.get(uid);
        winsByUid.put(uid, Integer.valueOf(cur == null ? 1 : cur.intValue() + 1));
        namesByUid.put(uid, winner.getName());
    }

    /** 排行榜（前 10 名，胜场从多到少）。 */
    private List<Object> leaderboard() {
        List<String> uids = new ArrayList<String>(winsByUid.keySet());
        Collections.sort(uids, new Comparator<String>() {
            public int compare(String a, String b) {
                Integer wa = winsByUid.get(a);
                Integer wb = winsByUid.get(b);
                int va = wa == null ? 0 : wa.intValue();
                int vb = wb == null ? 0 : wb.intValue();
                if (va != vb) {
                    return vb - va;
                }
                return a.compareTo(b);
            }
        });
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < uids.size() && i < 10; i++) {
            String uid = uids.get(i);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("uid", uid);
            m.put("name", namesByUid.get(uid));
            m.put("wins", winsByUid.get(uid));
            out.add(m);
        }
        return out;
    }

    /**
     * 房主把自己设备上存的排行榜回填给服务端。
     * 服务端内存里的数据在重启后就没了，持久化放在房主设备上，
     * 所以每次房主进房都会把这份"账本"交上来。
     */
    private void handleStats(WebSocketConn conn, Player me, Map<String, Object> msg) {
        if (!me.isHost()) {
            sendError(conn, "not-host", "只有房主能同步排行榜");
            return;
        }
        Map<String, Object> wins = Json.map(msg, "wins");
        Map<String, Object> names = Json.map(msg, "names");
        if (wins == null) {
            sendError(conn, "bad-message", "排行榜数据格式不对");
            return;
        }
        int merged = 0;
        for (Map.Entry<String, Object> e : wins.entrySet()) {
            String uid = cleanUid(e.getKey());
            if (uid == null || !(e.getValue() instanceof Number)) {
                continue;
            }
            int n = ((Number) e.getValue()).intValue();
            if (n <= 0) {
                continue;
            }
            Integer cur = winsByUid.get(uid);
            /* 取较大值：房主设备上的是历史账本，服务端内存里的是本次新增 */
            if (cur == null || n > cur.intValue()) {
                winsByUid.put(uid, Integer.valueOf(n));
                merged++;
            }
        }
        if (names != null) {
            for (Map.Entry<String, Object> e : names.entrySet()) {
                String uid = cleanUid(e.getKey());
                if (uid != null && e.getValue() != null) {
                    namesByUid.put(uid, String.valueOf(e.getValue()));
                }
            }
        }
        if (merged > 0) {
            System.out.println("  [stats] 已从房主设备恢复 " + merged + " 条胜场记录");
        }
        broadcastState();
    }

    /** 改名：只在大厅/结算后允许；对局中禁止（免得牌桌上认不出谁是谁）。 */    private void handleRename(WebSocketConn conn, Player me, Map<String, Object> msg) {
        if (PHASE_PLAYING.equals(phase)) {
            sendError(conn, "game-in-progress", "对局中不能改名，等这一局结束再改");
            return;
        }
        String name = cleanName(Json.str(msg, "name"));
        if (name == null) {
            sendError(conn, "name-required", "名称不能为空");
            return;
        }
        String finalName = uniqueName(name, me);
        me.setName(finalName);
        broadcastState();

        Map<String, Object> ok = simple("renamed");
        ok.put("playerId", me.getId());
        ok.put("name", finalName);
        conn.send(Json.write(ok));
    }

    /**
     * 保证同一个房间里没有重名：撞名就自动加 2、3、4… 后缀。
     * （手机端几个人默认都叫"玩家"，不处理的话牌桌上根本分不清谁是谁。）
     */
    private String uniqueName(String desired, Player self) {
        if (!nameTaken(desired, self)) {
            return desired;
        }
        for (int n = 2; n <= 99; n++) {
            String suffix = String.valueOf(n);
            String base = desired;
            int maxBase = MAX_NAME_LEN - suffix.length();
            if (base.length() > maxBase) {
                base = base.substring(0, maxBase);
            }
            String candidate = base + suffix;
            if (!nameTaken(candidate, self)) {
                return candidate;
            }
        }
        return desired;
    }

    private boolean nameTaken(String name, Player self) {
        for (Player p : players) {
            if (p != self && p.getName() != null && p.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 添加一个机器人（补齐人数；只有大厅阶段能加）。 */
    private void handleAddBot(WebSocketConn conn, Player me) {
        if (!me.isHost()) {
            sendError(conn, "not-host", "只有房主可以添加机器人");
            return;
        }
        if (!PHASE_LOBBY.equals(phase)) {
            sendError(conn, "game-in-progress", "对局中不能添加机器人，等这局结束再补");
            return;
        }
        if (players.size() >= Game.MAX_PLAYERS) {
            sendError(conn, "room-full", "房间已满（最多 " + Game.MAX_PLAYERS + " 人）");
            return;
        }
        botSeq++;
        String id = "b" + botSeq;
        String name = uniqueName("机器人" + botSeq, null);
        Player bot = new Player(id, "bot-" + id, players.size(), name, true);
        bot.setConnected(true);
        players.add(bot);
        byId.put(id, bot);
        byToken.put(bot.getToken(), bot);
        broadcastState();
    }

    /** 移除最后一个机器人（大厅阶段）。 */
    private void handleRemoveBot(WebSocketConn conn, Player me) {
        if (!me.isHost()) {
            sendError(conn, "not-host", "只有房主可以移除机器人");
            return;
        }
        if (!PHASE_LOBBY.equals(phase)) {
            sendError(conn, "game-in-progress", "对局中不能移除机器人");
            return;
        }
        for (int i = players.size() - 1; i >= 0; i--) {
            Player p = players.get(i);
            if (p.isBot()) {
                removePlayer(p);
                broadcastState();
                return;
            }
        }
        sendError(conn, "no-bot", "房间里还没有机器人");
    }

    /**
     * 修改"分享给别人的地址"。房主自动检测的网卡可能不对（虚拟机网卡、多网卡），
     * 也可能想让异地朋友直接用映射出来的公网地址，所以允许手工改。
     * 传空字符串表示恢复自动检测。
     */
    private void handleSetLan(WebSocketConn conn, Player me, Map<String, Object> msg) {
        if (!me.isHost()) {
            sendError(conn, "not-host", "只有房主可以修改分享地址");
            return;
        }
        String raw = Json.str(msg, "url");
        if (raw == null || raw.trim().isEmpty()) {
            lanCustom = false;
            lanUrl = autoLanUrl;
            broadcastState();
            return;
        }
        String url = normalizeAddress(raw);
        if (url == null) {
            sendError(conn, "bad-address",
                    "地址格式不对（示例：192.168.1.5 或 example.com:12345）");
            return;
        }
        lanCustom = true;
        lanUrl = url;
        broadcastState();
    }

    /** 把用户输入的地址补全成完整 URL；非法返回 null。 */
    private String normalizeAddress(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty() || v.length() > 120) {
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c <= ' ' || c == '"' || c == '<' || c == '>' || c == '\\' || c == '\'') {
                return null;
            }
        }
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "http://" + v;
        }
        int scheme = v.indexOf("//");
        if (scheme < 0) {
            return null;
        }
        String rest = v.substring(scheme + 2);
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        if (hostPort.isEmpty()) {
            return null;
        }
        if (hostPort.indexOf(':') < 0) {
            v = v + ":" + httpPort;                 // 没写端口就补上本机端口
        }
        if (!v.endsWith("/")) {
            v = v + "/";
        }
        return v.length() <= 160 ? v : null;
    }

    /** 局内聊天（被淘汰的玩家也能发，实时反馈里大家还要一起聊）。 */
    private void handleChat(WebSocketConn conn, Player me, Map<String, Object> msg) {
        String text = cleanChat(Json.str(msg, "text"));
        if (text == null) {
            sendError(conn, "bad-message", "消息不能为空");
            return;
        }
        /*
         * 注意：这里**不再**限制"淘汰后不能发言"。
         * 实测下来被淘汰的人往往要吐槽/复盘，禁言反而难受；
         * 观战者仍然不能出牌/质疑（那是操作层的事，和聊天无关）。
         */
        long now = System.currentTimeMillis();
        if (now - me.getLastChatAt() < 350L) {
            return;                                 // 限流：发太快的直接丢掉
        }
        me.setLastChatAt(now);

        Map<String, Object> out = simple("chat");
        out.put("playerId", me.getId());
        out.put("name", me.getName());
        out.put("text", text);
        out.put("ts", Long.valueOf(now));
        broadcast(out);
    }

    private static String cleanChat(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    // ------------------------------------------------------------ 头像

    /** 头像上限：前端会把图片压到 96×96，正常只有几 KB。 */
    public static final int MAX_AVATAR_BYTES = 128 * 1024;

    /**
     * 上传/清除自己的头像。前端已经缩好图，这里只做大小与格式校验。
     * 数据存在内存里，由 HTTP 的 {@code /avatar/<playerId>} 提供。
     */
    private void handleAvatar(WebSocketConn conn, Player me, Map<String, Object> msg) {
        String data = Json.str(msg, "data");
        if (data == null || data.trim().isEmpty()) {
            me.setAvatar(null, null);            // 传空 = 清除头像
            broadcastState();
            return;
        }
        String mime = Json.str(msg, "mime", "image/jpeg");

        /* 允许直接传 data:image/jpeg;base64,xxxx 形式 */
        String trimmed = data.trim();
        if (trimmed.startsWith("data:")) {
            int comma = trimmed.indexOf(',');
            if (comma < 0) {
                sendError(conn, "bad-avatar", "图片数据格式不对");
                return;
            }
            String head = trimmed.substring(5, comma);
            int semi = head.indexOf(';');
            if (semi > 0) {
                mime = head.substring(0, semi);
            }
            trimmed = trimmed.substring(comma + 1);
        }
        if (mime == null || !mime.startsWith("image/")) {
            sendError(conn, "bad-avatar", "只支持图片");
            return;
        }

        byte[] bytes;
        try {
            bytes = Base64.decode(trimmed);
        } catch (Exception e) {
            sendError(conn, "bad-avatar", "图片数据解析失败");
            return;
        }
        if (bytes == null || bytes.length == 0) {
            sendError(conn, "bad-avatar", "图片是空的");
            return;
        }
        if (bytes.length > MAX_AVATAR_BYTES) {
            sendError(conn, "avatar-too-large",
                    "头像太大了（" + (bytes.length / 1024) + "KB，请控制在 "
                            + (MAX_AVATAR_BYTES / 1024) + "KB 内）");
            return;
        }

        me.setAvatar(mime, bytes);
        broadcastState();

        Map<String, Object> ok = simple("avatar-ok");
        ok.put("playerId", me.getId());
        ok.put("avatar", Integer.valueOf(me.getAvatarVersion()));
        conn.send(Json.write(ok));
    }

    @Override
    public byte[] avatar(String playerId) {
        synchronized (lock) {
            Player p = byId.get(playerId);
            return p == null ? null : p.getAvatarData();
        }
    }

    @Override
    public String avatarMime(String playerId) {
        synchronized (lock) {
            Player p = byId.get(playerId);
            return p == null ? null : p.getAvatarMime();
        }
    }

    private void handlePlay(WebSocketConn conn, Player me, Map<String, Object> msg) {
        List<Integer> indices = new ArrayList<Integer>();
        List<Object> raw = Json.list(msg, "cards");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof Number) {
                    indices.add(Integer.valueOf(((Number) o).intValue()));
                }
            }
        }
        try {
            doPlay(me, indices);
        } catch (RuleException e) {
            sendError(conn, e.getCode(), e.getMessage());
        }
    }

    /**
     * 判定演出的时长（毫秒），要和前端 js/app.js 里 ANIM_HOLD 的总和对得上：
     * 異議あり 3.0s + 亮牌 2.0s + 左轮 3.0s = 8.0s，再留 0.3s 余量。
     * 测试里用 {@link #setJudgePauseMs} 调短，免得回归要跑几分钟。
     */
    private static volatile long judgePauseMs = 8300L;

    /** 仅供测试调节判定锁时长。 */
    public static void setJudgePauseMs(long ms) {
        judgePauseMs = Math.max(0L, ms);
    }

    private void doPlay(Player me, List<Integer> indices) {
        if (game == null || !PHASE_PLAYING.equals(phase)) {
            throw new RuleException("not-your-turn", "现在还不能出牌");
        }
        /* 判定动画没播完，谁都不许出牌（机器人也一样，它们看 canPlay） */
        if (System.currentTimeMillis() < judgeUntil) {
            throw new RuleException("judging", "判定演出中，等这一波播完再出牌");
        }
        lastReveal = null;
        Game.PlayResult r = game.play(me, indices);
        broadcast(playedEvent(me, r.play));
        if (r.roundEnded) {
            game.startRound(r.nextFirst);
            broadcast(roundEvent());
        }
        broadcastState();
    }

    private void handleDoubt(WebSocketConn conn, Player me) {
        try {
            doDoubt(me);
        } catch (RuleException e) {
            sendError(conn, e.getCode(), e.getMessage());
        }
    }

    /** 质疑：人类和机器人共用。 */
    private void doDoubt(Player me) {
        if (game == null || !PHASE_PLAYING.equals(phase)) {
            throw new RuleException("nothing-to-doubt", "现在不能质疑");
        }
        if (System.currentTimeMillis() < judgeUntil) {
            throw new RuleException("judging", "判定演出中，稍等一下");
        }
        Game.DoubtResult r = game.doubt(me);

        /* 从这里开始锁住操作，直到整段判定动画播完 */
        judgeUntil = System.currentTimeMillis() + judgePauseMs;
        judgeReleased = false;

        Map<String, Object> doubtEvent = simple("doubt");
        doubtEvent.put("byId", me.getId());
        doubtEvent.put("targetId", r.accused.getId());
        broadcast(doubtEvent);

        Map<String, Object> reveal = simple("reveal");
        reveal.put("playerId", r.accused.getId());
        reveal.put("cards", lastPlayCards());
        reveal.put("lie", Boolean.valueOf(r.lie));
        broadcast(reveal);
        lastReveal = reveal;

        Map<String, Object> shot = simple("shot");
        shot.put("playerId", r.shooter.getId());
        shot.put("hit", Boolean.valueOf(r.hit));
        shot.put("shots", Integer.valueOf(r.shooter.getGun().getShots()));
        shot.put("remaining", Integer.valueOf(game.aliveCount()));
        broadcast(shot);

        if (game.isOver()) {
            phase = PHASE_OVER;
            Map<String, Object> over = simple("gameover");
            Player w = game.getWinner();
            recordWin(w);                       // 胜场榜：按 UID 记一笔
            over.put("winnerId", w == null ? null : w.getId());
            over.put("winnerName", w == null ? null : w.getName());
            broadcast(over);
        } else {
            game.startRound(r.nextFirst);
            broadcast(roundEvent());
        }
        broadcastState();
    }

    private void handleStart(WebSocketConn conn, Player me) {
        if (!me.isHost()) {
            sendError(conn, "not-host", "只有房主可以开始游戏");
            return;
        }
        if (PHASE_PLAYING.equals(phase)) {
            sendError(conn, "game-in-progress", "游戏已经开始了");
            return;
        }
        // 开始前把仍处于掉线状态的玩家移出（机器人不算掉线），避免开一局残局
        for (Player p : new ArrayList<Player>(players)) {
            if (!p.isConnected() && !p.isBot()) {
                removePlayer(p);
            }
        }
        if (players.size() < Game.MIN_PLAYERS) {
            sendError(conn, "not-enough-players", "至少需要 " + Game.MIN_PLAYERS + " 名玩家（可以加机器人）");
            return;
        }
        phase = PHASE_PLAYING;
        lastReveal = null;
        botActAt = 0;
        game = new Game(players);
        game.startGame();
        broadcast(roundEvent());
        broadcastState();
    }

    private void handleRestart(WebSocketConn conn, Player me) {
        if (!me.isHost()) {
            sendError(conn, "not-host", "只有房主可以重开");
            return;
        }
        if (!PHASE_OVER.equals(phase)) {
            sendError(conn, "game-in-progress", "本局还没结束");
            return;
        }
        for (Player p : new ArrayList<Player>(players)) {
            if (!p.isConnected() && !p.isBot()) {
                removePlayer(p);
            }
        }
        if (players.size() < Game.MIN_PLAYERS) {
            sendError(conn, "not-enough-players", "至少需要 " + Game.MIN_PLAYERS + " 名玩家（可以加机器人）");
            return;
        }
        phase = PHASE_PLAYING;
        lastReveal = null;
        botActAt = 0;
        game = new Game(players);
        game.startGame();
        broadcast(roundEvent());
        broadcastState();
    }

    // ------------------------------------------------------------ 玩家管理

    /** 必须在 lock 内调用。 */
    private void removePlayer(Player p) {
        players.remove(p);
        byId.remove(p.getId());
        byToken.remove(p.getToken());
        WebSocketConn c = connsByPlayer.remove(p.getId());
        if (c != null) {
            playersByConn.remove(c);
        }
        for (Player x : players) {
            x.setHost(false);
        }
        if (!players.isEmpty()) {
            players.get(0).setHost(true);
        }
        // 对局中少人 / 已结束 → 回到大厅，等待房主重新开始
        if (game != null && (PHASE_PLAYING.equals(phase) || PHASE_OVER.equals(phase))) {
            game = null;
            phase = PHASE_LOBBY;
            lastReveal = null;
        }
    }

    private static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replaceAll("[\\p{Cntrl}]", "");
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > MAX_NAME_LEN) {
            s = s.substring(0, MAX_NAME_LEN);
        }
        return s;
    }

    // ------------------------------------------------------------ 广播

    private void broadcastState() {
        Map<String, Object> base = new LinkedHashMap<String, Object>();
        base.put("t", "state");
        base.put("phase", phase);
        base.put("lanUrl", lanUrl);
        base.put("lanCustom", Boolean.valueOf(lanCustom));
        List<Object> candidates = new ArrayList<Object>();
        for (String ip : HttpWsServer.findAllLanIps()) {
            candidates.add(ip);
        }
        base.put("lanCandidates", candidates);
        base.put("minPlayers", Integer.valueOf(Game.MIN_PLAYERS));
        base.put("maxPlayers", Integer.valueOf(Game.MAX_PLAYERS));
        base.put("canStart", Boolean.valueOf(canStart()));
        base.put("roundNumber", Integer.valueOf(game == null ? 0 : game.getRoundNumber()));

        List<Object> ps = new ArrayList<Object>();
        for (Player p : players) {
            ps.add(playerJson(p));
        }
        base.put("players", ps);

        base.put("targetCard", game == null || game.getTargetCard() == null
                ? null : game.getTargetCard().name());
        base.put("currentPlayerId", game == null || game.getCurrentPlayer() == null
                ? null : game.getCurrentPlayer().getId());
        base.put("lastPlayerId", game == null || game.getLastPlay() == null
                ? null : game.getLastPlay().getPlayer().getId());

        List<Object> table = new ArrayList<Object>();
        if (game != null) {
            for (Play play : game.getTable()) {
                Map<String, Object> t = new LinkedHashMap<String, Object>();
                t.put("playerId", play.getPlayer().getId());
                t.put("count", Integer.valueOf(play.getCount()));
                if (play.isRevealed()) {
                    t.put("cards", cardNames(play.getCards()));
                } else {
                    t.put("cards", null);
                }
                table.add(t);
            }
        }
        base.put("table", table);
        base.put("reveal", lastReveal);
        base.put("winnerId", game == null || game.getWinner() == null
                ? null : game.getWinner().getId());
        base.put("you", null);
        base.put("isHost", Boolean.FALSE);
        base.put("hand", new ArrayList<Object>());
        base.put("canPlay", Boolean.FALSE);
        base.put("canDoubt", Boolean.FALSE);
        base.put("canPass", Boolean.FALSE);
        /* 胜场排行榜（前 10）：房主的设备会把它存到本机 */
        base.put("leaderboard", leaderboard());
        /* 对外地址：房主大厅里的分享地址默认用它（朋友复制即可加入） */
        base.put("publicUrl", publicUrl);
        /* 判定演出中：前端据此把按钮灰掉并提示"判定中…" */
        boolean judging = System.currentTimeMillis() < judgeUntil;
        base.put("judging", Boolean.valueOf(judging));

        for (Player p : players) {
            if (!p.isConnected()) {
                continue;
            }
            WebSocketConn c = connsByPlayer.get(p.getId());
            if (c == null) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<String, Object>(base);
            copy.put("you", p.getId());
            copy.put("isHost", Boolean.valueOf(p.isHost()));
            copy.put("hand", cardNames(p.getHand()));
            /* 判定动画期间一律不许操作（下一家也别急着出牌） */
            copy.put("canPlay", Boolean.valueOf(!judging && game != null && game.canPlay(p)));
            copy.put("canDoubt", Boolean.valueOf(!judging && game != null && game.canDoubt(p)));
            copy.put("canPass", Boolean.valueOf(!judging && game != null && game.canPass(p)));
            c.send(Json.write(copy));
        }
    }

    private boolean canStart() {
        if (!PHASE_LOBBY.equals(phase)) {
            return false;
        }
        int connected = 0;
        for (Player p : players) {
            if (p.isConnected() || p.isBot()) {
                connected++;
            }
        }
        return connected >= Game.MIN_PLAYERS && connected <= Game.MAX_PLAYERS;
    }

    private Map<String, Object> playerJson(Player p) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("isHost", Boolean.valueOf(p.isHost()));
        m.put("connected", Boolean.valueOf(p.isBot() || p.isConnected()));
        m.put("bot", Boolean.valueOf(p.isBot()));
        m.put("avatar", Integer.valueOf(p.getAvatarVersion()));
        /* 设备 UID（用于排行榜定位"哪一行是我"，以及测试核对胜场归属） */
        m.put("uid", p.getUid());
        m.put("alive", Boolean.valueOf(p.isAlive()));
        m.put("shots", Integer.valueOf(p.getGun().getShots()));
        m.put("handCount", Integer.valueOf(p.getHand().size()));
        m.put("seat", Integer.valueOf(p.getSeat()));
        return m;
    }

    private void sendWelcome(WebSocketConn conn, Player p) {
        Map<String, Object> m = simple("welcome");
        m.put("playerId", p.getId());
        m.put("token", p.getToken());
        m.put("isHost", Boolean.valueOf(p.isHost()));
        conn.send(Json.write(m));
    }

    private void sendError(WebSocketConn conn, String code, String msg) {
        Map<String, Object> m = simple("error");
        m.put("code", code);
        m.put("msg", msg);
        conn.send(Json.write(m));
    }

    private void broadcast(Map<String, Object> event) {
        String json = Json.write(event);
        for (Player p : players) {
            if (!p.isConnected()) {
                continue;
            }
            WebSocketConn c = connsByPlayer.get(p.getId());
            if (c != null) {
                c.send(json);
            }
        }
    }

    private Map<String, Object> playedEvent(Player p, Play play) {
        Map<String, Object> m = simple("played");
        m.put("playerId", p.getId());
        m.put("count", Integer.valueOf(play.getCount()));
        return m;
    }

    private Map<String, Object> roundEvent() {
        return roundEvent(null);
    }

    /** reason: 可选，说明这一轮为什么重开（比如 "given-up" = 两圈没人质疑作废）。 */
    private Map<String, Object> roundEvent(String reason) {
        Map<String, Object> m = simple("round");
        m.put("targetCard", game == null || game.getTargetCard() == null
                ? null : game.getTargetCard().name());
        m.put("firstPlayerId", game == null || game.getCurrentPlayer() == null
                ? null : game.getCurrentPlayer().getId());
        m.put("roundNumber", Integer.valueOf(game == null ? 0 : game.getRoundNumber()));
        if (reason != null) {
            m.put("reason", reason);
        }
        return m;
    }

    private void handlePass(WebSocketConn conn, Player me) {
        try {
            doPass(me);
        } catch (RuleException e) {
            sendError(conn, e.getCode(), e.getMessage());
        }
    }

    /** 过牌（"不要"）：不打这一手，让下家接手；下家照样可以质疑上家。 */
    private void doPass(Player me) {
        if (game == null || !PHASE_PLAYING.equals(phase)) {
            throw new RuleException("not-your-turn", "现在不能过牌");
        }
        if (System.currentTimeMillis() < judgeUntil) {
            throw new RuleException("judging", "判定演出中，稍等一下");
        }
        Game.PassResult r = game.pass(me);
        Map<String, Object> ev = simple("passed");
        ev.put("playerId", me.getId());
        broadcast(ev);
        if (r.roundEnded) {
            game.startRound(r.nextFirst);
            broadcast(roundEvent("given-up"));
        }
        broadcastState();
    }

    private List<Object> lastPlayCards() {
        if (game == null || game.getLastPlay() == null) {
            return new ArrayList<Object>();
        }
        return cardNames(game.getLastPlay().getCards());
    }

    private static List<Object> cardNames(List<Card> cards) {
        List<Object> l = new ArrayList<Object>();
        if (cards != null) {
            for (Card c : cards) {
                l.add(c.name());
            }
        }
        return l;
    }

    private static Map<String, Object> simple(String type) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", type);
        return m;
    }

    // ------------------------------------------------------------ 供外壳使用

    /** 端口确定后由服务器回填局域网地址。 */
    public void setLanUrl(String lanUrl) {
        this.autoLanUrl = lanUrl;
        if (!lanCustom) {
            this.lanUrl = lanUrl;
        }
    }

    /**
     * 对外地址（端口映射 / 内网穿透给出的公网入口），由启动器/命令行传入。
     * 只要房主没手动改过分享地址，大厅里显示的就是这条 —— 朋友复制粘贴即可加入。
     */
    public void setPublicUrl(String publicUrl) {
        if (publicUrl == null || publicUrl.trim().isEmpty()) {
            return;
        }
        String u = publicUrl.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        if (!u.endsWith("/")) {
            u = u + "/";
        }
        this.publicUrl = u;
        /* 没有更具体的局域网/自定义地址时，直接把对外地址当成房间入口 */
        if (!lanCustom && (lanUrl == null || lanUrl.isEmpty())) {
            this.lanUrl = u;
        }
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    /** 端口确定后由服务器回填，用于用户自定义地址时补全端口。 */
    public void setHttpPort(int port) {
        this.httpPort = port;
    }

    /** 心跳：给所有在线连接发 ping，并清理长时间无响应的连接。 */
    public void pingAll() {
        List<WebSocketConn> all = new ArrayList<WebSocketConn>();
        synchronized (lock) {
            all.addAll(connsByPlayer.values());
            all.addAll(pendingConns.keySet());
        }
        long now = System.currentTimeMillis();
        for (WebSocketConn c : all) {
            if (!c.isOpen()) {
                continue;
            }
            if (now - c.getLastActivity() > 45_000L) {
                c.close(1001, "idle timeout");
                continue;
            }
            c.ping();
        }
    }

    /** 服务器停止时断开所有连接。 */
    public void closeAll() {
        List<WebSocketConn> all = new ArrayList<WebSocketConn>();
        synchronized (lock) {
            all.addAll(connsByPlayer.values());
            all.addAll(pendingConns.keySet());
        }
        for (WebSocketConn c : all) {
            c.close(1001, "server stopping");
        }
    }

    public int getPlayerCount() {
        synchronized (lock) {
            return players.size();
        }
    }

    public String getPhase() {
        synchronized (lock) {
            return phase;
        }
    }

    public long getUptimeMs() {
        return System.currentTimeMillis() - startedAt;
    }
}
