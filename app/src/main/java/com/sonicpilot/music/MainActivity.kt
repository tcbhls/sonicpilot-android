package com.sonicpilot.music

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sonicpilot.music.data.model.ChartCategory
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.ui.ai.AiAgentScreen
import com.sonicpilot.music.ui.components.MiniPlayerBar
import com.sonicpilot.music.ui.discover.DiscoverScreen
import com.sonicpilot.music.ui.library.LibraryScreen
import com.sonicpilot.music.ui.mv.MvPlayerOverlay
import com.sonicpilot.music.ui.onboarding.OnboardingQuestOverlay
import com.sonicpilot.music.ui.onboarding.SONIC_QUEST_STEPS
import com.sonicpilot.music.ui.player.FullPlayerOverlay
import com.sonicpilot.music.ui.player.PlayerViewMode
import com.sonicpilot.music.ui.settings.HubSettingsScreen
import com.sonicpilot.music.ui.singing.SingingCoachScreen
import com.sonicpilot.music.ui.theme.SonicColors
import com.sonicpilot.music.ui.theme.SonicPilotTheme
import com.sonicpilot.music.ui.theme.resolveIsDarkTheme
import kotlinx.coroutines.launch

private enum class MainTab(val route: String, val label: String, val icon: ImageVector) {
    DISCOVER("discover", "发现", Icons.Rounded.Explore),
    LIBRARY("library", "曲库", Icons.Rounded.LibraryMusic),
    AI("ai", "AI 管家", Icons.Rounded.AutoAwesome),
    SINGING("singing", "声乐棚", Icons.Rounded.Mic)
}

class MainActivity : ComponentActivity() {

    private var hasRecordPermissionState = mutableStateOf(false)

    // Activity 级常驻侧滑返回回调：兼容 Android 14/15/16 及国内各大 ROM，100% 先回到 App 主界面绝不误退桌面！
    private var unifiedBackHandler: () -> Unit = { moveTaskToBack(true) }

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermissionState.value = granted
        if (!granted) {
            Toast.makeText(this, "需要麦克风权限才能在声乐棚进行实时音准评测", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private var onGgufPickedCallback: ((Uri, String) -> Unit)? = null

    private val ggufPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = queryFileName(uri) ?: "local_model.gguf"
            onGgufPickedCallback?.invoke(uri, name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                unifiedBackHandler()
            }
        })

        hasRecordPermissionState.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val app = application as SonicPilotApp

        setContent {
            val themeMode by app.settings.themeMode.collectAsState()
            val accentStyle by app.settings.accentStyle.collectAsState()
            val isDark = resolveIsDarkTheme(themeMode)
            var forceDarkSystemBars by remember { mutableStateOf(false) }
            val effectiveDarkBars = isDark || forceDarkSystemBars

            LaunchedEffect(effectiveDarkBars) {
                if (effectiveDarkBars) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    )
                }
            }

            SonicPilotTheme(themeMode = themeMode, accentStyle = accentStyle) {
                SonicPilotMainScaffold(
                    app = app,
                    hasAudioPermission = hasRecordPermissionState.value,
                    onRequestAudioPermission = { audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onPickGgufModel = { callback ->
                        onGgufPickedCallback = callback
                        ggufPickerLauncher.launch(arrayOf("*/*"))
                    },
                    onForceDarkBarsChanged = { forceDarkSystemBars = it },
                    onBindBackHandler = { handler -> unifiedBackHandler = handler },
                    onMinimizeToBackground = { moveTaskToBack(true) },
                    onShowToast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }

    private fun queryFileName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

@Composable
private fun SonicPilotMainScaffold(
    app: SonicPilotApp,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onPickGgufModel: ((Uri, String) -> Unit) -> Unit,
    onForceDarkBarsChanged: (Boolean) -> Unit,
    onBindBackHandler: (() -> Unit) -> Unit,
    onMinimizeToBackground: () -> Unit,
    onShowToast: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val onboardingDone by app.settings.onboardingCompleted.collectAsState()

    var currentTab by remember { mutableStateOf(MainTab.DISCOVER) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var fullPlayerInitialMode by remember { mutableStateOf(PlayerViewMode.VINYL) }
    var showSettingsHub by remember { mutableStateOf(false) }
    var activeMvSong by remember { mutableStateOf<Song?>(null) }
    var mvFullscreen by remember { mutableStateOf(false) }
    var queuedAiPrompt by remember { mutableStateOf<String?>(null) }
    var subPageBackInterceptor by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    var showTutorial by remember(onboardingDone) { mutableStateOf(!onboardingDone) }
    var tutorialStep by remember { mutableIntStateOf(0) }

    val playbackState by app.playerController.state.collectAsState()
    val pendingAiNav by app.aiAgent.pendingNavTab.collectAsState()
    val pendingAiMv by app.aiAgent.pendingMvSong.collectAsState()
    val pendingAiPlayerMode by app.aiAgent.pendingPlayerMode.collectAsState()

    // 单一确定性返回优先级链：MV横屏全屏 → MV竖屏 → 教程 → 黑胶/歌词全屏 → 设置 → 搜索/歌单子页 → 回发现主界面 → 双击退后台
    SideEffect {
        onBindBackHandler {
            when {
                activeMvSong != null && mvFullscreen -> mvFullscreen = false
                activeMvSong != null -> {
                    mvFullscreen = false
                    activeMvSong = null
                }
                showTutorial -> {
                    showTutorial = false
                    app.settings.setOnboardingCompleted(true)
                }
                showFullPlayer -> showFullPlayer = false
                showSettingsHub -> showSettingsHub = false
                subPageBackInterceptor?.invoke() == true -> Unit
                currentTab != MainTab.DISCOVER -> currentTab = MainTab.DISCOVER
                else -> {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < 2000L) {
                        onMinimizeToBackground()
                    } else {
                        lastBackPressTime = now
                        onShowToast("再滑一次返回桌面（后台仍可继续播放）")
                    }
                }
            }
        }
    }

    LaunchedEffect(showFullPlayer, activeMvSong, playbackState.currentSong) {
        onForceDarkBarsChanged((showFullPlayer && playbackState.currentSong != null) || activeMvSong != null)
    }

    LaunchedEffect(pendingAiNav) {
        val target = app.aiAgent.consumePendingNavTab() ?: return@LaunchedEffect
        MainTab.entries.firstOrNull { it.route == target }?.let { currentTab = it }
    }

    LaunchedEffect(pendingAiMv) {
        val mvSong = app.aiAgent.consumePendingMvSong() ?: return@LaunchedEffect
        mvFullscreen = false
        activeMvSong = mvSong
    }

    LaunchedEffect(pendingAiPlayerMode) {
        val mode = app.aiAgent.consumePendingPlayerMode() ?: return@LaunchedEffect
        val targetMode = when (mode.lowercase()) {
            "lyrics", "immersive_lyrics" -> PlayerViewMode.IMMERSIVE_LYRICS
            "learn", "learn_studio" -> PlayerViewMode.LEARN_STUDIO
            else -> PlayerViewMode.VINYL
        }
        if (app.playerController.state.value.currentSong != null) {
            fullPlayerInitialMode = targetMode
            showFullPlayer = true
        }
    }

    LaunchedEffect(showTutorial, tutorialStep) {
        if (showTutorial) {
            val targetRoute = SONIC_QUEST_STEPS.getOrNull(tutorialStep)?.targetTabRoute
            MainTab.entries.firstOrNull { it.route == targetRoute }?.let { currentTab = it }
        }
    }

    fun openPlayer(mode: PlayerViewMode) {
        fullPlayerInitialMode = mode
        showFullPlayer = true
    }

    fun replayTutorial() {
        tutorialStep = 0
        showTutorial = true
    }

    Box(modifier = Modifier.fillMaxSize().background(SonicColors.BgDeep)) {
        Scaffold(
            containerColor = SonicColors.BgDeep,
            bottomBar = {
                Column(modifier = Modifier.background(SonicColors.BgDeep)) {
                    if (playbackState.currentSong != null) {
                        MiniPlayerBar(
                            playbackState = playbackState,
                            progressFlow = app.playerController.progress,
                            onOpenFullPlayer = { openPlayer(PlayerViewMode.VINYL) },
                            onOpenLyricsDirect = { openPlayer(PlayerViewMode.IMMERSIVE_LYRICS) },
                            onTogglePlay = { app.playerController.togglePlayPause() },
                            onNext = { app.playerController.nextTrack() },
                            onPrevious = { app.playerController.previousTrack() }
                        )
                    }
                    HorizontalDivider(color = SonicColors.Hairline.copy(alpha = 0.7f), thickness = 0.5.dp)
                    NavigationBar(containerColor = SonicColors.SurfaceCard, tonalElevation = 0.dp) {
                        MainTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = SonicColors.Accent,
                                    selectedTextColor = SonicColors.Accent,
                                    indicatorColor = Color.Transparent,
                                    unselectedIconColor = SonicColors.TextMuted,
                                    unselectedTextColor = SonicColors.TextMuted
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Crossfade(
                    targetState = currentTab,
                    animationSpec = tween(170),
                    label = "mainTabFade"
                ) { tab ->
                    when (tab) {
                        MainTab.DISCOVER -> DiscoverScreen(
                            app = app,
                            onOpenSettings = { showSettingsHub = true },
                            onReplayTutorial = { replayTutorial() },
                            onOpenPlayerWithMode = { openPlayer(it) },
                            onWatchMv = { song -> activeMvSong = song },
                            onJumpToAi = { prompt ->
                                queuedAiPrompt = prompt
                                currentTab = MainTab.AI
                            },
                            onRegisterBackInterceptor = { subPageBackInterceptor = it },
                            onShowToast = onShowToast
                        )
                        MainTab.LIBRARY -> LibraryScreen(
                            app = app,
                            onOpenSettings = { showSettingsHub = true },
                            onOpenPlayerWithMode = { openPlayer(it) },
                            onWatchMv = { song -> activeMvSong = song },
                            onJumpToAi = { prompt ->
                                queuedAiPrompt = prompt
                                currentTab = MainTab.AI
                            },
                            onRegisterBackInterceptor = { subPageBackInterceptor = it },
                            onShowToast = onShowToast
                        )
                        MainTab.AI -> AiAgentScreen(
                            app = app,
                            initialPrompt = queuedAiPrompt,
                            onInitialPromptConsumed = { queuedAiPrompt = null },
                            onOpenSettings = { showSettingsHub = true },
                            onOpenPlayerWithMode = { openPlayer(it) },
                            onWatchMv = { song -> activeMvSong = song },
                            onShowToast = onShowToast
                        )
                        MainTab.SINGING -> SingingCoachScreen(
                            app = app,
                            hasAudioPermission = hasAudioPermission,
                            onRequestAudioPermission = onRequestAudioPermission,
                            onOpenSettings = { showSettingsHub = true },
                            onAskAiToCritique = { ev ->
                                currentTab = MainTab.AI
                                app.aiAgent.submitSingingCritique(ev)
                            },
                            onShowToast = onShowToast
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showSettingsHub,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 12 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { it / 12 }
        ) {
            HubSettingsScreen(
                app = app,
                onBack = { showSettingsHub = false },
                onReplayTutorial = { replayTutorial() },
                onPickLocalGgufFile = {
                    onPickGgufModel { uri, name ->
                        scope.launch {
                            val res = app.localLlm.importModelFromUri(uri, name)
                            res.onSuccess { onShowToast("已导入并加载本地模型：$name") }
                                .onFailure { onShowToast("导入失败：${it.message}") }
                        }
                    }
                },
                onShowToast = onShowToast
            )
        }

        AnimatedVisibility(
            visible = showFullPlayer && playbackState.currentSong != null,
            enter = slideInVertically(tween(260)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160))
        ) {
            FullPlayerOverlay(
                app = app,
                initialMode = fullPlayerInitialMode,
                onDismiss = { showFullPlayer = false },
                onSingThisSong = { currentTab = MainTab.SINGING },
                onWatchMv = { song ->
                    mvFullscreen = false
                    activeMvSong = song
                },
                onAskAiAboutSong = { prompt ->
                    queuedAiPrompt = prompt
                    currentTab = MainTab.AI
                },
                onShowToast = onShowToast
            )
        }

        val mvTarget = activeMvSong
        if (mvTarget != null) {
            MvPlayerOverlay(
                app = app,
                song = mvTarget,
                isFullscreen = mvFullscreen,
                onToggleFullscreen = { mvFullscreen = it },
                onDismiss = {
                    mvFullscreen = false
                    activeMvSong = null
                },
                onSwitchToAudio = {
                    mvFullscreen = false
                    activeMvSong = null
                    openPlayer(PlayerViewMode.IMMERSIVE_LYRICS)
                }
            )
        }

        if (showTutorial) {
            OnboardingQuestOverlay(
                stepIndex = tutorialStep,
                onPrev = { tutorialStep = (tutorialStep - 1).coerceAtLeast(0) },
                onNextOrFinish = {
                    if (tutorialStep < SONIC_QUEST_STEPS.lastIndex) {
                        tutorialStep += 1
                    } else {
                        showTutorial = false
                        app.settings.setOnboardingCompleted(true)
                        onShowToast("新手教程已通关，开始听歌吧！")
                    }
                },
                onSkip = {
                    showTutorial = false
                    app.settings.setOnboardingCompleted(true)
                },
                onTriggerQuestAction = { step ->
                    when (step) {
                        0 -> {
                            scope.launch {
                                val songs = app.repository.loadChart(ChartCategory.HOT_CN, limit = 10)
                                if (songs.isNotEmpty()) {
                                    app.playerController.playQueue(songs, 0)
                                    onShowToast("正在为你播放：《${songs.first().title}》")
                                }
                                tutorialStep = 1
                            }
                        }
                        1 -> {
                            showTutorial = false
                            app.settings.setOnboardingCompleted(true)
                            if (app.playerController.state.value.currentSong != null) {
                                openPlayer(PlayerViewMode.LEARN_STUDIO)
                            } else {
                                scope.launch {
                                    val songs = app.repository.loadChart(ChartCategory.HOT_CN, limit = 5)
                                    if (songs.isNotEmpty()) {
                                        app.playerController.playQueue(songs, 0)
                                        openPlayer(PlayerViewMode.LEARN_STUDIO)
                                    }
                                }
                            }
                        }
                        2 -> {
                            showTutorial = false
                            app.settings.setOnboardingCompleted(true)
                            currentTab = MainTab.AI
                            queuedAiPrompt = "推荐 5 首抖音热歌并直接播放第一首"
                        }
                    }
                }
            )
        }
    }
}
