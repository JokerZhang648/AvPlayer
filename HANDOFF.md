# AvPlayKMP 交接说明（给接手本项目的 AI / 工程师）

> 这份文档的目的只有一个：**你（接手方）读完就能直接改代码、直接验证，不用重新摸索工程**。
> 所有路径、命令、坐标、站点端点都是本机真实测过的值，不是推测。
> 生成时间：2026-11-20 16:5x（UTC+8）· 工程状态：Phase 1–6 全部落地，Android 模拟器实测闭环。

---

## 0. 三句话说明这是什么东西

1. 原 App `AvPlay 2.2.2`（一个聚合六个性内容站点的 Android App）被完整反编译，产物只当 **golden reference（行为对照）**，不复用其代码。
2. 现在用 **Kotlin Multiplatform + Compose Multiplatform** 从零重建：`commonMain` 一套业务 + 一套 UI，Android 用 media3 播、iOS 用 AVPlayer 播。
3. 当前 Android 侧 **Phase 1–6 全部完成并实测**：六站点首页/详情可用、播放能到 `state=3`、历史收藏续播可用、WebDAV 同步与站点诊断可用；iOS 侧代码齐全但**在 Windows 上从未编译验证过**。

**最全的代码在这里（不是任何 zip）**：

```
C:\Users\Administrator\Doubao\chats\2026-11-20\new-chat\AvPlayKMP
```

---

## 1. 最快跑起来（5 分钟）

### 1.1 环境（已装好，不要重装）

| 项 | 本机路径 / 值 |
|---|---|
| JDK 21 | `C:\tools\jdk-21.0.2` |
| Gradle 8.11.1 | `C:\tools\gradle-8.11.1`（工程内也有 wrapper） |
| Android SDK | `C:\tools\android-sdk`（`platform-tools\adb.exe`、`emulator\emulator.exe`） |
| Android Studio | `C:\Program Files\Android\Android Studio`（已装中文语言包） |
| 模拟器 AVD | `Medium_Phone_API_37`（x86_64 / google_apis_playstore / 2048MB / **1080x2400**），设备号 `emulator-5554` |
| 包名 / 入口 | `com.javrocket.avplay` / `.MainActivity` |

> ⚠️ 本机是 **Windows + pwsh 7**，`Bash` 工具不可用，所有命令必须用 PowerShell 写。以下是 PowerShell 语法。

### 1.2 构建 → 装包 → 看日志

```powershell
$proj = "C:\Users\Administrator\Doubao\chats\2026-11-20\new-chat\AvPlayKMP"
$adb  = "C:\tools\android-sdk\platform-tools\adb.exe"

# ① 构建 Debug APK（增量约 6~8 秒；全量首次约 2 分钟）
Set-Location $proj
cmd /c build-android.bat

# ② 单测（40 个用例，应全绿）
cmd /c build-android.bat :composeApp:testDebugUnitTest

# ③ 装包并启动
& $adb devices     # 应看到 emulator-5554  device
& $adb install -r "$proj\composeApp\build\outputs\apk\debug\composeApp-debug.apk"
& $adb logcat -c
& $adb shell am force-stop com.javrocket.avplay
& $adb shell am start -n com.javrocket.avplay/.MainActivity

# ④ 看核心日志（这是最有效的验收手段）
& $adb logcat -d | Select-String "resp op=|D UI|D Playback|state=|D Aggregator|D JavDb|D Sync|D Diag"
```

> `build-android.bat` 输出尾部会出现 `'PK' 或 '也可以传任务名...' is not recognized` 之类的行 ——
> 那是 bat 里的中文注释被 cmd 当命令执行，**无害可忽略**，看最后一行 `BUILD SUCCESSFUL` 即可。

### 1.3 界面结构（1080x2400 像素下的实测坐标）

- 顶部一行 = 站点切换条（横向可滚动），y≈68；六个 tab 中心 x 依次为：
  **黄果 60 / XChina 270 / JavLibrary 480 / JavDB 686 / AVDB 855 / AV01 1010**
- 顶端 TopAppBar 右侧：**同步** `(841, 283)`、**诊断** `(993, 283)`
- 首页卡片列表：首卡 `(540, 670)`，之后每张 y +337
- 详情页按钮位置**随内容高度变化**，每次必须 `uiautomator dump` 现查，不要复用旧坐标

```powershell
# 查当前界面所有可点元素与文案（比看截图快，且不含画面内容）
& $adb shell uiautomator dump /sdcard/ui.xml
& $adb pull /sdcard/ui.xml "$env:TEMP\ui.xml"
$x = Get-Content "$env:TEMP\ui.xml" -Raw
[regex]::Matches($x,'text="([^"]{1,40})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') | ForEach-Object {
    "{0} | {1},{2}-{3},{4}" -f $_.Groups[1].Value,$_.Groups[2].Value,$_.Groups[3].Value,$_.Groups[4].Value,$_.Groups[5].Value
}
```

> **重要约定**：这个 App 的内容是成人内容。验收时**不要读截图**（`Read` 图片会触发内容审核），
> 一律用 `logcat` / `uiautomator` 文本 / `shared_prefs` 判断结果。

---

## 2. 目录与文件职责

### 2.1 顶层

```
C:\Users\Administrator\Doubao\chats\2026-11-20\new-chat\
├── AvPlayKMP\                     ← 当前工程（最全，80 个 .kt / 292 个文件）
├── AvPlay-2.2.2_decompiled\       ← 原 APK 反编译（33,306 个 .java / 413MB），只读参考
├── AvPlay-业务逻辑说明.md          ← 原 App 业务逻辑（17 章），改站点前值得查
├── AvPlay-安卓反编译报告.md        ← 反编译报告
├── AvPlay_自有代码.zip             ← 从反编译里摘出的自有代码（1,990 个 .java）
├── AvPlayKMP-Phase3-6-源码.zip     ← 当前源码快照（279 文件，排除了 build/.gradle）
├── AvPlayKMP-debug.apk            ← 当前可安装 APK（14.75MB）
└── AvPlayKMP-工程骨架.zip / -Phase2-HTML解析层.zip  ← 历史阶段产物，仅作对照
```

### 2.2 工程内（按职责分组）

`composeApp\src\commonMain\kotlin\com\javrocket\avplay\`：

| 包 | 职责 | 关键文件 |
|---|---|---|
| `core` | 错误类型学、超时包装、KV 存储、配置、调试落盘 | `AppError.kt`（6 类具名错误 + 业务码映射 + `LoginRetrySession`）、`ServiceOperation.kt`（`Timeouts.QUICK/STANDARD/LONG` + `runServiceOperation`）、`KeyValueStore.kt`（含 `InMemoryKeyValueStore` 供单测）、`SettingsStore.kt`（`SiteIds`）、`DebugDump.kt`（`dumpTextFile`/`dumpDirPath` 的 expect） |
| `model` | 领域模型 | `Models.kt`：`VideoBrief/VideoSection/HomeFeed/Episode/VideoDetail/ValueLink/Page/PlaybackInfo` |
| `net` | HTTP 工厂 + CF 判定门面 + Cookie | `Network.kt`（`createHttpClient(engine, userAgent, accept, siteUrl, cookieStorage)`、`bodyTextChecked(op)`、`AppJson`、`platformUserAgent()`、`platformLog()`）、`HtmlParsing.kt`（请求→CF 判定→解析器）、`CookiesStorage.kt` |
| `parser` | HTML 解析抽象（**Ksoup 唯一入口**） | `HtmlDoc.kt`（只有这里 import Ksoup）、`BaseParser.kt`、`ParseOptions.kt`、`ParserUtils.kt`（`absolutize/cleanText/firstNonBlank/originOf/queryParamOf/stripSiteSuffix`） |
| `player` | 播放契约 + 传输规则 | `PlatformPlayer.kt`（`expect class` + `@Composable expect fun PlayerSurface`）、`PlaybackTransport.kt` |
| `platform` | 平台原语 | `Platform.kt`（`epochMillis` / `platformName`） |
| `service` | 站点基础设施 | `ContentSource.kt`（站点统一接口）、`ManagedService.kt`、`Cloudflare.kt`（CF 判定规则）、`generic\GenericHtmlService.kt`（通用 HTML 适配器，目前未接 UI） |
| `service\<site>` | 六站点实现（每个 = Service + Source，HTML 站多一个 Parser） | 见 §4 表格 |
| `site` | 媒体地址提取算法 | `MediaUrlExtractor.kt`、`PackedJavaScript.kt` |
| `site\aggregator` | 聚合播放器（跨站找 m3u8） | `PlaybackAggregatorService.kt`、`PlaybackTargetResolver.kt`、`PlaybackTargetExtractors.kt`、`PlaybackTarget.kt` |
| `store` | 历史 / 收藏 / 续播 / 同步合并 | `LibraryStore.kt`（`record/resumeOf/continueWatching/toggleFavorite/snapshot/mergeRemote`、`MergeStats`、`LibrarySnapshot`） |
| `sync` | WebDAV 同步 | `SyncModels.kt`、`WebDavClient.kt`、`SyncService.kt` |
| `diag` | 站点诊断 | `DiagnosticsService.kt`（分层结论 + 导出报告） |
| `ui` | Compose 界面 | `App.kt`（根：站点切换、overlay 同步/诊断、CF 浮层）、`home/`、`detail/`、`player/`、`challenge/`、`media/`、`sync/`、`diag/` |
| `AppGraph.kt` | 依赖装配（等价原 `ServiceManager`） | `bootstrap()`（取 UA、开 dump）、`sources()`（六站点）、`rebuildSources()`、`library`、`sync`、`diagnostics`、`imageLoader`、`json()`、`generalHttp()` |

`androidMain` / `iosMain`（平台实现）：

| 文件 | 作用 |
|---|---|
| `player\PlatformPlayer.android.kt` / `.ios.kt` | media3 ExoPlayer / AVPlayer 实现（iOS 未验证） |
| `ui\challenge\ChallengeSheet.android.kt` / `.ios.kt` | WebView 过 CF / WKWebView |
| `net\CookiesStorage.android.kt` / `.ios.kt` | Android 直连 `CookieManager`；**iOS 返回 null（刻意不接管）** |
| `core\KeyValueStore.android.kt` / `.ios.kt` | SharedPreferences / NSUserDefaults |
| `core\DebugDump.android.kt` / `.ios.kt` | 落盘实现（Android 落到 `Android/data/<pkg>/files/dump/`，自动加 `.txt`） |
| `net\Network.android.kt` / `.ios.kt` | 引擎、UA、日志 |
| `MainActivity.kt` / `MainViewController.kt` | 两端入口，都只调 `App()` |

`commonTest`（8 个测试类 / 40 用例）：`CloudflareUtilsTest`、`JavLibraryParserTest`、`MediaUrlExtractorTest`、`PackedJavaScriptTest`、`ParserUtilsTest`、`PlaybackExtractorsTest`、`SyncMergeTest`、`WebDavConfigTest`(同文件)、`XChinaParserTest`。

其它：
`docs\MIGRATION.md`（**改站点前必读** §9–§12 是本轮落地的坑与证据）、`docs\本地环境-Windows.md`、`docs\BUILD.md`、`README.md`、`tools\webdav-test-server.py`（调试用极简 WebDAV 服务器，支持 PROPFIND/GET/PUT + Basic Auth）。

> `.kotlin\`、`.gradle\`、`build\`、`local.properties` 都是构建产物/本机配置，**可以忽略或删除**，不进版本管理。

### 2.3 完整文件清单（80 个 .kt，行数）

<details>
<summary>点开查看（路径相对 composeApp\src，测试/工程根文件已标注）</summary>

| 文件 | 行数 |
|---|---|
| `androidMain\kotlin\com\javrocket\avplay\core\DebugDump.android.kt` | 23 |
| `androidMain\kotlin\com\javrocket\avplay\core\KeyValueStore.android.kt` | 42 |
| `androidMain\kotlin\com\javrocket\avplay\MainActivity.kt` | 23 |
| `androidMain\kotlin\com\javrocket\avplay\net\CookiesStorage.android.kt` | 45 |
| `androidMain\kotlin\com\javrocket\avplay\net\Network.android.kt` | 49 |
| `androidMain\kotlin\com\javrocket\avplay\platform\Platform.android.kt` | 3 |
| `androidMain\kotlin\com\javrocket\avplay\player\PlatformPlayer.android.kt` | 185 |
| `androidMain\kotlin\com\javrocket\avplay\ui\challenge\ChallengeSheet.android.kt` | 90 |
| `androidMain\kotlin\com\javrocket\avplay\ui\media\RemoteImage.android.kt` | 20 |
| `commonMain\kotlin\com\javrocket\avplay\AppGraph.kt` | 178 |
| `commonMain\kotlin\com\javrocket\avplay\core\AppError.kt` | 60 |
| `commonMain\kotlin\com\javrocket\avplay\core\DebugDump.kt` | 11 |
| `commonMain\kotlin\com\javrocket\avplay\core\KeyValueStore.kt` | 85 |
| `commonMain\kotlin\com\javrocket\avplay\core\ServiceOperation.kt` | 55 |
| `commonMain\kotlin\com\javrocket\avplay\core\SettingsStore.kt` | 45 |
| `commonMain\kotlin\com\javrocket\avplay\diag\DiagnosticsService.kt` | 176 |
| `commonMain\kotlin\com\javrocket\avplay\model\Models.kt` | 69 |
| `commonMain\kotlin\com\javrocket\avplay\net\CookiesStorage.kt` | 16 |
| `commonMain\kotlin\com\javrocket\avplay\net\HtmlParsing.kt` | 45 |
| `commonMain\kotlin\com\javrocket\avplay\net\Network.kt` | 114 |
| `commonMain\kotlin\com\javrocket\avplay\parser\BaseParser.kt` | 11 |
| `commonMain\kotlin\com\javrocket\avplay\parser\HtmlDoc.kt` | 28 |
| `commonMain\kotlin\com\javrocket\avplay\parser\ParseOptions.kt` | 17 |
| `commonMain\kotlin\com\javrocket\avplay\parser\ParserUtils.kt` | 57 |
| `commonMain\kotlin\com\javrocket\avplay\platform\Platform.kt` | 5 |
| `commonMain\kotlin\com\javrocket\avplay\player\PlatformPlayer.kt` | 39 |
| `commonMain\kotlin\com\javrocket\avplay\player\PlaybackTransport.kt` | 123 |
| `commonMain\kotlin\com\javrocket\avplay\service\av01\Av01Service.kt` | 354 |
| `commonMain\kotlin\com\javrocket\avplay\service\av01\Av01Source.kt` | 54 |
| `commonMain\kotlin\com\javrocket\avplay\service\avdb\AvdbService.kt` | 237 |
| `commonMain\kotlin\com\javrocket\avplay\service\avdb\AvdbSource.kt` | 50 |
| `commonMain\kotlin\com\javrocket\avplay\service\Cloudflare.kt` | 121 |
| `commonMain\kotlin\com\javrocket\avplay\service\ContentSource.kt` | 31 |
| `commonMain\kotlin\com\javrocket\avplay\service\generic\GenericHtmlService.kt` | 146 |
| `commonMain\kotlin\com\javrocket\avplay\service\huangguo\HuangGuoService.kt` | 314 |
| `commonMain\kotlin\com\javrocket\avplay\service\huangguo\HuangGuoSource.kt` | 23 |
| `commonMain\kotlin\com\javrocket\avplay\service\javdb\JavDbService.kt` | 265 |
| `commonMain\kotlin\com\javrocket\avplay\service\javdb\JavDbSource.kt` | 31 |
| `commonMain\kotlin\com\javrocket\avplay\service\javlibrary\JavLibraryParser.kt` | 163 |
| `commonMain\kotlin\com\javrocket\avplay\service\javlibrary\JavLibraryService.kt` | 104 |
| `commonMain\kotlin\com\javrocket\avplay\service\javlibrary\JavLibrarySource.kt` | 42 |
| `commonMain\kotlin\com\javrocket\avplay\service\ManagedService.kt` | 9 |
| `commonMain\kotlin\com\javrocket\avplay\service\xchina\XChinaParser.kt` | 122 |
| `commonMain\kotlin\com\javrocket\avplay\service\xchina\XChinaService.kt` | 113 |
| `commonMain\kotlin\com\javrocket\avplay\service\xchina\XChinaSource.kt` | 30 |
| `commonMain\kotlin\com\javrocket\avplay\site\aggregator\PlaybackAggregatorService.kt` | 143 |
| `commonMain\kotlin\com\javrocket\avplay\site\aggregator\PlaybackTarget.kt` | 55 |
| `commonMain\kotlin\com\javrocket\avplay\site\aggregator\PlaybackTargetExtractors.kt` | 189 |
| `commonMain\kotlin\com\javrocket\avplay\site\aggregator\PlaybackTargetResolver.kt` | 137 |
| `commonMain\kotlin\com\javrocket\avplay\site\MediaUrlExtractor.kt` | 91 |
| `commonMain\kotlin\com\javrocket\avplay\site\PackedJavaScript.kt` | 134 |
| `commonMain\kotlin\com\javrocket\avplay\store\LibraryStore.kt` | 204 |
| `commonMain\kotlin\com\javrocket\avplay\sync\SyncModels.kt` | 46 |
| `commonMain\kotlin\com\javrocket\avplay\sync\SyncService.kt` | 158 |
| `commonMain\kotlin\com\javrocket\avplay\sync\WebDavClient.kt` | 90 |
| `commonMain\kotlin\com\javrocket\avplay\ui\App.kt` | 169 |
| `commonMain\kotlin\com\javrocket\avplay\ui\challenge\ChallengeSheet.kt` | 22 |
| `commonMain\kotlin\com\javrocket\avplay\ui\detail\DetailScreen.kt` | 238 |
| `commonMain\kotlin\com\javrocket\avplay\ui\diag\DiagnosticsScreen.kt` | 164 |
| `commonMain\kotlin\com\javrocket\avplay\ui\home\HomeScreen.kt` | 264 |
| `commonMain\kotlin\com\javrocket\avplay\ui\media\RemoteImage.kt` | 123 |
| `commonMain\kotlin\com\javrocket\avplay\ui\player\PlayerScreen.kt` | 225 |
| `commonMain\kotlin\com\javrocket\avplay\ui\sync\SyncScreen.kt` | 158 |
| `commonTest\...\CloudflareUtilsTest.kt` | 45 |
| `commonTest\...\JavLibraryParserTest.kt` | 112 |
| `commonTest\...\MediaUrlExtractorTest.kt` | 69 |
| `commonTest\...\PackedJavaScriptTest.kt` | 37 |
| `commonTest\...\ParserUtilsTest.kt` | 49 |
| `commonTest\...\PlaybackExtractorsTest.kt` | 67 |
| `commonTest\...\SyncMergeTest.kt` | 143（含 `WebDavConfigTest`） |
| `commonTest\...\XChinaParserTest.kt` | 89 |
| `iosMain\...\core\DebugDump.ios.kt` | 27 |
| `iosMain\...\core\KeyValueStore.ios.kt` | 25 |
| `iosMain\...\MainViewController.kt` | 9 |
| `iosMain\...\net\CookiesStorage.ios.kt` | 11 |
| `iosMain\...\net\Network.ios.kt` | 20 |
| `iosMain\...\platform\Platform.ios.kt` | 6 |
| `iosMain\...\player\PlatformPlayer.ios.kt` | 146 |
| `iosMain\...\ui\challenge\ChallengeSheet.ios.kt` | 84 |
| `iosMain\...\ui\media\RemoteImage.ios.kt` | 12 |

非代码文件：`build-android.bat`、`install-debug.bat`、`build.gradle.kts`、`composeApp\build.gradle.kts`、`gradle\libs.versions.toml`（media3 1.4.1 等版本在这里）、`composeApp\src\androidMain\AndroidManifest.xml`（**`usesCleartextTraffic="true"`，因为局域网 WebDAV 常是 http**）、`iosApp\project.yml`、三份 docs、`tools\webdav-test-server.py`。

</details>

---

## 3. 必须遵守的 8 个接口（改代码不要破坏）

```kotlin
// service/ContentSource.kt —— 站点统一入口，UI 不认具体站点
interface ContentSource {
    val id: String; val displayName: String; val baseUrl: String
    suspend fun home(): Result<HomeFeed>
    suspend fun detail(id: String): Result<VideoDetail>
    suspend fun search(keyword: String, page: Int): Result<Page<VideoBrief>>
    suspend fun playback(id: String): Result<PlaybackInfo>   // 有默认实现：不支持
}
```

- `model/Models.kt`：`VideoBrief(id,title,cover:String?,code,duration,rating,sourceSite)`、`VideoDetail(brief,description,actors,tags,episodes,related)`、`PlaybackInfo(url,mimeType,headers,sourcePageUrl)`
- `core/AppError.kt`：失败一律是具名类型（`AuthenticationRequired / PremiumRequired / IpForbidden / CopyrightForbidden / CloudflareChallenge(url) / SiteBlocked / Timeout / Http / Network / Parse`）+ `ofHttpStatus(status)`。**UI 按类型分流，不要用 `-1/false` 表达失败。**
- `core/ServiceOperation.kt`：`Timeouts.QUICK/STANDARD=15s/LONG=30s` + `runServiceOperation(op, timeout)`，所有站点方法都要套。
- `net/Network.kt`：`bodyTextChecked(op)` 会先做 CF 判定 —— **不能跳**，否则挑战页会被当业务页解析（表现为"列表空但不报错"）。
- `parser/HtmlDoc.kt`：`Html.parse` → `HtmlDoc(select/selectFirst/title/text/html)`；**Ksoup 只允许在这里出现**。
- `player/PlatformPlayer.kt`：`snapshot: StateFlow<PlayerSnapshot>` + `onHttpError` + prepare/play/pause/seekTo/release + `PlayerSurface`。
- `store/LibraryStore.kt`：`record / resumeOf / continueWatching(siteId:) / toggleFavorite / snapshot / mergeRemote`。

---

## 4. 六站点事实表（真实端点，改版时对着这张表查）

| 站点 | id / 默认域名 | 形态 | 关键端点与坑 |
|---|---|---|---|
| 黄果 | `huangguo` / `https://huangguoai.com` | JSON API | `GET /api/home`（200/12.7KB）、`GET /api/videos/{id}`；播放是 **POST** `/api/videos/{id}/episodes/{episode}/playback`（body `{}`，响应**两层** data，`streams.main`，`cdn.authKey="x-cdn-auth"`）；成功判定是 `status == 1`（与 javdb 的 `code==0` 相反）；请求必须带 `Origin/Referer`，所以**单独建 client**。封面域名 `https://pic.qnlycc.cn` 是 AES 密文 → 不做解密，UI 保持"无图" |
| XChina | `xchina` / `https://xchina.co` | HTML（2026-11 改版） | 列表 `/videos?page=N`（104 卡/页，200）、详情 `/video/<hash>`（带 CF，未验证时 403 `Just a moment...`）、演员 `/actor/<hash>`、标签 `/video-tag/<slug>`；**旧路径 `videos/series-`、`video/id-`、`download/id-` 全废**；解析用 `a[href*='/video/']` + `Regex("/video/([A-Za-z0-9]+)")`，标题取 `span[class*=line-clamp]`（不能取锚点全文，否则"分类+时长+评论数"进标题）；播放走详情页 `MediaUrlExtractor` |
| JavLibrary | `javlibrary` / `https://www.javlibrary.com/cn/` | HTML | 首页 `vl_bestrated.php?page=1`；列表 `div.video#vid_<hash>`，`div.id` 文本才是番号，详情链接 `./<hash>.html`；详情 `#video_id`（文本带"识别码: "前缀，需 `substringAfterLast(':')`）；**id 与番号必须分开**（`detailId` 用于请求，`code` 用于聚合搜索）；播放走 `agg.playbackAnySite(code)` |
| JavDB | `javdb` / `https://javdb.com` | JSON+HTML 双通道 | `/api/v1/movies/{slug}` **恒 404** → 回落 `/v/{slug}`；`/search?q=` 403；番号藏在 **属性** `a.copy-to-clipboard[data-clipboard-text]`（不在 `<span class="value">` 裸文本里）；**2026-11 起多数详情页对未登录用户 302 `/login`** → 用"页面是否有 `name=password`"判定，命中抛 `AuthenticationRequired`，UI 给「打开登录页」；播放先 detail 换番号再聚合 |
| AVDB | `avdb` / `https://avdbapi.com` | JSON API | `GET /api.php/provide/vod/?ac=list&pg=1`（200/87.8KB，pagecount 5398 / total 539755 / 100 条）、`ac=list&wd=<番号>`、`ac=detail&ids=<id>`；`episodes` 是**对象**不是数组（`{"server_name":...,"server_data":{"Full":{slug,link_embed}}}`）；`actor/category/country` 是 JsonArray 需过滤 `"Updating"` 占位；播放走 link_embed 页（如 `https://upload18.to/play/index/<slug>`） |
| AV01 | `av01` / `https://www.av01.media` | 纯 JSON API（逆向所得） | 列表 `videos/types/latest|hottest?page=&limit=`、详情 `videos/{id}`、搜索 **POST** `videos/search?lang=jp` body `{"query":..,"pagination":{"page":1,"limit":20}}`（参数名必须是 `query`）；**播放三段链**：`videos/{id}/cdn-access` → `manifest/master.m3u8` → `manifest/<档位>?access_token=<cdn token>`；封面要拼 `https://files.iw01.xyz/covers/{id}/400.webp?token_v2=&expires=&ip=`（token 来自 `https://files.iw01.xyz/edge/geo.js?json`，TTL 600s 且绑出口 IP） |

聚合站（`site/aggregator`）：`JAV_GG = https://javgg.net/?s=<code>`、`JAV_GURU = https://jav.guru/searcho/<code小写>.html`（后者常 403）。
`playbackAnySite(code)` = 先 JAV_GG 再 JAV_GURU，返回第一个成功。

---

## 5. 当前完成度（如实）

| Phase | 内容 | 状态 |
|---|---|---|
| 1 | 工程骨架（KMP + Compose MP + 两端入口） | ✅ |
| 2 | HTML 解析层 + 错误类型学 + HTTP 工厂 + 3 站点 | ✅ |
| 3 | WebView 过 CF + Cookie 桥 + 真实 UA + 封面加载 + 域名持久化 | ✅ 模拟器实测 |
| 4 | media3 播放 + 401 刷新重试 + 换集不串台 + 续播/历史/收藏 | ✅ 实测到 `state=3`（401 重试未构造用例） |
| 5 | aggregator 4 extractor + javdb 双通道 + avdb + av01 | ✅ 实测；javdb 播放受"需登录"限制 |
| 6 | WebDAV 同步 + 站点诊断 | ✅ 实测（首次/合并/坏包拒写/401 四条链路） |
| — | iOS 侧 | ⚠️ 代码齐全但**从未编译验证** |

实测证据（可复现的关键日志行）：

```
resp op=huangguo.playback status=200 len=1125        → state=2 → state=3
dumpTextFile av01_226387.m3u8 → segs=803 → prepare mime=application/x-mpegURL → state=3
aggregator SNOS-359 → 2 候选页 → dramiyos-cdn/acek-cdn 双命中 → state=3
Diag: 5/6 个站点连通，1 个需要过人机验证
Sync: 首次同步，已上传本机数据（共 3 条历史 / 0 条收藏）/ 历史合并 2 条 · 收藏合并 1 条
```

---

## 6. 待办清单（按优先级，含验收标准）

> 这些是**已知未闭环**的部分，不是"可能有 bug"。每条都给了验收方式，改完照着验即可。

### P0 — javdb 播放端到端（被登录态卡住）

- 现状：未登录时多数 javdb 详情 302 `/login`，App 会提示"需要登录"并给「打开登录页」。聚合器本身已用 javlibrary/AVDB/AV01 的番号验到 `state=3`。
- 做法：在 App 内点「打开登录页」→ WebView 里手动登录 javdb → Cookie 共享 → 返回详情点播放。
- 验收：logcat 出现 `html detail id=.. code=<真番号>`（不再是 `code=` 空）+ `aggregate by code=<番号>` + `state=3`。
- 注意：`MDVR-448`（VR）与欧美番号在聚合站确实搜不到（`candidates=0`），**换日系番号再试**，别误判成解析 bug。

### P1 — 401 播放刷新重试的实测用例

- 现状：`PlatformPlayer.android.kt` 已实现"遍历 cause 链找 `HttpDataSource.InvalidResponseCodeException(401)` → 回调 `onHttpError` 刷新地址重试"，但没有构造过真实 401。
- 做法：起一个本地 m3u8 服务，第一次请求返回 401、刷新后返回 200（可复用 `tools/webdav-test-server.py` 的思路加个 m3u8 端点）。
- 验收：logcat 里能看到 `http 401 <url>` 紧跟一次重新 prepare，最终 `state=3`。

### P1 — 聚合器 / AV01 的回归单测

- 现状：`PlaybackExtractorsTest` 覆盖了 extractor，但 AV01 服务（三段链、pickVariant、coverUrl）、`playbackAnySite`、javdb `panelValue` 三级兜底都没有单测。
- 做法：把已抓到的真实样本（HTML/JSON 片段）塞进 `commonTest`，只测纯函数部分（不需要网络的），例如 `pickVariant`、`parsePlayerConfig`、`panelValue`、AV01 列表 JSON → `VideoBrief`。
- 验收：`cmd /c build-android.bat :composeApp:testDebugUnitTest` 全绿且新增用例覆盖上述函数。

### P2 — iOS 侧（必须有 macOS）

- `PlatformPlayer.ios.kt`、`ChallengeSheet.ios.kt`、`RemoteImage.ios.kt`、WebDAV 的 Darwin engine：**都未编译验证**。
- `AVAssetResourceLoaderDelegate`（给分片请求加自定义头）未实现 —— AV01 的 `file://` playlist 在 iOS 上能否播也未知。
- 验收：macOS 上 `cd iosApp && xcodegen generate && open iosApp.xcodeproj`，真机跑通 六站点首页 + 一次播放。

### P3 — 产品化缺口（不阻塞当前使用）

- 域名配置目前只能改 KV（`cfg.base_url.<siteId>`），没有 UI。
- `HtmlParsing.dumpEnabled = true` 在 `AppGraph.bootstrap()` 里硬开，发布前应关（会往设备写大量 HTML）。
- `AndroidManifest.xml` 的 `usesCleartextTraffic="true"`（为局域网 http WebDAV）发布前应收紧到域名级配置。
- 首页 `cate_rec` / `topic_rec` 目前当"顶层散块"处理，语义不准。
- `service\generic\GenericHtmlService.kt` 已写但未接 UI。

---

## 7. 踩过的坑（重复踩会浪费一整天，按"症状 → 原因 → 修法"记）

### 播放器（media3）

| 症状 | 原因 | 修法 |
|---|---|---|
| `None of the available extractors` | 给 HLS 设了 `mimeType="application/vnd.apple.mpegurl"` → 落到 ProgressiveMediaPeriod | 按后缀/mime **显式选** `HlsMediaSource.Factory`，不要设 mimeType |
| 分片 403，且日志里 `User-Agent` 出现两次 | `setDefaultRequestProperties(headers)` 与 `setUserAgent(ua)` 都会写 UA | `headers.filterKeys { !it.equals("User-Agent", true) }` + `setUserAgent(headers["User-Agent"])` |
| `Malformed URL` | `HlsMediaSource.Factory(httpFactory)` 读不了 `file://` | 全链路换 `DefaultDataSource.Factory(ctx, httpFactory)` |
| 换集后头部/进度残留 | 复用 ExoPlayer 实例 | **每次 prepare 重建 ExoPlayer** + 500ms 轮询进度 |
| 401 不触发重试 | `InvalidResponseCodeException` 不在 cause 第一层 | `while (cursor != null)` 遍历 cause 链 |

### 数据与 UI

| 症状 | 原因 | 修法 |
|---|---|---|
| 首页"继续观看"点开是错内容 | 各站 videoId 是各自 id 空间（黄果与 AVDB 都有 `10586`） | `continueWatching(siteId = source.id)` 按站点过滤 |
| javdb 详情 `code=` 空、`actors=0` | 该条目 302 到 `/login`，解析的是登录表单 | 检测 `name=password` → 抛 `AuthenticationRequired` → UI 引导登录 |
| 改了 `LibraryStore` 的合并测试全绿但实际会丢数据 | `record()` 会强制 `updatedAt = epochMillis()`，覆盖传入值 | 造本地数据要**预置 KV**（`store.history` / `store.favorites` 的 JSON），不要用 `record()` |
| XChina 首页 404 | 2026-11 改版，旧路径全废 | 见 §4，改路径 + 改解析器 |
| 标题里混进"分类 17:30 85" | 取了锚点全文 | 取 `span[class*=line-clamp]` |
| AVDB `episodes` 解析崩 | 它是对象不是数组 | 加"形态 4"分支 |
| upload18 的 m3u8 抠不到 | 地址无 `.m3u8` 后缀，是 `PLAYER_CONFIG` 里 base64 路径 | 用 `parsePlayerConfig()` **按字段名**取（`m3u8/alternate720/mp4`） |
| 媒体 URL 被截断 | HTML 里是 `\u0026` 转义 | 先还原转义再跑正则（同时保留原文匹配） |

### AV01 专项

- `cdn-access` 的 token issuer 必须是 `av01-cdn`；用 geo 的 token 会 401 `Jwt issuer is not configured`。
- master 里的档位地址指向 `customers.iw01.xyz/...`，该 CDN 对 playlist 路径**恒 403 `Forbidden Resource Pattern`**（无 token / `hdnea=` Cookie / query / Bearer / `/v2/` 全试过）→ **不能把裸 master 交给播放器**，必须经站点 `/manifest/` 代理拿到完整 media playlist 再落盘用 `file://` 播。
- 列表里 `cover` / `preview` 是**布尔**，不是 URL。

### 工程与环境

- **Windows + pwsh**：`Bash` 工具不可用，命令一律 PowerShell；`&&` 可用，`$env:NAME` 取环境变量。
- `build-android.bat` 尾部的 `'PK' is not recognized` 是中文注释被 cmd 当命令，**无害**。
- `pm clear com.javrocket.avplay` 会清掉 UA、域名配置、**Cloudflare Cookie** → 之后首次进 JavLibrary 需要点「打开验证页」重新过验证。
- 模拟器访问宿主机服务用 `http://10.0.2.2:<port>`；本机 `curl.exe` 测站点时**必须带真实 UA**，否则很多站直接 403。

---

## 8. 验证手册（怎么证明你改对了）

### 8.1 看日志（最有效）

日志 tag 与含义：

| tag | 内容 |
|---|---|
| `D HTTP` | `REQUEST:` / `FROM:`（Referer）/ `resp op=<名字> status=<码> len=<字节> head=<前160字>`；播放器另有 `http <code> <url>` |
| `D UI` | `home sections=N first=id:.. cover:.. title:..`（首页解析结果形状） |
| `D Playback` | prepare 的 `mime`、`state=` 状态机变化 |
| `D Aggregator` | `site=JAV_GG entry=.. candidates=N`、失败原因 |
| `D JavDb` | `api channel failed` / `html detail id=.. code=.. actors=..` / `aggregate by code=..` / `requires login` |
| `D Sync` | `config saved / probe dir=.. code=.. / download 404 / upload code=.. / merge history: local=X -> Y adopted=N / done` |
| `D Diag` | `site=<id> layer=<层> ok=<bool> <ms>ms items=N` + `report <一句结论>` |
| `D App` | `bootstrap ua=.. sites=..` |

```powershell
$adb = "C:\tools\android-sdk\platform-tools\adb.exe"
& $adb logcat -d | Select-String "resp op=" | Select-Object -Last 20
& $adb logcat -d | Select-String "state="
& $adb logcat -d | Select-String "D Sync|D Diag"
```

### 8.2 看落盘材料

```powershell
# 站点原始 HTML、聚合页、AV01 落盘 playlist、诊断报告都在这里
& $adb shell "ls -lt /sdcard/Android/data/com.javrocket.avplay/files/dump/ | head -10"
& $adb pull "/sdcard/Android/data/com.javrocket.avplay/files/dump/av01_226387.m3u8.txt" "$env:TEMP\av01.m3u8.txt"

# 配置与历史（JSON 存在 SharedPreferences 的字符串里）
& $adb shell "run-as com.javrocket.avplay cat shared_prefs/avplay_settings.xml"
```

### 8.3 抓站点样本（改解析器前必做）

用一个**真实 WebView UA**（本机实测值）：

```powershell
$ua = "Mozilla/5.0 (Linux; Android 17; sdk_gphone64_x86_64 Build/CE2A.260420.051.A2; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.7632.120 Mobile Safari/537.36"
curl.exe -sS -A $ua -o "$env:TEMP\sample.html" -w "%{http_code} %{size_download}`n" "https://<站点>/<路径>"
```

常用探针（本机已验证过的）：

```
https://www.av01.media/api/v1/videos/types/latest?page=1&limit=20
https://www.av01.media/api/v1/videos/226387
https://www.av01.media/api/v1/videos/226387/cdn-access?token_v2=&expires=&ip=
https://www.av01.media/api/v1/videos/226387/manifest/master.m3u8
https://avdbapi.com/api.php/provide/vod/?ac=list&pg=1
https://avdbapi.com/api.php/provide/vod/?ac=detail&ids=658712
https://xchina.co/videos?page=1
https://javdb.com/v/<slug>          （注意：多数会 302 到 /login）
https://javgg.net/?s=<番号>
https://jav.guru/searcho/<番号小写>.html
```

### 8.4 单测

```powershell
Set-Location "C:\Users\Administrator\Doubao\chats\2026-11-20\new-chat\AvPlayKMP"
cmd /c build-android.bat :composeApp:testDebugUnitTest
# 结果 XML 在这里，可统计用例数与失败数
Get-ChildItem "composeApp\build\test-results\testDebugUnitTest\*.xml" |
  ForEach-Object { $x=[xml](Get-Content $_.FullName); "{0}: tests={1} failures={2}" -f $x.testsuite.name,$x.testsuite.tests,$x.testsuite.failures }
```

### 8.5 同步功能的本地验证（不需要真实网盘）

```powershell
python "C:\Users\Administrator\Doubao\chats\2026-11-20\new-chat\AvPlayKMP\tools\webdav-test-server.py" --port 8099
# App 内：同步页填 http://10.0.2.2:8099/dav/avplay，账号 demo / demo123
# 验证四条路径：首次(GET 404→PUT 201) / 合并(远端改一条更新的) / 坏包(把远端写成非 JSON，应拒绝且不覆盖) / 密码错(401)
```

---

## 9. 对话产物清单（这次协作产出了什么）

| 产物 | 路径 | 用途 |
|---|---|---|
| **当前工程（最全）** | `new-chat\AvPlayKMP\` | 唯一活代码 |
| 源码快照 | `new-chat\AvPlayKMP-Phase3-6-源码.zip`（2.35MB / 279 文件） | 给外部 AI 的完整拷贝（**不含 build**，解压后需重新构建） |
| 可安装包 | `new-chat\AvPlayKMP-debug.apk`（14.75MB） | 与工程 `composeApp\build\outputs\apk\debug\composeApp-debug.apk` 相同 |
| 原 App 业务逻辑 | `new-chat\AvPlay-业务逻辑说明.md`（17 章） | 改站点前查"原实现怎么写" |
| 反编译报告 | `new-chat\AvPlay-安卓反编译报告.md` | 架构/依赖/混淆概览 |
| 反编译源码 | `new-chat\AvPlay-2.2.2_decompiled\`（33,306 .java / 413MB） | golden reference，`sources\com\javrocket\playground\` |
| 自有代码摘录 | `new-chat\AvPlay_自有代码.zip`（1,990 .java） | 只看自家逻辑，不含三方库 |
| 阶段产物 | `AvPlayKMP-工程骨架.zip`、`AvPlayKMP-Phase2-HTML解析层.zip` | 历史对照，可忽略 |
| 落地文档 | `AvPlayKMP\docs\MIGRATION.md` §9–§12 | **Phase 3–6 的坑与实测证据，改代码前必读** |
| 环境文档 | `AvPlayKMP\docs\本地环境-Windows.md` | 工具链、构建、模拟器、日志清单 |
| 构建说明 | `AvPlayKMP\docs\BUILD.md` | 构建/产物/常见错误 |
| 调试工具 | `AvPlayKMP\tools\webdav-test-server.py` | 极简 WebDAV 服务器 |
| 截图（历史） | `new-chat\p3-home.png`、`p3-cf.png`、`p3-state.png` 等 | ⚠️ 含成人内容，**不要读、不要传播** |

> 历史对话里抓的站点样本（`av01_latest.json`、`jd.html`、`avdb_p1.json`、`upload18.html` 等）都在 `%TEMP%`，**可能已被清理**。
> 需要时按 §8.3 的命令重新抓，不要假设文件还在。

---

## 10. 改动约定（写给你，避免把工程带偏）

1. **改站点解析器之前，先抓真实 HTML/JSON 落盘**（§8.3），对着样本改选择器；不要凭印象猜 class 名。
2. **每次改动都要跑三件事**：`build-android.bat` → 装包实测（至少 logcat 看目标那行）→ `:composeApp:testDebugUnitTest`。
3. **失败用 `Result` + 具名 `AppError`**，不要用 `null` / `-1` 表达业务失败。
4. **Ksoup 只在 `parser\HtmlDoc.kt` 里 import**；新解析器走 `BaseParser` + `HtmlParsing.fetch`。
5. **站点方法必须套 `runServiceOperation("site.method", Timeouts.X)`**，否则超时/异常归一化会失效。
6. **UI 不认具体站点**：新站点只在 `AppGraph.buildSources()` 里加一行 + 实现 `ContentSource`。
7. **隐私红线（不要做）**：
   - 不实现黄果封面 AES 解密（属于绕过内容保护）
   - 不移植原 App 里硬编码的 RSA 私钥 / AES 信封方案（凭据一律运行时注入）
   - 不尝试 frida/lldb attach iOS 应用
   - 同步包中**不得**加入 Cookie / UA / 密码（那是设备本地身份；包会放在第三方 WebDAV 上）
8. **不要把 `build\`、`.gradle\`、`.kotlin\`、`local.properties` 纳入交付**；`local.properties` 里有本机 SDK 路径。
9. **发布前收尾**：关 `HtmlParsing.dumpEnabled`、收紧 `usesCleartextTraffic`、把 `DiagnosticsService` 的 `SITE_GAP_MS`（2s 站点间隔）保留（防限流）。

---

## 11. 接手自检清单（开工前 10 分钟跑一遍）

```powershell
$proj = "C:\Users\Administrator\Doubao\chats\2026-11-20\new-chat\AvPlayKMP"
$adb  = "C:\tools\android-sdk\platform-tools\adb.exe"

# 1) 工程完整吗：应输出 80
(Get-ChildItem $proj -Recurse -Filter *.kt -File | Where-Object { $_.FullName -notmatch "\\build\\" }).Count

# 2) 能构建吗：应输出 BUILD SUCCESSFUL
Set-Location $proj; cmd /c build-android.bat 2>&1 | Select-String "BUILD"

# 3) 单测绿吗：应 40 用例 0 失败
cmd /c build-android.bat :composeApp:testDebugUnitTest 2>&1 | Select-String "FAILED|BUILD"

# 4) 模拟器在吗
& $adb devices

# 5) 装包并看启动日志（应出现 bootstrap ua=... sites=[huangguo, ...]）
& $adb install -r "$proj\composeApp\build\outputs\apk\debug\composeApp-debug.apk"
& $adb logcat -c; & $adb shell am force-stop com.javrocket.avplay
& $adb shell am start -n com.javrocket.avplay/.MainActivity
Start-Sleep -Seconds 8
& $adb logcat -d | Select-String "D App|D UI"

# 6) 一键体检六个站点（点"诊断"→"开始诊断"，或直接用日志确认）
#    期望：5/6 连通，JavLibrary 需要过人机验证（未过验证时）
```

以上六步都正常 → 工程健康，可以开始改代码。

---

## 12. 如果你只看三处

1. `composeApp\src\commonMain\kotlin\com\javrocket\avplay\AppGraph.kt` —— 全部依赖怎么装起来的（178 行，读完就知道工程结构）。
2. `docs\MIGRATION.md` §9–§12 —— 六个 Phase 的坑与实测证据（尤其 §7 的坑表，能省你一天）。
3. `composeApp\src\commonMain\kotlin\com\javrocket\avplay\ui\App.kt` —— UI 只有三个状态（站点 / 打开的详情 / 播放中）+ 两个浮层（同步 / 诊断），169 行就够。
