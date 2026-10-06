package com.liarsbar.core.model;

import java.util.Random;

/**
 * 左轮手枪：6 个弹位、1 发实弹。
 *
 * <p>规则与《Liar's Bar》一致：**实弹位置固定**，从位置 0 开始逐个扣扳机，
 * 因此第 1~6 次扣扳机中必定命中一次。玩家头顶的 x/6 就是 {@link #getShots()}。</p>
 */
public final class Gun {

    private static final Random RANDOM = new Random();

    private final int bulletPosition;
    private int position;
    private int shots;
    private boolean fired; // 是否已经中弹

    public Gun() {
        this.bulletPosition = RANDOM.nextInt(6);
        this.position = 0;
        this.shots = 0;
        this.fired = false;
    }

    /** 已扣扳机次数（0~6）。 */
    public int getShots() {
        return shots;
    }

    public boolean isFired() {
        return fired;
    }

    /**
     * 扣一次扳机。
     *
     * @return true = 中弹（该玩家淘汰）
     */
    public boolean shoot() {
        if (fired) {
            return true;
        }
        shots++;
        if (position == bulletPosition) {
            fired = true;
            return true;
        }
        position = (position + 1) % 6;
        return false;
    }
}
