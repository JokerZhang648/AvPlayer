# 构建指南

## 0. 先说结论：两端需要什么机器

| 目标 | 需要 | 能不能在当前这台 Windows 机器上编 |
|---|---|---|
| Android APK | JDK 17+、Android SDK 35、Gradle | ✅ 装上 JDK + SDK 后可以 |
| iOS framework / App | **macOS + Xcode 15+** + Gradle | ❌ Kotlin/Native 的 iOS 目标只能在 macOS 上编 |

> 这是 Kotlin/Native 的硬限制（它需要 Xcode 的 toolchain 与 linker），不是工程配置问题。

当前机器实测：只有便携 JRE 21（`%TEMP%\ipa_check\jre_raw`），**没有 JDK、没有 Gradle、没有 Android SDK、没有 Android Studio**。所以要真正编出 APK，先补下面第 1 节。

---

## 1. Android（Windows）

### 1.1 装 JDK 17 或 21

```powershell
# 方式一：已有 Android Studio → 用自带 JBR，不用另装
#   路径通常是 C:\Program Files\Android\Android Studio\jbr

# 方式二：单独装 Temurin（本机之前用清华镜像下过 JRE，注意 JRE 不能编译，要 JDK）
#   下载后设置：
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.x-hotspot"
```

验证：

```powershell
& "$env:JAVA_HOME\bin\javac" -version    # 必须能输出 javac 版本，只有 java 不行
```

### 1.2 装 Android SDK

最省事的方式是装 **Android Studio**，然后在 SDK Manager 里勾：

- `Android SDK Platform 35`
- `Android SDK Build-Tools 35.x`
- `Android SDK Platform-Tools`

或者只装命令行工具：

```powershell
# 装好后设环境变量
setx ANDROID_HOME "$env:LOCALAPPDATA\Android\Sdk"
```

并在工程根目录建 `local.properties`（**不要提交到 git**）：

```properties
sdk.dir=C\:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk
```

### 1.3 生成 Gradle wrapper（工程里还没有）

需要一个可用的 gradle 命令：

```powershell
# 用 scoop / choco / 官网 zip 任选其一装 gradle，然后：
cd C:\Users\Administrator\Doubao\chats\2026-11-20\new-chat\AvPlayKMP
gradle wrapper --gradle-version 8.11
```

之后就可以只用 `.\gradlew`：

```powershell
.\gradlew :composeApp:assembleDebug          # 产出 app-debug.apk
.\gradlew :composeApp:allTests               # 跑 commonTest（packer / CF / 媒体地址）
.\gradlew :composeApp:installDebug           # 装到已连接的设备
```

APK 落在：`composeApp\build\outputs\apk\debug\composeApp-debug.apk`

---

## 2. iOS（macOS）

### 2.1 依赖

```bash
xcode-select --install                 # 或装完整 Xcode 15+
brew install xcodegen                  # 生成 .xcodeproj
brew install --cask temurin@17         # JDK 17
```

### 2.2 生成并打开工程

```bash
cd AvPlayKMP/iosApp
xcodegen generate
open iosApp.xcodeproj
```

`project.yml` 里已经配好预编译脚本，Xcode 构建时会自动跑：

```
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
```

这个 task 依赖 Xcode 注入的环境变量（`CONFIGURATION`、`SDK_NAME`、`ARCHS`），**只能在 Xcode 构建过程中调用**，命令行直接跑会报“找不到 SDK”。

### 2.3 只想验证 Kotlin 侧能编译（不启动 Xcode）

```bash
cd AvPlayKMP
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
# 产物: composeApp/build/bin/iosSimulatorArm64/debugFramework/ComposeApp.framework
```

---

## 3. 常见坑（按踩坑概率排序）

| 现象 | 原因 | 处理 |
|---|---|---|
| `Unresolved reference: ComposeUIViewController` | 少了 `compose.components...` 或 compose 插件没生效 | 确认 `plugins { alias(libs.plugins.composeMultiplatform) }` 与 `compose.material3` 依赖都在 |
| `Please install the Android SDK` | 缺 `local.properties` / `ANDROID_HOME` | 见 1.2 |
| `Task :composeApp:embedAndSignAppleFrameworkForXcode FAILED`（命令行） | 该 task 必须在 Xcode 环境跑 | 见 2.2 |
| Xcode 报 `Sandbox: bash deny file-read-data` | Xcode 15+ 脚本沙箱 | `project.yml` 里已设 `ENABLE_USER_SCRIPT_SANDBOXING: NO`，若手改工程要保留 |
| `No such module 'ComposeApp'` | framework 没编出来 / 搜索路径不对 | 先跑 2.3 确认 framework 存在；检查 `FRAMEWORK_SEARCH_PATHS` |
| iOS 上网络请求全失败 | ATS 拦截明文 http | `project.yml` 已配 `NSAllowsArbitraryLoads: true`（仅调试期；上架前收敛） |
| Kotlin/AGP 版本不匹配报警 | 版本组合不兼容 | 以 `gradle/libs.versions.toml` 为唯一改动点，改完跑一次 `./gradlew help` 验证 |
| 首次构建下载依赖很慢 | Maven Central 网络 | 配阿里云镜像或走代理；本工程依赖很轻（只有 compose + ktor） |

---

## 4. 最小验证路径（建议按顺序）

```powershell
# 1) 配置能解析
.\gradlew help

# 2) 纯逻辑正确性（不需要设备，最快）
.\gradlew :composeApp:allTests

# 3) Android 能编出包
.\gradlew :composeApp:assembleDebug
```

第 2 步能过，说明 packer 解包、CF 判定、媒体地址提取这三块核心算法是对的 —— 这是整个项目里最容易写错、最难调试的部分。
