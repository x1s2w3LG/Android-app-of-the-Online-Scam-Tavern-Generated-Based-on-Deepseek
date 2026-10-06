package com.liarsbar.core.model;

import java.util.List;

/** 一次出牌记录。 */
public final class Play {

    private final Player player;
    private final List<Card> cards;
    private boolean revealed;

    public Play(Player player, List<Card> cards) {
        this.player = player;
        this.cards = cards;
    }

    public Player getPlayer() {
        return player;
    }

    public List<Card> getCards() {
        return cards;
    }

    public int getCount() {
        return cards.size();
    }

    public boolean isRevealed() {
        return revealed;
    }

    public void setRevealed(boolean revealed) {
        this.revealed = revealed;
    }
}
