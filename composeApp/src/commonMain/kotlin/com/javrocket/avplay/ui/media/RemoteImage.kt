package com.javrocket.avplay.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.service.huangguo.HuangGuoImageCrypto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 字节 → 位图。平台差异只有这一行代码：
 * Android 用 BitmapFactory，iOS 用 skia 的 Image.makeFromEncoded。
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/**
 * 图片加载器 —— 替代 Coil3。
 *
 * 为什么不用 Coil：Coil3 的 KMP 版要额外引 4 个 artifact，且它对“每个请求都要带
 * Referer/UA 才能拿到图”的支持要靠自定义 Fetcher 才能和我们的 HttpClient 复用 Cookie ——
 * 而这套站点图片正是“不带 Referer 就 403”的典型。既然要自己控制请求头，
 * 直接用现成的 HttpClient + 一层位图缓存反而更短、更可控（依赖 0 增加）。
 *
 * 缓存策略：内存 LRU 上限 80 张。封面是列表用的缩略图，80 张足够覆盖一屏多的滚动。
 */
class ImageLoader(private val client: HttpClient) {

    suspend fun load(url: String, headers: Map<String, String> = emptyMap()): ImageBitmap? {
        if (url.isBlank()) return null
        ImageMemoryCache.get(url)?.let { return it }

        // 网络 IO 交给 Ktor，解码放到 Default 线程池（大图解码在 UI 线程会掉帧）
        return try {
            val resp = client.get(url) {
                headers.forEach { (k, v) -> header(k, v) }
            }
            val bytes = HuangGuoImageCrypto.prepareBytes(url, resp.bodyAsBytes())
            val bitmap = withContext(Dispatchers.Default) { decodeImageBitmap(bytes) }
            // 图片加载是“静默失败”的重灾区：没有这两行日志，只能看到占位符，根本不知道卡在哪
            platformLog(
                "IMG",
                "status=${resp.status.value} len=${bytes.size} decoded=${bitmap != null} url=${url.takeLast(48)}",
            )
            bitmap?.also { ImageMemoryCache.put(url, it) }
        } catch (t: Throwable) {
            platformLog("IMG", "load failed ${t::class.simpleName}: ${t.message} url=${url.takeLast(48)}")
            null
        }
    }
}

object ImageMemoryCache {
    private const val MAX_ENTRIES = 80
    private val entries = LinkedHashMap<String, ImageBitmap>()

    fun get(key: String): ImageBitmap? = entries[key]

    fun put(key: String, bitmap: ImageBitmap) {
        if (entries.size >= MAX_ENTRIES) {
            entries.keys.firstOrNull()?.let { entries.remove(it) }
        }
        entries[key] = bitmap
    }

    fun clear() = entries.clear()
}

/**
 * 带占位与失败的封面图。占位不是装饰 —— 它保证列表滚动时布局高度不跳，
 * 否则异步加载完成后 item 高度突变，滚动位置会“自己往上窜”。
 */
@Composable
fun RemoteImage(
    loader: ImageLoader,
    url: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    contentScale: ContentScale = ContentScale.Crop,
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(loader, url) {
        if (url.isNullOrBlank()) {
            platformLog("IMG", "skip: empty url")
            failed = true
            return@LaunchedEffect
        }
        val loaded = loader.load(url, headers)
        if (loaded == null) failed = true else bitmap = loaded
    }

    Box(modifier = modifier.clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )

            failed -> Text("无图", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

            else -> Text("·", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

/** 固定尺寸占位（列表里用），避免依赖调用方传 size。 */
@Composable
fun RemoteThumb(
    loader: ImageLoader,
    url: String?,
    headers: Map<String, String> = emptyMap(),
) {
    RemoteImage(loader = loader, url = url, modifier = Modifier.size(72.dp, 100.dp), headers = headers)
}
