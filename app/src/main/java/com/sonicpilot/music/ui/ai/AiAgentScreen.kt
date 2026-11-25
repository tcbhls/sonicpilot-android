package com.sonicpilot.music.ui.ai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicpilot.music.SonicPilotApp
import com.sonicpilot.music.data.model.ChatMessage
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.ui.components.RichMarkdownText
import com.sonicpilot.music.ui.components.SongRowItem
import com.sonicpilot.music.ui.player.PlayerViewMode
import com.sonicpilot.music.ui.theme.SonicColors
import kotlinx.coroutines.launch

/**
 * 极简高级「AI 管家」页：
 * - 去繁就简：空态仅展示 3 张精炼意图卡片，对话时工具执行日志自动收纳为可展开单行胶囊
 * - 无廉价渐变气泡，专注清晰可读性与直接交互
 */
@Composable
fun AiAgentScreen(
    app: SonicPilotApp,
    initialPrompt: String?,
    onInitialPromptConsumed: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlayerWithMode: (PlayerViewMode) -> Unit,
    onWatchMv: (Song) -> Unit,
    onShowToast: (String) -> Unit
) {
    val messages by app.aiAgent.messages.collectAsState()
    val isThinking by app.aiAgent.isThinking.collectAsState()
    val thinkingStage by app.aiAgent.thinkingStage.collectAsState()
    val aiConfig by app.settings.aiConfig.collectAsState()
    val localStatus by app.localLlm.status.collectAsState()
    val playbackState by app.playerController.state.collectAsState()
    val favoriteKeys by app.repository.favoriteKeys.collectAsState()
    val playlists by app.repository.playlists.collectAsState()

    val keyboardController = LocalSoftwareKeyboardController.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 使用应用级 submitUserMessage 提交，确保 onInitialPromptConsumed 触发重组或用户切 Tab 时绝不取消 AI 请求！
    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank()) {
            app.aiAgent.submitUserMessage(initialPrompt)
            onInitialPromptConsumed()
        }
    }

    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
            val latestSongs = messages.lastOrNull()?.recommendedSongs.orEmpty()
            if (latestSongs.isNotEmpty()) {
                launch {
                    app.channelManager.prewarmSongs(latestSongs, app.settings.preferredQuality.value, maxCount = 2)
                }
            }
        }
    }

    val engineSubtitle = remember(aiConfig, localStatus) {
        when {
            aiConfig.cloudApiKey.isNotBlank() && aiConfig.engineMode.name != "LOCAL_ONLY" ->
                "● ${aiConfig.cloudModel}"
            localStatus.isModelLoaded ->
                "● 本地 ${localStatus.loadedModelName}"
            else ->
                "● 端侧即时引擎可用 · 点此配云端/离线模型"
        }
    }

    val hasRealConversation = messages.any { it.role == "user" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SonicColors.BgDeep)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        // 极简顶栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.clickable { onOpenSettings() }) {
                Text("AI 管家", style = MaterialTheme.typography.headlineMedium, color = SonicColors.TextPrimary)
                Text(engineSubtitle, style = MaterialTheme.typography.labelSmall, color = SonicColors.Accent)
            }
            Row {
                if (hasRealConversation) {
                    IconButton(onClick = {
                        app.aiAgent.clearHistory()
                        onShowToast("已开启新对话")
                    }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.DeleteSweep, "清空", tint = SonicColors.TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Tune, "AI 设置", tint = SonicColors.TextPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (!hasRealConversation) {
            // 高留白极简欢迎引导态（3 张焦点任务卡）
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = SonicColors.Accent, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    "一句话掌管整个音乐 App",
                    style = MaterialTheme.typography.headlineSmall,
                    color = SonicColors.TextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "搜全球热歌、按歌词寻曲、控音质建歌单、评判你的唱功——仅限本 App 内安全执行。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonicColors.TextSecondary
                )
                Spacer(Modifier.height(24.dp))

                val starterCards = if (favoriteKeys.size >= 10) {
                    listOf(
                        "按品味定制高音质原唱歌单" to "推荐 4 首像《体面》那样好听的华语失恋治愈女声，并直接从第一首开播",
                        "随机播放我收藏里的粤语歌" to "把我收藏里的粤语歌随机播放，并挑几首经典给我介绍一下",
                        "基础音乐操控一句话搞定" to "把陈奕迅的《富士山下》加到下一首播放，并帮我设一个 30 分钟后的睡眠定时",
                        "把收藏里的说唱一键建歌单" to "把我收藏里的说唱歌曲整理成一个「我的说唱精选」歌单并开始播放"
                    )
                } else {
                    listOf(
                        "按品味定制高音质原唱歌单" to "推荐 4 首像《体面》那样好听的华语失恋治愈女声，并直接从第一首开播",
                        "来点全网热歌与抖音爆款" to "推荐 6 首最近最火的高音质华语与抖音热歌，直接开始播放第一首",
                        "基础音乐操控一句话搞定" to "把陈奕迅的《富士山下》加到下一首播放，并帮我设一个 30 分钟后的睡眠定时",
                        "看周杰伦《晴天》高清 MV" to "我想看周杰伦《晴天》的 MV"
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    starterCards.forEach { (title, prompt) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(SonicColors.SurfaceCard)
                                .clickable { app.aiAgent.submitUserMessage(prompt) }
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(prompt, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary, maxLines = 1)
                        }
                    }
                }
            }
        } else {
            val visibleMessages = remember(messages) {
                messages.filterNot { it.engineBadge == "音枢管家就绪" }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(visibleMessages, key = { _, it -> it.id }) { idx, msg ->
                    val isLatestAssistant = !isThinking && idx == visibleMessages.lastIndex && msg.role != "user"
                    MinimalChatItem(
                        message = msg,
                        isLatestAssistant = isLatestAssistant,
                        app = app,
                        currentPlayingKey = playbackState.currentSong?.uniqueKey,
                        isAudioPlaying = playbackState.isPlaying,
                        favoriteKeys = favoriteKeys,
                        playlists = playlists,
                        onQuickFollowUp = { prompt -> app.aiAgent.submitUserMessage(prompt) },
                        onOpenPlayerWithMode = onOpenPlayerWithMode,
                        onWatchMv = onWatchMv,
                        onShowToast = onShowToast
                    )
                }
                if (isThinking) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(SonicColors.SurfaceCard)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                ThinkingPulseDots(color = SonicColors.Accent)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = thinkingStage.ifBlank { "正在思考..." },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SonicColors.TextSecondary
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SonicColors.RoseFavorite.copy(alpha = 0.14f))
                                    .clickable { app.aiAgent.cancelCurrentRequest() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("停止", style = MaterialTheme.typography.labelSmall, color = SonicColors.RoseFavorite, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        // 极简底部输入胶囊
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SonicColors.SurfaceCard)
                .padding(start = 8.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("想听什么，或让我帮你做什么？", color = SonicColors.TextMuted) },
                maxLines = 3,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = SonicColors.TextPrimary,
                    unfocusedTextColor = SonicColors.TextPrimary,
                    cursorColor = SonicColors.Accent
                )
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isThinking -> SonicColors.RoseFavorite
                            input.isNotBlank() -> SonicColors.Accent
                            else -> SonicColors.SurfaceElevated
                        }
                    )
                    .clickable(enabled = isThinking || input.isNotBlank()) {
                        if (isThinking) {
                            app.aiAgent.cancelCurrentRequest()
                        } else {
                            val text = input.trim()
                            input = ""
                            keyboardController?.hide()
                            app.aiAgent.submitUserMessage(text)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isThinking) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                    contentDescription = if (isThinking) "停止" else "发送",
                    tint = if (isThinking || input.isNotBlank()) Color.White else SonicColors.TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ThinkingPulseDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "aiDots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, delayMillis = i * 160),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun MinimalChatItem(
    message: ChatMessage,
    isLatestAssistant: Boolean,
    app: SonicPilotApp,
    currentPlayingKey: String?,
    isAudioPlaying: Boolean,
    favoriteKeys: Set<String>,
    playlists: List<com.sonicpilot.music.data.model.Playlist>,
    onQuickFollowUp: (String) -> Unit,
    onOpenPlayerWithMode: (PlayerViewMode) -> Unit,
    onWatchMv: (Song) -> Unit,
    onShowToast: (String) -> Unit
) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
    var expandTools by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(SonicColors.Accent)
                    .padding(horizontal = 15.dp, vertical = 11.dp)
            ) {
                Text(message.content, style = MaterialTheme.typography.bodyLarge, color = SonicColors.OnAccent)
            }
        } else {
            // 可折叠的极简工具审计胶囊（默认收起一行，不抢正文视觉）
            if (message.toolLogs.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SonicColors.SurfaceCard)
                        .clickable { expandTools = !expandTools }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = SonicColors.Accent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = if (message.toolLogs.size == 1) {
                                "${message.toolLogs.first().displayTitle} · ${message.toolLogs.first().resultSummary}"
                            } else {
                                "已自动执行 ${message.toolLogs.size} 项 App 操作"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = SonicColors.TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (expandTools) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            null,
                            tint = SonicColors.TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (expandTools) {
                        Spacer(Modifier.height(6.dp))
                        message.toolLogs.forEach { log ->
                            Text(
                                "• ${log.displayTitle}：${log.resultSummary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SonicColors.TextMuted,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // 杂志级 Markdown 富文本排版（彻底消除裸露 ** / ### 符号）
            RichMarkdownText(
                markdown = message.content,
                textColor = SonicColors.TextPrimary,
                secondaryColor = SonicColors.TextSecondary,
                accentColor = SonicColors.Accent,
                codeBgColor = SonicColors.SurfaceElevated
            )

            // 附带可交互歌曲卡
            if (message.recommendedSongs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SonicColors.SurfaceCard)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    message.recommendedSongs.forEach { song ->
                        SongRowItem(
                            song = song,
                            isPlayingThis = currentPlayingKey == song.uniqueKey,
                            isAudioPlaying = isAudioPlaying,
                            isFavorite = favoriteKeys.contains(song.uniqueKey),
                            playlists = playlists,
                            onPlay = { app.playerController.playSong(song, newQueue = message.recommendedSongs) },
                            onPlayNext = {
                                app.playerController.playNext(song)
                                onShowToast("已设为下一首播放")
                            },
                            onToggleFavorite = {
                                val f = app.repository.toggleFavorite(song)
                                onShowToast(if (f) "已收藏" else "已取消收藏")
                            },
                            onDownload = {
                                app.downloader.enqueueDownload(song)
                                onShowToast("开始无损下载：《${song.title}》")
                            },
                            onAddToPlaylist = { pl ->
                                app.repository.addSongToPlaylist(pl.id, song)
                                onShowToast("已加入「${pl.name}」")
                            },
                            onLearnSong = {
                                app.playerController.playSong(song, newQueue = message.recommendedSongs)
                                onOpenPlayerWithMode(PlayerViewMode.LEARN_STUDIO)
                            },
                            onWatchMv = { onWatchMv(song) }
                        )
                    }
                }
            }

            // 极简底部元信息栏（引擎徽章 + 一键复制回复）
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = message.engineBadge.ifBlank { "音枢 AI" },
                    style = MaterialTheme.typography.labelSmall,
                    color = SonicColors.TextMuted
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(message.content))
                            onShowToast("已复制回复内容")
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.ContentCopy, "复制", tint = SonicColors.TextMuted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextMuted)
                }
            }

            // 最新回复下方展示智能情境追问胶囊（单指一键继续操控）
            if (isLatestAssistant) {
                val followUps = if (message.recommendedSongs.isNotEmpty()) {
                    listOf(
                        "一键全收藏" to "把刚才推荐的这几首歌全部收藏起来",
                        "换一批同风格" to "保持刚才的品味和风格，再换一批我没收藏过的新歌并开播",
                        "存为新歌单" to "把刚才推荐的这几首歌全部收藏并加到歌单「AI 精选」"
                    )
                } else {
                    listOf(
                        "打开沉浸歌词" to "帮我打开全屏沉浸歌词",
                        "30分钟后定时关" to "30分钟后自动停止播放"
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(followUps) { (label, prompt) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(SonicColors.SurfaceCard)
                                .border(0.7.dp, SonicColors.Hairline, RoundedCornerShape(16.dp))
                                .clickable { onQuickFollowUp(prompt) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextPrimary, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
