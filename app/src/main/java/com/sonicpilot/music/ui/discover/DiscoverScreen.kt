package com.sonicpilot.music.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicpilot.music.SonicPilotApp
import com.sonicpilot.music.ai.AppAgentToolRegistry
import com.sonicpilot.music.data.model.AppThemeMode
import com.sonicpilot.music.data.model.ChartCategory
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.data.model.SongClassifier
import com.sonicpilot.music.ui.components.QualityBadge
import com.sonicpilot.music.ui.components.ShimmerSongRowPlaceholder
import com.sonicpilot.music.ui.components.SongArtwork
import com.sonicpilot.music.ui.components.SongRowItem
import com.sonicpilot.music.ui.player.PlayerViewMode
import com.sonicpilot.music.ui.theme.SonicColors
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 对标网易云 & QQ音乐 打造的极速「发现」首页：
 * - 极简浅/深自适应美学 + 顶部一键日夜切换图标 + 新手教程 + 设置入口
 * - 毫秒级搜索（网易云直搜 ~600ms + 缓存秒出）+ 热搜词与本地搜索历史记录
 * - 4 枚精炼金刚区入口（每日热歌 / 抖音爆款 / 全球 Billboard / AI 猜你喜欢）
 * - 1 张主视觉 Hero 榜首精选卡 + 极简文字下划线榜单 + 零卡顿缓存列表
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    app: SonicPilotApp,
    onOpenSettings: () -> Unit,
    onReplayTutorial: () -> Unit,
    onOpenPlayerWithMode: (PlayerViewMode) -> Unit,
    onWatchMv: (Song) -> Unit,
    onJumpToAi: (String) -> Unit,
    onRegisterBackInterceptor: ((() -> Boolean)?) -> Unit,
    onShowToast: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val channels by app.channelManager.channels.collectAsState()
    val playbackState by app.playerController.state.collectAsState()
    val favorites by app.repository.favorites.collectAsState()
    val favoriteKeys by app.repository.favoriteKeys.collectAsState()
    val playlists by app.repository.playlists.collectAsState()
    val preferredQuality by app.settings.preferredQuality.collectAsState()
    val searchHistory by app.settings.searchHistory.collectAsState()
    val isDarkNow = SonicColors.isDark

    val greetingText = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> "早安 · 用好音乐开启今天"
            in 11..13 -> "午间小憩 · 无损聆听"
            in 14..17 -> "午后时光 · 发现新声"
            in 18..22 -> "夜晚好 · 沉浸每一首"
            else -> "夜深了 · 慢听片刻"
        }
    }

    var query by remember { mutableStateOf("") }
    var isSearchMode by remember { mutableStateOf(false) }
    var selectedChannelId by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<Song>>(emptyList()) }
    var hasSubmittedSearch by remember { mutableStateOf(false) }

    var selectedChart by remember { mutableStateOf(ChartCategory.HOT_CN) }
    var chartSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoadingChart by remember { mutableStateOf(false) }
    var chartReloadNonce by remember { mutableIntStateOf(0) }

    fun doSearch(keyword: String) {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        keyboardController?.hide()
        query = kw
        isSearchMode = true
        hasSubmittedSearch = true
        app.settings.recordSearchHistory(kw)
        scope.launch {
            isSearching = true
            val primaryFound = app.channelManager.searchSongs(
                query = kw,
                channelId = selectedChannelId,
                limit = 25,
                aggregateAll = false
            )
            // 防翻唱原唱优先排序：若主渠道前几首全是翻唱/假冒上传者（如周杰伦等腾讯独家艺人），自动补查 JOOX 合并排序！
            val combined = mutableListOf<Song>().apply { addAll(primaryFound) }
            if (selectedChannelId == null) {
                val topScore = primaryFound.take(3).maxOfOrNull {
                    AppAgentToolRegistry.scoreOriginalCandidate(it, kw, "", kw)
                } ?: Int.MIN_VALUE
                val hasJoox = channels.any { it.id == "gd_joox" && it.enabled }
                if (hasJoox && (primaryFound.isEmpty() || topScore < 45)) {
                    val jooxExtra = app.channelManager.searchSongs(query = kw, channelId = "gd_joox", limit = 10)
                    combined.addAll(jooxExtra)
                }
            }
            val ranked = AppAgentToolRegistry.rankOriginalsFirst(
                combined.distinctBy { it.uniqueKey },
                targetTitle = kw,
                userQuery = kw
            )
            searchResults = ranked
            isSearching = false
            // 搜索结果一出来立刻后台真并行预热前 3 首音频直链，用户点击播放几乎 0 秒秒开！
            if (ranked.isNotEmpty()) {
                launch {
                    app.channelManager.prewarmSongs(ranked, preferredQuality, maxCount = 3)
                }
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(isSearchMode) {
        if (isSearchMode) {
            onRegisterBackInterceptor {
                isSearchMode = false
                hasSubmittedSearch = false
                query = ""
                searchResults = emptyList()
                true
            }
        } else {
            onRegisterBackInterceptor(null)
        }
        onDispose { onRegisterBackInterceptor(null) }
    }

    LaunchedEffect(selectedChart, chartReloadNonce) {
        isLoadingChart = chartSongs.isEmpty() || chartReloadNonce > 0
        val loaded = app.repository.loadChart(selectedChart, limit = 30, forceRefresh = chartReloadNonce > 0)
        chartSongs = loaded
        isLoadingChart = false
        // 后台真并行预热榜首前 3 首音频直链，点击播放秒开
        if (loaded.isNotEmpty()) {
            launch {
                app.channelManager.prewarmSongs(loaded, preferredQuality, maxCount = 3)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SonicColors.BgDeep)
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        // 顶栏（极简标题 + 一键日夜主题切换 + 新手教程 + 设置）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("发现", style = MaterialTheme.typography.headlineMedium, color = SonicColors.TextPrimary)
                    QualityBadge(preferredQuality.badge)
                }
                Text(greetingText, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        val next = if (isDarkNow) AppThemeMode.LIGHT else AppThemeMode.DARK
                        app.settings.setThemeMode(next)
                        onShowToast("已切换为${next.label}")
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = if (isDarkNow) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        contentDescription = "切换浅色/深色",
                        tint = SonicColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onReplayTutorial, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.HelpOutline, "新手教程", tint = SonicColors.TextSecondary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Tune, "设置与渠道", tint = SonicColors.TextPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 搜索栏（支持一键进入热搜/历史态）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    isSearchMode = true
                    if (it.isBlank()) {
                        hasSubmittedSearch = false
                        searchResults = emptyList()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .onFocusChanged { if (it.isFocused) isSearchMode = true },
                placeholder = { Text("搜全网歌曲、抖音热歌、歌手...", color = SonicColors.TextMuted) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = SonicColors.TextSecondary, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = {
                            query = ""
                            hasSubmittedSearch = false
                            searchResults = emptyList()
                        }) {
                            Icon(Icons.Rounded.Close, "清空", tint = SonicColors.TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SonicColors.SurfaceCard,
                    unfocusedContainerColor = SonicColors.SurfaceCard,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = SonicColors.TextPrimary,
                    unfocusedTextColor = SonicColors.TextPrimary,
                    cursorColor = SonicColors.Accent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch(query) })
            )
            if (isSearchMode) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "取消",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonicColors.TextSecondary,
                    modifier = Modifier.clickable {
                        isSearchMode = false
                        hasSubmittedSearch = false
                        query = ""
                        searchResults = emptyList()
                    }.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (isSearchMode) {
            // 渠道快速筛选
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    MinimalPill("主渠道极速", selectedChannelId == null) {
                        selectedChannelId = null
                        if (hasSubmittedSearch) doSearch(query)
                    }
                }
                items(channels.filter { it.enabled }) { ch ->
                    MinimalPill(ch.name.substringBefore(" ·"), selectedChannelId == ch.id) {
                        selectedChannelId = ch.id
                        if (hasSubmittedSearch) doSearch(query)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (!hasSubmittedSearch) {
                // 对标网易云/QQ音乐：搜索历史 + 热搜风向标
                LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
                    if (searchHistory.isNotEmpty()) {
                        item {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("最近搜索", style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary)
                                    IconButton(onClick = { app.settings.clearSearchHistory() }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Rounded.DeleteOutline, "清空历史", tint = SonicColors.TextMuted, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    searchHistory.forEach { term ->
                                        SearchTagChip(term) { doSearch(term) }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column {
                            Text("全网大家都在搜", style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary)
                            Spacer(Modifier.height(10.dp))
                            val hotKeywords = listOf(
                                "抖音热歌", "周杰伦", "Taylor Swift", "陈奕迅", "YOASOBI",
                                "林俊杰", "The Weeknd", "毛不易", "Billie Eilish", "邓紫棋", "薛之谦", "Coldplay"
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                hotKeywords.forEach { term ->
                                    SearchTagChip(term) { doSearch(term) }
                                }
                            }
                        }
                    }
                }
            } else if (isSearching) {
                Box(modifier = Modifier.fillMaxSize().padding(top = 6.dp)) {
                    ShimmerSongRowPlaceholder(count = 7)
                }
            } else if (searchResults.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("未搜到「$query」", style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "交给 AI 按歌词或全球曲库帮你找 →",
                            color = SonicColors.Accent,
                            modifier = Modifier.clickable { onJumpToAi("帮我联网找歌：$query") }
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 130.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(searchResults, key = { it.uniqueKey }, contentType = { "song" }) { song ->
                        SongRowItem(
                            song = song,
                            isPlayingThis = playbackState.currentSong?.uniqueKey == song.uniqueKey,
                            isAudioPlaying = playbackState.isPlaying,
                            isFavorite = favoriteKeys.contains(song.uniqueKey),
                            playlists = playlists,
                            onPlay = { app.playerController.playSong(song, newQueue = searchResults) },
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
                                app.playerController.playSong(song, newQueue = searchResults)
                                onOpenPlayerWithMode(PlayerViewMode.LEARN_STUDIO)
                            },
                            onWatchMv = { onWatchMv(song) },
                            onAskAi = { onJumpToAi("赏析《${song.title}》-${song.artist}并推荐同风格歌曲") }
                        )
                    }
                }
            }
        } else {
            // 主发现瀑布流（丝滑零卡顿）
            LazyColumn(
                contentPadding = PaddingValues(bottom = 130.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 对标网易云/QQ音乐：4 枚专属色彩柔光金刚区
                item(key = "quick_grid") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickShortcutTile(Icons.Rounded.Whatshot, "每日热歌", Color(0xFFE63946), Modifier.weight(1f)) {
                            selectedChart = ChartCategory.HOT_CN
                            scope.launch {
                                val list = app.repository.loadChart(ChartCategory.HOT_CN, 30)
                                if (list.isNotEmpty()) app.playerController.playQueue(list.shuffled(), 0)
                            }
                        }
                        QuickShortcutTile(Icons.Rounded.LocalFireDepartment, "抖音热歌", Color(0xFFF97316), Modifier.weight(1f)) {
                            selectedChart = ChartCategory.DOUYIN
                        }
                        QuickShortcutTile(Icons.Rounded.Public, "全球流行", Color(0xFF2563EB), Modifier.weight(1f)) {
                            selectedChart = ChartCategory.BILLBOARD
                        }
                        QuickShortcutTile(Icons.Rounded.AutoAwesome, "AI 猜你喜欢", Color(0xFF9333EA), Modifier.weight(1f)) {
                            onJumpToAi("结合我的收藏品味，为我策展 5 首我还没收藏过的高音质好歌并直接放第一首")
                        }
                    }
                }

                // 你的私人收藏漫游直通车（首页一键随心播自己收藏的粤语 / 说唱 / 欧美 / 全部随机）
                if (favorites.size >= 10) {
                    item(key = "personal_roam") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("你的私人漫游 · ${favorites.size} 首收藏", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val roamSpecs = listOf(
                                    Triple("全部随机漫游", "从 ${favorites.size} 首精选里随心播", Pair(Color(0xFFE63946), Color(0xFFFB7185))),
                                    Triple("粤语经典电台", "港乐 · 自动逐字粤拼注音", Pair(Color(0xFF0EA5E9), Color(0xFF38BDF8))),
                                    Triple("欧美流行 / R&B", "中英双语 & 仅英文模式", Pair(Color(0xFF7C3AED), Color(0xFFA78BFA))),
                                    Triple("说唱燃炸专场", "高能律动 · 一键洗耳", Pair(Color(0xFF059669), Color(0xFF34D399)))
                                )
                                itemsIndexed(roamSpecs) { idx, (title, sub, colors) ->
                                    Column(
                                        modifier = Modifier
                                            .width(156.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(colors.first.copy(alpha = 0.16f), colors.second.copy(alpha = 0.06f))
                                                )
                                            )
                                            .clickable {
                                                val pool = when (idx) {
                                                    1 -> favorites.filter { SongClassifier.resolveLanguage(it) == "粤语" }
                                                    2 -> favorites.filter { SongClassifier.resolveLanguage(it) == "英语" }
                                                    3 -> favorites.filter { SongClassifier.resolveGenre(it) == "说唱" }
                                                    else -> favorites
                                                }.ifEmpty { favorites }
                                                app.playerController.playShuffled(pool)
                                                onShowToast("已开启「$title」（${pool.size} 首）")
                                            }
                                            .padding(horizontal = 13.dp, vertical = 11.dp)
                                    ) {
                                        Text(title, style = MaterialTheme.typography.titleSmall, color = SonicColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Spacer(Modifier.height(2.dp))
                                        Text(sub, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item(key = "public_import_banner") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(SonicColors.Accent.copy(alpha = 0.14f), SonicColors.SurfaceCard)
                                    )
                                )
                                .clickable {
                                    onShowToast("前往底部「曲库」点右上角云朵图标，即可粘贴 QQ 音乐 / 网易云歌单链接一键导入！")
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("一键迁移你的 QQ 音乐 / 网易云歌单", style = MaterialTheme.typography.titleSmall, color = SonicColors.TextPrimary, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(2.dp))
                                Text("前往「曲库」粘贴分享链接，4 路并发匹配原唱并自动保留语种/流派分类", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary)
                            }
                        }
                    }
                }

                // 极简文字下划线榜单栏
                item(key = "chart_tabs") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(ChartCategory.entries, key = { it.id }) { cat ->
                            val selected = cat == selectedChart
                            Column(
                                modifier = Modifier.clickable { selectedChart = cat },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = cat.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selected) SonicColors.TextPrimary else SonicColors.TextMuted,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(Modifier.height(5.dp))
                                Box(
                                    modifier = Modifier
                                        .width(if (selected) 18.dp else 0.dp)
                                        .height(2.5.dp)
                                        .clip(CircleShape)
                                        .background(SonicColors.Accent)
                                )
                            }
                        }
                    }
                }

                if (isLoadingChart) {
                    item {
                        ShimmerSongRowPlaceholder(count = 6)
                    }
                } else if (chartSongs.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SonicColors.SurfaceCard)
                                .padding(vertical = 36.dp, horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("当前榜单加载较慢或网络波动", style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary)
                            Spacer(Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(SonicColors.Accent)
                                    .clickable { chartReloadNonce++ }
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                            ) {
                                Text("点击重试加载", style = MaterialTheme.typography.labelMedium, color = SonicColors.OnAccent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    val heroSong = chartSongs.firstOrNull()
                    if (heroSong != null) {
                        item(key = "hero_${selectedChart.id}") {
                            HeroSpotlightCard(
                                categoryTitle = selectedChart.title,
                                song = heroSong,
                                onPlayAll = { app.playerController.playQueue(chartSongs, 0) },
                                onLearnHero = {
                                    app.playerController.playQueue(chartSongs, 0)
                                    onOpenPlayerWithMode(PlayerViewMode.IMMERSIVE_LYRICS)
                                },
                                onWatchMv = { onWatchMv(heroSong) }
                            )
                        }
                    }

                    item(key = "chart_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(selectedChart.subtitle, style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                            if (chartSongs.isNotEmpty()) {
                                Text(
                                    "播放全部",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SonicColors.Accent,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { app.playerController.playQueue(chartSongs, 0) }
                                )
                            }
                        }
                    }

                    itemsIndexed(
                        items = chartSongs,
                        key = { _, it -> "${selectedChart.id}_${it.uniqueKey}" },
                        contentType = { _, _ -> "song" }
                    ) { index, song ->
                        SongRowItem(
                            song = song,
                            rankNumber = index + 1,
                            isPlayingThis = playbackState.currentSong?.uniqueKey == song.uniqueKey,
                            isAudioPlaying = playbackState.isPlaying,
                            isFavorite = favoriteKeys.contains(song.uniqueKey),
                            playlists = playlists,
                            onPlay = { app.playerController.playSong(song, newQueue = chartSongs) },
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
                                app.playerController.playSong(song, newQueue = chartSongs)
                                onOpenPlayerWithMode(PlayerViewMode.LEARN_STUDIO)
                            },
                            onWatchMv = { onWatchMv(song) },
                            onAskAi = { onJumpToAi("赏析《${song.title}》-${song.artist}并推荐同风格歌曲") }
                        )
                        if (index < chartSongs.lastIndex) {
                            HorizontalDivider(
                                color = SonicColors.Hairline.copy(alpha = 0.55f),
                                modifier = Modifier.padding(start = 74.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickShortcutTile(
    icon: ImageVector,
    label: String,
    tintColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SonicColors.SurfaceCard)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(tintColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tintColor, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextPrimary, maxLines = 1)
    }
}

@Composable
private fun HeroSpotlightCard(
    categoryTitle: String,
    song: Song,
    onPlayAll: () -> Unit,
    onLearnHero: () -> Unit,
    onWatchMv: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SonicColors.SurfaceCard)
            .clickable { onPlayAll() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongArtwork(coverUrl = song.coverUrl, size = 88.dp, cornerRadius = 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("NO.1 · $categoryTitle", style = MaterialTheme.typography.labelSmall, color = SonicColors.Accent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(song.title, style = MaterialTheme.typography.titleLarge, color = SonicColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = SonicColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(SonicColors.Accent)
                        .clickable { onPlayAll() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = SonicColors.OnAccent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("播放", style = MaterialTheme.typography.labelMedium, color = SonicColors.OnAccent, fontWeight = FontWeight.Bold)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(SonicColors.SurfaceElevated)
                        .clickable { onLearnHero() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Lyrics, null, tint = SonicColors.TextPrimary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("沉浸词", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextPrimary)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(SonicColors.SurfaceElevated)
                        .clickable { onWatchMv() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.OndemandVideo, null, tint = SonicColors.Accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("MV", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun MinimalPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) SonicColors.Accent else SonicColors.SurfaceCard)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) SonicColors.OnAccent else SonicColors.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun SearchTagChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SonicColors.SurfaceCard)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = SonicColors.TextPrimary)
    }
}
