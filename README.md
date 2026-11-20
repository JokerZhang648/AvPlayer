# AvPlayKMP — 一套代码跑 Android + iOS

从零重建 `AvPlay 2.2.2` 的 KMP 工程骨架。**反编译产物只当 golden reference（行为对照），不复用它的代码。**

- 共享业务逻辑：Kotlin Multiplatform（commonMain）
- 共享 UI：Compose Multiplatform（同一套 Composable 两端渲染）
- Android 播放内核：media3（Phase 4 接入）
- iOS 播放内核：AVPlayer（Phase 4 接入）

---

## 1. 当前进度（Phase 1–6 全部落地 · Android 模拟器实测闭环 · 40 个单测全绿）

| 能力 | 状态 | 位置 |
|---|---|---|
| 错误类型学（6 类具名错误 + 业务码 10000/10001/10002） | ✅ | `core/AppError.kt` |
| 统一超时包装 / 异常归一化 / 登录后自动重放 | ✅ | `core/ServiceOperation.kt`、`LoginRetrySession` |
| 站点域名可配置 + 落盘（重启仍生效） | ✅ | `core/SettingsStore.kt`、`core/KeyValueStore.kt` |
| HTTP 工厂（引擎/UA/超时/日志/JSON） | ✅ | `net/Network.kt` |
| Cloudflare 判定 + WebView 过验证 + Cookie 桥 | ✅ | `service/Cloudflare.kt`、`ui/challenge/`、`platformCookieStorage` |
| 真实 UA（从 WebView 取，首启落盘后固定） | ✅ | `net/Network.kt` |
| 图片加载（自写 `ImageLoader`，带 UA/Cookie，内存 LRU） | ✅ | `ui/media/` |
| 播放地址解析（packer 解包 + 媒体 URL 正则 + PLAYER_CONFIG 字段） | ✅ 含单测 | `site/PackedJavaScript.kt`、`site/MediaUrlExtractor.kt` |
| 播放内核（Android media3 / iOS AVPlayer） | ✅ Android 实测；⚠️ iOS 未编译验证 | `player/PlatformPlayer.kt` + 两端 actual |
| 历史 / 收藏 / 续播（JSON in KV，200/500 上限） | ✅ | `store/LibraryStore.kt` |
| 六站点：黄果 / XChina / JavLibrary / JavDB / AVDB / AV01 | ✅ | `service/{huangguo,xchina,javlibrary,javdb,avdb,av01}/` |
| 聚合播放器（4 个 extractor + 双聚合站回退） | ✅ 实测 | `site/aggregator/` |
| WebDAV 同步（首次/合并/坏包拒写/401） | ✅ 模拟器 + 本机测试服务器实测 | `sync/` |
| 站点诊断（分层结论 + 导出报告） | ✅ 实测 5/6 连通 | `diag/`、`ui/diag/` |
| 共享 UI（首页 / 详情 / 播放 / 同步 / 诊断） | ✅ | `ui/` |
| 单测（40 个用例） | ✅ `:composeApp:testDebugUnitTest` 全绿 | `commonTest/` |

**尚未闭环（明确边界）**：iOS 侧全部代码无法在 Windows 编译验证；`AVAssetResourceLoaderDelegate`（分片带自定义头）未实现；AV01 的 `file://` playlist 与 WebDAV 在 iOS 未验证；javdb 详情页 2026-11 起多数需登录（App 已引导登录）；黄果封面是 AES 密文，按取舍不解密；`HtmlParsing.dumpEnabled` 调试期硬开。

较长的落地细节与踩坑记录见 [`docs/MIGRATION.md`](docs/MIGRATION.md) §9–§12。

---

## 2. 目录结构

```
AvPlayKMP/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── composeApp/
│   ├── build.gradle.kts          # KMP targets: android + iosX64/iosArm64/iosSimulatorArm64
│   └── src/
│       ├── commonMain/kotlin/com/javrocket/avplay/
│       │   ├── core/             # 错误、超时、配置存储（跨站点共用）
│       │   ├── model/            # VideoBrief / VideoDetail / ValueLink / Page
│       │   ├── net/              # HttpClient 工厂 + engine/UA/日志的 expect + HtmlParsing 门面
│       │   ├── parser/           # HTML 解析抽象（Ksoup 的唯一边界）+ 解析原语
│       │   ├── service/          # ManagedService、CloudflareUtils、ContentSource、各站点适配器
│       │   ├── site/             # 聚合解析算法（packer 解包、媒体地址提取）
│       │   ├── player/           # 播放传输规则（共享）
│       │   ├── platform/         # epochMillis / platformName 的 expect
│       │   └── ui/               # Compose 界面（首页 / 详情 / 根 App）
│       ├── androidMain/          # MainActivity + actual 实现 + Manifest
│       ├── iosMain/              # MainViewController + actual 实现
│       └── commonTest/           # 纯逻辑单测
└── iosApp/
    ├── project.yml               # XcodeGen 工程定义（含 gradle 预编译脚本）
    └── iosApp/                   # iOSApp.swift / ContentView.swift
```

---

## 3. 平台边界（expect/actual 清单）

| expect（commonMain） | Android actual | iOS actual |
|---|---|---|
| `createHttpEngine()` | `OkHttp.create()` | `Darwin.create()` |
| `platformUserAgent()` | 从 WebView 取真实 UA | WKWebView UA（未编译验证） |
| `platformLog(tag, msg)` | Logcat（带 3000 字符分段） | `NSLog` |
| `epochMillis()` | `System.currentTimeMillis()` | `NSDate().timeIntervalSince1970 * 1000` |
| `platformName()` | `Build.VERSION.SDK_INT`（日志里显示 `Android 37`） | `UIDevice.systemVersion` |
| `platformCookieStorage()` | 直读写 `android.webkit.CookieManager` | 返 null（刻意不接管 WKWebView Cookie） |
| `dumpTextFile(name, content)` / `dumpDirPath()` | 写 `Android/data/<pkg>/files/dump/`（自动加 `.txt`） | 写 Documents 目录 |
| `PlatformPlayer` 类 + `PlayerSurface` | media3 ExoPlayer（`DefaultDataSource` + 每次 prepare 重建） | AVPlayer + `AVPlayerViewController` |

**只有这 8 组**。其余全是共享代码 —— 这就是"一套代码两端跑"的边界成本。

---

## 4. 快速开始（本机已验证可构建）

Windows 上已经装好 JDK 21 + Android SDK 35 + Gradle 8.11.1，装法与坑见
[`docs/本地环境-Windows.md`](docs/本地环境-Windows.md)。

```bat
:: ① 打 Debug APK（内部已设好 JAVA_HOME / ANDROID_HOME）
build-android.bat

:: ② 跑单测（40 个，已全绿）
build-android.bat :composeApp:testDebugUnitTest

:: ③ 装到模拟器并启动（模拟器需先用 Android Studio 的 Device Manager 起来）
set ADB=C:\tools\android-sdk\platform-tools\adb.exe
%ADB% install -r composeApp\build\outputs\apk\debug\composeApp-debug.apk
%ADB% shell am force-stop com.javrocket.avplay
%ADB% shell am start -n com.javrocket.avplay/.MainActivity

:: ④ 看运行日志（这是最有效的验收手段，比看界面快）
%ADB% logcat -d | findstr "D HTTP D UI D Playback D Aggregator D JavDb D Sync D Diag"
```

产物：`composeApp\build\outputs\apk\debug\composeApp-debug.apk`

打开 App 后：

- 顶部一行是站点切换（黄果 / XChina / JavLibrary / JavDB / AVDB / AV01），横向可滑动
- 右上是 **同步**（WebDAV 填地址 → 测试连接 → 立即同步）与 **诊断**（逐站点跑真实首页，按故障层给结论，可导出报告）
- 撞到人机验证时页面会弹 WebView，验证一次后自动继续
- 抓回放/解析问题的原始材料都在设备 `Android/data/com.javrocket.avplay/files/dump/` 里（含 `html_*.txt`、`av01_<id>.m3u8.txt`、`diag_*.txt`）

调试期的 WebDAV 可用仓库自带脚本起一个极简服务器（支持 PROPFIND/GET/PUT + Basic Auth）：

```bat
python tools\webdav-test-server.py --port 8099
:: 模拟器里填 http://10.0.2.2:8099/dav/avplay，账号 demo / demo123
```

手动命令（任意平台）：

```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:testDebugUnitTest   # Windows 用 testDebugUnitTest，Mac 可跑 allTests

# iOS（只能在 macOS 上）
cd iosApp && xcodegen generate && open iosApp.xcodeproj
```

> Windows 上 iOS 目标会被 Kotlin/Native 自动禁用（`iosArm64/iosX64/iosSimulatorArm64`），
> 这是宿主限制，不是工程问题；共享代码本身是两端同一份。

---

## 5. 新增一个站点适配器（模板，10 分钟一个）

以黄果为模板复制，步骤固定：

1. **建包**：`service/<site>/<Site>Service.kt`，实现 `ManagedService`。
2. **端点**：从 golden reference 里抄路径字面量（原实现的 `XxxService` 里全是硬编码路径），
   优先抄“方法名 → 路径”的对应关系，别自己猜。
3. **每个方法都套 `runServiceOperation("site.method") { ... }`**，返回值统一 `Result<T>`。
4. **取正文统一用 `bodyTextChecked()`**，它会先做 Cloudflare 判定 —— 这一步不能跳，
   否则挑战页会被当业务页解析，表现为“列表空但没报错”。
5. **解析**：JSON 站点按字段别名兜底（参考 `HuangGuoService.stringOf` 的用法）；
   HTML 站点写成 `<Site>Parser`（继承 `BaseParser<T>`），再用
   `HtmlParsing.fetch(client, url, parser, options) { ... }` 串起来 —— 它内部已先做 CF 判定。
   Ksoup 只在 `parser/HtmlDoc.kt` 里出现，新解析器不要直接 import 第三方库。
6. **接到 UI**：实现 `service/ContentSource.kt`（`id/displayName/baseUrl/home/detail/search`），
   在 `AppGraph.lazySources` 里加一行即可出现在顶部 SiteBar；`playback()` 有默认实现，
   走聚合器的站点不用覆盖。
7. **补单测**：至少测“解析器对一份真实样本 HTML/JSON 的输出”（参考 `XChinaParserTest` 的写法）。

两种额外形态（六站点里各有一个样板）：

- **站点自己不给直链，交给聚合器**（JavLibrary / JavDB / AVDB）：
  `ContentSource.playback(id)` 里先换出**番号**再走 `aggregator.playbackAnySite(code)`
  （`JavLibrarySource.playback` / `JavDbService.playbackCode`）。注意 id 与番号是两套标识，别混用。
- **纯 JSON API + 多段鉴权**（AV01）：体量上比 HTML 站点更省事，但播放链路要看清楚
  （AV01 是 `cdn-access` → `master.m3u8` → `manifest/<档位>` 三段，缺一段就 403）。
  这种站点的"逆向成果"一定要写进 `docs/MIGRATION.md`：端点表、请求体字段名、鉴权顺序，
  否则下次改版要再逆一遍。

14 个站点的清单与协议类型见 `docs/MIGRATION.md`。

---

## 6. 与 golden reference 的取舍原则

| 原则 | 说明 |
|---|---|
| 抄“行为”，不抄“代码” | 端点、参数名、判定规则、错误映射照抄；类结构、命名、状态管理重新设计 |
| 原实现的坑要修 | 原 `JavDbService` 单文件 10 万行、`upsertSnapshot` 逻辑缺失、`/reviews/lastest` 拼写错误 —— 这些不复制 |
| 原实现的护栏要留 | packer 的 6 个 MAX 常量、重定向上限 5、`allowHttpsDowngrade=false` —— 这些是防 OOM/防劫持的关键 |
| 隐私边界 | 不移植硬编码 RSA 私钥那种做法；凭据一律走运行时注入 |

详细映射表见 `docs/MIGRATION.md`。
