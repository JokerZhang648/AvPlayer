package com.javrocket.avplay.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javrocket.avplay.model.ValueLink
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.ui.media.ImageLoader
import com.javrocket.avplay.ui.media.RemoteImage

/** XChina 首页横版 16:9 海报卡，对齐站内列表样式。 */
@Composable
fun XChinaWidePosterCard(
    item: VideoBrief,
    imageLoader: ImageLoader,
    coverHeaders: Map<String, String>,
    onClick: () -> Unit,
    onOpenActor: ((ValueLink) -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
        ) {
            RemoteImage(
                loader = imageLoader,
                url = item.cover,
                headers = coverHeaders,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Text(
            text = item.title.ifBlank { item.id },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable(onClick = onClick),
        )

        val actor = item.actorLink
        val sub = item.subtitle?.takeIf { it.isNotBlank() }
        val canOpenActor = actor != null && actor.link.isNotBlank() && onOpenActor != null
        if (canOpenActor || sub != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canOpenActor) {
                    TextButton(
                        onClick = { onOpenActor?.invoke(actor) },
                        modifier = Modifier.widthIn(min = 1.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            actor.value,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (sub != null) {
                    val prefix = if (canOpenActor) " · " else ""
                    Text(
                        text = prefix + sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (canOpenActor) Modifier.weight(1f, fill = false) else Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}