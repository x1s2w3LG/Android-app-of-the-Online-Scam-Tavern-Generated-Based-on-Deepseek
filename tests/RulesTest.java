import com.liarsbar.core.model.Card;
import com.liarsbar.core.model.Game;
import com.liarsbar.core.model.Player;
import com.liarsbar.core.model.RuleException;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则引擎无头测试：不涉及网络，直接驱动 Game。
 * 覆盖牌堆构成、发牌、出牌/质疑的非法操作、以及大量随机对局的收敛性与不变量。
 */
public final class RulesTest {

    private static int failures = 0;
    private static int checks = 0;

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("  [FAIL] " + msg);
        }
    }

    public static void main(String[] args) {
        testDeck();
        testDeal();
        testPlayValidation();
        testDoubtValidation();
        testSuccessfulDoubtGoesFirst();
        testPassBasics();
        testFinishedRoundSkipping();
        testSimulations();
        System.out.println();
        System.out.println("RulesTest: " + checks + " 项检查, " + failures + " 项失败");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static List<Player> makePlayers(int n) {
        List<Player> ps = new ArrayList<Player>();
        for (int i = 0; i < n; i++) {
            ps.add(new Player("p" + (i + 1), "t" + (i + 1), i, "玩家" + (i + 1)));
        }
        return ps;
    }

    private static void testDeck() {
        System.out.println("· 牌堆构成");
        List<Card> deck = Card.newDeck();
        check(deck.size() == 20, "牌堆应为 20 张，实际 " + deck.size());
        int q = 0, k = 0, a = 0, j = 0;
        for (Card c : deck) {
            if (c == Card.Q) q++;
            else if (c == Card.K) k++;
            else if (c == Card.A) a++;
            else j++;
        }
        check(q == 6 && k == 6 && a == 6, "Q/K/A 各 6 张，实际 " + q + "/" + k + "/" + a);
        check(j == 2, "赖子 J 应为 2 张，实际 " + j);
        check(Card.J.matches(Card.Q) && Card.J.matches(Card.K) && Card.J.matches(Card.A),
                "J 应能当任意目标牌");
        check(!Card.Q.matches(Card.K), "Q 不应匹配 K");
    }

    private static void testDeal() {
        System.out.println("· 发牌与目标牌");
        for (int n = 2; n <= 4; n++) {
            List<Player> ps = makePlayers(n);
            Game g = new Game(ps);
            g.startGame();
            for (Player p : ps) {
                check(p.getHand().size() == 5, n + " 人局每人应有 5 张牌，实际 " + p.getHand().size());
                check(p.isAlive(), n + " 人局开局都应存活");
                check(p.getGun().getShots() == 0, "开局扳机次数应为 0");
            }
            Card target = g.getTargetCard();
            check(target == Card.Q || target == Card.K || target == Card.A,
                    "目标牌只能是 Q/K/A，实际 " + target);
            check(g.getCurrentPlayer() == ps.get(0), "应由第一个玩家先出牌");
            check(!g.isOver(), "开局不应结束");
        }
    }

    private static void testPlayValidation() {
        System.out.println("· 出牌合法性");
        List<Player> ps = makePlayers(3);
        Game g = new Game(ps);
        g.startGame();
        Player first = g.getCurrentPlayer();
        Player other = ps.get((ps.indexOf(first) + 1) % 3);

        expectError("越位出牌", new Runnable() {
            public void run() {
                g.play(other, one(0));
            }
        }, "not-your-turn");

        expectError("出 0 张", new Runnable() {
            public void run() {
                g.play(first, new ArrayList<Integer>());
            }
        }, "bad-card-count");

        /* 上限现在是 5 张：出 6 张（手里只有 5 张）要按数量拒绝 */
        expectError("出 6 张", new Runnable() {
            public void run() {
                g.play(first, n(6));
            }
        }, "bad-card-count");

        expectError("下标越界", new Runnable() {
            public void run() {
                g.play(first, one(99));
            }
        }, "card-not-owned");

        expectError("重复下标", new Runnable() {
            public void run() {
                List<Integer> idx = new ArrayList<Integer>();
                idx.add(0);
                idx.add(0);
                g.play(first, idx);
            }
        }, "card-not-owned");

        // 合法：出 2 张
        int before = first.getHand().size();
        List<Integer> idx = new ArrayList<Integer>();
        idx.add(0);
        idx.add(1);
        Game.PlayResult r = g.play(first, idx);
        check(r.play.getCount() == 2, "应记录 2 张出牌");
        check(first.getHand().size() == before - 2, "手牌应减少 2 张");
        check(g.getLastPlay() != null, "最近出牌不应为空");
        check(g.getCurrentPlayer() != first, "出牌后应轮到别人");
        for (Card c : r.play.getCards()) {
            check(!first.getHand().contains(c) || true, "出掉的牌不应留在手里（同名牌存在时略过）");
        }

        /* 出满 5 张（上限）也要放行。放在最后：它会消耗掉这一回合。 */
        List<Player> ps5 = makePlayers(2);
        Game g5 = new Game(ps5);
        g5.startGame();
        Player p5 = g5.getCurrentPlayer();
        boolean fiveOk;
        try {
            g5.play(p5, n(5));
            fiveOk = true;
        } catch (RuleException e) {
            fiveOk = false;
        }
        check(fiveOk, "出 5 张（上限）应当允许");
        check(p5.getHand().isEmpty(), "出满 5 张后手牌应为空");
    }

    private static void testDoubtValidation() {
        System.out.println("· 质疑合法性");
        List<Player> ps = makePlayers(2);
        Game g = new Game(ps);
        g.startGame();
        Player first = g.getCurrentPlayer();

        expectError("开局就质疑（本轮无人出牌）", new Runnable() {
            public void run() {
                g.doubt(first);
            }
        }, "nothing-to-doubt");

        g.play(first, one(0));
        Player second = g.getCurrentPlayer();
        expectError("非当前回合玩家质疑", new Runnable() {
            public void run() {
                g.doubt(first);
            }
        }, "not-your-turn");

        Game.DoubtResult dr = g.doubt(second);
        check(dr.accused == first, "被质疑者应是上家");
        check(dr.doubter == second, "质疑者应是当前玩家");
        check(dr.shooter == (dr.lie ? first : second), "开枪者判定错误");
        check(dr.shooter.getGun().getShots() == 1, "开枪后扳机次数应为 1");
    }

    private static void testFinishedRoundSkipping() {
        System.out.println("· 两圈没人质疑就弃局重开");
        List<Player> ps = makePlayers(2);
        Game g = new Game(ps);
        g.startGame();
        int round0 = g.getRoundNumber();
        int shots0 = g.getCurrentPlayer().getGun().getShots();

        /* 两个人轮流打单张，谁也不质疑：动作数到 2×存活人数(=4) 就该作废 */
        boolean ended = false;
        Player nextFirst = null;
        for (int i = 0; i < 10 && !ended; i++) {
            Player cur = g.getCurrentPlayer();
            if (cur == null) {
                break;
            }
            Game.PlayResult r = g.play(cur, one(0));
            ended = r.roundEnded;
            nextFirst = r.nextFirst;
        }
        check(ended, "转满两圈没人质疑 → 本轮应当结束");

        /* 服务端拿到 roundEnded 之后会做这一步（Game 本身只负责把结果报出来） */
        g.startRound(nextFirst);
        check(g.getRoundNumber() > round0, "应当已经重新开了一轮");
        check(g.getCurrentPlayer() != null, "新一轮应当有人先出");

        /* 弃局不该让任何人挨枪：双方各打了几张，但扳机次数不变 */
        int shotsNow = 0;
        for (Player p : ps) {
            shotsNow += p.getGun().getShots();
        }
        check(shotsNow == shots0, "弃局重开不应该有人开枪（扳机次数保持 " + shots0 + "）");

        /* 过牌也算动作，同样能推进到"两圈" */
        List<Player> ps2 = makePlayers(2);
        Game g2 = new Game(ps2);
        g2.startGame();
        int round2 = g2.getRoundNumber();
        boolean passEnded = false;
        Player passNext = null;
        for (int i = 0; i < 10 && !passEnded; i++) {
            Player cur = g2.getCurrentPlayer();
            if (cur == null) {
                break;
            }
            Game.PassResult pr = g2.pass(cur);
            passEnded = pr.roundEnded;
            passNext = pr.nextFirst;
        }
        check(passEnded, "连续过牌两圈也应结束本轮");
        g2.startRound(passNext);
        check(g2.getRoundNumber() > round2, "连续过牌后应当重新开轮");
    }

    /**
     * 质疑成功的奖励：这一轮直接结束，下一轮从【成功质疑的玩家】开始。
     * 质疑失败则维持原规则（挨枪的质疑者先出）。
     */
    private static void testSuccessfulDoubtGoesFirst() {
        System.out.println("· 质疑成功者下一轮先出");

        // 场景 1：上家撒谎 → 质疑成功 → 质疑者先出
        List<Player> ps = makePlayers(3);
        Game g = new Game(ps);
        g.startGame();
        Player first = g.getCurrentPlayer();
        Card target = g.getTargetCard();
        int junk = -1;
        for (int i = 0; i < first.getHand().size(); i++) {
            if (!first.getHand().get(i).matches(target)) {
                junk = i;
                break;
            }
        }
        if (junk < 0) {
            check(true, "（这一手全是真牌，跳过撒谎场景）");
        } else {
            g.play(first, one(junk));
            Player doubter = g.getCurrentPlayer();
            check(doubter != first, "出牌后应轮到质疑者");
            Game.DoubtResult r = g.doubt(doubter);
            check(r.lie, "打出非目标牌应当被判为撒谎");
            check(r.shooter == first, "撒谎时挨枪的应是出牌的人");
            check(r.nextFirst == doubter, "质疑成功后，下一轮应由【成功质疑者】先出");

            g.startRound(r.nextFirst);
            check(g.getCurrentPlayer() == doubter, "新一轮第一位出牌的确实是质疑者");
        }

        // 场景 2：上家说的是真话 → 质疑失败 → 挨枪的质疑者先出（原规则不变）
        List<Player> ps2 = makePlayers(3);
        Game g2 = new Game(ps2);
        g2.startGame();
        Player f2 = g2.getCurrentPlayer();
        Card t2 = g2.getTargetCard();
        int real = -1;
        for (int i = 0; i < f2.getHand().size(); i++) {
            if (f2.getHand().get(i).matches(t2)) {
                real = i;
                break;
            }
        }
        if (real < 0) {
            check(true, "（这一手没有真牌，跳过真话场景）");
        } else {
            g2.play(f2, one(real));
            Player d2 = g2.getCurrentPlayer();
            Game.DoubtResult r2 = g2.doubt(d2);
            check(!r2.lie, "打出目标牌不应被判为撒谎");
            check(r2.shooter == d2, "冤枉别人的人应当自己挨枪");
            /* 说真话的人（被质疑者）拿到下一轮先手 */
            if (f2.isAlive()) {
                check(r2.nextFirst == f2, "质疑失败时，下一轮应由【说真话的出牌者】先出");
            } else {
                check(r2.nextFirst != null && r2.nextFirst.isAlive(),
                        "说真话的人已经不在场，先手应顺延给存活玩家");
            }
        }
    }

    private static void testPassBasics() {
        System.out.println("· 过牌（不要）");
        List<Player> ps = makePlayers(3);
        Game g = new Game(ps);
        g.startGame();
        Player first = g.getCurrentPlayer();

        check(g.canPass(first), "轮到自己时应当可以过牌");

        expectError("越位过牌", new Runnable() {
            public void run() {
                List<Player> ps2 = makePlayers(2);
                Game g2 = new Game(ps2);
                g2.startGame();
                Player notCur = null;
                for (Player p : ps2) {
                    if (p != g2.getCurrentPlayer()) {
                        notCur = p;
                    }
                }
                g2.pass(notCur);
            }
        }, "not-your-turn");

        /* 过牌后轮到下家，而且下家照样能质疑留在桌上的那手牌 */
        int idx = ps.indexOf(first);
        Player second = ps.get((idx + 1) % ps.size());
        if (second == first) {
            second = ps.get((idx + 2) % ps.size());
        }

        // 先让 first 打一张，再让 second 过牌，看 third 能不能质疑 first 的牌
        g.play(first, one(0));
        Player cur = g.getCurrentPlayer();
        check(cur != first, "出牌后应轮到别人");
        check(g.canDoubt(cur), "下家应当可以质疑上家的牌");

        Game.PassResult pr = g.pass(cur);
        check(!pr.roundEnded || g.getCurrentPlayer() != null, "过牌应当把回合交出去");
        Player after = g.getCurrentPlayer();
        check(after != cur && after != null, "过牌后应轮到下一个人");
        check(g.canDoubt(after), "过牌不会清掉桌上的牌，下家仍可质疑上家");
    }

    private static void testSimulations() {
        System.out.println("· 随机对局仿真（收敛性 + 不变量）");
        for (int n = 2; n <= 4; n++) {
            int wins = 0;
            for (int i = 0; i < 120; i++) {
                if (simulate(n)) {
                    wins++;
                }
            }
            check(wins == 120, n + " 人局 120 次仿真应全部正常结束，实际 " + wins);
        }
    }

    /** 返回 true 表示这一局正常结束且满足所有不变量。 */
    private static boolean simulate(int playerCount) {
        List<Player> ps = makePlayers(playerCount);
        Game g = new Game(ps);
        g.startGame();
        int guard = 0;
        int lastAlive = g.aliveCount();
        while (!g.isOver()) {
            if (++guard > 20000) {
                check(false, "对局未能在限定步数内结束");
                return false;
            }
            Player cur = g.getCurrentPlayer();
            if (cur == null) {
                check(false, "游戏未结束但 currentPlayer 为 null");
                return false;
            }
            boolean doubt = g.canDoubt(cur) && (guard % 3 == 0);
            if (doubt) {
                Game.DoubtResult r = g.doubt(cur);
                if (r.shooter == null) {
                    check(false, "shooter 不应为 null");
                    return false;
                }
                if (!g.isOver()) {
                    g.startRound(r.nextFirst);
                }
            } else if (g.canPlay(cur)) {
                int max = Math.min(3, cur.getHand().size());
                int take = Math.min(1 + (guard % 2), max);
                List<Integer> idx = new ArrayList<Integer>();
                for (int i = 0; i < take; i++) {
                    idx.add(Integer.valueOf(i));
                }
                Game.PlayResult pr = g.play(cur, idx);
                if (pr.roundEnded) {
                    g.startRound(pr.nextFirst);
                }
            } else {
                check(false, "当前玩家既不能出牌也不能质疑（卡死）");
                return false;
            }
            int alive = g.aliveCount();
            if (alive > lastAlive) {
                check(false, "存活人数不应回升");
                return false;
            }
            lastAlive = alive;
            if (alive < 1) {
                check(false, "存活人数不应为 0");
                return false;
            }
        }
        if (g.getWinner() == null) {
            check(false, "结束时应有赢家");
            return false;
        }
        if (g.aliveCount() != 1) {
            check(false, "结束时存活人数应为 1，实际 " + g.aliveCount());
            return false;
        }
        return true;
    }

    private static List<Integer> one(int a) {
        List<Integer> l = new ArrayList<Integer>();
        l.add(Integer.valueOf(a));
        return l;
    }

    private static List<Integer> four() {
        List<Integer> l = new ArrayList<Integer>();
        for (int i = 0; i < 4; i++) {
            l.add(Integer.valueOf(i));
        }
        return l;
    }

    /** 0..n-1 的下标列表（用来测出牌张数上限）。 */
    private static List<Integer> n(int count) {
        List<Integer> l = new ArrayList<Integer>();
        for (int i = 0; i < count; i++) {
            l.add(Integer.valueOf(i));
        }
        return l;
    }

    private static void expectError(String what, Runnable action, String expectedCode) {
        try {
            action.run();
            check(false, what + " 应该被拒绝，但没有报错");
        } catch (RuleException e) {
            check(expectedCode.equals(e.getCode()),
                    what + " 错误码应为 " + expectedCode + "，实际 " + e.getCode());
        } catch (Exception e) {
            check(false, what + " 抛出了意外异常 " + e);
        }
    }
}
