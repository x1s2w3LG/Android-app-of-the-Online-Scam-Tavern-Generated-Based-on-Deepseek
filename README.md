> ⚠️ **非官方粉丝作品**
> 本项目是参考 Steam 游戏《Liar's Bar》（骗子酒馆）纸牌玩法做的**独立实现**，
> 仅供学习与朋友之间联机娱乐，**与原作者 / Curious Fox Games 无关**，
> **未使用其任何代码、美术或音频资源**。
> 本版规则经过魔改（加了过牌、两圈作废、判断正确者先出、胜场榜、观战等），
> **和原作不完全一样**，一切以 `core/src/main/resources/web/rules.html` 为准。

一个**零第三方依赖**的联机版骗子酒馆：一台设备开房，2~4 人（或加机器人）联机对战。

* **服务端**：纯 Java 手写 HTTP + WebSocket（RFC6455），自带极简 JSON 解析，**不依赖 Spring、Netty 或任何库**
* **客户端**：原生 JS + CSS，无框架、无 CDN、无构建步骤
* **两种外壳**：电脑上跑 `liarbar-server.jar`，或者手机装 APK 自己当主机
* **电脑只当服务器**：电脑不占座位、不当房主，**房主是第一个连进来的设备**

---

## 玩法规则（魔改版摘要）

| 规则 | 说明 |
|---|---|
| 牌堆 | Q×6、K×6、A×6，外加 **2 张小丑牌**（万能牌，可当任意目标牌）= 20 张 |
| 目标牌 | 每轮从 Q/K/A 中随机指定一张，每人发 **5 张**手牌 |
| 出牌 | 轮到你时扣着出 **1~5 张**，不要求真是目标牌（吹牛是核心） |
| **不要（过牌）** | 这一手不打，让给下家；**上家的牌仍留在桌上，下家照样可以质疑** |
| 质疑 | 只有当前玩家能质疑上家；翻牌后**只要有一张**既非目标牌也非小丑，即算撒谎 |
| 谁挨枪 | **判断错的那一方**对自己开一枪；左轮 6 弹位 1 发实弹，位置固定，6 枪内必响 |
| **谁先出** | 下一轮由**这一手判断正确**的人先出（质疑成功=质疑者先出；质疑失败=说真话者先出） |
| **两圈作废** | 转满两圈（动作数 ≥ 2×存活人数）都没人质疑 → 本轮作废重开，谁都不用挨枪 |
| **判定锁** | 质疑结算整段演出（异议→亮牌→左轮，约 7.4 秒）期间任何人不许出牌，避免节奏错乱 |
| **观战** | 被淘汰后仍能看牌桌，**聊天照常可用**，只是不能再出牌 |
| **胜场榜** | 按设备内置 UID 统计胜场，**改名/换头像/重连都不换身份**；数据存在房主设备上 |

细节见 [`rules.html`](core/src/main/resources/web/rules.html)（游戏内也内置了「📖 规则」按钮）。

---

## 怎么玩

### 方式一：手机当主机（最简单）
1. 手机装 `liarbar-*.apk`，打开后点「进入牌桌」
2. 把启动页上的地址告诉朋友（同一 WiFi 直接连）
3. 朋友用同样的 APK 点「加入房间」→ 粘贴地址
4. 第一个进来的人是房主，点「开始游戏」（人不够可以「＋ 添加机器人」）

### 方式二：电脑当主机（异地联机推荐）
1. 电脑上跑服务端：
   ```bash
   java -jar liarbar-server.jar --port 8080
   ```
2. 用**樱花映射**之类的内网穿透把 8080 端口暴露出去，拿到公网地址
3. 让服务端知道这个地址，房主大厅里的分享链接就会自动是它：
   ```bash
   java -jar liarbar-server.jar --port 8080 --public-url http://xxx.example.com:29629/
   ```
4. 朋友们在 APK 里「加入房间」粘贴这条隧道地址即可
5. **电脑自始至终不占座位、不当房主**，房主是第一个连进来的手机

命令行参数：

| 参数 | 说明 |
|---|---|
| `--port N` | 监听端口（默认 8080，被占用会自动往后找） |
| `--bind IP` | 只监听某块网卡（默认 `0.0.0.0` 全部） |
| `--public-url URL` | 公网/隧道地址，会同步给房主当默认分享地址 |
| `--open` | 启动后自动打开浏览器 |
| `--web DIR` | 从目录读前端（开发时热更新用） |

### 手机端 URL 参数（自动化/直达用）
| 参数 | 说明 |
|---|---|
| `?join=0` | 本机不入座，只当主机 |
| `?auto=enter` | 8 秒后自动进桌 |
| `?addr=host:port` | 直接填好房间地址 |
| `?addr=...&auto=join` | 填好并直接连过去 |

---

## 从源码构建

### 服务端 / 图形启动器（需要 JDK 17+）
```bash
powershell -ExecutionPolicy Bypass -File tools/build.ps1
# -> build/liarbar-server.jar（命令行）
# -> build/liarbar-launcher.jar（图形启动器，可自选端口/网卡、自动读樱花隧道地址）
```

### 安卓 APK（不用 Gradle，手工管线）
```bash
powershell -ExecutionPolicy Bypass -File tools/sync-web.ps1    # 同步前端到 assets
powershell -ExecutionPolicy Bypass -File tools/build-apk.ps1   # aapt2 -> javac -> d8 -> zipalign -> apksigner
# -> build/liarbar.apk
```
> 需要 Android SDK build-tools（脚本里可配路径），首次运行会提示缺少的组件。

### 测试
```bash
java -cp build/classes;build/test-classes RulesTest        # 规则引擎
java -cp build/classes;build/test-classes E2ETest          # 协议端到端
java -cp build/classes;build/test-classes ChatRenameTest   # 聊天/改名/头像/观战/胜场榜
java -cp build/classes;build/test-classes BotGameTest      # 机器人整局对战
```
合计约 190 项断言，全部纯 Java、不需要任何测试框架。

---

## 目录结构

```
core/       规则引擎 + 服务端（HTTP/WebSocket/JSON 全手写）+ 前端页面
desktop/    命令行入口（liarbar-server.jar）
launcher/   图形启动器（Swing，自选端口/网卡、自动识别樱花隧道地址）
android/    安卓外壳（WebView + 前台服务，把服务端跑在手机里）
tests/      纯 Java 测试（规则/协议/整局对战）
tools/      构建脚本（含免 Gradle 的 APK 打包管线）
```

---

## 一些实现说明

* **服务端权威**：所有出牌/质疑判定都在服务端，客户端只发意图，防作弊
* **断线重连**：60 秒宽限期，凭 token 恢复座位、手牌和左轮弹位
* **机器人**：服务端 `BotBrain` 走和真人**完全相同**的判定路径，会看牌、出牌、质疑、偶尔过牌
* **局域网发现**：UDP 广播（18923）自动扫描同网段房间
* **音效**：出牌/开枪/转轮用 Web Audio 实时合成，不需要音频文件
* **自适应**：界面按高度分档（≤620/460/430/370/320px）+ 安全区适配，窄屏横屏也不溢出

---

## 关于素材

仓库内**不含任何第三方美术/音频素材**：卡面、小丑牌、机器人头像、左轮都是 CSS/SVG 现场绘制。

质疑时会弹出「異議あり！」——图片和语音是**可选素材**，本仓库**故意没有附带**：

* 图片缺失 → 自动降级成大字「異議あり！」（`web/js/app.js` 里的 `objection-fallback`）
* 语音缺失 → 自动降级成系统日文语音 / 合成吼声

想换成自己的素材，把文件放回去即可，**文件名保持不变、无需改代码**：

```
core/src/main/resources/web/img/objection.png     透明背景 PNG
core/src/main/resources/web/audio/objection.mp3   约 3 秒
```

---

## 授权

代码以 [MIT](LICENSE) 授权。请保留上面的**非官方粉丝作品**声明，
不要用本项目名义发布到应用商店，也不要说成官方版本。

游戏名与玩法创意的权利属于其原作者；本项目只是玩法层面的独立实现。
