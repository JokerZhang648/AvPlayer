# 迁移映射与后续排期

本文件是“反编译 golden reference → 新工程”的对账单。改代码前先查这里，避免把原实现的坑一起搬过来。

---

## 1. 文件级映射

| golden reference（`com/javrocket/playground/`） | 新工程位置 | 状态 |
|---|---|---|
| `service/ServiceManager.java`（72 KB，StateFlow 懒加载总线） | `AppGraph.kt` | ✅ 极简版；升级为 StateFlow 在 Phase 3 |
| `service/ServiceConfig.java`（`baseUrl + userAgent`） | `core/SettingsStore.kt` | ✅ |
| `service/ManagedService.java` | `service/ManagedService.kt` | ✅ |
| `service/ErrorKt.java`（状态码→中文文案、异常映射） | `core/AppError.kt` 的 `ofHttpStatus` + `toAppError` | ✅ |
| `service/AuthenticationRetrySession.java` | `core/AppError.kt` 的 `LoginRetrySession` | ✅ |
| `service/ServiceOperationKt.java`（超时包装、Operation 名） | `core/ServiceOperation.kt` | ✅ 含 Box 包装细节 |
| `service/CloudflareUtils.java`（15 条规则 + 3 个 Cookie） | `service/Cloudflare.kt` | ✅ 正则版；DOM 版待 Phase 3 |
| `service/JavDbConst.java`（10000/10001/10002） | `HuangGuoService.mapBusinessCode` + `AppError` | ✅ |
| `service/HtmlParsingPluginKt.java`（响应体→对象） | `net/HtmlParsing.kt` | ✅ Phase 2（改成显式门面，非 Ktor 插件，见 §7） |
| `parser/BaseParser`、`ParseOptions`、`WebUrl`、`ParserUtilsKt` | `parser/{BaseParser,ParseOptions,ParserUtils,HtmlDoc}.kt` | ✅ Phase 2（`WebUrl` 折叠进 `ParserUtils`；Ksoup 只从 `HtmlDoc` 一处进来） |
| `service/huangguo/HuangGuoService.java`（20 个方法） | `service/huangguo/HuangGuoService.kt` | ✅ 7 个核心端点（列表/详情/播放） |
| `service/aggregator/PackedJavaScript.java` | `site/PackedJavaScript.kt` | ✅ 含单测 |
| `service/aggregator/PlaybackTargetResolverKt.java`（媒体正则/排序/过滤） | `site/MediaUrlExtractor.kt` | ✅ |
| `service/aggregator/PlaybackAggregatorService.java`（盯 CF、抛异常） | 待建 `site/PlaybackAggregator.kt` | ⬜ Phase 5 |
| `service/aggregator/{KnownPlayer,NestedFrame,JavGuru,TurboVid}Extractor` | 待建 `site/extractor/` | ⬜ Phase 5 |
| `player/PlaybackGatewayKt.java`（gatewayRequest 组装） | `player/PlaybackTransport.kt` | ✅ 共享部分 |
| `player/AndroidPlaybackHttpInterceptor.java`（OkHttp 拦截、401 重试、generation 守卫） | 待建 `androidMain/player/AndroidPlaybackInterceptor.kt` | ⬜ Phase 4 |
| `player/HlsMasterPlaylistParser.java` | 待建 `player/HlsPlaylist.kt` | ⬜ Phase 4 |
| `player/MediaPlayer_androidKt.java`（media3 集成、失败归因） | 待建 `androidMain/player/AndroidPlayer.kt` | ⬜ Phase 4 |
| `player/IosPlayerStateMachineKt`、`IosPlayerEvent/Transition` | 共享状态机保留为 `player/PlayerStateMachine.kt` | ⬜ Phase 4 |
| `service/javdb/**`（HTML 60 方法 + API 41 文件） | 待建 `service/javdb/` | ⬜ Phase 5 |
| `webview/NativeAppCookieManager.java`（71 KB 反射 CookieManager） | 待建 `platform/CookieBridge` | ⬜ Phase 3 |
| `webview/AppWebViewMessageBridge.java` | 待建 `ui/web/WebViewBridge.kt` | ⬜ Phase 3 |
| `network/AppUserAgentManager.java`（42 KB，UA 缓存/环境） | `platformUserAgent()` 占位 | 🟡 固定模板；Phase 3 接真实 UA |
| `network/HeaderInterceptor.java`（Coil3 图片 Header） | 待建（Coil3 接入时一起做） | ⬜ Phase 3 |
| `store/ConfigStore` / `LocalUrlStore` / `WebDavSyncStore` | `core/SettingsStore.kt`（内存版） | 🟡 Phase 3/6 换 DataStore + WebDAV |
| `database/AppDatabase` + 3 个 DAO | 待建 `database/`（Room KMP） | ⬜ Phase 4 |
| `ui/**`（1,084 文件） | `ui/`（首页/详情已成型） | 🟡 骨架就位，其余页面按需补 |

图例：✅ 已落地 ｜ 🟡 部分/占位 ｜ ⬜ 未开始

---

## 2. 14 个站点的协议分类与移植顺序

| 顺序 | 站点 | 协议 | 移植难点 | 建议 |
|---|---|---|---|---|
| 1 | **huangguo** | JSON REST | 无盾、无签名 | ✅ 已做样板 |
| 2 | avdb | JSON | 番号搜索 + 从响应里扒 HLS | 第二个做，练“番号归一” |
| 3 | xchina | HTML | 下载页解析 | ✅ Phase 2（列表/详情/搜索/下载页；播放走聚合器） |
| 4 | javlibrary | HTML | 6 个列表解析器 + dmm 图床 | ✅ Phase 2（bestRated/bestReviews/star/userPosts/搜索/详情） |
| 5 | missav | HTML | 静态 master + 番号归一 | 练“多语言路径” |
| 6 | btdigg | HTML | 磁力列表 | 磁力模型 |
| 7 | xcili | HTML | 磁力直出 `magnet:` | 与 6 合并做 MagnetService |
| 8 | whatslink | JSON | 磁力→元信息 | 接在 6/7 后面 |
| 9 | javmenu | 网页 | 只做播放地址 | 归入 aggregator |
| 10 | memojav | 签名 JSON | `sig`+`sts` 签名 | Phase 5，注意签名算法要从 golden 反推 |
| 11 | av01 | Token JSON | geo token + CDN 票据 + sha256 路径 | Phase 4（正好练凭据刷新） |
| 12 | javdb | HTML + API | 双通道、重定向策略、60 方法 | 最后做，工作量最大 |
| 13 | sgpi | 加密 JSON | RSA+AES 信封 | **不建议移植**（见 §4） |
| 14 | javrocket | JSON | 只做远程配置 | 顺手做 |

---

## 3. 阶段计划与验收

| Phase | 内容 | 验收标准 |
|---|---|---|
| **1（已完成）** | 核心框架 + huangguo 竖切 + 共享 UI + 3 个单测 | `:composeApp:allTests` 全绿；Android 能出 APK 并显示首页 |
| **2（代码已完成，待编译验证）** | 接 Ksoup + `HtmlParsing` 门面 + `parser/` 抽象 + `ContentSource` + xchina/javlibrary | 一个 HTML 站点的列表与详情字段，与 golden reference 的输出一致；解析器单测 3 个类已写（`XChinaParserTest` / `JavLibraryParserTest` / `ParserUtilsTest`），**尚未在本机执行**（无 JDK/Gradle） |
| **3** | WebView（两端）+ Cookie 桥 + CF 挑战页 + 真实 UA + Coil3 图片 Header + DataStore | 撞 CF 能弹页、点完能继续；封面图不 403；域名配置可持久化 |
| **4** | 播放内核：Android media3 + 拦截器；iOS AVPlayer + `AVAssetResourceLoaderDelegate`；Room（历史/收藏/续播） | 两端都能播 m3u8；401 后自动刷新凭据重试一次；换集不串台（generation 守卫）；历史能续播 |
| **5** | aggregator 全链路（4 个 extractor）+ javdb 双通道 + avdb/av01 | 抓播放页→解包→出直链；javdb API 通道可切换 |
| **6** | WebDAV 同步 + 诊断页（站点连通性自测 + 日志导出） | 双端配置能互相同步；站点挂了能一眼看出是哪一层 |

---

## 4. 明确不移植的部分

| 不移植 | 原因 |
|---|---|
`service/sgpi/*` 的 RSA 私钥 + AES 信封 | 客户端持私钥的“白盒加密”本身就是缺陷；照搬等于把同一个缺陷复制一遍。真需要加密协议，应改为服务端公钥 + 客户端会话密钥 |
| `Ui/Theme` 里 jadx 内联出来的 `XxxKt.CONST` 引用 | 那是反编译的产物（常量占位），不是设计；新工程用具名常量 |
| `upsertSnapshot` 之外缺失的方法 | 277 处 `Method not decompiled` 是信息缺口，不是可抄的代码 |
| 原 `JavDbService` 的 10 万行单文件结构 | 按“一站点一文件 + Parser 独立”重排 |
| Firebase/Mixpanel 归因 SDK | 与业务无关；如要统计，用可替换的 analytics 接口 |

---

## 5. iOS 侧移植清单（Phase 3–4 必读）

| 能力 | iOS 实现要点 |
|---|---|
| Cookie 桥 | `WKHTTPCookieStore.getAllCookies` ↔ Ktor `CookiesStorage`，双向同步；`cf_clearance` 是 HttpOnly，只能从这里取 |
| WebView | `UIKitView` 包 `WKWebView`；JS 桥用 `WKUserContentController.addScriptMessageHandler` |
| 播放 Header | `AVURLAsset` 的 `AVURLAssetHTTPHeaderFieldsKey` 只能覆盖首次请求；分片必须用 `AVAssetResourceLoaderDelegate` 逐请求改写 |
| 凭据刷新 | 复用共享的 `gatewayRequest()`；401/403 时走 `PlaybackCredentialRefreshReason.UNAUTHORIZED` |
| 失败归因 | `AVPlayerItem.error` + `AVPlayerItemFailedToPlayToEndTime` 通知，映射成同一个 `PlaybackHttpFailure` |
| 数据库 | Room KMP + `BundledSQLiteDriver`，路径在 `NSDocumentDirectory` |
| 后台音频 | `AVAudioSession(.playback)` + `UIBackgroundModes: audio`（`project.yml` 已配） |
| 线程 | UI 必须主线程；Compose MP 的 `ComposeUIViewController` 内部已处理，但回调要 `dispatch_async(main)` |

---

## 6. 三条不可动摇的设计约束

1. **响应体先过 Cloudflare 判定，再交给解析器。** 违反的后果是“空数据无报错”，排查成本极高。
2. **失败一律是具名 `AppError`，不允许返回 null/false/空列表表示失败。** UI 靠类型决定展示哪个页面。
3. **播放请求规则必须带 `host + path` 匹配。** 否则播放凭据会泄漏给第三方域，既失效又不安全。

---

## 7. Phase 2 落地记录（HTML 解析层，与原实现的 6 处差异）

| # | golden reference 的做法 | 新工程的做法 | 为什么改 |
|---|---|---|---|
| 1 | `createClientPlugin("HtmlParsingPlugin")` + `builder.transformResponseBody(parser, json)` | 显式门面 `HtmlParsing.fetch(client, url, parser, options) { ... }` | `transformResponseBody` 属 Ktor 内部扩展，跨大版本可用性不稳；门面只有几十行，行为等价（都先做 CF 判定再解析），需要时可无痛换回插件 |
| 2 | 每个解析器直接 `import com.fleeksoft.ksoup.Ksoup` | 只有 `parser/HtmlDoc.kt` 一处 import，对外暴露 `HtmlDoc` / `HtmlNode` 自己的最小 API | 换库或换 Ksoup 大版本只改一个文件；也让单测不必关心第三方类型 |
| 3 | `XxxService` 里既发请求又解析 HTML | `XxxService` 只负责“取 HTML”，解析在 `XxxParser`，两者用 `HtmlParsing.fetch(parser = ...)` 拼接 | 解析器可脱离网络单测（Phase 2 的 3 个测试类正是这么写的） |
| 4 | 站点服务直接被 UI 调用（`ServiceManager` 里取具体 Service） | UI 只认 `service/ContentSource.kt` 接口（`id/displayName/baseUrl/home/detail/search/playback`） | 加站点不动 UI；`playback()` 有默认实现返回 `AppError.Parse("不支持直接解析，需走聚合器")`，用类型表达差异而不是散落 if/else |
| 5 | HTML 站点都按固定 class 取值 | xchina 走“链接驱动”（`a[href*='video/id-']` 抠 id，再从锚点/img 取标题封面）；javlibrary 走“固定 id 选择器”（`#video_title` 等） | 前者抗改版，后者是因该站 DOM 十几年未变、按 id 取最省 |
| 6 | 解析器是无状态单例、靠参数夹带 id | `XChinaDetailParser(videoId)` / `JavLibraryDetailParser(code)` 构造即带上下文 | 便宜对象、每请求 new 一个；不污染 `ParseOptions` |

**Phase 2 尚未闭环的 3 件事**（下个会话第一件事）：

1. **真实编译**：本机无 JDK/Gradle/Android SDK，所有验证只到“结构自检”（词法括号 0 失衡、expect/actual 5 对齐全、无残留 `graph.` 调用）。拿到可用环境后先跑 `:composeApp:allTests`。
2. **核实 Ksoup 坐标**：`com.fleeksoft.ksoup:ksoup:0.2.2` 是按印象填的，构建前先查 Maven 实际版本（`libs.versions.toml` 里已有注释）。
3. **抓真实页面复核 XChina 路径**：`/search`、`/model/`、`/videos/series-` 三个是推断值（代码用 `PATH_*` 常量集中并标了 `TODO(核对)`），javlibrary 的路径来自反编译字面量、可信度较高。

## 8. 首次真机（模拟器 API 37）联调记录：两个口径 bug

2026-11-20 首次在 `Medium_Phone_API_37.0`（x86_64 / Play Store 镜像）上装包实测，暴露出两处“代码能编译、单测全绿、但对接真实站点就错”的口径差异。两处都是**照着 golden reference 才能发现**的：

| # | 现象 | 真实原因（反编译依据） | 修复 |
|---|---|---|---|
| 1 | 所有黄果请求都显示「接口返回错误（1）」 | 原实现 `HuangGuoService.lambda$1$2$0` 的默认头是 `UA + Accept + Origin: siteUrl + Referer: siteUrl + "/"`；我们只带了 UA/Accept，站点按来源校验直接回 `code:1` | `createHttpClient(..., siteUrl = ...)` 补 `Origin`/`Referer`；黄果单独建一个带站点头的 client（HTML 站点不校验来源，继续共用通用 client） |
| 2 | 补头后 HTTP 200、响应正文完整，仍报「接口返回错误（1）」 | `HuangGuoResult.isSuccess()` 的实现是 **`status == 1`** —— 黄果的 `status` 是 **1 成功、0 失败**，与 JavDB 的 `code == 0` 成功相反。原先按 `0/200` 判成功，等于把每次正常响应都当失败 | `unwrap()` 拆成两套判定：`code`（0 成功，10000/10001/10002 分别映射登录/订阅/版权）；`status`（**1 才算成功**，其余抛 `Http(status, msg)`） |

顺带修了展示层的三个字段映射（同样来自实测 JSON，不再靠猜）：

- `banners` 同时有 `id` 和 `video_id`，前者是 banner 自己的序号（28、33…），**必须 `video_id` 优先**，否则点进去是错内容；
- banner 的 `title` 是空串，正文在 `desc` 里 → 空标题用简介截断兜底；
- `cover` / `image` 全是 `/upload_01/...` 站内相对路径 → 统一走 `absolutize(base, ...)`。

**验证方式**（以后改站点代码都用这套）：

```powershell
cd C:\Users\Administrator\Doubao\chats\2026-11-20\new-chat\AvPlayKMP
cmd /c build-android.bat
$adb = "C:\tools\android-sdk\platform-tools\adb.exe"
& $adb install -r composeApp\build\outputs\apk\debug\composeApp-debug.apk
& $adb shell am force-stop com.javrocket.avplay
& $adb shell am start -n com.javrocket.avplay/.MainActivity
& $adb logcat -d | Select-String "D HTTP"   # 看 resp op=... status=... len=... head=
```

`bodyTextChecked` 里已经留了一条常驻日志（`resp op= status= len= head=`，只打前 160 字），改站点代码时先看这一行，比翻 UI 快得多。

**当前状态**：黄果首页 4 个区块（`banners` / `featured` / `latest` + 专题）全部解析出来并能渲染标题、简介、评分；JavLibrary 返回 `403 Just a moment...`，被 `CloudflareUtils` 正确识别成「需要人机验证」—— 属于 Phase 3 才解决的 WebView 过验证范围，不是 bug。

**仍未闭环**：封面图仍是占位（要接 Coil3）、详情页/播放未在真机验证、XChina 未实测。

---

## 9. Phase 3 落地记录：WebView 过 CF + Cookie 桥 + 真实 UA

| 组件 | 实现 | 关键点 |
|---|---|---|
| 配置持久化 | `KeyValueStore`（Android SharedPreferences / iOS NSUserDefaults）+ `PersistentSettingsStore` | 键 `cfg.base_url.<siteId>`、`cfg.user_agent`；换域名后重启仍生效 |
| Cookie 桥 | `platformCookieStorage()` | Android 直接读写 `android.webkit.CookieManager`，与 `ChallengeSheet` 的 WebView 共享；**iOS 返 null 刻意不接管**（WKWebView 的 Cookie 需要 `WKHTTPCookieStore`，在 Windows 上无法编译验证，宁可显式不支持） |
| 人机验证 | `ChallengeSheet`（WebView + `onPageFinished` 查 `cf_clearance`） | 任何页面撞到 `CloudflareChallenge(url)` 都把 URL 抛回根节点弹层；验证后 `reloadTick++` 强制重请求 |
| 真实 UA | `platformUserAgent()` | 从 WebView 取真机 UA（实测 `Mozilla/5.0 (Linux; Android 17; sdk_gphone64_x86_64 ... wv) ... Chrome/145.0.7632.120 Mobile Safari/537.36`），首启落盘后不再变 |
| 图片 | 自写 `ImageLoader` + `RemoteImage/RemoteThumb` | 不用 Coil3：只要"带 UA/Cookie 取图 + 内存 LRU"，Coil3 的 KMP 依赖面太大 |

**封面图定性（决定不实现解密）**：黄果封面真实域名是 `https://pic.qnlycc.cn`，HTTP 200 但响应体不是 JPEG magic，而是 AES-128-CBC/NoPadding 密文，key/IV 来自页面里的 `CryptoImageProtocol.decryptKey/decryptIV`。这属于绕过内容保护，**代码里不实现**；黄果卡片保持"无图"占位。

## 10. Phase 4 落地记录：media3 播放内核

契约：`commonMain/player/PlatformPlayer.kt`（`expect class`）——
`snapshot: StateFlow<PlayerSnapshot>`、`onHttpError`、prepare/play/pause/seekTo/release、`@Composable expect fun PlayerSurface`。
Android actual：`OkHttpDataSource` + **每次 prepare 重建 ExoPlayer** + 500ms 轮询进度写 `LibraryStore`。

实测踩到的四个坑（修之前分别表现为"401 不重试""403""None of the available extractors""Malformed URL"）：

| # | 现象 | 原因 | 修复 |
|---|---|---|---|
| 1 | 401 不触发刷新 | `HttpDataSource.InvalidResponseCodeException` 不在 `cause` 第一层 | 用 `while (cursor != null)` 遍历整条 cause 链找 401 |
| 2 | 黄果播放 403 | 原实现是 **POST** `/api/videos/{id}/episodes/{episode}/playback`（body `{}`），响应两层 data | 按 POST 实现并拆两层 |
| 3 | `None of the available extractors` | 给 HLS 设了 `mimeType = application/vnd.apple.mpegurl` → 落到 ProgressiveMediaPeriod | 按后缀/mime 显式选 `HlsMediaSource.Factory`，**不要**给 HLS 设 mimeType |
| 4 | `Malformed URL` | `HlsMediaSource.Factory(httpFactory)` 读不了 `file://`（AV01 落盘 playlist 就是 file://） | 全部换成 `DefaultDataSource.Factory(ctx, httpFactory)` |

**UA 双头导致 403**：media3 的 `setDefaultRequestProperties(headers)` 与 `setUserAgent(ua)` 都会写 UA，headers 里再带一个就变成两个 `User-Agent` 头。已改为
`headers.filterKeys { !it.equals("User-Agent", true) }` + `setUserAgent(headers["User-Agent"])`。

**续播与串台**：`continueWatching(siteId)` 必须按站点过滤 —— 各站 videoId 是各自 id 空间（实测黄果与 AVDB 都有 `10586`，不过滤就会点开错内容）。

## 11. Phase 5 落地记录：聚合器 + 四个站点

### 11.1 aggregator（`site/aggregator`）

`PlaybackTargetResolver(loader, extractors = [JavGuruGatewayExtractor, TurboVidExtractor, KnownPlayerExtractor, NestedFrameExtractor], maxPages = 12, concurrency = 3)`

- `parsePlayerConfig(html): List<Pair<url, mime>>`：`window.PLAYER_CONFIG` 里的 `m3u8 / alternate720 / mp4` 字段，**按字段名取**而不是靠后缀 —— upload18 这类站的 m3u8 路径是 base64 且无后缀，整页唯一的 `m3u8` 字样只是 JSON 键名。
- `MediaUrlExtractor` 先还原 `\u0026` 再跑正则，并保留原文匹配 + `PackedJavaScript.unpackAll`。
- `PlaybackAggregatorService.playbackAnySite(code)`：`JAV_GG`（`https://javgg.net/?s=<code>`）→ `JAV_GURU`（`https://jav.guru/searcho/<code小写>.html`）顺序回退，返回第一个成功；失败日志 `site=X failed: ...`。
- 实测：`SNOS-359` 在 javgg 拿到 **2 个候选页**，分别命中 `dramiyos-cdn.com/hls2/...` 与 `acek-cdn.com/hls2/...` → `state=3`。

### 11.2 AV01（从零逆向，纯 JSON API）

站点 API 与页面同源：`/api/v1`。列表 `videos/types/latest|hottest?page=&limit=`，详情 `videos/{id}`，
搜索 **POST** `videos/search?lang=jp`，body 必须是
`{"query":"...","pagination":{"page":1,"limit":20}}`（参数名是 `query`，传 `q` 恒回空）。

**播放是三段链，缺一段就 403**：

1. `GET /api/v1/videos/{id}/cdn-access` → `access_token`（issuer 必须是 `av01-cdn`，用 geo 的 `av01-api` token 会 401 `Jwt issuer is not configured`）
2. `GET /api/v1/videos/{id}/manifest/master.m3u8`（约 500B，三档）
3. `GET /api/v1/videos/{id}/manifest/<档位>?access_token=<cdn token>` → 完整 media playlist（296–329KB、约 803 段），其中 `#EXT-X-MAP` 与分片全是绝对 URL 且**已自带 token**

master 里的档位地址指向 `customers.iw01.xyz/fmp4/...`，该 CDN 对 playlist 路径恒回 403 `Forbidden Resource Pattern`（无 token / Cookie `hdnea=` / query `hdnea=` / `Bearer` / `/v2/` 全试过）—— 所以**不能把裸 master 交给 ExoPlayer**（会在 init map 处 403）。
落地方案：拿到完整 playlist 后 `dumpTextFile("av01_<id>.m3u8", playlist)`，用 `file://` 交给播放器（实测 `state=3`，分片全 200）。

封面：`cover`/`preview` 在列表里是**布尔**；真实图要拼
`https://files.iw01.xyz/covers/{id}/{400|800}.webp?token_v2=&expires=&ip=`，
token 来自 `https://files.iw01.xyz/edge/geo.js?json`（TTL 600s、绑出口 IP），列表侧缓存 5 分钟。

### 11.3 JavDB（双通道 + 登录识别）

- `/api/v1/movies/{slug}` 恒 404 → 回落 `/v/{slug}` HTML。
- 番号在 **属性** 里：`a.copy-to-clipboard[data-clipboard-text="EROFV-399"]`，不在 `<span class="value">` 的裸文本里（`panelValue` 已重写为三级兜底）。
- **2026-11 起多数详情页对未登录用户 302 到 `/login`**：`loadDetailViaHtml` 用"页面是否存在 `name=password`"判定登录页，命中就抛 `AppError.AuthenticationRequired`，UI 给「打开登录页」（登录后 Cookie 共享，重试即可解析）。不这样处理的话，解析出来是个空壳（`code=` 空、`actors=0`），看起来像解析 bug。

### 11.4 XChina（2026-11 改版修复）

旧路径 `videos/series-`、`video/id-<id>`、`download/id-<id>` 全部失效（`/videos/series-` 403、`/download/<id>` 404）。
新结构：列表 `/videos`（`?page=N` 有效，104 卡/页）、详情 `/video/<hash>`（带 CF）、演员 `/actor/<hash>`、标签 `/video-tag/<slug>`。
解析器改为：`a[href*='/video/']` + 正则 `/video/([A-Za-z0-9]+)`（`/video-series/`、`/video-category/`、`/video-tag/` 因带连字符天然排除），
标题取 `span[class*=line-clamp]` —— **不能取锚点全文**，否则会把封面浮层的"分类 + 时长 + 评论数"写进标题。

## 12. Phase 6 落地记录：WebDAV 同步 + 站点诊断

### 12.1 同步（`sync/`）

`SyncBundle(schema=1, exportedAt, device, library, baseUrls)` —— 带显式版本号，`schema` 更高时直接拒绝（不猜字段语义）。
**刻意不含 Cookie/UA**：那是设备本地身份，跨设备复制会被站点判成会话异常。密码只存本地 KV，永不进包（包在第三方 WebDAV 上）。

四步：GET（404 = 首次同步，不报错）→ 解析（坏包/高版本 → `Result.failure` 且**不覆盖远端**）→ `LibraryStore.mergeRemote()` → PUT 合并结果。

合并策略（`LibraryStore.mergeRemote`）：

- 历史：同 `(siteId, videoId)` 取 `updatedAt` 较新者；远端有本地没有的则新增；按 `updatedAt` 倒序 + `take(200)`
- 收藏：取并集，同键取较新 + `take(500)`
- `baseUrls`：**本地优先**，远端只补本地空白（域名是"这台设备能连通的那个"，被旧域名覆盖会让整站打不开）

实测（本机起了一个极简 WebDAV 测试服务器 `tools/webdav-test-server.py`，模拟器访问 `http://10.0.2.2:8099/dav/avplay`）：

| 场景 | 预期 | 实测 |
|---|---|---|
| 首次同步 | GET 404 → PUT 201 | `首次同步，已上传本机数据（共 3 条历史 / 0 条收藏）` |
| 远端有更新 + 新增 | 按时间合并 | `历史合并 2 条 · 收藏合并 1 条（共 4 条历史 / 1 条收藏）`，本机 prefs 落盘正确 |
| 远端包是坏 JSON | 停止且不覆盖远端 | `远端同步包解析失败，已停止（避免覆盖远端数据）`，远端文件原样保留 |
| 密码错误 | 401 报错 | `WebDAV 探活失败（401）` |

### 12.2 诊断（`diag/`）

`DiagnosticsService.run()` 对每个站点跑**真实 `home()`**，按异常归层（只 ping 域名会得到"能连上但完全用不了"的假阳性）：

`OK` / `NETWORK` / `ANTI_BOT`（CF、403、IP 封）/ `PROTOCOL`（4xx/5xx）/ `PARSE`（200 但 0 条）/ `UNKNOWN`，
每层给一句"该修哪一层"的建议；`export()` 落盘 `diag_<epoch>.txt`（含 UA、平台、逐站耗时与分层结论）。

实测（同一台模拟器，间隔约 20 分钟两次）：

| 站点 | 第一次 | 修复 XChina 后 |
|---|---|---|
| 黄果 | OK 26 条 1338ms | OK 26 条 862ms |
| XChina | **PROTOCOL 404** | **OK 101 条 4139ms** |
| JavLibrary | ANTI_BOT（需过 CF） | ANTI_BOT（未过验证） |
| JavDB | OK 30 条 3800ms | OK 30 条 8526ms |
| AVDB | OK 100 条 2124ms | OK 100 条 1610ms |
| AV01 | OK 40 条 7464ms | OK 40 条 6898ms |

合计 `5/6 连通，1 个需要过人机验证`。诊断页的价值就在这张表上：XChina 的 404 一直藏着，只有逐站跑真实请求才会暴露。

### 12.3 边界（不能闭环的部分，交付时按此口径）

- **iOS 侧全部代码无法在 Windows 编译验证**（`PlatformPlayer.ios.kt`、`ChallengeSheet.ios.kt`、`RemoteImage.ios.kt`、WebDAV 的 Darwin engine）。`AVAssetResourceLoaderDelegate`（播放分片带自定义头）未实现；AV01 的 `file://` playlist 与 WebDAV 同步在 iOS 未验证。
- **javdb 播放端到端需要登录**：当前未登录状态下多数详情页 302 `/login`，App 会引导登录；登录后聚合器链路才有番号可搜。聚合器本身已用 javlibrary / avdb / av01 的番号验证过。
- **聚合站内容覆盖有限**：VR 番号（如 `MDVR-448`）在 javgg / jav.guru 均 `candidates=0`，curl 复核确认是搜索无此条（只有相近番号），属外部限制而非解析 bug。
- **黄果封面**：AES 密文，按上面的取舍不解密。
- `HtmlParsing.dumpEnabled = true` 在 `AppGraph.bootstrap()` 里硬开（调试用），发布版应关。
