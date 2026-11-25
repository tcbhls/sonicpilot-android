package com.sonicpilot.music.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sonicpilot.music.data.model.Playlist
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.player.PlaybackProgress
import com.sonicpilot.music.player.PlaybackUiState
import com.sonicpilot.music.ui.theme.SonicColors
import kotlinx.coroutines.flow.StateFlow

/**
 * 智能缩略图 URL 优化：
 * 对网易云 CDN 图片按显示尺寸附加 `?param=240y240` / `?param=640y640`，流量立省 90%，滑动丝滑零卡顿！
 */
fun optimizeArtworkUrl(url: String, targetPx: Int = 240): String {
    if (!url.startsWith("http")) return url
    val clean = url.replace("http://", "https://")
    return if (clean.contains("music.126.net") && !clean.contains("param=")) {
        "$clean?param=${targetPx}y${targetPx}"
    } else clean
}

/**
 * 极简圆角封面组件（自动按尺寸请求合适分辨率缩略图）
 */
@Composable
fun SongArtwork(
    coverUrl: String,
    size: Dp = 50.dp,
    cornerRadius: Dp = 10.dp,
    highRes: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)
    val optimized = remember(coverUrl, highRes) {
        optimizeArtworkUrl(coverUrl, if (highRes) 640 else 240)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(SonicColors.SurfaceElevated),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = SonicColors.TextMuted,
            modifier = Modifier.size(size * 0.42f)
        )
        if (optimized.startsWith("http")) {
            AsyncImage(
                model = optimized,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/**
 * 极简克制音质与状态徽章（自动匹配主题强调色）
 */
@Composable
fun QualityBadge(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    val isHighlight = text.contains("Hi-Res", true) || text.contains("FLAC", true) ||
        text.contains("无损") || text.contains("主渠道") || text.contains("免费") || text.contains("推荐")
    val tint = if (isHighlight) SonicColors.Accent else SonicColors.TextSecondary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.13f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/**
 * 极简三柱律动音波指示器（正在播放时丝滑跳动，暂停时静默定格）
 */
@Composable
fun AnimatedEqBars(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val heights = if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "eqBars")
        val h1 by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "eq1"
        )
        val h2 by transition.animateFloat(
            initialValue = 0.95f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(tween(340, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "eq2"
        )
        val h3 by transition.animateFloat(
            initialValue = 0.40f,
            targetValue = 0.90f,
            animationSpec = infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "eq3"
        )
        listOf(h1, h2, h3)
    } else {
        listOf(0.35f, 0.65f, 0.45f)
    }
    Row(
        modifier = modifier.height(13.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEach { ratio ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((13f * ratio).dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color)
            )
        }
    }
}

/**
 * 对标网易云 / QQ音乐 的极简流畅歌曲行：
 * 封面 + 歌名/歌手 + 右侧 `···` 底部抽屉（含收藏、下一首播放、无损下载、歌单、沉浸学歌、问 AI）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongRowItem(
    song: Song,
    isPlayingThis: Boolean,
    isAudioPlaying: Boolean = true,
    isFavorite: Boolean,
    isOfflineDownloaded: Boolean = false,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    playlists: List<Playlist>,
    rankNumber: Int? = null,
    onPlay: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: (Playlist) -> Unit,
    onLearnSong: (() -> Unit)? = null,
    onWatchMv: (() -> Unit)? = null,
    onAskAi: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showActionSheet by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    selectionMode && isSelected -> SonicColors.Accent.copy(alpha = 0.13f)
                    isPlayingThis -> SonicColors.Accent.copy(alpha = 0.09f)
                    else -> Color.Transparent
                }
            )
            .clickable {
                if (selectionMode && onToggleSelect != null) onToggleSelect() else onPlay()
            }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (isSelected) "已选中" else "未选中",
                tint = if (isSelected) SonicColors.Accent else SonicColors.TextMuted,
                modifier = Modifier.padding(end = 8.dp).size(22.dp)
            )
        } else if (rankNumber != null) {
            Text(
                text = rankNumber.toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelMedium,
                color = if (rankNumber <= 3) SonicColors.Accent else SonicColors.TextMuted,
                fontWeight = if (rankNumber <= 3) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.width(26.dp)
            )
        }
        SongArtwork(coverUrl = song.coverUrl, size = 48.dp, cornerRadius = 9.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlayingThis) {
                    AnimatedEqBars(isPlaying = isAudioPlaying, color = SonicColors.Accent)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isPlayingThis) SonicColors.Accent else SonicColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = SonicColors.RoseFavorite,
                        modifier = Modifier.size(12.dp)
                    )
                }
                if (isOfflineDownloaded) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = "已离线下载",
                        tint = Color(0xFF1DB954),
                        modifier = Modifier.size(13.dp)
                    )
                }
                if (song.qualityBadge.isNotBlank()) {
                    QualityBadge(song.qualityBadge)
                }
                Text(
                    text = buildString {
                        append(song.artist)
                        if (song.album.isNotBlank()) append(" · ${song.album}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = SonicColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!selectionMode) {
            // 一键直达 1080P 高清 MV（每首歌右侧直接露出，再也不用去菜单里翻找！）
            if (onWatchMv != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            if (song.mvId.isNotBlank()) SonicColors.Accent.copy(alpha = 0.14f)
                            else SonicColors.SurfaceElevated
                        )
                        .clickable { onWatchMv() }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "MV",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (song.mvId.isNotBlank()) SonicColors.Accent else SonicColors.TextSecondary
                    )
                }
            }
            IconButton(onClick = { showActionSheet = true }, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = "更多",
                    tint = SonicColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showActionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showActionSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SonicColors.SurfaceCard
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SongArtwork(coverUrl = song.coverUrl, size = 54.dp, cornerRadius = 11.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.titleLarge, color = SonicColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = SonicColors.TextSecondary, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = SonicColors.Hairline)
                Spacer(Modifier.height(6.dp))

                if (onPlayNext != null) {
                    SheetActionItem(Icons.AutoMirrored.Rounded.QueueMusic, SonicColors.TextPrimary, "下一首播放") {
                        onPlayNext()
                        showActionSheet = false
                    }
                }
                SheetActionItem(
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    tint = if (isFavorite) SonicColors.RoseFavorite else SonicColors.TextPrimary,
                    label = if (isFavorite) "移出「我喜欢」" else "收藏到「我喜欢」"
                ) {
                    onToggleFavorite()
                    showActionSheet = false
                }
                SheetActionItem(Icons.Rounded.Download, SonicColors.Accent, "下载无损（离线 + 导出 Music/SonicPilot）") {
                    onDownload()
                    showActionSheet = false
                }
                if (playlists.isNotEmpty()) {
                    SheetActionItem(Icons.AutoMirrored.Rounded.PlaylistAdd, SonicColors.TextPrimary, "添加到歌单") {
                        showActionSheet = false
                        showPlaylistDialog = true
                    }
                }
                if (onWatchMv != null) {
                    SheetActionItem(Icons.Rounded.OndemandVideo, SonicColors.Accent, "观看高清 MV") {
                        showActionSheet = false
                        onWatchMv()
                    }
                }
                if (onLearnSong != null) {
                    SheetActionItem(Icons.Rounded.Lyrics, SonicColors.TextPrimary, "进入「沉浸歌词 / 粤拼与双语学歌」") {
                        showActionSheet = false
                        onLearnSong()
                    }
                }
                if (onAskAi != null) {
                    SheetActionItem(Icons.Rounded.AutoAwesome, SonicColors.Accent, "问 AI 赏析或找同风格曲目") {
                        showActionSheet = false
                        onAskAi()
                    }
                }
            }
        }
    }

    if (showPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            containerColor = SonicColors.SurfaceCard,
            title = { Text("加入歌单", style = MaterialTheme.typography.titleLarge, color = SonicColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    playlists.forEach { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SonicColors.SurfaceElevated)
                                .clickable {
                                    onAddToPlaylist(pl)
                                    showPlaylistDialog = false
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(pl.name, color = SonicColors.TextPrimary, modifier = Modifier.weight(1f))
                            Text("${pl.songCount} 首", color = SonicColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPlaylistDialog = false }) { Text("关闭") } }
        )
    }
}

@Composable
private fun SheetActionItem(
    icon: ImageVector,
    tint: Color,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = SonicColors.TextPrimary)
    }
}

/**
 * 极简悬浮迷你播放条：
 * - 内部隔离订阅 progressFlow 仅重绘最底下的 2dp 细进度条，完全不影响整个页面流畅度
 * - 支持左右滑动快速切歌（左滑下一首 / 右滑上一首，对标 QQ音乐 & 网易云手势）
 */
@Composable
fun MiniPlayerBar(
    playbackState: PlaybackUiState,
    progressFlow: StateFlow<PlaybackProgress>,
    onOpenFullPlayer: () -> Unit,
    onOpenLyricsDirect: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val isDark = SonicColors.isDark
    val haptics = LocalHapticFeedback.current
    var horizontalDragSum by remember { mutableFloatStateOf(0f) }
    val visualOffset by animateFloatAsState(
        targetValue = (horizontalDragSum * 0.25f).coerceIn(-30f, 30f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "miniSwipe"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .graphicsLayer { translationX = visualOffset }
            .shadow(if (isDark) 0.dp else 8.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.09f))
            .clip(RoundedCornerShape(16.dp))
            .background(SonicColors.SurfaceCard)
            .border(0.6.dp, SonicColors.Hairline.copy(alpha = if (isDark) 0.8f else 0.5f), RoundedCornerShape(16.dp))
            .pointerInput(onNext, onPrevious) {
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDragSum = 0f },
                    onDragCancel = { horizontalDragSum = 0f },
                    onDragEnd = {
                        when {
                            horizontalDragSum < -56f -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNext()
                            }
                            horizontalDragSum > 56f -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPrevious?.invoke()
                            }
                        }
                        horizontalDragSum = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        horizontalDragSum += dragAmount
                    }
                )
            }
            .clickable { onOpenFullPlayer() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongArtwork(coverUrl = song.coverUrl, size = 42.dp, cornerRadius = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SonicColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        playbackState.errorMessage != null -> playbackState.errorMessage
                        playbackState.isBuffering && playbackState.loadingStage.isNotBlank() -> playbackState.loadingStage
                        else -> buildString {
                            append(song.artist)
                            if (playbackState.qualityLabel.isNotBlank()) append(" · ${playbackState.qualityLabel}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        playbackState.errorMessage != null -> SonicColors.RoseFavorite
                        playbackState.isBuffering -> SonicColors.Accent
                        else -> SonicColors.TextSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onOpenLyricsDirect, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Lyrics, "沉浸歌词", tint = SonicColors.Accent, modifier = Modifier.size(19.dp))
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(SonicColors.Accent)
                    .clickable { onTogglePlay() },
                contentAlignment = Alignment.Center
            ) {
                if (playbackState.isBuffering && !playbackState.isPlaying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = SonicColors.OnAccent
                    )
                } else {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "播放/暂停",
                        tint = SonicColors.OnAccent,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.SkipNext, "下一首", tint = SonicColors.TextPrimary, modifier = Modifier.size(22.dp))
            }
        }
        // 局部独立重组进度条（绝不触发外部界面重绘）
        IsolatedMiniProgressLine(progressFlow)
    }
}

@Composable
private fun IsolatedMiniProgressLine(progressFlow: StateFlow<PlaybackProgress>) {
    val prog by progressFlow.collectAsState()
    val fraction = if (prog.durationMs > 0L) {
        (prog.positionMs.toFloat() / prog.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(2.dp),
        color = SonicColors.Accent,
        trackColor = Color.Transparent
    )
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}

/**
 * 旗舰呼吸骨架屏歌曲行（替代干巴巴的空白转圈，切榜/搜索瞬间饱满舒适）
 */
@Composable
fun ShimmerSongRowPlaceholder(count: Int = 6) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(count) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(SonicColors.SurfaceElevated.copy(alpha = alpha))
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.58f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SonicColors.SurfaceElevated.copy(alpha = alpha))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.36f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(SonicColors.SurfaceElevated.copy(alpha = alpha * 0.75f))
                    )
                }
            }
        }
    }
}
