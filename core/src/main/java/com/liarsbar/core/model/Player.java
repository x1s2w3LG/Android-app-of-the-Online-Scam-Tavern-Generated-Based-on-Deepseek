package com.liarsbar.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 玩家。纯数据对象，不依赖任何网络/框架类型，方便单元测试。
 * 连接状态由 service 层维护，这里只保留一个 {@link #connected} 标记供前端展示。
 */
public final class Player {

    private final String id;
    private final String token;
    private final int seat;
    /** 电脑玩家（由服务端托管，没有连接）。 */
    private final boolean bot;

    private String name;
    private boolean host;
    private boolean connected = true;

    private List<Card> hand = new ArrayList<Card>();
    private Gun gun = new Gun();

    private boolean alive = true;
    private boolean finishedRound = false;

    private long disconnectedAt;
    /** 上次发言时间，用于聊天限流。 */
    private long lastChatAt;

    /** 头像版本号：0 = 没设置；每次换头像 +1，前端用它拼 URL 破缓存。 */
    private int avatarVersion;
    private String avatarMime;
    private byte[] avatarData;

    /**
     * 设备内置 UID：客户端第一次运行时生成、永久保存在本机。
     * 改名、换头像、断线重连都不会变，胜场排行榜就是按它统计的。
     */
    private String uid;

    public Player(String id, String token, int seat, String name) {
        this(id, token, seat, name, false);
    }

    public Player(String id, String token, int seat, String name, boolean bot) {
        this.id = id;
        this.token = token;
        this.seat = seat;
        this.name = name;
        this.bot = bot;
    }

    public boolean isBot() {
        return bot;
    }

    public String getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public int getSeat() {
        return seat;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isHost() {
        return host;
    }

    public void setHost(boolean host) {
        this.host = host;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        if (connected) {
            this.disconnectedAt = 0;
        } else {
            this.disconnectedAt = System.currentTimeMillis();
        }
    }

    public long getDisconnectedAt() {
        return disconnectedAt;
    }

    public long getLastChatAt() {
        return lastChatAt;
    }

    public void setLastChatAt(long lastChatAt) {
        this.lastChatAt = lastChatAt;
    }

    public int getAvatarVersion() {
        return avatarVersion;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getAvatarMime() {
        return avatarMime;
    }

    public byte[] getAvatarData() {
        return avatarData;
    }

    /** 设置头像（data 为 null 表示清除），版本号自增。 */
    public void setAvatar(String mime, byte[] data) {
        this.avatarMime = mime;
        this.avatarData = data;
        this.avatarVersion++;
    }

    public List<Card> getHand() {
        return hand;
    }

    public void setHand(List<Card> hand) {
        this.hand = hand == null ? new ArrayList<Card>() : hand;
    }

    public Gun getGun() {
        return gun;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    /** 本轮是否已经出完手牌（出完后本轮跳过）。 */
    public boolean isFinishedRound() {
        return finishedRound;
    }

    public void setFinishedRound(boolean finishedRound) {
        this.finishedRound = finishedRound;
    }

    /** 新的一局：重置生命与手枪。 */
    public void resetForNewGame() {
        this.alive = true;
        this.finishedRound = false;
        this.gun = new Gun();
        this.hand = new ArrayList<Card>();
    }

    /** 按手牌下标出牌（下标会被去重排序后从大到小删除）。 */
    public List<Card> removeCards(List<Integer> indices) {
        List<Integer> sorted = new ArrayList<Integer>(indices);
        Collections.sort(sorted, Collections.reverseOrder());
        List<Card> removed = new ArrayList<Card>();
        for (Integer i : sorted) {
            removed.add(hand.remove(i.intValue()));
        }
        Collections.reverse(removed);
        return removed;
    }

    @Override
    public String toString() {
        return name + "(" + id + ")";
    }
}
