package com.javrocket.avplay.net

import com.javrocket.avplay.core.dumpTextFile
import com.javrocket.avplay.parser.BaseParser
import com.javrocket.avplay.parser.ParseOptions
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get

/**
 * HTML 解析入口 —— 对应反编译里的 service/HtmlParsingPluginKt。
 *
 * ⚠️ 实现方式与原版不同，这点需要明确：
 *   原版是一个 Ktor `ClientPlugin`，用 `builder.transformResponseBody(parser, json)`
 *   在 response 管线里把 HTML 直接替换成对象；客户端调完 `client.get(url)` 拿到的
 *   就是模型，业务层完全不碰字符串。
 *
 *   这里改成显式的 `fetch(...)` 门面，原因有两个：
 *   1) `transformResponseBody` 的可用性随 Ktor 大版本变化，写进脚手架容易一升级就编译不过；
 *   2) 显式门面更适合做单元测试与错误定位（HTML 解析失败时能立刻知道是哪一步）。
 *
 *   两者行为等价：只发一次请求、响应体在本层被解析成对象、业务层不接触原始 HTML。
 *   如果之后要严格对齐原版，把 fetch 内部换成
 *   `createClientPlugin(...) { transformResponseBody { ... } }` 即可，调用方无需改动。
 */
object HtmlParsing {

    /**
     * 是否把每次 HTML 响应落盘（debug 包默认开）。
     * 站点改版时把原文拉回来对着选择器改，比在 logcat 里猜快得多。
     */
    var dumpEnabled: Boolean = false

    suspend fun <T> fetch(
        client: HttpClient,
        url: String,
        parser: BaseParser<T>,
        options: ParseOptions = ParseOptions.DEFAULT,
        block: HttpRequestBuilder.() -> Unit = {},
    ): T {
        // bodyTextChecked 内部已做 Cloudflare 判定 + 状态码映射，这里不能直接用 bodyAsText
        val body = client.get(url, block).bodyTextChecked("html")
        if (dumpEnabled) {
            val name = "html_" + url.hashCode().toString(16) + "_" + parser::class.simpleName
            dumpTextFile(name, body)
        }
        return parser.parse(body, options)
    }
}
