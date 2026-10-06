package com.liarsbar.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 一局游戏的规则引擎（纯逻辑，不涉及任何网络，便于单独测试）。
 *
 * <p>规则对齐《Liar's Bar》纸牌模式：
 * <ul>
 *   <li>每轮从 Q/K/A 随机指定目标牌，每人发 5 张；</li>
 *   <li>轮到你时必须出 1~5 张真在手里的牌（J 是小丑，可当目标牌）；</li>
 *   <li>只有当前回合玩家能质疑「上家」，被质疑的牌翻开：
 *       只要有一张既不是目标牌也不是 J，就算欺骗，出牌者开枪；否则质疑者开枪；</li>
 *   <li>出完手牌的玩家本轮被跳过；若所有人都出完则本轮自然结束；</li>
 *   <li>开枪用固定弹位的左轮（第 1~6 次必中一发），中弹者淘汰；</li>
 *   <li>只剩 1 名存活玩家时游戏结束。</li>
 * </ul>
 */
public final class Game {

    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 4;
    /** 每人每轮手牌数。 */
    public static final int HAND_SIZE = 5;
    /** 单回合最多出牌数（手牌一共 5 张，所以最多就是 5）。 */
    public static final int MAX_PLAY_PER_TURN = 5;

    private static final Random RANDOM = new Random();

    private final List<Player> players;
    private final List<Play> table = new ArrayList<Play>();
    /**
     * 本轮已经发生的动作数（出牌 + 过牌）。
     * 用来实现「转了两圈还没人质疑就弃掉这一轮」：
     * 到了 2 × 存活人数 就重新发牌，谁都不用挨枪。
     */
    private int actionsThisRound;

    private Card targetCard;
    private Player currentPlayer;
    private Play lastPlay;
    private int roundNumber;
    private boolean over;
    private Player winner;

    public Game(List<Player> players) {
        this.players = players;
    }

    // ------------------------------------------------------------ 生命周期

    /** 开新的一局：所有玩家复活、换新枪。 */
    public void startGame() {
        for (Player p : players) {
            p.resetForNewGame();
        }
        table.clear();
        lastPlay = null;
        targetCard = null;
        currentPlayer = null;
        winner = null;
        over = false;
        roundNumber = 0;
        startRound(firstAlive());
    }

    /** 开新一轮：重新发牌、重新指定目标牌。 */
    public void startRound(Player first) {
        roundNumber++;

        List<Card> deck = Card.newDeck();
        Card.shuffle(deck);
        int cursor = 0;
        for (Player p : players) {
            p.setFinishedRound(false);
            List<Card> hand = new ArrayList<Card>();
            if (p.isAlive()) {
                for (int i = 0; i < HAND_SIZE && cursor < deck.size(); i++) {
                    hand.add(deck.get(cursor++));
                }
            }
            p.setHand(hand);
        }

        targetCard = Card.TARGETS[RANDOM.nextInt(Card.TARGETS.length)];
        table.clear();
        lastPlay = null;
        actionsThisRound = 0;

        if (first != null && first.isAlive()) {
            currentPlayer = first;
        } else {
            currentPlayer = firstAlive();
        }
    }

    // ------------------------------------------------------------ 玩家操作

    /** 出牌。 */
    public PlayResult play(Player p, List<Integer> indices) {
        requireRunning();
        if (p != currentPlayer) {
            throw new RuleException("not-your-turn", "还没轮到你出牌");
        }
        if (indices == null || indices.isEmpty()) {
            throw new RuleException("bad-card-count", "至少要出 1 张牌");
        }
        if (indices.size() > MAX_PLAY_PER_TURN) {
            throw new RuleException("bad-card-count", "一个回合最多出 " + MAX_PLAY_PER_TURN + " 张牌");
        }
        Set<Integer> unique = new HashSet<Integer>();
        for (Integer i : indices) {
            if (i == null || i.intValue() < 0 || i.intValue() >= p.getHand().size()) {
                throw new RuleException("card-not-owned", "手牌下标非法");
            }
            if (!unique.add(i)) {
                throw new RuleException("card-not-owned", "同一张牌不能出两次");
            }
        }

        List<Card> played = p.removeCards(indices);
        Play play = new Play(p, played);
        table.add(play);
        lastPlay = play;
        actionsThisRound++;
        if (p.getHand().isEmpty()) {
            p.setFinishedRound(true);
        }

        Player next = nextActiveAfter(p);
        if (next == null) {
            // 所有存活玩家都出完了牌 → 本轮自然结束，最后出牌者成为本轮胜者
            currentPlayer = null;
            return new PlayResult(play, true, p);
        }
        /* 转了两圈还是没人质疑 → 这一轮作废，重新发牌（谁都不用开枪） */
        if (actionsThisRound >= aliveCount() * 2) {
            currentPlayer = null;
            return new PlayResult(play, true, nextAliveAfter(p));
        }
        currentPlayer = next;
        return new PlayResult(play, false, null);
    }

    /**
     * 过牌（"不要"）：这一手不打，把机会让给下家。
     * 上家出的牌仍然留在桌上，下家照样可以质疑它。
     * 如果转了两圈都没人质疑，这一轮就作废重开。
     */
    public PassResult pass(Player p) {
        requireRunning();
        if (p != currentPlayer) {
            throw new RuleException("not-your-turn", "还没轮到你");
        }
        actionsThisRound++;
        Player next = nextActiveAfter(p);
        if (next == null) {
            // 其他人都已经出完牌了，没人接 → 本轮到此为止
            currentPlayer = null;
            return new PassResult(true, nextAliveAfter(p));
        }
        if (actionsThisRound >= aliveCount() * 2) {
            // 两圈都没人质疑 → 弃掉这一轮
            currentPlayer = null;
            return new PassResult(true, nextAliveAfter(p));
        }
        currentPlayer = next;
        return new PassResult(false, null);
    }

    /** 质疑上家。 */
    public DoubtResult doubt(Player p) {
        requireRunning();
        if (p != currentPlayer) {
            throw new RuleException("not-your-turn", "还没轮到你质疑");
        }
        if (lastPlay == null) {
            throw new RuleException("nothing-to-doubt", "本轮还没有人出牌，无法质疑");
        }
        if (lastPlay.getPlayer() == p) {
            throw new RuleException("nothing-to-doubt", "不能质疑自己");
        }
        if (!lastPlay.getPlayer().isAlive()) {
            // 上家已经离场（掉线超时被淘汰），这手牌作废
            throw new RuleException("nothing-to-doubt", "上家已经离场，无法质疑");
        }

        lastPlay.setRevealed(true);
        boolean lie = false;
        for (Card c : lastPlay.getCards()) {
            if (!c.matches(targetCard)) {
                lie = true;
                break;
            }
        }

        Player accused = lastPlay.getPlayer();
        Player shooter = lie ? accused : p;
        boolean hit = shooter.getGun().shoot();
        if (hit) {
            shooter.setAlive(false);
        }

        if (aliveCount() <= 1) {
            over = true;
            winner = firstAlive();
            currentPlayer = null;
            return new DoubtResult(lie, accused, p, shooter, hit, null);
        }

        /*
         * 下一轮谁先出 —— 规则很简单：**说对的那个人先出**。
         *   质疑成功（上家确实撒谎）→ 质疑的人说对了 → 他先出；
         *   质疑失败（上家说的是真话）→ 出牌的人说对了 → 他先出。
         * 也就是说"这一手判断正确"的人拿到下一轮先手，鼓励大家认真读牌。
         * 万一这个人已经不在场（被枪打死 / 掉线），就顺延给下一位存活玩家。
         */
        Player nextFirst;
        if (lie) {
            nextFirst = p.isAlive() ? p : nextAliveAfter(p);
        } else {
            nextFirst = accused.isAlive() ? accused : nextAliveAfter(accused);
        }
        return new DoubtResult(lie, accused, p, shooter, hit, nextFirst);
    }

    /** 该玩家此刻能否过牌。 */
    public boolean canPass(Player p) {
        return !over && p == currentPlayer && p.isAlive();
    }

    /**
     * 强制淘汰（掉线超时）：该玩家出局，并修正回合归属。
     */
    public TurnFix eliminate(Player p) {
        boolean wasCurrent = (p == currentPlayer);
        p.setAlive(false);
        p.setFinishedRound(true);

        if (aliveCount() <= 1) {
            over = true;
            winner = firstAlive();
            currentPlayer = null;
            return new TurnFix(true, false, null);
        }
        if (wasCurrent) {
            Player next = nextActiveAfter(p);
            if (next == null) {
                Player first = lastPlay != null ? lastPlay.getPlayer() : firstAlive();
                currentPlayer = null;
                return new TurnFix(false, true, first);
            }
            currentPlayer = next;
        }
        return new TurnFix(false, false, null);
    }

    // ------------------------------------------------------------ 查询

    /** 该玩家此刻能否出牌。 */
    public boolean canPlay(Player p) {
        return !over && p == currentPlayer && p.isAlive() && !p.getHand().isEmpty();
    }

    /** 该玩家此刻能否质疑。 */
    public boolean canDoubt(Player p) {
        return !over && p == currentPlayer && lastPlay != null && lastPlay.getPlayer() != p;
    }

    public int aliveCount() {
        int n = 0;
        for (Player p : players) {
            if (p.isAlive()) {
                n++;
            }
        }
        return n;
    }

    public Player firstAlive() {
        for (Player p : players) {
            if (p.isAlive()) {
                return p;
            }
        }
        return null;
    }

    /** 从 p 之后开始找第一个存活玩家（不含 p 自己，除非绕一圈）。 */
    public Player nextAliveAfter(Player p) {
        int n = players.size();
        int start = players.indexOf(p);
        if (start < 0) {
            return firstAlive();
        }
        for (int i = 1; i <= n; i++) {
            Player cand = players.get((start + i) % n);
            if (cand.isAlive()) {
                return cand;
            }
        }
        return null;
    }

    /** 从 p 之后找下一个还能行动（存活且本轮未出完）的玩家；找不到说明本轮结束。 */
    private Player nextActiveAfter(Player p) {
        int n = players.size();
        int start = players.indexOf(p);
        for (int i = 1; i <= n; i++) {
            Player cand = players.get((start + i) % n);
            if (cand.isAlive() && !cand.isFinishedRound()) {
                return cand;
            }
        }
        return null;
    }

    private void requireRunning() {
        if (over) {
            throw new RuleException("game-over", "本局已经结束");
        }
        if (currentPlayer == null) {
            throw new RuleException("not-your-turn", "当前没有轮到任何人");
        }
    }

    // ------------------------------------------------------------ getter

    public List<Player> getPlayers() {
        return Collections.unmodifiableList(players);
    }

    public List<Play> getTable() {
        return Collections.unmodifiableList(table);
    }

    public Play getLastPlay() {
        return lastPlay;
    }

    public Card getTargetCard() {
        return targetCard;
    }

    public Player getCurrentPlayer() {
        return currentPlayer;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public boolean isOver() {
        return over;
    }

    public Player getWinner() {
        return winner;
    }

    // ------------------------------------------------------------ 结果对象

    /** 强制淘汰后的回合修正结果。 */
    public static final class TurnFix {
        /** true = 游戏结束。 */
        public final boolean over;
        /** true = 本轮无质疑结束，需要开新一轮。 */
        public final boolean roundEnded;
        /** roundEnded 时的下一轮首出玩家。 */
        public final Player nextFirst;

        TurnFix(boolean over, boolean roundEnded, Player nextFirst) {
            this.over = over;
            this.roundEnded = roundEnded;
            this.nextFirst = nextFirst;
        }
    }

    /** 出牌结果。 */
    public static final class PlayResult {
        public final Play play;
        /** true = 本轮结束（要么所有人出完牌，要么转了两圈没人质疑）。 */
        public final boolean roundEnded;
        /** roundEnded 时的下一轮首出玩家。 */
        public final Player nextFirst;

        PlayResult(Play play, boolean roundEnded, Player nextFirst) {
            this.play = play;
            this.roundEnded = roundEnded;
            this.nextFirst = nextFirst;
        }
    }

    /** 过牌结果。 */
    public static final class PassResult {
        /** true = 这一轮到此为止（两圈没人质疑，或没人能接）。 */
        public final boolean roundEnded;
        /** roundEnded 时的下一轮首出玩家。 */
        public final Player nextFirst;

        PassResult(boolean roundEnded, Player nextFirst) {
            this.roundEnded = roundEnded;
            this.nextFirst = nextFirst;
        }
    }

    /** 质疑结果。 */
    public static final class DoubtResult {
        public final boolean lie;
        public final Player accused;
        public final Player doubter;
        public final Player shooter;
        public final boolean hit;
        /** 下一轮首出玩家；游戏结束时为 null。 */
        public final Player nextFirst;

        DoubtResult(boolean lie, Player accused, Player doubter, Player shooter, boolean hit,
                    Player nextFirst) {
            this.lie = lie;
            this.accused = accused;
            this.doubter = doubter;
            this.shooter = shooter;
            this.hit = hit;
            this.nextFirst = nextFirst;
        }
    }
}
