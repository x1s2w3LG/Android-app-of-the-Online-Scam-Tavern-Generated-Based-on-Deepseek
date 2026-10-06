package com.liarsbar.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 牌面：Q / K / A 为目标牌候选，J 是赖子牌（可当任意目标牌）。
 * 牌堆构成与《Liar's Bar》一致：Q×6 + K×6 + A×6 + J×2 = 20 张。
 */
public enum Card {
    Q, K, A, J;

    /** 每轮目标牌只能从这三张里选。 */
    public static final Card[] TARGETS = {Q, K, A};

    /** 该牌能否被视为目标牌（J 是赖子）。 */
    public boolean matches(Card target) {
        return this == J || this == target;
    }

    /** 新建一副完整的 20 张牌（未洗牌）。 */
    public static List<Card> newDeck() {
        List<Card> deck = new ArrayList<Card>(20);
        for (int i = 0; i < 6; i++) {
            deck.add(Q);
            deck.add(K);
            deck.add(A);
        }
        deck.add(J);
        deck.add(J);
        return deck;
    }

    /** 洗牌。 */
    public static void shuffle(List<Card> deck) {
        Collections.shuffle(deck);
    }

    /** 解析前端传来的牌名，非法返回 null。 */
    public static Card parse(String s) {
        if (s == null) {
            return null;
        }
        for (Card c : values()) {
            if (c.name().equalsIgnoreCase(s.trim())) {
                return c;
            }
        }
        return null;
    }
}
