package com.javrocket.avplay.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javrocket.avplay.model.Episode
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.player.PlatformPlayer
import com.javrocket.avplay.player.PlayerSnapshot
import com.javrocket.avplay.player.PlayerStatus
import com.javrocket.avplay.player.PlayerSurface

private val SpeedSteps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuangGuoPortraitPlayer(
    player: PlatformPlayer,
    playbackUrl: String,
    snapshot: PlayerSnapshot,
    brief: VideoBrief,
    episodeIndex: Int,
    totalEpisodes: Int,
    episodeLine: String?,
    playbackSpeed: Float,
    episodes: List<Episode>,
    switchingEpisode: Boolean,
    showEpisodeSheet: Boolean,
    dragging: Boolean,
    dragValue: Float,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onOpenEpisodes: () -> Unit,
    onDismissEpisodes: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onDragProgress: (Float) -> Unit,
    onDragProgressFinished: (Float) -> Unit,
) {
    val tap = remember { MutableInteractionSource() }
    var showSpeedMenu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = tap, indication = null, onClick = onTogglePlay),
        ) {
            androidx.compose.runtime.key(playbackUrl) {
                PlayerSurface(
                    player = player,
                    modifier = Modifier.fillMaxSize(),
                    fillPortraitScreen = true,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("←", color = Color.White, fontSize = 22.sp)
            }
            Text(
                text = "第${episodeIndex}集",
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Box {
                TextButton(onClick = { showSpeedMenu = true }) {
                    Text(
                        text = "${formatSpeed(playbackSpeed)} ▾",
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = { showSpeedMenu = false },
                ) {
                    SpeedSteps.forEach { speed ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = formatSpeed(speed),
                                    fontWeight = if (speed == playbackSpeed) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                onSelectSpeed(speed)
                                showSpeedMenu = false
                            },
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 4.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                Text(
                    text = brief.title.ifBlank { brief.id },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    append("第${episodeIndex}集")
                    episodeLine?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                }
                Text(
                    text = meta,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            val duration = snapshot.durationMs
            val positionMs = snapshot.positionMs
            if (duration > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatTimeMs(if (dragging) (dragValue * duration).toLong() else positionMs),
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        modifier = Modifier.width(44.dp),
                    )
                    Slider(
                        value = if (dragging) dragValue else (positionMs.toFloat() / duration),
                        onValueChange = onDragProgress,
                        onValueChangeFinished = { onDragProgressFinished(dragValue) },
                        modifier = Modifier
                            .weight(1f)
                            .height(12.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White.copy(alpha = 0.95f),
                            activeTrackColor = Color.White.copy(alpha = 0.85f),
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                        ),
                    )
                    Text(
                        text = formatTimeMs(duration),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(44.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC1E1E1E))
                    .clickable(onClick = onOpenEpisodes)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "选集 · 全${totalEpisodes}集",
                    color = Color.White,
                    fontSize = 15.sp,
                )
                Text("⤢", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
            }
        }

        if (snapshot.status == PlayerStatus.BUFFERING || switchingEpisode) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }
    }

    if (showEpisodeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissEpisodes,
            sheetState = sheetState,
            containerColor = Color(0xFF1A1A1A),
        ) {
            Text(
                "选集",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            val indices = if (episodes.isNotEmpty()) {
                episodes.map { it.index }
            } else {
                (1..totalEpisodes).toList()
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(indices) { ep ->
                    val selected = ep == episodeIndex
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFFE91E63) else Color(0xFF333333))
                            .clickable { onSelectEpisode(ep) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$ep",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

fun formatSpeed(speed: Float): String {
    return if (speed == speed.toLong().toFloat()) {
        "${speed.toLong()}.0x"
    } else {
        "${speed}x"
    }
}

private fun formatTimeMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}