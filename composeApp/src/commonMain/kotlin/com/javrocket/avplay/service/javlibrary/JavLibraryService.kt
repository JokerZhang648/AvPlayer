package com.javrocket.avplay.service.javlibrary

import com.javrocket.avplay.core.Timeouts
import com.javrocket.avplay.core.runServiceOperation
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.model.ValueLink
import com.javrocket.avplay.net.HtmlParsing
import com.javrocket.avplay.parser.ParseOptions
import com.javrocket.avplay.service.ManagedService
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.http.encodeURLParameter

/**
 * JavLibrary 站点服务。路径全部来自反编译里的字面量：
 *   /vl_bestrated.php?page=        最高评分
 *   /tl_bestreviews.php?mode=      最佳评论
 *   /userposts.php?u=              用户投稿
 *   /star_mostfav.php              最受欢迎女优
 *   /vl_searchbyid.php?keyword=    按番号搜索
 *   /?v=<code>                     详情
 */
class JavLibraryService(
    baseUrl: String,
    private val http: HttpClient,
) : ManagedService {

    private val base: String = baseUrl.trimEnd('/')

    override fun getBaseUrl(): String = base

    suspend fun bestRated(page: Int = 1): Result<List<VideoBrief>> =
        listRequest("javlibrary.bestRated", "/vl_bestrated.php", page)

    suspend fun bestReviews(mode: String = "", page: Int = 1): Result<List<VideoBrief>> =
        runServiceOperation("javlibrary.bestReviews", Timeouts.STANDARD) {
            HtmlParsing.fetch(
                client = http,
                url = "$base/tl_bestreviews.php",
                parser = JavLibraryListParser(),
                options = ParseOptions(baseUrl = base, page = page, sitePrefix = mode),
            ) {
                if (mode.isNotBlank()) parameter("mode", mode)
                parameter("page", page)
            }
        }

    suspend fun userPosts(user: String, page: Int = 1): Result<List<VideoBrief>> =
        runServiceOperation("javlibrary.userPosts", Timeouts.STANDARD) {
            HtmlParsing.fetch(
                client = http,
                url = "$base/userposts.php",
                parser = JavLibraryListParser(),
                options = ParseOptions(baseUrl = base, page = page, sitePrefix = user),
            ) {
                parameter("u", user)
                parameter("page", page)
            }
        }

    suspend fun mostFavStar(): Result<List<ValueLink>> =
        runServiceOperation("javlibrary.mostFavStar", Timeouts.STANDARD) {
            HtmlParsing.fetch(
                client = http,
                url = "$base/star_mostfav.php",
                parser = JavLibraryStarParser(),
                options = ParseOptions(baseUrl = base),
            )
        }

    suspend fun searchById(keyword: String, page: Int = 1): Result<List<VideoBrief>> =
        runServiceOperation("javlibrary.searchById", Timeouts.STANDARD) {
            HtmlParsing.fetch(
                client = http,
                url = "$base/vl_searchbyid.php",
                parser = JavLibraryListParser(),
                options = ParseOptions(baseUrl = base, page = page, sitePrefix = keyword),
            ) {
                parameter("keyword", keyword)
                parameter("page", page)
            }
        }

    /**
     * 详情页。`id` 必须是**站点内部 hash**（如 `javme3yw6u`），不是番号：
     * 列表里真实链接就是 `./javme3yw6u.html`，用番号拼出来的是 404。
     */
    suspend fun detail(code: String): Result<VideoDetail> =
        runServiceOperation("javlibrary.detail", Timeouts.STANDARD) {
            val path = if (code.endsWith(".html")) code else "$code.html"
            HtmlParsing.fetch(
                client = http,
                url = "$base/$path",
                parser = JavLibraryDetailParser(code),
                options = ParseOptions(baseUrl = base, sitePrefix = code),
            )
        }

    private suspend fun listRequest(
        operation: String,
        path: String,
        page: Int,
    ): Result<List<VideoBrief>> = runServiceOperation(operation, Timeouts.STANDARD) {
        HtmlParsing.fetch(
            client = http,
            url = "$base$path",
            parser = JavLibraryListParser(),
            options = ParseOptions(baseUrl = base, page = page),
        ) { parameter("page", page) }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.javlibrary.com/cn/"
    }
}
