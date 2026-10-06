# 骗子酒馆 · Android 壳工程 —— 构建与安装说明

这个工程把**纯 Java 的游戏服务端**（`liarbar/core`）整个塞进 APK：

- 手机装上 APK 后，**自己就是主机**（HTTP + WebSocket 跑在手机里）
- 手机上的游戏界面就是内嵌 WebView 打开的 `http://127.0.0.1:8080/`
- 同一个 WiFi 下的朋友用**浏览器**打开 `http://<手机IP>:8080/` 就能加入
  —— iPhone、电脑、平板都能进，**不需要装 APK**
- 全程**零第三方依赖**：没有 androidx、没有 appcompat、没有 Kotlin
  （只有构建时必需的 Android Gradle Plugin）

---

## 1. 前置条件

### 方案 A：Android Studio（推荐，最省事）

装 **Android Studio**（任意较新的稳定版，如 Koala / Ladybug / Meerkat 都可以），
然后在 SDK Manager 里确认装上：

| 组件 | 版本 |
|---|---|
| SDK Platform | **Android 14.0 (API 34)** |
| Build-Tools | **34.0.0** |
| Android SDK Platform-Tools | 任意较新版本（要里面的 `adb`） |

Android Studio 自带 JDK，不需要额外装。

### 方案 B：纯命令行 SDK

只要 **cmdline-tools**（不需要 Android Studio）：

```powershell
# 假设 SDK 放在 C:\Android\sdk
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

### JDK

需要 **JDK 17 或 21**（AGP 8.5.2 要求 JDK 17+）。

本机已经装好了：

```
C:\Program Files\Android\openjdk\jdk-21.0.8
```

让 Gradle 用它：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
```

### 环境变量

命令行构建时告诉 Gradle SDK 在哪（Android Studio 会自动配好，可跳过）：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"   # 换成你自己的 SDK 路径
# 或者写到 android\local.properties（这个文件不要提交到 git）
```

`android\local.properties` 内容示例：

```properties
sdk.dir=C\:\\Android\\sdk
```

---

## 2. 先同步网页资源（必做）

前端页面由 core 模块提供，需要先复制进 Android 的 assets：

```powershell
cd <仓库>\liarbar
powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-web.ps1
```

脚本会把 `core\src\main\resources\web\**` 复制到
`android\app\src\main\assets\web\**`（先清空目标目录，再复制）。

想先看看会复制什么，加 `-DryRun`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-web.ps1 -DryRun
```

> 每次前端改动后都要**重新跑一次**这个脚本，否则 APK 里还是旧页面。

也可以让 Gradle 自动同步（可选，默认关闭）：

```powershell
.\gradlew assembleDebug -PsyncWeb=true
```

---

## 3. 构建 APK

### 方案 A：Android Studio

1. Android Studio → **Open** → 选中 **`liarbar\android`** 目录（注意是 `android`，不是仓库根目录）
2. 首次打开会提示 **Gradle Sync**，点 **Sync Now** 等它跑完
   - 第一次会自动下载 Gradle 8.7 和 Android Gradle Plugin 8.5.2，需要联网
   - Android Studio 还会**自动补全缺失的 `gradle-wrapper.jar`**（见下面第 4 节）
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. 构建完成后点提示里的 **locate**，APK 在：

```
android\app\build\outputs\apk\debug\app-debug.apk
```

### 方案 B：命令行

```powershell
cd <仓库>\liarbar\android
.\gradlew assembleDebug
```

产物同样是 `app\build\outputs\apk\debug\app-debug.apk`。

只编译不打包（快速查语法错误）：

```powershell
.\gradlew compileDebugJavaWithJavac
```

清理：

```powershell
.\gradlew clean
```

---

## 4. 关于 `gradle-wrapper.jar`（重要）

本工程只提供了 `gradle\wrapper\gradle-wrapper.properties`（内容是 Gradle **8.7**），
**没有 `gradle-wrapper.jar` 这个二进制文件**——生成它需要有 Gradle 环境，
而当前生成这个工程的环境里没有 Gradle。

两种解决办法，任选其一：

- **用 Android Studio 打开**：首次 Sync 会自动补全 wrapper 的 jar 和 `gradlew` 脚本，之后
  命令行也能直接用 `.\gradlew`。
- **命令行自己生成**：先装一个 Gradle 8.7（或任意 8.x），在 `android` 目录下执行一次：

  ```powershell
  gradle wrapper --gradle-version 8.7
  ```

  之后就一直用 `.\gradlew` 即可。

> 如果连 `gradlew` 脚本都没有，说明 wrapper 完全没生成过；
> 有网络也可以用 `gradle wrapper` 之外的方式，比如直接用系统的 `gradle assembleDebug`。

---

## 5. 安装到手机 / 模拟器

手机打开「开发者选项 → USB 调试」，用数据线连上电脑：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

也可以把 `app-debug.apk` 直接传到手机上点安装（需要允许「安装未知来源应用」）。

启动应用：

```powershell
adb shell am start -n com.liarsbar.android/.MainActivity
```

看日志（排查问题很有用）：

```powershell
adb logcat -s MainActivity ServerService AssetStaticProvider
```

---

## 6. 用 MuMu 模拟器测试

MuMu 是 Android 12，适合快速验证。

MuMu 自带的 adb 路径：

```
C:\Program Files\Netease\MuMu\nx_main\adb.exe
```

MuMu 的 adb 端口不一定是默认的 5555，**先让 adb 自己发现设备**：

```powershell
$adb = 'C:\Program Files\Netease\MuMu\nx_main\adb.exe'
& $adb devices
```

如果列表里没有设备：

1. 确认 MuMu 已经启动并且完全进入桌面
2. 用 MuMu 的 adb 去连它的端口（端口号在 MuMu 设置 → 其他 → ADB 调试里能看到）

   ```powershell
   & $adb connect 127.0.0.1:7555    # 7555 是 MuMu 常见端口，以设置里显示的为准
   & $adb devices
   ```

3. 还是不行就把 MuMu 的多开器/设置里的 **ADB 调试** 打开，重启模拟器

然后正常安装：

```powershell
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.liarsbar.android/.MainActivity
```

> **重点**：模拟器里要用「局域网地址」测试真实联机比较麻烦（模拟器网络是 NAT）。
> 建议这样验证：
> 1. 先在模拟器里确认能正常进游戏、能发牌、能开枪（本机 WebView 走的也是 WebSocket，够验证协议）
> 2. 真机联机再找两台手机/电脑在同一个 WiFi 下测

---

## 7. 手机端使用说明（给玩家看的）

1. **开房的人（房主）** 装好 APK，打开应用
2. 应用会自动启动主机，屏幕顶部会弹出常驻通知：**「骗子酒馆 主机运行中」**，
   通知内容就是局域网地址，例如：

   ```
   http://192.168.1.5:8080/
   ```

3. 点通知里的 **「停止主机」** 可以关房
4. 房主在游戏里点「开始游戏」（**至少 2 人**才能开）
5. **其他人怎么加入？**
   - 确保手机/电脑连的是**同一个 WiFi**
   - 打开浏览器，输入房主通知里的那个地址，例如 `http://192.168.1.5:8080/`
   - **iPhone、iPad、Windows、Mac 都能进，不用装任何东西**
   - 大厅里也会显示这个地址，房主可以直接念给朋友听
6. 游戏中途息屏/切后台都没问题：主机有前台服务 + 唤醒锁保活，
   只要没点「退出并关房」就一直在跑

> 找不到地址？注意手机是不是连在「访客 WiFi」或者开了 AP 隔离的路由器上——
> 那种网络下设备之间互相 ping 不通，换一个 WiFi 就行。

---

## 8. 省电设置（强烈建议做，否则锁屏后可能被杀）

手机当主机时，系统会因为省电把应用杀掉，导致**所有人掉线**。
请在系统设置里给本应用开「允许后台运行 / 无限制」：

| 品牌 | 大致路径 |
|---|---|
| 小米 / Redmi | 设置 → 应用设置 → 应用管理 → 骗子酒馆 → **省电策略 → 无限制**；再打开 **自启动** 和 **后台弹出界面** |
| 华为 / 荣耀 | 设置 → 应用 → 应用启动管理 → 骗子酒馆 → 关闭「自动管理」，手动勾选 **允许自启动 / 允许关联启动 / 允许后台活动** |
| OPPO / 一加 / realme | 设置 → 电池 → 应用耗电管理 → 骗子酒馆 → 允许 **后台运行 / 智能省电关闭** |
| vivo / iQOO | 设置 → 电池 → 后台高耗电 → 允许骗子酒馆；i 管家 → 自启动管理 |
| 三星 | 设置 → 电池 → 后台使用限制 → 把骗子酒馆加入 **「永不休眠的应用」** |
| 原生 Android | 设置 → 应用 → 骗子酒馆 → 电池 → **不受限制** |

另外两个小提示：

- 别用「一键清理」类工具清后台，会把主机一起清掉
- 如果通知权限被拒了，前台服务通知可能看不见；建议在
  设置 → 应用 → 骗子酒馆 → 通知 里允许通知（这样才方便点「停止主机」）

---

## 9. 项目结构

```
liarbar\
├── PROTOCOL.md                     # 前后端契约（含 core 对外 API）
├── core\                           # 纯 Java 服务端 + 网页（另一个人写，本工程只引用）
│   ├── src\main\java\com\liarsbar\core\...
│   └── src\main\resources\web\...   # 网页源文件
├── tools\
│   └── sync-web.ps1                # 网页 → APK assets 同步脚本
└── android\                        # ← 本工程
    ├── settings.gradle
    ├── build.gradle                # 根：只声明 AGP 8.5.2
    ├── gradle.properties
    ├── gradle\wrapper\gradle-wrapper.properties   # Gradle 8.7（jar 需自行补全）
    ├── README-构建.md               # 本文件
    ├── _selfcheck.ps1              # 自检：XML 完好性 / 资源引用 / 清单组件
    ├── _compile-check.ps1          # 自检：用 API 桩真正编译一遍 Java
    └── app\
        ├── build.gradle            # compileSdk 34 / minSdk 26 / Java 11 / 零依赖
        ├── proguard-rules.pro
        └── src\main\
            ├── AndroidManifest.xml
            ├── assets\web\         # ← sync-web.ps1 填充，不要手写
            ├── java\com\liarsbar\android\
            │   ├── MainActivity.java
            │   ├── ServerService.java
            │   └── AssetStaticProvider.java
            └── res\
```

`app\build.gradle` 里这一行把 core 源码一起编译进 APK：

```groovy
android.sourceSets.main.java.srcDirs(['../../core/src/main/java'])
```

（路径相对 `app\` 目录：`app\` → `android\` → `liarbar\` → `liarbar\core\src\main\java`）

---

## 10. 自检脚本（没有 Android SDK 时也能跑）

这两个脚本**不需要 Android SDK**，只用本机 JDK：

```powershell
cd <仓库>\liarbar\android

# 1) 用「Android API 桩」真正编译一遍 3 个 Java 文件（先跑这个）
powershell -NoProfile -ExecutionPolicy Bypass -File _compile-check.ps1

# 2) XML 完好性 + Java 编译 + 所有 @string/@drawable/R.* 引用是否存在 + 清单组件是否存在
powershell -NoProfile -ExecutionPolicy Bypass -File _selfcheck.ps1

# 3) 连 core 源码一起编译（集成检查；core 还没写完时会报它的错，属正常）
powershell -NoProfile -ExecutionPolicy Bypass -File _compile-check.ps1 -WithRealCore
```

> 先跑 1 再跑 2：`_selfcheck.ps1` 复用 `_compile-check.ps1` 生成的那套桩类来做真正的
> Java 编译检查（这样它自己不需要再写一套脆弱的语法解析器）。
> 直接跑 2 也安全，它只会提示「桩类还没生成」并跳过第 2 项。
>
> 桩类放在 `%TEMP%\liarbar-javac-check\`，不会污染工程目录。

`_compile-check.ps1` 会临时生成一套极简的 Android API 桩类（`Activity`、`WebView`、
`Notification`、`Service`…签名与真实 SDK 一致）和一个严格照 `PROTOCOL.md` 第 6 节写的
`LiarbarServer` 桩，然后用 `javac` 编译真实源码。**编译通过 = 所有 Android API 调用与
core API 调用都是对的。**

两个脚本都可以在构建前随手跑一下，比等 Gradle 报错快得多。

> 这两个脚本是**纯 ASCII** 的。原因：Windows PowerShell 5.1 读取无 BOM 的 UTF-8
> 文件时按系统 ANSI 代码页（简体中文 Windows 是 GBK）解码，脚本里如果有中文会导致
> 「字符串缺少终止符」这类解析错误。工程里的 `.java` / `.gradle` / `.md` 都是无 BOM
> UTF-8（Gradle 用平台默认编码、javac 用 UTF-8），只有 `.ps1` 需要特别注意。

---

## 11. 常见问题

| 现象 | 原因 / 解决 |
|---|---|
| `Could not find or load main class org.gradle.wrapper.GradleWrapperMain` | `gradle-wrapper.jar` 没生成 → 看第 4 节 |
| `SDK location not found` | 没配 `ANDROID_HOME` 或 `local.properties` → 看第 1 节 |
| `Unsupported class file major version` / `Unsupported Java` | JDK 太新或太旧 → 用 JDK 17 或 21，设置 `JAVA_HOME` |
| `package com.liarsbar.core does not exist` | core 源码不在预期位置，或还没写完 → 确认 `liarbar\core\src\main\java\com\liarsbar\core\` 存在 |
| 应用里显示「主机启动失败：端口…」 | 8080 起连续 20 个端口都被占（很少见）→ 关掉其它占端口的应用重开 |
| 应用里显示「主机启动超时」 | 系统限制了前台服务 → 检查第 8 节的省电设置 |
| 页面白屏 / 404 | 忘了跑 `tools\sync-web.ps1`，assets 里没有 `index.html` |
| 朋友打不开局域网地址 | 不在同一 WiFi / 路由器开了 AP 隔离 / 手机在访客网络 |
| 锁屏一会儿大家就掉线 | 没做第 8 节的省电白名单 |
| 通知里看不到「停止主机」按钮 | 正常——按钮只在主机**成功启动后**才出现；启动中不给，避免误触 |
| 通知权限被拒 | 前台服务通知在 Android 13+ 仍会显示，只是用户划不掉；想收到提醒就去设置里允许通知 |

---

## 12. 技术要点速查

| 项 | 值 |
|---|---|
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26（Android 8.0+） |
| Java 兼容级别 | 11 |
| namespace / applicationId | `com.liarsbar.android` |
| 第三方依赖 | **无** |
| 语言 | 纯 Java（无 Kotlin） |
| 默认端口 | 8080（被占时由 core 自动向后避让，最多 20 个） |
| 权限 | INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE、WAKE_LOCK、FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC、POST_NOTIFICATIONS（**全是普通权限，没有存储/定位/相机等危险权限**） |
| 保活 | 前台服务（dataSync）+ 常驻通知 + PARTIAL_WAKE_LOCK + WifiLock(HIGH_PERF) + START_STICKY |
| 屏幕方向 | `sensorLandscape`（横屏） |
| 明文流量 | 允许（WebSocket 是 `ws://`，见 `res/xml/network_security_config.xml`） |
