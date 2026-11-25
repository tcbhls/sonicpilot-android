package com.sonicpilot.music.ui.mv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sonicpilot.music.SonicPilotApp
import com.sonicpilot.music.data.model.MvStream
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.ui.theme.SonicColors
import kotlinx.coroutines.delay

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 影院级高清 MV 播放器（v2.0 深度升级）：
 * - 单一持久化 PlayerView 画布：横屏沉浸全屏 ↔ 竖屏切换 0 重建、0 黑屏掉帧！
 * - 自动画质容灾降级：1080P 流失效时无感自动尝试 720P → 480P，绝不轻易红字中断！
 * - 支持画质热切、倍速、画面铺满/适应、手势唤醒悬浮顶栏与屏幕常亮。
 */
@OptIn(UnstableApi::class)
@Composable
fun MvPlayerOverlay(
    app: SonicPilotApp,
    song: Song,
    isFullscreen: Boolean,
    onToggleFullscreen: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSwitchToAudio: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val accentColor = SonicColors.Accent

    var isLoading by remember(song.uniqueKey) { mutableStateOf(true) }
    var isVideoBuffering by remember(song.uniqueKey) { mutableStateOf(false) }
    var mvStream by remember(song.uniqueKey) { mutableStateOf<MvStream?>(null) }
    var selectedQuality by remember(song.uniqueKey) { mutableStateOf("") }
    var failedQualities by remember(song.uniqueKey) { mutableStateOf<Set<String>>(emptySet()) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var fillScreenMode by remember { mutableStateOf(false) }
    var showFullscreenTopBar by remember { mutableStateOf(true) }
    var errorText by remember(song.uniqueKey) { mutableStateOf<String?>(null) }
    var reloadNonce by remember(song.uniqueKey) { mutableIntStateOf(0) }

    val latestStream by rememberUpdatedState(mvStream)
    val latestQuality by rememberUpdatedState(selectedQuality)
    val latestFailed by rememberUpdatedState(failedQualities)
    val latestFullscreen by rememberUpdatedState(isFullscreen)

    val exoPlayer = remember(context) {
        val fastLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 40_000, 600, 1_200)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(fastLoadControl)
            .build()
            .apply { playWhenReady = true }
    }

    // 同步横竖屏旋转 + 沉浸式隐藏/恢复状态栏与导航栏
    LaunchedEffect(isFullscreen, activity) {
        val act = activity ?: return@LaunchedEffect
        val insets = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (isFullscreen) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
            showFullscreenTopBar = true
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 全屏悬浮顶栏 4 秒后自动淡出
    LaunchedEffect(isFullscreen, showFullscreenTopBar) {
        if (isFullscreen && showFullscreenTopBar) {
            delay(4000L)
            showFullscreenTopBar = false
        }
    }

    // 进入 MV 暂停音频 + 开启屏幕常亮 + 监听解码器错误并自动清晰度降级重试
    DisposableEffect(Unit) {
        app.playerController.pause()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isVideoBuffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                isVideoBuffering = false
                val curQ = latestQuality
                val nextFailed = if (curQ.isNotBlank()) latestFailed + curQ else latestFailed
                failedQualities = nextFailed
                val fallbackQ = latestStream?.qualityUrls?.keys?.firstOrNull { !nextFailed.contains(it) }
                if (fallbackQ != null) {
                    selectedQuality = fallbackQ
                } else {
                    errorText = "当前画质视频流播放失败，可尝试点击重新匹配"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            activity?.let { act ->
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 异步多源解析高清 MV 直链（网易云 1080P -> 酷狗 -> Apple 官方）
    LaunchedEffect(song.uniqueKey, reloadNonce) {
        isLoading = true
        errorText = null
        failedQualities = emptySet()
        val resolved = app.mvEngine.resolveMvForSong(song)
        mvStream = resolved
        if (resolved == null || resolved.qualityUrls.isEmpty()) {
            errorText = "暂未在网易云 / 酷狗 / Apple 官方源找到《${song.title}》的 MV"
        } else {
            selectedQuality = resolved.bestQualityLabel
        }
        isLoading = false
    }

    LaunchedEffect(mvStream, selectedQuality) {
        val url = mvStream?.qualityUrls?.get(selectedQuality) ?: mvStream?.bestUrl ?: return@LaunchedEffect
        if (url.startsWith("http")) {
            errorText = null
            val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed, 1.0f)
            exoPlayer.prepare()
            if (currentPos > 1000L) exoPlayer.seekTo(currentPos)
            exoPlayer.play()
        }
    }

    val qualities = mvStream?.qualityUrls?.keys?.toList().orEmpty()

    // 统一容器：始终保留同一个 AndroidView(PlayerView)，横竖屏切换 0 重建、0 黑屏！
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isFullscreen) Color.Black else Color(0xFF07080B))
            .then(if (!isFullscreen) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)
    ) {
        if (!isFullscreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mvStream?.title ?: song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(mvStream?.artist ?: song.artist)
                            mvStream?.sourceLabel?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFA0A6B4),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (mvStream != null && errorText == null) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(accentColor.copy(alpha = 0.22f))
                            .clickable { onToggleFullscreen(true) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Fullscreen, null, tint = accentColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("全屏", style = MaterialTheme.typography.labelSmall, color = accentColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 持久化视频画布（全屏时 fillMaxSize，竖屏时 16:9 影院画幅，PlayerView 实例永不销毁重建）
        Box(
            modifier = if (isFullscreen) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
            },
            contentAlignment = Alignment.Center
        ) {
            if (mvStream != null && errorText == null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            controllerAutoShow = true
                            resizeMode = if (fillScreenMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            } else {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                            setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { vis ->
                                    if (latestFullscreen) {
                                        showFullscreenTopBar = vis == android.view.View.VISIBLE
                                    }
                                }
                            )
                            setFullscreenButtonClickListener {
                                onToggleFullscreen(!latestFullscreen)
                            }
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { pv ->
                        pv.resizeMode = if (fillScreenMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        pv.setFullscreenButtonClickListener {
                            onToggleFullscreen(!latestFullscreen)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (!isFullscreen) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x99000000))
                            .clickable { onToggleFullscreen(true) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Fullscreen, "横屏全屏", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }

            // 横屏全屏顶部沉浸悬浮条
            if (isFullscreen && (showFullscreenTopBar || isLoading || errorText != null)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onToggleFullscreen(false) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "退出全屏", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mvStream?.title ?: song.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${mvStream?.artist ?: song.artist} · ${mvStream?.sourceLabel.orEmpty()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB5BAC6),
                            maxLines = 1
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (qualities.size > 1) {
                            MvHudChip(selectedQuality.ifBlank { "画质" }, true, accentColor) {
                                val nextIdx = (qualities.indexOf(selectedQuality) + 1) % qualities.size
                                selectedQuality = qualities[nextIdx]
                            }
                        }
                        MvHudChip("${playbackSpeed}x", playbackSpeed != 1.0f, accentColor) {
                            val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f)
                            val next = speeds[(speeds.indexOf(playbackSpeed) + 1) % speeds.size]
                            playbackSpeed = next
                            exoPlayer.playbackParameters = PlaybackParameters(next, 1.0f)
                        }
                        MvHudChip(if (fillScreenMode) "铺满" else "适应", fillScreenMode, accentColor) {
                            fillScreenMode = !fillScreenMode
                        }
                        IconButton(onClick = { onToggleFullscreen(false) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.FullscreenExit, "退出全屏", tint = Color.White)
                        }
                    }
                }
            }

            if (isLoading || isVideoBuffering) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = accentColor, strokeWidth = 2.6.dp, modifier = Modifier.size(32.dp))
                    if (isLoading) {
                        Spacer(Modifier.height(10.dp))
                        Text("正在多渠道匹配 1080P 高清 MV...", style = MaterialTheme.typography.labelMedium, color = Color(0xFFD0D4DC))
                    }
                }
            } else if (errorText != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp)
                ) {
                    Text(errorText.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD0D4DC))
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(accentColor)
                            .clickable { reloadNonce++ }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("重新匹配", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 竖屏影院控制面板
        if (!isFullscreen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (mvStream != null && errorText == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(accentColor)
                                .clickable { onToggleFullscreen(true) }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Fullscreen, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("横屏全屏观看", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF171922))
                                .clickable { fillScreenMode = !fillScreenMode }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.AspectRatio, null, tint = Color(0xFFB5BAC6), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (fillScreenMode) "铺满" else "适应", style = MaterialTheme.typography.labelMedium, color = Color.White)
                        }
                    }
                }

                if (qualities.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF14161D))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.HighQuality, null, tint = accentColor, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("清晰度", style = MaterialTheme.typography.titleSmall, color = Color.White)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                qualities.forEach { q ->
                                    MvHudChip(q, q == selectedQuality, accentColor) { selectedQuality = q }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Speed, null, tint = accentColor, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("倍速", style = MaterialTheme.typography.titleSmall, color = Color.White)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { spd ->
                                    MvHudChip("${spd}x", playbackSpeed == spd, accentColor) {
                                        playbackSpeed = spd
                                        exoPlayer.playbackParameters = PlaybackParameters(spd, 1.0f)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF14161D))
                        .clickable {
                            app.playerController.playSong(song)
                            onSwitchToAudio()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = accentColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("切回无损听歌 / 沉浸歌词", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Text("支持粤语粤拼/谐音注音、英文纯词与单句学歌", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8E95A5))
                    }
                }
            }
        }
    }
}

@Composable
private fun MvHudChip(
    label: String,
    active: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) accentColor else Color.White.copy(alpha = 0.1f))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Color.White else Color(0xFFD0D4DC),
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}
