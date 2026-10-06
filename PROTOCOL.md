# Liar's Bar 联机协议规范 v2（冻结版）

> 本文件是前后端之间的**契约**。前端、core、Android 壳都必须严格遵守。
> 任何一方需要改动字段，必须先改本文件。

## 0. 架构总览

```
                     ┌──────────────────────────────────────┐
                     │  LiarbarServer (纯 Java, 零依赖)      │
   浏览器 / WebView  │  ├── HTTP  服务静态页面 (单端口)      │
   ───────────────►  │  └── WebSocket 升级 (/ws 同端口)      │
                     └──────────────────────────────────────┘
```

- **HTTP 与 WebSocket 共用同一个端口**（默认 `8080`）。这样局域网、樱花映射 TCP 隧道、
  HTTPS 隧道三种场景都只需要一条隧道、一个 URL。
- 前端**不允许**硬编码任何主机名/端口/IP。一律用：

```js
const WS_URL = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
```

- 入口 URL：`/` 与 `/gamepage` 都返回同一个 `index.html`。
- 静态资源路径：`/js/app.js`、`/css/basic.css` 等（相对 `index.html`）。

## 1. 规则（对齐原作《Liar's Bar》纸牌模式）

| 规则 | 实现 |
|---|---|
| 牌堆 | `Q×6 K×6 A×6` + 小丑牌 `J×2`（**小丑牌可当目标牌**）= 20 张 |
| 手牌 | 每人每轮发 **5 张** |
| 目标牌 | 每轮从 `Q/K/A` 中随机指定一张 |
| 出牌 | 每回合必须出 **1~5 张**，且必须是手里真有的牌 |
| 过牌 | 轮到自己时可选「不要」：不打牌，把机会交给下家；上家的牌仍留在桌上可被质疑 |
| 回合推进 | 转满 **两圈**（动作数 ≥ 2×存活人数）都没人质疑 → 本轮作废、重新发牌，谁都不用挨枪 |
| 质疑 | **只有当前回合玩家**可以质疑**上家**（最近一次出牌者） |
| 判定 | 上家打出的牌**只要有一张**既不是目标牌也不是 `J`，即算欺骗 |
| 欺骗成立 | 出牌者对自己开枪，**且下一轮由成功质疑者先出**（鼓励质疑） |
| 欺骗不成立 | 质疑者对自己开枪，下一轮由他先出（原来的规则） |
| 判定锁 | 质疑结算的整段演出（异议→亮牌→左轮，约 **7.4 秒**）期间，任何人都不能出牌/质疑/过牌；解禁时服务端会补一次 state |
| 左轮 | 每人一把，6 个弹位、1 发实弹，**位置固定，第 1~6 次扣扳机中必中一次**；头顶显示 `x/6` |
| 出完手牌 | 该玩家本轮**跳过**并成为本轮胜利者；但若被下家质疑则照常翻牌判定 |
| 一轮结束 | 发生质疑并开枪后 → 重新发牌、重新指定目标牌，由上面「先出」规则决定谁先出 |
| 观战 | 被淘汰后仍收到 state（能看），但**不能发言**（`dead-cannot-chat`），也不再参与出牌 |
| 胜负 | 场上只剩 **1 名存活玩家** 时结束，该玩家获胜 |
| 人数 | **2~4 人**。房主点「开始游戏」即开局（≥2 人，**机器人也算人数**） |
| 机器人 | 房主可在大厅「＋ 添加机器人」补齐空位；机器人走与真人完全相同的规则路径，不会作弊 |
| 改名 | **只在大厅 / 结算后**允许；对局中服务端会拒绝（`game-in-progress`） |
| 重名 | 同一房间内重名会自动加序号（玩家 → 玩家2 → 玩家3…） |

## 2. 连接

```
ws://<host>:<port>/ws
```

客户端连上后**必须**在 5 秒内发送 `join`，否则被断开。

## 3. 客户端 → 服务端

所有消息都是 JSON 对象，`t` 为类型。

```jsonc
// 加入/重连（必须第一条）
{"t":"join","name":"张三"}                 // name 可省略，默认 "玩家"
{"t":"join","name":"张三","token":"<上次的 token>"}  // 带 token 则是断线重连

// 房主开始游戏（2~4 人）
{"t":"start"}

// 出牌：cards 是「手牌下标数组」，1~5 个，下标 0 起
{"t":"play","cards":[0,2]}

// 质疑上家
{"t":"doubt"}

// 过牌（「不要」）：这一手不打，交给下家；上家的牌仍在桌上，下家可以质疑它。
// 转满两圈没人质疑时，服务端会作废这一轮并重新发牌。
{"t":"pass"}

// 改名：只在大厅 / 结算后有效，对局中会被拒绝（game-in-progress）
{"t":"rename","name":"新名字"}

// 添加 / 移除机器人（仅房主，仅大厅阶段）
{"t":"add-bot"}
{"t":"remove-bot"}

// 修改"分享给朋友的地址"（仅房主）。填 IP 或 域名:端口 都行；
// 传空字符串表示恢复自动检测。
{"t":"set-lan","url":"192.168.1.5"}
{"t":"set-lan","url":"xxx.natfrp.com:12345"}
{"t":"set-lan","url":""}

// 聊天（大厅与对局中都能发）。服务端限流 350ms/条，单条最多 60 字。
{"t":"chat","text":"我先出个牌"}

// 上传头像：data 是图片的 base64（前端已裁成 96×96 的 JPEG，约几 KB）。
// 传空字符串表示清除头像。上限 128KB，超了回 avatar-too-large。
// 头像不进 state 广播，前端按 /avatar/<playerId>?v=<版本> 拉取。
{"t":"avatar","data":"<base64>","mime":"image/jpeg"}
{"t":"avatar","data":""}

// 游戏结束后房主重开
{"t":"restart"}

// 心跳
{"t":"ping"}
```

## 4. 服务端 → 客户端

### 4.1 全量状态快照 `state`

**任何状态变化后，服务端都会广播一份新的 `state` 给所有人（每人收到的手牌不同）。**
前端收到 `state` 就整体重绘，不要自己拼状态。

```jsonc
{
  "t": "state",
  "phase": "lobby",              // lobby | playing | over
  "you": "p1",                   // 你自己的 playerId
  "isHost": true,                // 你是不是房主
  "lanUrl": "http://192.168.1.5:8080/",   // 分享给朋友的地址（房主可改），可能为 null
  "lanCustom": false,            // true = 房主手工改过（不再是自动检测的）
  "lanCandidates": [             // 自动检测到的本机网卡地址，供房主一键选用
    "192.168.1.5",
    "10.0.2.15"
  ],
  "minPlayers": 2,
  "maxPlayers": 4,
  "canStart": true,              // 房主视角：现在能否开始（人数>=2 且 phase=lobby）
  "players": [                   // 按座位顺序，4 人桌时可能不足 4 个
    {
      "id": "p1",
      "name": "张三",
      "isHost": true,
      "connected": true,         // false = 断线中（保留座位）；机器人恒为 true
      "bot": false,              // true = 机器人（服务端托管，没有连接）
      "alive": true,
      "shots": 2,                // 已扣扳机次数 x/6
      "handCount": 5             // 剩余手牌数（别人的手牌内容不公开）
    }
  ],
  "hand": ["Q","K","A","J","Q"], // 只有你自己的手牌；非 playing 阶段为 []
  "targetCard": "Q",             // 本轮目标牌；lobby 阶段为 null
  "currentPlayerId": "p2",       // 当前该谁行动；lobby/over 为 null
  "table": [                     // 本轮所有出牌记录，按时间顺序
    { "playerId": "p1", "count": 2, "cards": null }  // cards 仅在 reveal 后回填
  ],
  "lastPlayerId": "p1",          // 最近一次出牌者（可质疑对象）；无则 null
  "canPlay": true,               // 你此刻能否出牌（轮到你 & 手里有牌）
  "canDoubt": false,             // 你此刻能否质疑（轮到你 & 有可质疑对象）
  "winnerId": null,              // phase=over 时的赢家
  "message": null                // 可选提示文案
}
```

### 4.2 事件消息（用于动画/音效，前端可忽略但建议处理）

```jsonc
{"t":"welcome","playerId":"p1","token":"9f2c...","isHost":true}
{"t":"renamed","playerId":"p1","name":"张三2"}     // 改名生效后的最终名字（重名会带序号）
{"t":"chat","playerId":"p1","name":"张三","text":"我先出个牌","ts":1730000000000}
{"t":"played","playerId":"p1","count":2}          // 有人出牌
{"t":"doubt","byId":"p2","targetId":"p1"}         // 质疑发生
{"t":"reveal","playerId":"p1","cards":["Q","A"],"lie":true}  // 翻牌结果
{"t":"shot","playerId":"p1","hit":true,"shots":3,"remaining":2} // 开枪结果
{"t":"round","targetCard":"A","firstPlayerId":"p2"} // 新回合开始
{"t":"gameover","winnerId":"p2","winnerName":"李四"}
{"t":"error","code":"not-your-turn","msg":"还没轮到你"}
{"t":"pong"}
```

`error.code` 取值：`bad-message` / `not-in-room` / `not-host` / `not-your-turn` /
`bad-card-count` / `card-not-owned` / `nothing-to-doubt` / `not-enough-players` /
`game-in-progress` / `name-required` / `join-timeout` / `room-full` / `no-bot` /
`bad-address` / `game-over`。

### 4.3 断线规则

- 玩家掉线：座位**保留 60 秒**，期间 `connected=false`，其他人看到「掉线中」。
- 60 秒内用同一 `token` 发 `join` 即可**恢复原座位、原手牌、原枪**。
- 超时：
  - 大厅阶段 → 直接移除该玩家。
  - 游戏进行中 → 该玩家**淘汰**（等同中弹），游戏继续；若因此只剩 1 人则立即结算。
- WebSocket 层每秒发一次 ping 帧保活（服务端主动），30 秒无任何数据则判定掉线。

## 5. 静态资源

服务端从 `StaticProvider` 读取，路径形如 `index.html`、`js/app.js`、`css/basic.css`。
- 桌面端：classpath `/web/...`
- Android：`assets/web/...`

需返回正确的 `Content-Type`（html/css/js/png/jpg/svg/ico）。

## 6. core 对外 Java API（Android 与桌面端共用）

```java
package com.liarsbar.core;

public final class LiarbarServer {
    /** 静态资源提供者：path 形如 "index.html" / "js/app.js"，找不到返回 null。 */
    public interface StaticProvider { byte[] read(String path); }

    public LiarbarServer();                          // 默认 8080，classpath:/web
    public LiarbarServer(int port, StaticProvider provider);
    public void start() throws java.io.IOException;   // 立即返回，内部线程
    public void stop();
    public int getPort();                            // 实际监听端口（自动避让后）
    public String getLanUrl();                       // "http://192.168.x.x:8080/" 或 null
    public int getPlayerCount();
}
```

构造/启动失败抛 `java.io.IOException`。

## 7. 局域网自动发现（可选能力）

客户端往广播地址的 **UDP 18923** 端口发一个 `LIARBAR?`，服务器回一条
`LIARBAR|端口|人数|阶段`。客户端从 UDP 包的来源地址就知道房主 IP（所以回复里不带 IP）。

- 安卓 App 启动页的「搜索附近房间」用的就是它；
- 广播失败/被网络禁用时不影响游戏，只是退回手动填地址（这是预期行为，不是 bug）;
- 服务端绑定失败会静默降级，不影响 HTTP/WS 服务。

## 8. 内置页面

| 路径 | 说明 |
|---|---|
| `/` 或 `/gamepage` | 游戏主页面（大厅 + 牌桌） |
| `/start.html` | 启动页：创建房间 / 加入房间 / 搜索附近房间（安卓 App 的入口页） |
| `/rules.html` | 内置规则说明（启动页可单独打开；牌桌里用浮层 iframe 内嵌，避免跳转导致掉线） |
| `/js/sfx.js` | 音效引擎（Web Audio 现场合成，不加载任何音频文件） |
