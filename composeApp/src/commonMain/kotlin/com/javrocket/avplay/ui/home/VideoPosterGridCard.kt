package com.javrocket.avplay.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.ui.media.ImageLoader
import com.javrocket.avplay.ui.media.RemoteImage
import kotlin.math.roundToInt

/** 黄果 / XChina 等站点共用的竖版双列海报卡片。 */
@Composable
fun VideoPosterGridCard(
    item: VideoBrief,
    imageLoader: ImageLoader,
    coverHeaders: Map<String, String>,
    rank: Int? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            RemoteImage(
                loader = imageLoader,
                url = item.cover,
                headers = coverHeaders,
                modifier = Modifier.fillMaxSize(),
            )
            rank?.let { r ->
                val (bg, badgeSize) = when (r) {
                    1 -> Color(0xFFE53935) to 26.dp
                    2 -> Color(0xFFFF7043) to 24.dp
                    3 -> Color(0xFFFFB300) to 24.dp
                    else -> Color(0xCC212121) to 22.dp
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(bg, if (r <= 3) CircleShape else RoundedCornerShape(4.dp))
                        .size(badgeSize),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$r",
                        color = Color.White,
                        fontSize = if (r <= 3) 14.sp else 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            item.rating?.let { score ->
                Text(
                    text = "%.1f分".format(score),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xCCFF9800), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            item.hot?.let { hot ->
                Text(
                    text = formatPosterHot(hot),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    fontSize = 10.sp,
                )
            }
        }

        Text(
            text = item.title.ifBlank { item.id },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )

        item.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        posterEpisodeLine(item.episodeCount, item.isFinished)?.let { line ->
            Text(
                text = line,
                color = Color(0xFFE91E63),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        val meta = listOfNotNull(
            item.code?.takeIf { it.isNotBlank() },
            item.duration?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotBlank() && item.subtitle.isNullOrBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun formatPosterHot(hot: Long): String {
    val wan = hot / 10000.0
    return if (wan >= 1.0) {
        val text = ((wan * 10).roundToInt() / 10.0).toString().removeSuffix(".0")
        "${text}万热度"
    } else {
        "${hot}热度"
    }
}

private fun posterEpisodeLine(count: Int?, finished: Boolean?): String? {
    if (count == null || count <= 0) return null
    return if (finished == true) "全${count}集" else "更新至${count}集"
}