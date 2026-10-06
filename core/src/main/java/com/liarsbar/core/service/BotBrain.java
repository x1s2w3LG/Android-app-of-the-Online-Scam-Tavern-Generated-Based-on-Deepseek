package com.liarsbar.core.service;

import com.liarsbar.core.model.Card;
import com.liarsbar.core.model.Game;
import com.liarsbar.core.model.Play;
import com.liarsbar.core.model.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 机器人决策（纯逻辑，不碰网络，方便单独测试）。
 *
 * <p>策略贴近真人直觉：
 * <ul>
 *   <li>手里目标牌（含赖子 J）越多，说明别人手里的目标牌越少，就越倾向于质疑；</li>
 *   <li>上家一次出的牌越多越可疑（要凑出那么多目标牌更难）；</li>
 *   <li>出牌时有一定概率"吹牛"——把手里用不上的杂牌打出去，真目标牌留着；</li>
 *   <li>赖子 J 是万能牌，尽量留到关键时刻。</li>
 * </ul>
 */
public final class BotBrain {

    private static final Random RANDOM = new Random();

    private BotBrain() {
    }

    /** 机器人想做的动作。 */
    public static final class Action {
        /** true = 质疑，false = 出牌。 */
        public final boolean doubt;
        /** true = 过牌（不要）。 */
        public final boolean pass;
        /** doubt=false 时，要打出的手牌下标。 */
        public final List<Integer> cards;

        private Action(boolean doubt, boolean pass, List<Integer> cards) {
            this.doubt = doubt;
            this.pass = pass;
            this.cards = cards;
        }

        static Action doubt() {
            return new Action(true, false, null);
        }

        static Action pass() {
            return new Action(false, true, null);
        }

        static Action play(List<Integer> idx) {
            return new Action(false, false, idx);
        }
    }

    /** 根据当前局面决定动作；返回 null 表示什么都不做（不该发生）。 */
    public static Action decide(Game game, Player bot) {
        if (game == null || bot == null) {
            return null;
        }
        if (game.canDoubt(bot) && shouldDoubt(game, bot)) {
            return Action.doubt();
        }
        if (game.canPlay(bot)) {
            /*
             * 手里一张目标牌都没有、又不想硬吹牛时，偶尔选择"不要"过牌，
             * 把难题丢给下家（下家照样可以质疑上家）。概率刻意压低，
             * 免得一桌机器人互相过牌、两圈就弃局。
             */
            if (game.canPass(bot) && !hasTarget(game, bot) && RANDOM.nextInt(100) < 8) {
                return Action.pass();
            }
            return Action.play(pickCards(game, bot));
        }
        if (game.canDoubt(bot)) {
            return Action.doubt();
        }
        return null;
    }

    // ------------------------------------------------------------ 质疑判断

    private static boolean shouldDoubt(Game game, Player bot) {
        Play last = game.getLastPlay();
        if (last == null) {
            return false;
        }
        Card target = game.getTargetCard();
        List<Card> hand = bot.getHand();

        int myTargets = 0;
        int myJokers = 0;
        int myJunk = 0;
        for (Card c : hand) {
            if (c == Card.J) {
                myJokers++;
            } else if (c == target) {
                myTargets++;
            } else {
                myJunk++;
            }
        }

        // 整副牌里目标牌 6 张，赖子 2 张（赖子也能当目标牌用）
        int targetCardsTotal = 6;
        int jokersTotal = 2;
        // 别人理论上最多能凑出的"目标牌"张数
        int availableElsewhere = Math.max(0, targetCardsTotal - myTargets)
                + Math.max(0, jokersTotal - myJokers);
        int claimed = last.getCount();

        // 对方声称的张数超过别人手里可能有的总数 → 必假，直接开
        if (claimed > availableElsewhere) {
            return true;
        }

        double p = 0.22;                       // 基础怀疑度
        p += 0.09 * claimed;                   // 一次出得越多越可疑
        p += 0.05 * myJunk;                    // 自己一堆杂牌 → 别人也大概率在吹
        p -= 0.06 * myTargets;                 // 自己攥着一堆目标牌 → 别人更可能说真话
        if (myJokers > 0) {
            p -= 0.05 * myJokers;
        }
        if (p < 0.05) {
            p = 0.05;
        }
        if (p > 0.85) {
            p = 0.85;
        }
        return RANDOM.nextDouble() < p;
    }

    // ------------------------------------------------------------ 出牌选择

    /** 手里有没有"真牌"（目标牌或小丑）。没有的话只能吹牛，或者干脆过牌。 */
    private static boolean hasTarget(Game game, Player bot) {
        Card target = game.getTargetCard();
        if (target == null) {
            return false;
        }
        for (Card c : bot.getHand()) {
            if (c.matches(target)) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> pickCards(Game game, Player bot) {
        List<Card> hand = bot.getHand();
        Card target = game.getTargetCard();

        List<Integer> honest = new ArrayList<Integer>();   // 真的目标牌 / 赖子
        List<Integer> junk = new ArrayList<Integer>();     // 杂牌
        List<Integer> jokers = new ArrayList<Integer>();
        for (int i = 0; i < hand.size(); i++) {
            Card c = hand.get(i);
            if (c == Card.J) {
                jokers.add(Integer.valueOf(i));
            } else if (c == target) {
                honest.add(Integer.valueOf(i));
            } else {
                junk.add(Integer.valueOf(i));
            }
        }

        // 45% 老实出真牌，其余时候吹牛把杂牌打出去（赖子尽量留着）
        boolean bluff = RANDOM.nextDouble() < 0.55 && !junk.isEmpty();

        List<Integer> pool = bluff ? junk : honest;
        if (pool.isEmpty()) {
            pool = bluff ? honest : junk;
        }
        if (pool.isEmpty()) {
            pool = jokers;
        }
        if (pool.isEmpty()) {
            // 兜底：随便出第一张
            List<Integer> only = new ArrayList<Integer>();
            if (!hand.isEmpty()) {
                only.add(Integer.valueOf(0));
            }
            return only;
        }

        // 一次出 1~2 张，手里牌少的时候更倾向多出一点
        int maxTake = Math.min(Game.MAX_PLAY_PER_TURN, pool.size());
        int take = 1;
        if (maxTake >= 2 && RANDOM.nextDouble() < 0.45) {
            take = 2;
        }
        if (maxTake >= 3 && hand.size() <= 3 && RANDOM.nextDouble() < 0.5) {
            take = 3;
        }
        if (take > maxTake) {
            take = maxTake;
        }

        List<Integer> out = new ArrayList<Integer>(take);
        for (int i = 0; i < take; i++) {
            out.add(pool.get(i));
        }
        return out;
    }
}
