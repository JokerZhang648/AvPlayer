# 本机运行环境（Windows）— 已装好，可直接构建

> 这份文档记录的是**这台 Windows 机器上实际装了什么、装在哪、为什么这么装**，以及踩到的坑。
> 换机器照抄 §2 的清单即可，不用照抄路径。

---

## 1. 现状一览（已验证）

| 项 | 值 |
|---|---|
| JDK | OpenJDK 21.0.2（华为云镜像的 Oracle OpenJDK build）`C:\tools\jdk-21.0.2` |
| Android Studio | 2026.2.2.9（`AI-262.10968.63.2622.16840690`）`C:\Program Files\Android\Android Studio`，占用约 3.4 GB |
| Gradle | 8.11.1 `C:\tools\gradle-8.11.1`（另外 wrapper 已生成，`gradlew.bat` 可用） |
| Android SDK | `C:\tools\android-sdk`：`platform-tools` + `platforms;android-35` + `build-tools;35.0.0` |
| 环境变量 | 用户级 `JAVA_HOME=C:\tools\jdk-21.0.2`；`PATH` 追加了 JDK\bin 与 Gradle\bin |
| 工程配置 | `AvPlayKMP\local.properties` 指向 `C:\tools\android-sdk` |
| 构建结果 | `composeApp-debug.apk` 11.12 MB，BUILD SUCCESSFUL |
| 单元测试 | 24 个测试全部通过（6 个测试类） |

**Android Studio 是后装的**（用户要求），本身不影响命令行构建：两条路径都能用。

---

## 2. 换机器要装的三样（按顺序）

### 2.1 JDK 21（必需）
```powershell
curl.exe -L -o C:\tools\jdk21.zip "https://repo.huaweicloud.com/openjdk/21.0.2/openjdk-21.0.2_windows-x64_bin.zip"
Expand-Archive C:\tools\jdk21.zip -DestinationPath C:\tools
[Environment]::SetEnvironmentVariable("JAVA_HOME","C:\tools\jdk-21.0.2","User")
```
> 为什么不用 `winget install EclipseAdoptium.Temurin.21.JDK`：winget 会从 GitHub Release 拉 msi，这台机器上 GitHub 链接被掐（`InternetOpenUrl() failed 0x80072efd`）。华为云镜像是 Oracle OpenJDK 官方构建的原样镜像。

### 2.2 Android SDK（必需）
```powershell
curl.exe -L -o C:\tools\commandlinetools-win.zip "https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip"
New-Item -ItemType Directory -Force C:\tools\android-sdk\cmdline-tools | Out-Null
Expand-Archive C:\tools\commandlinetools-win.zip -DestinationPath C:\tools\android-sdk\cmdline-tools
Move-Item C:\tools\android-sdk\cmdline-tools\cmdline-tools C:\tools\android-sdk\cmdline-tools\latest

# 接受许可（一路 y）
1..40 | ForEach-Object { "y" } | & C:\tools\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat --sdk_root=C:\tools\android-sdk --licenses

# 装三个包（compileSdk 35 需要）
& C:\tools\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat --sdk_root=C:\tools\android-sdk `
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

### 2.3 Gradle 8.11.1（必需，官方分发站在本机 SSL 不通）
```powershell
curl.exe -L -o C:\tools\gradle-8.11.1-bin.zip "https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip"
Expand-Archive C:\tools\gradle-8.11.1-bin.zip -DestinationPath C:\tools
```

### 2.4 工程侧两行配置
`local.properties`（不入 git）：
```properties
sdk.dir=C\:\\tools\\android-sdk
```

`settings.gradle.kts` 里已把仓库换成「google → 华为云 → 腾讯云 → mavenCentral」的顺序：
Maven Central 直连在这台机器上会偶发 `Remote host terminated the handshake`（TLS 被掐），
镜像命中后就不会再回落。

---

## 3. 日常三条命令

```powershell
cd C:\Users\Administrator\Doubao\chats\2026-11-20\new-chat\AvPlayKMP

# ① 打 Debug APK（内部已设好 JAVA_HOME / ANDROID_HOME）
build-android.bat

# ② 跑单元测试（24 个）
build-android.bat :composeApp:testDebugUnitTest

# ③ 装到手机/模拟器（先开 USB 调试）
install-debug.bat
```

产物：`composeApp\build\outputs\apk\debug\composeApp-debug.apk`

---

## 4. 怎么真正“跑起来”

### 现状（本机已就绪，直接用）

本机已经有一个**实测可用**的 AVD：`Medium_Phone_API_37`（x86_64 / google_apis_playstore / 2048MB / 1080x2400）。
启动与装包：

```powershell
$adb = "C:\tools\android-sdk\platform-tools\adb.exe"
# 模拟器没起就先起（也可以直接用 Android Studio 的 Device Manager 点启动）
& C:\tools\android-sdk\emulator\emulator.exe -avd Medium_Phone_API_37

& $adb devices          # 应看到 emulator-5554  device
& $adb install -r C:\Users\Administrator\Doubao\chats\2026-11-20\new-chat\AvPlayKMP\composeApp\build\outputs\apk\debug\composeApp-debug.apk
& $adb shell am force-stop com.javrocket.avplay
& $adb shell am start -n com.javrocket.avplay/.MainActivity
```

跑起来后：

| 想做的事 | 命令 / 位置 |
|---|---|
| 看有没有请求成功 | `& $adb logcat -d \| Select-String "resp op="` |
| 看页面解析结果 | `& $adb logcat -d \| Select-String "D UI"` |
| 看播放状态机 | `& $adb logcat -d \| Select-String "state="`、`"D HTTP"`（含 `http <code> <url>`） |
| 看同步/诊断 | `& $adb logcat -d \| Select-String "D Sync|D Diag"` |
| 拿站点原始 HTML / 落盘 playlist | `& $adb shell "ls -lt /sdcard/Android/data/com.javrocket.avplay/files/dump/"` |
| 看已落盘的配置与历史 | `& $adb shell "run-as com.javrocket.avplay cat shared_prefs/avplay_settings.xml"` |
| 点按钮定位（不知道坐标时） | `& $adb shell uiautomator dump /sdcard/ui.xml` + `& $adb pull /sdcard/ui.xml $env:TEMP\ui.xml`，再正则取 `text="..."` 与 `bounds` |

> ⚠️ `pm clear` 会连带清掉 UA、站点域名配置和 Cloudflare Cookie —— 之后首次进 JavLibrary 需要点「打开验证页」重新过一次验证。

### A. 真机（插线即用）

1. 手机开「开发者选项 → USB 调试」，插线；
2. `C:\tools\android-sdk\platform-tools\adb.exe devices` 能看到设备；
3. `& $adb install -r composeApp\build\outputs\apk\debug\composeApp-debug.apk` → 桌面出现 AvPlay 图标，点开就是 SiteBar + 首页列表。

### B. 换一台机器从零建模拟器（仅当这台没有现成 AVD）

```powershell
& C:\tools\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat --sdk_root=C:\tools\android-sdk `
    "emulator" "system-images;android-35;google_apis;x86_64"

& C:\tools\android-sdk\cmdline-tools\latest\bin\avdmanager.bat create avd `
    -n avplay35 -k "system-images;android-35;google_apis;x86_64" -d pixel_6
& C:\tools\android-sdk\emulator\emulator.exe -avd avplay35
```
> 若 `HypervisorPresent=False`（WHPX/Hyper-V 未启用），模拟器不加加速会非常卡，需要开启「Windows 虚拟机监控程序平台」并重启。

---

## 5. 这一轮实际踩到并修掉的 5 个坑

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | `winget install` JDK 失败 `InternetOpenUrl() failed 0x80072efd` | GitHub Release 连接被掐 | 改用华为云镜像的 openjdk zip |
| 2 | Gradle 依赖下载 `Remote host terminated the handshake` | Maven Central TLS 握手在这台机器上被中断 | `settings.gradle.kts` 前置华为云/腾讯云镜像 |
| 3 | `gradle wrapper` 报 `BUILD FAILED`（1 task executed） | Gradle 8.2+ 的 wrapper 任务默认会 HEAD 校验 distributionUrl | 加 `--no-validate-url` 生成 wrapper |
| 4 | `gradlew.bat` 下载分发包 `zip END header not found` | wrapper 自带下载器对镜像响应处理有问题（zip 本身是完整的 136,920,070 字节） | 手动把已校验的包预置进 `~/.gradle/wrapper/dists/...` 并写 `.ok` 标记 |
| 5 | 单测 `CloudflareUtilsTest` 抛 `RuntimeException` | JVM 单测里 `android.util.Log` 是未实现的桩 | `platformLog` 改成 `runCatching { Log.d(...) }.onFailure { println(...) }` |

另外编译期还有 3 个纯代码问题（首次真编译才暴露）：
- `HttpResponse.url` / `.request` 在 Ktor 3 不存在 → 改用 `call.request.url`；
- `ContentSource.baseUrl` 与委托来的 `ManagedService.getBaseUrl()` JVM 签名冲突 → 去掉 Source 对 `ManagedService` 的委托；
- `MainViewController.kt` 里 `ui.App()` 是非法半限定调用 → 改为 import 后直接 `App()`。

---

## 6. Android Studio（后装的）

| 项 | 值 |
|---|---|
| 版本 | 2026.2.2.9（build `AI-262.10968.63.2622.16840690`） |
| 安装位置 | `C:\Program Files\Android\Android Studio`（3.4 GB） |
| 启动 | 开始菜单「Android Studio」或 `"C:\Program Files\Android\Android Studio\bin\studio64.exe"` |
| 自带 JDK | JBR **25.0.4**（`...\Android Studio\jbr`） |

### 6.1 装的时候踩的坑

winget 的 `Google.AndroidStudio` 会从 `edgedl.me.gvt1.com` 拉 1.5 GB 安装包，这台机器连不上
（`InternetOpenUrl() failed 0x80072efd`，和装 JDK 时同一个问题）。改成从 **dl.google.com** 直连下载：

```powershell
curl.exe -L -o C:\tools\android-studio.exe `
  "https://dl.google.com/android/studio/install/2026.2.2.9/android-studio-rabbit2-patch1-windows.exe"
# NSIS 静默安装到 Program Files（首次执行会弹一次 UAC，装完退出码可能是 1223，正常）
Start-Process C:\tools\android-studio.exe -ArgumentList "/S" -Wait
```

### 6.2 必须做的一步：把 Gradle 的 JDK 钉在 21

Studio 自带 **JBR 25**，而本工程用的 Gradle 8.11.1 只支持到 JDK 23。
如果 daemon 跑在 25 上，会直接报 `Unsupported class file major version`。
已经在用户级 `~/.gradle/gradle.properties` 里写死：

```properties
org.gradle.java.home=C:/tools/jdk-21.0.2
```

**已实测**：把 `JAVA_HOME` 指向 Studio 的 JBR 25 再构建，依然 `BUILD SUCCESSFUL`
（daemon 被强制切到 21）。所以无论是命令行还是 IDE 点 Build，都不会踩这个坑。

> 如果以后在 Studio 里想再确认一遍：`Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` 选 `C:\tools\jdk-21.0.2`。

### 6.3 首次打开工程的设置清单

1. `File → Open` → 选 `...\new-chat\AvPlayKMP`（**打开这个目录，不要打开 composeApp 子目录**）；
2. SDK 位置：`local.properties` 里已写 `sdk.dir=C:\tools\android-sdk`，Studio 会直接读到，
   弹「SDK is missing」时选这个目录即可；
3. 等 Gradle Sync 跑完（首次会下 Kotlin/Compose 的 IDE 元数据，几分钟）；
4. 上方设备下拉框选真机（已开 USB 调试）或 `Device Manager` 里建一个 AVD，点 Run ▶。

### 6.4 环境变量（都已写入用户级）

| 变量 | 值 |
|---|---|
| `JAVA_HOME` | `C:\tools\jdk-21.0.2` |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `C:\tools\android-sdk` |
| `PATH` | 追加了 `C:\tools\jdk-21.0.2\bin`、`C:\tools\gradle-8.11.1\bin` |

> 环境变量是用户级的，**已经开着的终端/Studio 需要重启才会读到**；新开的窗口自动生效。

### 6.5 界面汉化（2026-11-20 完成）

| 项 | 值 |
|---|---|
| 语言包 | `zh-262.12573.28.jar`（`com.intellij.zh` v262.12573.28，14.15 MB） |
| 来源 | <https://github.com/sollyu/AndroidStudioChineseLanguagePack/releases>（官方 `com.intellij.zh` 的改版，`until-build="AI-999.*"` 兼容全部 AS 版本） |
| 落地位置 | `%APPDATA%\Google\AndroidStudio2026.2.2\plugins\localization-zh\lib\` |
| 启动参数 | `%APPDATA%\Google\AndroidStudio2026.2.2\studio64.exe.vmoptions` 内 `-Duser.language=zh`、`-Duser.country=CN` |
| 验证 | `idea.log` 出现 `Loaded custom plugins: Chinese (Simplified) Language Pack / 中文语言包 (262.12573.28)`、`[i18n] Language defined from registry: zh-CN`；窗口标题为「欢迎访问 Android Studio」 |

**为什么不能直接用官方中文插件**：`plugins.jetbrains.com` 上 `com.intellij.zh` 的最新发布停在
build 232（2024-07），JetBrains 从 2024.2 起把语言包改为随 IDEA 内置、不再单独发布，而 Android Studio
并未内置中文资源（`lib\*.jar` 里扫不到 `messages_zh`）。本机的 `plugins.jetbrains.com` / `download.jetbrains.com`
HTTPS 全部握手失败，只能走 GitHub 代理（`gh-proxy.com`）取社区改版包。

**改语言的关键机制（2026.2 平台）**：语言不是存在 `options\ide.general.xml` 的 Registry 里，
而是 `LocalizationStateServiceImpl` 启动时从 **`early-access-registry.txt`**（键值交替行）读一次
`i18n.locale`，读到后立即消费置空、并持久化到内部状态。所以手工切换语言的写法是：**关掉 Studio**
→ 把 `%APPDATA%\Google\AndroidStudio2026.2.2\early-access-registry.txt` 里 `i18n.locale` 的值
改成 `zh-CN`（回退英文则改 `en`）→ 启动一次即生效，之后无需再改。

---

## 7. iOS 端在这台机器上跑不了

Gradle 已经在配置阶段直接告知：
```
The following Kotlin/Native targets cannot be built on this machine and are disabled:
iosArm64, iosSimulatorArm64, iosX64
```
这是 Kotlin/Native 的硬限制：**iOS 目标只能在 macOS 上编译**。Windows 上能做的只有
「共享代码编译到 Android」，iOS 侧要拿到 Mac 上：
```bash
cd iosApp && xcodegen generate && open iosApp.xcodeproj
```
工程里 `iosApp/project.yml` 已包含 XcodeGen 定义与 gradle 预编译脚本，Mac 上装好
Xcode + JDK 后直接跑即可。
