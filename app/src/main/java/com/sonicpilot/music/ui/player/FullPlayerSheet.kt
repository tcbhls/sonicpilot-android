package com.sonicpilot.music.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sonicpilot.music.SonicPilotApp
import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.CantonesePhoneticMode
import com.sonicpilot.music.data.model.LyricDisplayMode
import com.sonicpilot.music.data.model.LyricLine
import com.sonicpilot.music.data.model.PlayMode
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.lyrics.CantoneseJyutpingEngine
import com.sonicpilot.music.player.PlaybackProgress
import com.sonicpilot.music.player.PlaybackUiState
import com.sonicpilot.music.ui.components.AnimatedEqBars
import com.sonicpilot.music.ui.components.QualityBadge
import com.sonicpilot.music.ui.components.SongArtwork
import com.sonicpilot.music.ui.components.formatDuration
import com.sonicpilot.music.ui.components.optimizeArtworkUrl
import com.sonicpilot.music.ui.theme.AmbientStagePalette
import com.sonicpilot.music.ui.theme.ArtworkColorExtractor
import com.sonicpilot.music.ui.theme.SonicColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class PlayerViewMode(val label: String) {
    VINYL("黑胶"),
    IMMERSIVE_LYRICS("沉浸词"),
    LEARN_STUDIO("学歌室")
}

/**
 * 旗舰全屏播放舞台（v2.0 深度重构）：
 * - 动态专辑封面流光氛围背景（平滑呼吸过渡）
 * - 真实金属黑胶唱针 + 细腻唱片纹路 + 左右滑切歌/下滑收起 + 触感反馈
 * - 进度条与歌词行下标彻底隔离订阅，根治每秒 4.5 次全页重组
 * - 沉浸歌词顶部/底部柔和羽化渐隐 + 手动滑动不抢焦点 + 「回到当前句」悬浮胶囊
 * - 暗色磨砂 ModalBottomSheet 队列 / 音质 / 定时关闭抽屉
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerOverlay(
    app: SonicPilotApp,
    initialMode: PlayerViewMode = PlayerViewMode.VINYL,
    onDismiss: () -> Unit,
    onSingThisSong: () -> Unit,
    onWatchMv: (Song) -> Unit,
    onAskAiAboutSong: (String) -> Unit,
    onShowToast: (String) -> Unit
) {
    val state by app.playerController.state.collectAsState()
    val favoriteKeys by app.repository.favoriteKeys.collectAsState()
    val preferredQuality by app.settings.preferredQuality.collectAsState()
    val lyricDisplayMode by app.settings.lyricDisplayMode.collectAsState()
    val autoCantoneseJyutping by app.settings.autoCantoneseJyutping.collectAsState()
    val defaultPhoneticMode by app.settings.cantonesePhoneticMode.collectAsState()
    val jyutpingLoaded by app.jyutpingEngine.isLoaded.collectAsState()
    val song = state.currentSong ?: return

    val hostContext = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    var pureFocusMode by remember { mutableStateOf(false) }
    var lyricSizeTier by remember { mutableIntStateOf(1) }
    var showQualitySheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }

    val lyricLines = state.lyrics.lines
    val hasTranslations = remember(lyricLines) { lyricLines.any { it.translation.isNotBlank() } }
    val hasChineseLyrics = remember(lyricLines) { lyricLines.any { CantoneseJyutpingEngine.containsCjk(it.text) } }
    val hasOfficialRomaji = remember(lyricLines) { lyricLines.any { it.romaji.isNotBlank() } }
    val isEnglishSong = remember(lyricLines) { CantoneseJyutpingEngine.isMostlyEnglish(lyricLines) }
    val autoDetectedCantonese = remember(song.uniqueKey, lyricLines) {
        CantoneseJyutpingEngine.isLikelyCantoneseSong(song.title, song.artist, lyricLines)
    }

    // 仅在歌词行真正切换时触发单次更新，彻底消灭 220ms 高频全屏重组！
    var activeLyricIndex by remember(song.uniqueKey, lyricLines) { mutableIntStateOf(0) }
    LaunchedEffect(song.uniqueKey, lyricLines) {
        if (lyricLines.isEmpty()) {
            activeLyricIndex = 0
            return@LaunchedEffect
        }
        app.playerController.progress
            .map { prog -> lyricLines.indexOfLast { it.timeMs <= prog.positionMs + 250L }.coerceAtLeast(0) }
            .distinctUntilChanged()
            .collect { idx -> activeLyricIndex = idx }
    }

    // 提取专辑封面主视觉动态流光色板（带内存 LRU 缓存，切歌丝滑渐变）
    val bgCover = remember(song.coverUrl) { optimizeArtworkUrl(song.coverUrl, 640) }
    var ambientPalette by remember { mutableStateOf(AmbientStagePalette.Default) }
    LaunchedEffect(bgCover) {
        ambientPalette = if (bgCover.startsWith("http")) {
            ArtworkColorExtractor.extract(hostContext, bgCover)
        } else {
            AmbientStagePalette.Default
        }
    }
    val animPrimaryGlow by animateColorAsState(ambientPalette.primaryGlow, tween(850), label = "glow1")
    val animSecondaryGlow by animateColorAsState(ambientPalette.secondaryGlow, tween(950), label = "glow2")
    val animDeepBase by animateColorAsState(ambientPalette.deepBase, tween(850), label = "glowBase")

    // 每首歌独立可切：粤拼 / QQ 谐音 / 关；粤语歌自动开启用户偏好注音模式
    var manualPhoneticMode by remember(song.uniqueKey) { mutableStateOf<CantonesePhoneticMode?>(null) }
    val activePhoneticMode: CantonesePhoneticMode = remember(
        manualPhoneticMode, autoCantoneseJyutping, autoDetectedCantonese, defaultPhoneticMode, hasChineseLyrics
    ) {
        when {
            !hasChineseLyrics -> CantonesePhoneticMode.OFF
            manualPhoneticMode != null -> manualPhoneticMode!!
            autoCantoneseJyutping && autoDetectedCantonese ->
                if (defaultPhoneticMode == CantonesePhoneticMode.OFF) CantonesePhoneticMode.JYUTPING else defaultPhoneticMode
            else -> CantonesePhoneticMode.OFF
        }
    }

    LaunchedEffect(activePhoneticMode) {
        if (activePhoneticMode != CantonesePhoneticMode.OFF && !jyutpingLoaded) {
            app.jyutpingEngine.ensureLoaded()
        }
    }

    fun cyclePhoneticMode() {
        val next = activePhoneticMode.next()
        manualPhoneticMode = next
        if (next != CantonesePhoneticMode.OFF) {
            app.settings.setCantonesePhoneticMode(next)
        }
        val tip = when (next) {
            CantonesePhoneticMode.JYUTPING -> "粤语注音：标准带调粤拼（每字下标）"
            CantonesePhoneticMode.HOMOPHONE -> "粤语注音：QQ 风格普通话谐音跟唱"
            CantonesePhoneticMode.OFF -> "已关闭粤语注音"
        }
        onShowToast(tip)
    }

    // 沉浸歌词智能防抢滚：手指手动滑歌词时暂停自动回滚 4 秒，并浮出「回到当前句」胶囊
    val lyricListState = rememberLazyListState()
    var userPausedAutoScroll by remember(song.uniqueKey) { mutableStateOf(false) }
    LaunchedEffect(lyricListState.isScrollInProgress) {
        if (lyricListState.isScrollInProgress) {
            userPausedAutoScroll = true
        } else if (userPausedAutoScroll) {
            delay(4200L)
            userPausedAutoScroll = false
        }
    }
    LaunchedEffect(activeLyricIndex, mode, userPausedAutoScroll) {
        if (!userPausedAutoScroll &&
            (mode == PlayerViewMode.IMMERSIVE_LYRICS || mode == PlayerViewMode.LEARN_STUDIO) &&
            lyricLines.isNotEmpty()
        ) {
            lyricListState.animateScrollToItem((activeLyricIndex - 2).coerceAtLeast(0))
        }
    }

    val isFav = favoriteKeys.contains(song.uniqueKey)
    val heartScale = remember { Animatable(1f) }
    val accentColor = SonicColors.Accent

    val lyricModeChipLabel = remember(lyricDisplayMode, isEnglishSong) {
        when (lyricDisplayMode) {
            LyricDisplayMode.BILINGUAL -> "双语"
            LyricDisplayMode.ORIGINAL_ONLY -> if (isEnglishSong) "仅英文" else "仅原文"
            LyricDisplayMode.TRANSLATION_ONLY -> if (isEnglishSong) "仅中文" else "仅译文"
        }
    }

    // 沉浸歌词 / 学歌室期间保持屏幕常亮，避免练唱中途黑屏
    DisposableEffect(mode, hostContext) {
        var ctx: Context? = hostContext
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val act = ctx as? Activity
        if (mode != PlayerViewMode.VINYL) {
            act?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 丝滑旋转黑胶（暂停优雅定格，续播无缝衔接）+ 物理黑胶唱针角度联动
    val vinylRotation = remember { Animatable(0f) }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            while (true) {
                val target = vinylRotation.value + 360f
                vinylRotation.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 22_000, easing = LinearEasing)
                )
                vinylRotation.snapTo(vinylRotation.value % 360f)
            }
        }
    }
    val tonearmAngle by animateFloatAsState(
        targetValue = if (state.isPlaying) 0f else -25f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
        label = "tonearm"
    )

    val stageCard = Color(0xFF171923)
    val stageText = Color(0xFFF6F7FA)
    val stageSubText = Color(0xFFB0B7C6)

    Box(modifier = Modifier.fillMaxSize().background(animDeepBase)) {
        // 1. 柔和专辑虚化底纹（Android 12+ 原生硬件高斯模糊，旧机型平滑叠加径向流光）
        if (bgCover.startsWith("http")) {
            val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.fillMaxSize().blur(52.dp).alpha(0.32f)
            } else {
                Modifier.fillMaxSize().alpha(0.18f)
            }
            AsyncImage(
                model = bgCover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = blurMod
            )
        }
        // 2. 双极光动态专辑流光舞台（Apple Music / QQ 音乐沉浸氛围光）
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(animPrimaryGlow.copy(alpha = 0.78f), Color.Transparent),
                    center = Offset(size.width * 0.22f, size.height * 0.24f),
                    radius = size.maxDimension * 0.78f
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(animSecondaryGlow.copy(alpha = 0.65f), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.72f),
                    radius = size.maxDimension * 0.72f
                )
            )
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0x6608090D), Color(0x450B0D14), Color(0xE608090E))
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            AnimatedVisibility(visible = !pureFocusMode, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "收起", tint = stageText, modifier = Modifier.size(28.dp))
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.09f))
                            .border(0.6.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(20.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerViewMode.entries.forEach { m ->
                            val selected = mode == m
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(17.dp))
                                    .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                                    .clickable {
                                        mode = m
                                        if (m != PlayerViewMode.IMMERSIVE_LYRICS) pureFocusMode = false
                                    }
                                    .padding(horizontal = 11.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = m.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) stageText else stageSubText,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(17.dp))
                                .background(accentColor.copy(alpha = 0.24f))
                                .clickable { onWatchMv(song) }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.OndemandVideo, null, tint = accentColor, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("MV", style = MaterialTheme.typography.labelSmall, color = accentColor, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (mode == PlayerViewMode.IMMERSIVE_LYRICS) {
                        IconButton(onClick = { pureFocusMode = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Fullscreen, "纯净全屏", tint = stageSubText)
                        }
                    } else {
                        IconButton(onClick = { showQueueSheet = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, "队列", tint = stageSubText)
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (mode) {
                    PlayerViewMode.VINYL -> {
                        var dragX by remember { mutableFloatStateOf(0f) }
                        var dragY by remember { mutableFloatStateOf(0f) }
                        val animatedOffsetX by animateFloatAsState(
                            targetValue = (dragX * 0.28f).coerceIn(-46f, 46f),
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "vinylSwipeX"
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(song.uniqueKey) {
                                    detectDragGestures(
                                        onDragStart = { dragX = 0f; dragY = 0f },
                                        onDragCancel = { dragX = 0f; dragY = 0f },
                                        onDragEnd = {
                                            when {
                                                dragY > 135f && abs(dragY) > abs(dragX) -> onDismiss()
                                                dragX < -95f && abs(dragX) > abs(dragY) -> {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    app.playerController.nextTrack()
                                                }
                                                dragX > 95f && abs(dragX) > abs(dragY) -> {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    app.playerController.previousTrack()
                                                }
                                            }
                                            dragX = 0f
                                            dragY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragX += dragAmount.x
                                            if (dragAmount.y > 0f || dragY > 0f) dragY += dragAmount.y
                                        }
                                    )
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // 唱片 + 金属质感黑胶唱针组合舞台
                            Box(
                                modifier = Modifier.size(308.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // 柔和专辑流光外环光晕
                                Box(
                                    modifier = Modifier
                                        .size(274.dp)
                                        .offset(x = animatedOffsetX.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.radialGradient(
                                                listOf(animPrimaryGlow.copy(alpha = 0.55f), Color.Transparent)
                                            )
                                        )
                                )
                                // 旋转黑胶唱片（带真实同心音轨纹路与高光）
                                Box(
                                    modifier = Modifier
                                        .size(262.dp)
                                        .offset(x = animatedOffsetX.dp)
                                        .rotate(vinylRotation.value)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.radialGradient(
                                                listOf(Color(0xFF1F212A), Color(0xFF0C0D12), Color(0xFF191B24), Color(0xFF090A0F))
                                            )
                                        )
                                        .border(1.8.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                                        .clickable { mode = PlayerViewMode.IMMERSIVE_LYRICS },
                                    contentAlignment = Alignment.Center
                                ) {
                                    // 精细黑胶音轨纹路 + 金属反光扇面
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val center = Offset(size.width / 2f, size.height / 2f)
                                        val maxR = size.minDimension / 2f
                                        for (step in 1..6) {
                                            val r = maxR * (0.71f + step * 0.043f)
                                            drawCircle(
                                                color = Color.White.copy(alpha = if (step % 2 == 0) 0.06f else 0.03f),
                                                radius = r,
                                                center = center,
                                                style = Stroke(width = 1.1f)
                                            )
                                        }
                                    }
                                    SongArtwork(
                                        coverUrl = song.coverUrl,
                                        size = 176.dp,
                                        cornerRadius = 88.dp,
                                        highRes = true
                                    )
                                    // 唱片中央金属孔环
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xCC0D0E14))
                                            .border(1.5.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                                    )
                                }

                                // 顶部右侧金属唱针（播放落针 0°，暂停抬针 -25°）
                                VinylTonearm(
                                    angleDegrees = tonearmAngle,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(end = 18.dp, top = 2.dp)
                                        .size(width = 96.dp, height = 112.dp)
                                )
                            }

                            Spacer(Modifier.height(14.dp))
                            val curLine = lyricLines.getOrNull(activeLyricIndex)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { mode = PlayerViewMode.IMMERSIVE_LYRICS }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                if (curLine == null) {
                                    Text(
                                        text = if (state.isBuffering && state.loadingStage.isNotBlank()) state.loadingStage else "轻点黑胶进入沉浸歌词 · 左右滑切歌",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = stageText.copy(alpha = 0.78f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else {
                                    val showOriginal = lyricDisplayMode != LyricDisplayMode.TRANSLATION_ONLY || curLine.translation.isBlank()
                                    val showTrans = curLine.translation.isNotBlank() && lyricDisplayMode != LyricDisplayMode.ORIGINAL_ONLY
                                    if (showOriginal) {
                                        if (activePhoneticMode != CantonesePhoneticMode.OFF && jyutpingLoaded && CantoneseJyutpingEngine.containsCjk(curLine.text)) {
                                            JyutpingRubyLine(
                                                text = curLine.text,
                                                engine = app.jyutpingEngine,
                                                phoneticMode = activePhoneticMode,
                                                jyutpingLoaded = jyutpingLoaded,
                                                textColor = stageText,
                                                jyutpingColor = accentColor,
                                                charFontSp = 17f,
                                                jyutpingFontSp = 10.5f,
                                                centered = true,
                                                bold = true
                                            )
                                        } else {
                                            Text(
                                                text = curLine.text,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = stageText.copy(alpha = 0.94f),
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (showTrans) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            text = curLine.translation,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (showOriginal) stageSubText else stageText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    PlayerViewMode.IMMERSIVE_LYRICS -> {
                        ImmersiveLyricsView(
                            songTitle = song.title,
                            songArtist = song.artist,
                            lines = lyricLines,
                            activeIndex = activeLyricIndex,
                            listState = lyricListState,
                            userPausedAutoScroll = userPausedAutoScroll,
                            accentColor = accentColor,
                            lyricDisplayMode = lyricDisplayMode,
                            lyricModeChipLabel = lyricModeChipLabel,
                            hasTranslations = hasTranslations,
                            hasChineseLyrics = hasChineseLyrics,
                            hasOfficialRomaji = hasOfficialRomaji,
                            phoneticMode = activePhoneticMode,
                            jyutpingLoaded = jyutpingLoaded,
                            jyutpingEngine = app.jyutpingEngine,
                            sizeTier = lyricSizeTier,
                            pureFocusMode = pureFocusMode,
                            onTogglePureFocus = { pureFocusMode = !pureFocusMode },
                            onResumeAutoScroll = {
                                userPausedAutoScroll = false
                                scope.launch {
                                    lyricListState.animateScrollToItem((activeLyricIndex - 2).coerceAtLeast(0))
                                }
                            },
                            onCycleLyricMode = {
                                val next = lyricDisplayMode.next()
                                app.settings.setLyricDisplayMode(next)
                                val toastLabel = when (next) {
                                    LyricDisplayMode.BILINGUAL -> "双语对照歌词"
                                    LyricDisplayMode.ORIGINAL_ONLY -> if (isEnglishSong) "仅显示英文原词" else "仅显示原文歌词"
                                    LyricDisplayMode.TRANSLATION_ONLY -> if (isEnglishSong) "仅显示中文翻译" else "仅显示译文歌词"
                                }
                                onShowToast("已切换：$toastLabel")
                            },
                            onCyclePhoneticMode = { cyclePhoneticMode() },
                            onCycleFontSize = { lyricSizeTier = (lyricSizeTier + 1) % 3 },
                            onJumpToLearn = {
                                pureFocusMode = false
                                mode = PlayerViewMode.LEARN_STUDIO
                            },
                            onSeekLine = { line ->
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                userPausedAutoScroll = false
                                app.playerController.seekTo(line.timeMs)
                                app.playerController.play()
                            }
                        )
                    }

                    PlayerViewMode.LEARN_STUDIO -> {
                        LearnSongStudioView(
                            lines = lyricLines,
                            activeIndex = activeLyricIndex,
                            loopLineIndex = state.loopLineIndex,
                            playbackSpeed = state.playbackSpeed,
                            accentColor = accentColor,
                            stageCard = stageCard.copy(alpha = 0.85f),
                            listState = lyricListState,
                            lyricDisplayMode = lyricDisplayMode,
                            lyricModeChipLabel = lyricModeChipLabel,
                            hasTranslations = hasTranslations,
                            hasChineseLyrics = hasChineseLyrics,
                            phoneticMode = activePhoneticMode,
                            jyutpingLoaded = jyutpingLoaded,
                            jyutpingEngine = app.jyutpingEngine,
                            onCycleLyricMode = {
                                val next = lyricDisplayMode.next()
                                app.settings.setLyricDisplayMode(next)
                                onShowToast("歌词模式：${next.label}")
                            },
                            onCyclePhoneticMode = { cyclePhoneticMode() },
                            onStepLine = { delta ->
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                app.playerController.stepLyricLine(delta)
                            },
                            onToggleLoopLine = { idx ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                app.playerController.toggleLineLoop(idx)
                                val activeNow = app.playerController.state.value.loopLineIndex
                                onShowToast(if (activeNow != null) "已开启单句循环复读" else "已取消单句循环")
                            },
                            onSetSpeed = { spd ->
                                app.playerController.setPlaybackSpeed(spd)
                                onShowToast("原调变速：${spd}x")
                            },
                            onAskAiTeachLine = { line ->
                                onDismiss()
                                val lineHint = line?.text?.takeIf { it.isNotBlank() }?.let { "尤其是「$it」这句" } ?: ""
                                onAskAiAboutSong("请当我的声乐教练，教我怎么唱《${song.title}》-${song.artist}，$lineHint，告诉我换气点、咬字和情感处理技巧。")
                            },
                            onGoSingingCoach = {
                                onDismiss()
                                onSingThisSong()
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(visible = !pureFocusMode, enter = fadeIn(), exit = fadeOut()) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    if (mode == PlayerViewMode.VINYL) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.headlineSmall, color = stageText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = stageSubText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (state.qualityLabel.isNotBlank()) {
                                        QualityBadge(text = "${state.qualityLabel} ▾", modifier = Modifier.clickable { showQualitySheet = true })
                                    }
                                }
                            }
                            IconButton(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val f = app.repository.toggleFavorite(song)
                                scope.launch {
                                    heartScale.snapTo(0.75f)
                                    heartScale.animateTo(1.28f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium))
                                    heartScale.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow))
                                }
                                onShowToast(if (f) "已加入我喜欢" else "已移出我喜欢")
                            }) {
                                Icon(
                                    imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "收藏",
                                    tint = if (isFav) SonicColors.RoseFavorite else stageSubText,
                                    modifier = Modifier.scale(heartScale.value)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    // 独立高频隔离进度条（绝不触发黑胶/流光/按钮重组）
                    IsolatedFullProgressBar(
                        progressFlow = app.playerController.progress,
                        state = state,
                        accentColor = accentColor,
                        stageText = stageText,
                        stageSubText = stageSubText,
                        onSeek = { app.playerController.seekTo(it) }
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val m = app.playerController.cyclePlayMode()
                            onShowToast(m.label)
                        }) {
                            Icon(
                                imageVector = when (state.playMode) {
                                    PlayMode.SEQUENCE -> Icons.Rounded.Repeat
                                    PlayMode.SINGLE -> Icons.Rounded.RepeatOne
                                    PlayMode.SHUFFLE -> Icons.Rounded.Shuffle
                                },
                                contentDescription = "播放模式",
                                tint = stageSubText,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            app.playerController.previousTrack()
                        }) {
                            Icon(Icons.Rounded.SkipPrevious, "上一首", tint = stageText, modifier = Modifier.size(34.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (state.errorMessage != null) {
                                        app.playerController.playSong(song)
                                    } else {
                                        app.playerController.togglePlayPause()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.isBuffering && !state.isPlaying) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.6.dp,
                                    modifier = Modifier.size(26.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = "播放/暂停",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            app.playerController.nextTrack()
                        }) {
                            Icon(Icons.Rounded.SkipNext, "下一首", tint = stageText, modifier = Modifier.size(34.dp))
                        }
                        IconButton(onClick = {
                            app.downloader.enqueueDownload(song, preferredQuality)
                            onShowToast("加入无损下载：《${song.title}》")
                        }) {
                            Icon(Icons.Rounded.Download, "无损下载", tint = stageSubText, modifier = Modifier.size(22.dp))
                        }
                    }

                    if (mode == PlayerViewMode.VINYL) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StageActionText(Icons.Rounded.OndemandVideo, "看 MV") {
                                onWatchMv(song)
                            }
                            StageActionText(Icons.Rounded.Bedtime, if (state.sleepTimerRemainingMinutes > 0) "${state.sleepTimerRemainingMinutes}分关" else "定时关") {
                                showSleepSheet = true
                            }
                            StageActionText(Icons.Rounded.Mic, "跟唱") {
                                onDismiss()
                                onSingThisSong()
                            }
                            StageActionText(Icons.Rounded.AutoAwesome, "问 AI") {
                                onDismiss()
                                onAskAiAboutSong("给我赏析《${song.title}》-${song.artist}，并推荐 4 首同氛围曲目")
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== 暗色磨砂抽屉 1：睡眠定时 ====================
    if (showSleepSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSleepSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = stageCard
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("睡眠定时关闭", style = MaterialTheme.typography.titleLarge, color = stageText)
                Spacer(Modifier.height(4.dp))
                Text("到点自动温和暂停播放，安心入睡", style = MaterialTheme.typography.labelSmall, color = stageSubText)
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to "不开启（持续播放）",
                        15 to "15 分钟后暂停",
                        30 to "30 分钟后暂停（助眠推荐）",
                        45 to "45 分钟后暂停",
                        60 to "60 分钟后暂停"
                    ).forEach { (mins, text) ->
                        val active = state.sleepTimerRemainingMinutes == mins
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) accentColor.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    app.playerController.setSleepTimerMinutes(mins)
                                    showSleepSheet = false
                                    onShowToast(if (mins == 0) "已取消定时关闭" else "将在 $mins 分钟后自动暂停")
                                }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text, color = if (active) accentColor else stageText, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            if (active) Text("当前", style = MaterialTheme.typography.labelSmall, color = accentColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ==================== 暗色磨砂抽屉 2：音质热切 ====================
    if (showQualitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showQualitySheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = stageCard
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("切换音质", style = MaterialTheme.typography.titleLarge, color = stageText)
                Spacer(Modifier.height(4.dp))
                Text("立即重新解析当前曲目并同步为默认首选音质", style = MaterialTheme.typography.labelSmall, color = stageSubText)
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioQuality.entries.forEach { q ->
                        val active = q == preferredQuality
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) accentColor.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    app.playerController.switchQualityAndReload(q)
                                    showQualitySheet = false
                                    onShowToast("已切至 ${q.displayName}")
                                }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(q.displayName, color = if (active) accentColor else stageText, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            QualityBadge(q.badge)
                        }
                    }
                }
            }
        }
    }

    // ==================== 暗色磨砂抽屉 3：旗舰播放队列 ====================
    if (showQueueSheet) {
        val queueListState = rememberLazyListState()
        LaunchedEffect(showQueueSheet) {
            if (state.currentIndex in state.queue.indices) {
                queueListState.scrollToItem((state.currentIndex - 2).coerceAtLeast(0))
            }
        }
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = stageCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp)
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val curDisplay = if (state.currentIndex >= 0) "${state.currentIndex + 1}/" else ""
                    Column {
                        Text("播放队列 ($curDisplay${state.queue.size})", color = stageText, style = MaterialTheme.typography.titleLarge)
                        Text(state.playMode.label, color = stageSubText, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    val m = app.playerController.cyclePlayMode()
                                    onShowToast(m.label)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(state.playMode.label, style = MaterialTheme.typography.labelSmall, color = stageText)
                        }
                        if (state.queue.size > 2) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(accentColor.copy(alpha = 0.2f))
                                    .clickable {
                                        app.playerController.playShuffled(state.queue)
                                        onShowToast("已随机打乱队列")
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("随机打乱", style = MaterialTheme.typography.labelSmall, color = accentColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    state = queueListState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(state.queue, key = { idx, item -> "${idx}_${item.uniqueKey}" }) { idx, item ->
                        val active = idx == state.currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) accentColor.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable {
                                    app.playerController.playQueue(state.queue, idx)
                                    showQueueSheet = false
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SongArtwork(coverUrl = item.coverUrl, size = 40.dp, cornerRadius = 8.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (active) {
                                        AnimatedEqBars(isPlaying = state.isPlaying, color = accentColor)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = item.title,
                                        color = if (active) accentColor else stageText,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = item.artist,
                                    color = stageSubText,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (state.queue.size > 1) {
                                IconButton(onClick = { app.playerController.removeFromQueue(idx) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Close, "移除", tint = stageSubText.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 独立隔离高频进度条（每 220ms 仅重绘本区域，彻底消除全屏播放器重组卡顿）
 */
@Composable
private fun IsolatedFullProgressBar(
    progressFlow: StateFlow<PlaybackProgress>,
    state: PlaybackUiState,
    accentColor: Color,
    stageText: Color,
    stageSubText: Color,
    onSeek: (Long) -> Unit
) {
    val progress by progressFlow.collectAsState()
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderDragPosMs by remember { mutableFloatStateOf(0f) }

    val duration = progress.durationMs.coerceAtLeast(1L)
    val shownPositionMs = if (isDraggingSlider) sliderDragPosMs.toLong() else progress.positionMs

    Slider(
        value = shownPositionMs.toFloat().coerceIn(0f, duration.toFloat()),
        onValueChange = {
            isDraggingSlider = true
            sliderDragPosMs = it
        },
        onValueChangeFinished = {
            onSeek(sliderDragPosMs.toLong())
            isDraggingSlider = false
        },
        valueRange = 0f..duration.toFloat(),
        colors = SliderDefaults.colors(
            thumbColor = stageText,
            activeTrackColor = accentColor,
            inactiveTrackColor = Color.White.copy(alpha = 0.16f)
        )
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatDuration(shownPositionMs), style = MaterialTheme.typography.labelSmall, color = stageSubText)
        Text(
            text = when {
                state.errorMessage != null -> state.errorMessage!!
                state.isBuffering && state.loadingStage.isNotBlank() -> state.loadingStage
                else -> buildString {
                    if (state.playbackSpeed != 1.0f) append("${state.playbackSpeed}x · ")
                    if (state.sleepTimerRemainingMinutes > 0) append("定时 ${state.sleepTimerRemainingMinutes}m · ")
                    append(state.activeChannelName)
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                state.errorMessage != null -> SonicColors.RoseFavorite
                state.isBuffering || state.playbackSpeed != 1.0f || state.sleepTimerRemainingMinutes > 0 -> accentColor
                else -> stageSubText
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(formatDuration(progress.durationMs), style = MaterialTheme.typography.labelSmall, color = stageSubText)
    }
}

/**
 * 精致金属质感黑胶唱针（播放平滑落针 0°，暂停抬针 -25°）
 */
@Composable
private fun VinylTonearm(
    angleDegrees: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.graphicsLayer {
            rotationZ = angleDegrees
            transformOrigin = TransformOrigin(0.76f, 0.14f)
        }
    ) {
        val pivot = Offset(size.width * 0.76f, size.height * 0.14f)
        val elbow = Offset(size.width * 0.50f, size.height * 0.62f)
        val head = Offset(size.width * 0.25f, size.height * 0.86f)

        // 唱臂金属主杆（带柔和投影）
        drawLine(
            color = Color.Black.copy(alpha = 0.45f),
            start = pivot + Offset(2f, 4f),
            end = elbow + Offset(2f, 4f),
            strokeWidth = 7f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFFD6DAE3),
            start = pivot,
            end = elbow,
            strokeWidth = 5.5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFFC0C6D2),
            start = elbow,
            end = head,
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
        // 唱头拾音盒
        drawLine(
            color = Color(0xFF2B2E3A),
            start = elbow + (head - elbow) * 0.62f,
            end = head,
            strokeWidth = 11f,
            cap = StrokeCap.Round
        )
        drawCircle(color = Color(0xFFEFF2F7), radius = 3.2f, center = head)

        // 金属转轴底座
        drawCircle(color = Color(0xFF2B2E38), radius = 15f, center = pivot)
        drawCircle(color = Color(0xFFDCE0EA), radius = 11f, center = pivot)
        drawCircle(color = Color(0xFF4B5060), radius = 4.5f, center = pivot)
    }
}

/**
 * 粤语逐字下标音标（Ruby Below）渲染组件：
 * - 粤拼模式：每个汉字正下方显示带声调粤拼
 * - QQ 谐音模式：每个汉字正下方显示普通话近似谐音
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JyutpingRubyLine(
    text: String,
    engine: CantoneseJyutpingEngine,
    phoneticMode: CantonesePhoneticMode,
    jyutpingLoaded: Boolean,
    textColor: Color,
    jyutpingColor: Color,
    charFontSp: Float,
    jyutpingFontSp: Float,
    centered: Boolean = false,
    bold: Boolean = false
) {
    val tokens = remember(text, jyutpingLoaded) { engine.annotateLine(text) }
    FlowRow(
        horizontalArrangement = if (centered) Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = if (centered) Modifier.fillMaxWidth() else Modifier
    ) {
        tokens.forEach { token ->
            if (token.text == " ") {
                Spacer(Modifier.width(7.dp))
            } else {
                val subLabel = when (phoneticMode) {
                    CantonesePhoneticMode.JYUTPING -> token.jyutping
                    CantonesePhoneticMode.HOMOPHONE -> token.homophone.ifBlank { token.jyutping }
                    CantonesePhoneticMode.OFF -> ""
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = token.text,
                        color = textColor,
                        fontSize = charFontSp.sp,
                        lineHeight = (charFontSp * 1.15f).sp,
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
                    )
                    if (subLabel.isNotBlank()) {
                        Text(
                            text = subLabel,
                            color = jyutpingColor,
                            fontSize = (if (phoneticMode == CantonesePhoneticMode.HOMOPHONE) jyutpingFontSp + 1.5f else jyutpingFontSp).sp,
                            lineHeight = (jyutpingFontSp * 1.25f).sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricToggleChip(
    label: String,
    active: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) accentColor.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.09f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) accentColor else Color(0xFFB5BAC6),
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ImmersiveLyricsView(
    songTitle: String,
    songArtist: String,
    lines: List<LyricLine>,
    activeIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    userPausedAutoScroll: Boolean,
    accentColor: Color,
    lyricDisplayMode: LyricDisplayMode,
    lyricModeChipLabel: String,
    hasTranslations: Boolean,
    hasChineseLyrics: Boolean,
    hasOfficialRomaji: Boolean,
    phoneticMode: CantonesePhoneticMode,
    jyutpingLoaded: Boolean,
    jyutpingEngine: CantoneseJyutpingEngine,
    sizeTier: Int,
    pureFocusMode: Boolean,
    onTogglePureFocus: () -> Unit,
    onResumeAutoScroll: () -> Unit,
    onCycleLyricMode: () -> Unit,
    onCyclePhoneticMode: () -> Unit,
    onCycleFontSize: () -> Unit,
    onJumpToLearn: () -> Unit,
    onSeekLine: (LyricLine) -> Unit
) {
    val activeFontSp = when (sizeTier) {
        0 -> 20f
        1 -> 24f
        else -> 29f
    }
    val inactiveFontSp = activeFontSp * 0.72f
    val textBright = Color(0xFFF6F7FA)
    val textDim = Color(0xFFA7AEBE)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (pureFocusMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(songTitle, style = MaterialTheme.typography.titleMedium, color = textBright.copy(alpha = 0.80f), maxLines = 1)
                        Text(songArtist, style = MaterialTheme.typography.labelSmall, color = textDim, maxLines = 1)
                    }
                    IconButton(onClick = onTogglePureFocus) {
                        Icon(Icons.Rounded.FullscreenExit, "退出纯净", tint = textDim)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(songTitle, style = MaterialTheme.typography.titleLarge, color = textBright, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(songArtist, style = MaterialTheme.typography.labelMedium, color = textDim, maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (hasChineseLyrics) {
                            LyricToggleChip(
                                label = when (phoneticMode) {
                                    CantonesePhoneticMode.JYUTPING -> "音·粤拼"
                                    CantonesePhoneticMode.HOMOPHONE -> "音·谐音"
                                    CantonesePhoneticMode.OFF -> "音"
                                },
                                active = phoneticMode != CantonesePhoneticMode.OFF,
                                accentColor = accentColor,
                                onClick = onCyclePhoneticMode
                            )
                        }
                        if (hasTranslations) {
                            LyricToggleChip(
                                label = lyricModeChipLabel,
                                active = lyricDisplayMode != LyricDisplayMode.ORIGINAL_ONLY,
                                accentColor = accentColor,
                                onClick = onCycleLyricMode
                            )
                        }
                        IconButton(onClick = onCycleFontSize, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Rounded.FormatSize, "字号", tint = textDim, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (lines.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (pureFocusMode) onTogglePureFocus()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("正在加载双语歌词，或当前为纯音乐", color = textDim)
                }
            } else {
                // Apple Music / QQ 音乐同款顶部 + 底部柔光羽化渐隐遮罩
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.08f to Color.Black,
                                    0.86f to Color.Black,
                                    1.0f to Color.Transparent
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        },
                    contentPadding = PaddingValues(top = 42.dp, bottom = 150.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    itemsIndexed(lines) { idx, line ->
                        val isCurrent = idx == activeIndex
                        val targetAlpha by animateFloatAsState(if (isCurrent) 1f else 0.42f, tween(260), label = "lrcAlpha")
                        val targetColor by animateColorAsState(if (isCurrent) textBright else textDim, tween(260), label = "lrcColor")
                        val showOriginal = lyricDisplayMode != LyricDisplayMode.TRANSLATION_ONLY || line.translation.isBlank()
                        val showTrans = line.translation.isNotBlank() && lyricDisplayMode != LyricDisplayMode.ORIGINAL_ONLY

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSeekLine(line) }
                                .padding(vertical = 4.dp)
                                .alpha(targetAlpha)
                        ) {
                            if (showOriginal) {
                                if (phoneticMode != CantonesePhoneticMode.OFF && jyutpingLoaded && CantoneseJyutpingEngine.containsCjk(line.text)) {
                                    JyutpingRubyLine(
                                        text = line.text,
                                        engine = jyutpingEngine,
                                        phoneticMode = phoneticMode,
                                        jyutpingLoaded = jyutpingLoaded,
                                        textColor = targetColor,
                                        jyutpingColor = if (isCurrent) accentColor else textDim.copy(alpha = 0.85f),
                                        charFontSp = if (isCurrent) activeFontSp else inactiveFontSp,
                                        jyutpingFontSp = if (isCurrent) (activeFontSp * 0.48f).coerceIn(10.5f, 14f) else (inactiveFontSp * 0.54f).coerceIn(9.5f, 12f),
                                        bold = isCurrent
                                    )
                                } else {
                                    Text(
                                        text = line.text,
                                        color = targetColor,
                                        fontSize = (if (isCurrent) activeFontSp else inactiveFontSp).sp,
                                        lineHeight = (if (isCurrent) activeFontSp * 1.35f else inactiveFontSp * 1.35f).sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (!hasChineseLyrics && hasOfficialRomaji && line.romaji.isNotBlank() && lyricDisplayMode == LyricDisplayMode.BILINGUAL) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            text = line.romaji,
                                            color = if (isCurrent) accentColor.copy(alpha = 0.85f) else textDim.copy(alpha = 0.7f),
                                            fontSize = (if (isCurrent) 12.5f else 10.5f).sp
                                        )
                                    }
                                }
                            }
                            if (showTrans) {
                                if (showOriginal) Spacer(Modifier.height(5.dp))
                                val transFont = if (!showOriginal) {
                                    if (isCurrent) activeFontSp else inactiveFontSp
                                } else {
                                    if (isCurrent) activeFontSp * 0.58f else inactiveFontSp * 0.65f
                                }
                                Text(
                                    text = line.translation,
                                    color = if (!showOriginal) targetColor else if (isCurrent) accentColor else textDim,
                                    fontSize = transFont.sp,
                                    lineHeight = (transFont * 1.35f).sp,
                                    fontWeight = if (!showOriginal && isCurrent) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        // 手动滑动歌词时浮现「回到当前句」快捷胶囊（不和用户手指抢滚动权！）
        AnimatedVisibility(
            visible = userPausedAutoScroll && lines.isNotEmpty() && !pureFocusMode,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xD9161822))
                    .border(0.8.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    .clickable { onResumeAutoScroll() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.MyLocation, null, tint = accentColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("回到当前句", style = MaterialTheme.typography.labelSmall, color = textBright, fontWeight = FontWeight.Medium)
            }
        }

        // 右下角 QQ 音乐同款悬浮快捷「音 / 译 / 学歌」胶囊
        if (!pureFocusMode && lines.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xCC161822))
                    .border(0.8.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasChineseLyrics) {
                    LyricToggleChip(
                        label = when (phoneticMode) {
                            CantonesePhoneticMode.JYUTPING -> "粤拼 ✓"
                            CantonesePhoneticMode.HOMOPHONE -> "谐音 ✓"
                            CantonesePhoneticMode.OFF -> "粤语注音"
                        },
                        active = phoneticMode != CantonesePhoneticMode.OFF,
                        accentColor = accentColor,
                        onClick = onCyclePhoneticMode
                    )
                }
                if (hasTranslations) {
                    LyricToggleChip(
                        label = lyricModeChipLabel,
                        active = lyricDisplayMode != LyricDisplayMode.ORIGINAL_ONLY,
                        accentColor = accentColor,
                        onClick = onCycleLyricMode
                    )
                }
                LyricToggleChip("学歌", false, accentColor, onJumpToLearn)
            }
        }

        if (pureFocusMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x99161822))
                    .clickable { onTogglePureFocus() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("退出纯净歌词", style = MaterialTheme.typography.labelSmall, color = textDim)
            }
        }
    }
}

@Composable
private fun LearnSongStudioView(
    lines: List<LyricLine>,
    activeIndex: Int,
    loopLineIndex: Int?,
    playbackSpeed: Float,
    accentColor: Color,
    stageCard: Color,
    listState: androidx.compose.foundation.lazy.LazyListState,
    lyricDisplayMode: LyricDisplayMode,
    lyricModeChipLabel: String,
    hasTranslations: Boolean,
    hasChineseLyrics: Boolean,
    phoneticMode: CantonesePhoneticMode,
    jyutpingLoaded: Boolean,
    jyutpingEngine: CantoneseJyutpingEngine,
    onCycleLyricMode: () -> Unit,
    onCyclePhoneticMode: () -> Unit,
    onStepLine: (Int) -> Unit,
    onToggleLoopLine: (Int?) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onAskAiTeachLine: (LyricLine?) -> Unit,
    onGoSingingCoach: () -> Unit
) {
    val activeLine = lines.getOrNull(activeIndex)
    val isLoopingCurrent = loopLineIndex != null && loopLineIndex == activeIndex
    val textBright = Color(0xFFF6F7FA)
    val textDim = Color(0xFFA7AEBE)

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(stageCard)
                .border(0.6.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (loopLineIndex != null) "单句循环复读中 · 第 ${loopLineIndex + 1} 句" else "当前练唱句 · 点任意句锁句复读",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (loopLineIndex != null) accentColor else textDim,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasChineseLyrics) {
                        LyricToggleChip(
                            label = when (phoneticMode) {
                                CantonesePhoneticMode.JYUTPING -> "粤拼"
                                CantonesePhoneticMode.HOMOPHONE -> "谐音"
                                CantonesePhoneticMode.OFF -> "注音"
                            },
                            active = phoneticMode != CantonesePhoneticMode.OFF,
                            accentColor = accentColor,
                            onClick = onCyclePhoneticMode
                        )
                    }
                    if (hasTranslations) {
                        LyricToggleChip(lyricModeChipLabel, lyricDisplayMode != LyricDisplayMode.ORIGINAL_ONLY, accentColor, onCycleLyricMode)
                    }
                    if (loopLineIndex != null) {
                        Text(
                            "取消",
                            style = MaterialTheme.typography.labelSmall,
                            color = SonicColors.RoseFavorite,
                            modifier = Modifier.clickable { onToggleLoopLine(null) }.padding(start = 4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (activeLine == null) {
                Text(
                    text = "暂无逐行歌词，可直接使用原调慢放与 AI 教唱",
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = textBright
                )
            } else {
                val showOrig = lyricDisplayMode != LyricDisplayMode.TRANSLATION_ONLY || activeLine.translation.isBlank()
                val showTr = activeLine.translation.isNotBlank() && lyricDisplayMode != LyricDisplayMode.ORIGINAL_ONLY
                if (showOrig) {
                    if (phoneticMode != CantonesePhoneticMode.OFF && jyutpingLoaded && CantoneseJyutpingEngine.containsCjk(activeLine.text)) {
                        JyutpingRubyLine(
                            text = activeLine.text,
                            engine = jyutpingEngine,
                            phoneticMode = phoneticMode,
                            jyutpingLoaded = jyutpingLoaded,
                            textColor = textBright,
                            jyutpingColor = accentColor,
                            charFontSp = 20f,
                            jyutpingFontSp = 11.5f,
                            bold = true
                        )
                    } else {
                        Text(
                            text = activeLine.text,
                            fontSize = 20.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = textBright
                        )
                    }
                }
                if (showTr) {
                    Spacer(Modifier.height(5.dp))
                    Text(activeLine.translation, style = MaterialTheme.typography.bodyMedium, color = if (showOrig) accentColor else textBright)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StagePill("上一句", false, accentColor, Modifier.weight(1f)) { onStepLine(-1) }
                StagePill("重播", false, accentColor, Modifier.weight(0.9f)) { onStepLine(0) }
                StagePill(if (isLoopingCurrent) "循环中 ✓" else "单句循环", isLoopingCurrent, accentColor, Modifier.weight(1.2f)) { onToggleLoopLine(activeIndex) }
                StagePill("下一句", false, accentColor, Modifier.weight(1f)) { onStepLine(1) }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("原调慢放", style = MaterialTheme.typography.labelMedium, color = textDim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 0.75f, 0.85f, 1.0f, 1.25f).forEach { spd ->
                        val active = playbackSpeed == spd
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) accentColor else Color.White.copy(alpha = 0.08f))
                                .clickable { onSetSpeed(spd) }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Text("${spd}x", style = MaterialTheme.typography.labelSmall, color = if (active) Color.White else textDim, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onAskAiTeachLine(activeLine) }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = accentColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("AI 教唱这句", style = MaterialTheme.typography.labelMedium, color = textBright)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onGoSingingCoach() }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = textBright, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("跟唱测音准", style = MaterialTheme.typography.labelMedium, color = textBright)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(lines) { idx, line ->
                val isCurrent = idx == activeIndex
                val isLooped = idx == loopLineIndex
                val showOrig = lyricDisplayMode != LyricDisplayMode.TRANSLATION_ONLY || line.translation.isBlank()
                val showTr = line.translation.isNotBlank() && lyricDisplayMode != LyricDisplayMode.ORIGINAL_ONLY
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                isLooped -> accentColor.copy(alpha = 0.18f)
                                isCurrent -> Color.White.copy(alpha = 0.08f)
                                else -> Color.Transparent
                            }
                        )
                        .clickable { onToggleLoopLine(idx) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (showOrig) {
                            if (phoneticMode != CantonesePhoneticMode.OFF && jyutpingLoaded && CantoneseJyutpingEngine.containsCjk(line.text)) {
                                JyutpingRubyLine(
                                    text = line.text,
                                    engine = jyutpingEngine,
                                    phoneticMode = phoneticMode,
                                    jyutpingLoaded = jyutpingLoaded,
                                    textColor = if (isCurrent || isLooped) textBright else textDim,
                                    jyutpingColor = if (isCurrent || isLooped) accentColor else textDim.copy(alpha = 0.7f),
                                    charFontSp = 15.5f,
                                    jyutpingFontSp = 10f,
                                    bold = isCurrent || isLooped
                                )
                            } else {
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isCurrent || isLooped) textBright else textDim,
                                    fontWeight = if (isCurrent || isLooped) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                        if (showTr) {
                            Text(
                                line.translation,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!showOrig && (isCurrent || isLooped)) textBright else textDim.copy(alpha = 0.75f)
                            )
                        }
                    }
                    Icon(Icons.Rounded.Replay, "复读", tint = if (isLooped) accentColor else textDim.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun StagePill(label: String, active: Boolean, accentColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) accentColor else Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun StageActionText(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color(0xFFB0B7C6), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFFB0B7C6))
    }
}
