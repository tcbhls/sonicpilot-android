package com.sonicpilot.music.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicpilot.music.SonicPilotApp
import com.sonicpilot.music.data.model.DownloadStatus
import com.sonicpilot.music.data.model.Playlist
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.data.model.SongClassifier
import com.sonicpilot.music.ui.components.QualityBadge
import com.sonicpilot.music.ui.components.SongArtwork
import com.sonicpilot.music.ui.components.SongRowItem
import com.sonicpilot.music.ui.player.PlayerViewMode
import com.sonicpilot.music.ui.theme.SonicColors
import kotlinx.coroutines.launch

/**
 * 旗舰大曲库「曲库」页（全面对标 QQ 音乐大曲库体验）：
 * - 我喜欢：支持「一键批量下载全集/当前筛选」「批量多选模式」「语种/风格/状态多维筛选」「按歌手/按专辑分组」「多维排序」
 * - 离线中心：3 路并发稳速批量下载队列仪表盘（暂停/继续、重试全部失败、清空排队、状态筛选）
 * - 歌单：支持 QQ 音乐 / 网易云分享链接一键导入、整张歌单批量下载
 */
@Composable
fun LibraryScreen(
    app: SonicPilotApp,
    onOpenSettings: () -> Unit,
    onOpenPlayerWithMode: (PlayerViewMode) -> Unit,
    onWatchMv: (Song) -> Unit,
    onJumpToAi: (String) -> Unit,
    onRegisterBackInterceptor: ((() -> Boolean)?) -> Unit,
    onShowToast: (String) -> Unit
) {
    val favorites by app.repository.favorites.collectAsState()
    val favoriteKeys by app.repository.favoriteKeys.collectAsState()
    val playlists by app.repository.playlists.collectAsState()
    val downloads by app.repository.downloads.collectAsState()
    val completedDownloadKeys by app.repository.completedDownloadKeys.collectAsState()
    val recentHistory by app.repository.recentHistory.collectAsState()
    val playbackState by app.playerController.state.collectAsState()
    val preferredQuality by app.settings.preferredQuality.collectAsState()
    val isDownloadPaused by app.downloader.isQueuePaused.collectAsState()

    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var activePlaylist by remember { mutableStateOf<Playlist?>(null) }

    // 我喜欢页：QQ 音乐同款视图 / 多维筛选 / 歌手专辑下钻 / 批量多选状态
    var favViewMode by remember { mutableStateOf(FavViewMode.SONGS) }
    var favFilterState by remember { mutableStateOf(FavFilterState()) }
    var activeGroupBucket by remember { mutableStateOf<SongGroupBucket?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }

    // 批量多选模式状态
    var isBatchSelectMode by remember { mutableStateOf(false) }
    var selectedSongKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchPlaylistPicker by remember { mutableStateOf(false) }
    var showBatchRemoveConfirm by remember { mutableStateOf(false) }

    // 批量下载确认弹窗（任意列表均可复用：全部收藏 / 筛选结果 / 选中项 / 歌手全集 / 整张歌单）
    var pendingBatchDownload by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }

    // 搜索框状态
    var favFilterQuery by remember { mutableStateOf("") }
    var recentFilterQuery by remember { mutableStateOf("") }
    var playlistFilterQuery by remember { mutableStateOf("") }
    var offlineStatusFilter by remember { mutableIntStateOf(0) } // 0=全部 1=进行中/排队 2=已完成 3=失败

    // 新建 / 导入 / 删除确认弹窗
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var pendingDeletePlaylist by remember { mutableStateOf<Playlist?>(null) }
    var newPlName by remember { mutableStateOf("") }
    var newPlDesc by remember { mutableStateOf("") }
    var showImportPlaylistDialog by remember { mutableStateOf(false) }
    var importLinkInput by remember { mutableStateOf("") }
    var importIntoFavorites by remember { mutableStateOf(true) }
    var isImportingPlaylist by remember { mutableStateOf(false) }
    var importProgressText by remember { mutableStateOf("") }

    // 1. 先按搜索词 + 多维筛选（快捷/语种/风格）+ 排序过滤「我喜欢」
    val filteredFavorites = remember(favorites, favFilterQuery, favFilterState, completedDownloadKeys) {
        val q = favFilterQuery.trim()
        val monthAgoMs = System.currentTimeMillis() - 30L * 24L * 3600L * 1000L
        val base = favorites.filter { song ->
            val matchQuery = q.isBlank() ||
                song.title.contains(q, true) ||
                song.artist.contains(q, true) ||
                song.album.contains(q, true)
            if (!matchQuery) return@filter false

            val matchQuick = when (favFilterState.quick) {
                FavQuickFilter.ALL -> true
                FavQuickFilter.RECENT_MONTH -> song.addedAt >= monthAgoMs
                FavQuickFilter.DOWNLOADED -> completedDownloadKeys.contains(song.uniqueKey)
                FavQuickFilter.NOT_DOWNLOADED -> !completedDownloadKeys.contains(song.uniqueKey)
                FavQuickFilter.HAS_MV -> song.mvId.isNotBlank()
            }
            if (!matchQuick) return@filter false

            if (favFilterState.selectedLanguages.isNotEmpty()) {
                val lang = SongClassifier.resolveLanguage(song)
                if (!favFilterState.selectedLanguages.contains(lang)) return@filter false
            }
            if (favFilterState.selectedGenres.isNotEmpty()) {
                val genre = SongClassifier.resolveGenre(song)
                if (!favFilterState.selectedGenres.contains(genre)) return@filter false
            }
            true
        }
        when (favFilterState.sort) {
            FavSortMode.DEFAULT -> base
            FavSortMode.TITLE_AZ -> base.sortedBy { it.title.lowercase() }
            FavSortMode.ARTIST_AZ -> base.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            FavSortMode.DURATION_DESC -> base.sortedByDescending { it.durationMs }
        }
    }

    // 2. 按歌手聚合分组（基于当前筛选结果，支持筛选「粤语」后再按歌手看！）
    val artistBuckets = remember(filteredFavorites) {
        val map = LinkedHashMap<String, MutableList<Song>>()
        filteredFavorites.forEach { song ->
            val primaryArtist = song.artist.split("/", "、", ",").firstOrNull()?.trim().orEmpty().ifBlank { "未知歌手" }
            map.getOrPut(primaryArtist) { mutableListOf() }.add(song)
        }
        map.entries
            .sortedByDescending { it.value.size }
            .map { (artist, list) ->
                SongGroupBucket(
                    key = "artist:$artist",
                    title = artist,
                    subtitle = "${list.size} 首收藏曲目",
                    coverUrl = list.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
                    songs = list
                )
            }
    }

    // 3. 按专辑聚合分组
    val albumBuckets = remember(filteredFavorites) {
        val map = LinkedHashMap<String, MutableList<Song>>()
        filteredFavorites.forEach { song ->
            val albumName = song.album.trim().ifBlank { "单曲 / 未分专辑" }
            map.getOrPut(albumName) { mutableListOf() }.add(song)
        }
        map.entries
            .sortedByDescending { it.value.size }
            .map { (album, list) ->
                val artistHint = list.firstOrNull()?.artist.orEmpty()
                SongGroupBucket(
                    key = "album:$album",
                    title = album,
                    subtitle = "${list.size} 首 · $artistHint",
                    coverUrl = list.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty(),
                    songs = list
                )
            }
    }

    val filteredRecent = remember(recentHistory, recentFilterQuery) {
        val q = recentFilterQuery.trim()
        if (q.isBlank()) recentHistory
        else recentHistory.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
    }

    // 侧滑返回精准拦截链：批量选择退出 → 歌手/专辑子页返回 → 歌单详情返回
    DisposableEffect(activePlaylist, activeGroupBucket, isBatchSelectMode) {
        if (isBatchSelectMode || activeGroupBucket != null || activePlaylist != null) {
            onRegisterBackInterceptor {
                when {
                    isBatchSelectMode -> {
                        isBatchSelectMode = false
                        selectedSongKeys = emptySet()
                        true
                    }
                    activeGroupBucket != null -> {
                        activeGroupBucket = null
                        true
                    }
                    activePlaylist != null -> {
                        activePlaylist = null
                        playlistFilterQuery = ""
                        true
                    }
                    else -> false
                }
            }
        } else {
            onRegisterBackInterceptor(null)
        }
        onDispose { onRegisterBackInterceptor(null) }
    }

    fun exitBatchMode() {
        isBatchSelectMode = false
        selectedSongKeys = emptySet()
    }

    fun toggleSelectSong(key: String) {
        selectedSongKeys = if (selectedSongKeys.contains(key)) {
            selectedSongKeys - key
        } else {
            selectedSongKeys + key
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(SonicColors.BgDeep)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            // ==================== 子页面 A：自建歌单详情 ====================
            val openedPl = activePlaylist
            if (openedPl != null) {
                val plSongs = remember(openedPl.id, playlists) { app.repository.getPlaylistSongs(openedPl.id) }
                val filteredPlSongs = remember(plSongs, playlistFilterQuery) {
                    val q = playlistFilterQuery.trim()
                    if (q.isBlank()) plSongs
                    else plSongs.filter {
                        it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        exitBatchMode()
                        activePlaylist = null
                        playlistFilterQuery = ""
                    }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = SonicColors.TextPrimary)
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(openedPl.name, style = MaterialTheme.typography.headlineSmall, color = SonicColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${plSongs.size} 首歌曲", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                    }
                    if (plSongs.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(SonicColors.SurfaceElevated)
                                    .clickable { pendingBatchDownload = "下载歌单「${openedPl.name}」" to filteredPlSongs }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Download, "一键下载歌单", tint = SonicColors.TextPrimary, modifier = Modifier.size(16.dp))
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(SonicColors.Accent)
                                    .clickable { app.playerController.playQueue(filteredPlSongs, 0) }
                                    .padding(horizontal = 13.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = SonicColors.OnAccent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("播放", color = SonicColors.OnAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (plSongs.size >= 8) {
                    Spacer(Modifier.height(10.dp))
                    LibraryInlineSearchBox(
                        query = playlistFilterQuery,
                        onQueryChange = { playlistFilterQuery = it },
                        placeholder = "在歌单 ${plSongs.size} 首歌曲中筛选..."
                    )
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 130.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (filteredPlSongs.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    if (plSongs.isEmpty()) "歌单暂无曲目，可点歌曲菜单添加或让 AI 帮你填充" else "未找到匹配「$playlistFilterQuery」的歌曲",
                                    color = SonicColors.TextSecondary
                                )
                            }
                        }
                    }
                    items(filteredPlSongs, key = { it.uniqueKey }) { song ->
                        SongRowItem(
                            song = song,
                            isPlayingThis = playbackState.currentSong?.uniqueKey == song.uniqueKey,
                            isAudioPlaying = playbackState.isPlaying,
                            isFavorite = favoriteKeys.contains(song.uniqueKey),
                            isOfflineDownloaded = completedDownloadKeys.contains(song.uniqueKey),
                            playlists = playlists,
                            onPlay = { app.playerController.playSong(song, newQueue = filteredPlSongs) },
                            onPlayNext = {
                                app.playerController.playNext(song)
                                onShowToast("已设为下一首播放")
                            },
                            onToggleFavorite = { app.repository.toggleFavorite(song) },
                            onDownload = {
                                app.downloader.enqueueDownload(song)
                                onShowToast("加入无损下载：《${song.title}》")
                            },
                            onAddToPlaylist = { targetPl ->
                                app.repository.addSongToPlaylist(targetPl.id, song)
                                onShowToast("已加入「${targetPl.name}」")
                            },
                            onLearnSong = {
                                app.playerController.playSong(song, newQueue = filteredPlSongs)
                                onOpenPlayerWithMode(PlayerViewMode.LEARN_STUDIO)
                            },
                            onWatchMv = { onWatchMv(song) }
                        )
                    }
                }
                return@Column
            }

            // ==================== 子页面 B：按歌手 / 按专辑下钻列表 ====================
            val openedBucket = activeGroupBucket
            if (openedBucket != null) {
                val liveBucketSongs = remember(openedBucket, favorites) {
                    val favMap = favorites.associateBy { it.uniqueKey }
                    openedBucket.songs.mapNotNull { favMap[it.uniqueKey] }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        exitBatchMode()
                        activeGroupBucket = null
                    }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = SonicColors.TextPrimary)
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(openedBucket.title, style = MaterialTheme.typography.headlineSmall, color = SonicColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("共 ${liveBucketSongs.size} 首收藏", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(SonicColors.SurfaceCard)
                                .clickable { pendingBatchDownload = "下载「${openedBucket.title}」全部收藏" to liveBucketSongs }
                                .padding(horizontal = 11.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Download, null, tint = SonicColors.Accent, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("下载", color = SonicColors.Accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(SonicColors.Accent)
                                .clickable {
                                    if (liveBucketSongs.isNotEmpty()) app.playerController.playQueue(liveBucketSongs, 0)
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = SonicColors.OnAccent, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("播放", color = SonicColors.OnAccent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(contentPadding = PaddingValues(bottom = 135.dp)) {
                    items(liveBucketSongs, key = { it.uniqueKey }, contentType = { "song" }) { song ->
                        SongRowItem(
                            song = song,
                            isPlayingThis = playbackState.currentSong?.uniqueKey == song.uniqueKey,
                            isAudioPlaying = playbackState.isPlaying,
                            isFavorite = true,
                            isOfflineDownloaded = completedDownloadKeys.contains(song.uniqueKey),
                            playlists = playlists,
                            onPlay = { app.playerController.playSong(song, newQueue = liveBucketSongs) },
                            onPlayNext = {
                                app.playerController.playNext(song)
                                onShowToast("已设为下一首播放")
                            },
                            onToggleFavorite = {
                                app.repository.setFavorite(song, false)
                                onShowToast("已移出我喜欢")
                            },
                            onDownload = {
                                app.downloader.enqueueDownload(song)
                                onShowToast("加入无损下载：《${song.title}》")
                            },
                            onAddToPlaylist = { pl ->
                                app.repository.addSongToPlaylist(pl.id, song)
                                onShowToast("已加入「${pl.name}」")
                            },
                            onLearnSong = {
                                app.playerController.playSong(song, newQueue = liveBucketSongs)
                                onOpenPlayerWithMode(PlayerViewMode.LEARN_STUDIO)
                            },
                            onWatchMv = { onWatchMv(song) },
                            onAskAi = { onJumpToAi("赏析《${song.title}》-${song.artist}并推荐同风格歌曲") }
                        )
                    }
                }
                return@Column
            }

            // ==================== 顶栏 ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("曲库", style = MaterialTheme.typography.headlineMedium, color = SonicColors.TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showImportPlaylistDialog = true }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.CloudDownload, "导入 QQ/网易云歌单", tint = SonicColors.Accent, modifier = Modifier.size(21.dp))
                    }
                    IconButton(onClick = { showCreatePlaylistDialog = true }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.Add, "新建歌单", tint = SonicColors.TextPrimary, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.Tune, "设置", tint = SonicColors.TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 四段极简胶囊切换：喜欢 / 歌单 / 离线 / 最近播放
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SonicColors.SurfaceCard)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    "喜欢·${favorites.size}",
                    "歌单·${playlists.size}",
                    "离线·${downloads.count { it.status == DownloadStatus.COMPLETED }}",
                    "最近·${recentHistory.size}"
                ).forEachIndexed { idx, title ->
                    val active = selectedTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) SonicColors.SurfaceElevated else Color.Transparent)
                            .clickable {
                                if (selectedTab != idx) exitBatchMode()
                                selectedTab = idx
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) SonicColors.TextPrimary else SonicColors.TextSecondary,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                // ==================== Tab 0: 我喜欢（QQ 音乐级批量下载 + 多维分类 + 歌手/专辑视图） ====================
                0 -> {
                    if (favorites.isEmpty()) {
                        EmptyMinimalState("还没有收藏歌曲", "在发现页点歌曲右侧 ··· 即可收藏，或点右上角云图标导入外部歌单")
                    } else {
                        // 1. 顶部核心操作条（对标 QQ 音乐：播放 / 随机 / 一键下载 / 批量多选）
                        if (isBatchSelectMode) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SonicColors.SurfaceCard)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "已选 ${selectedSongKeys.size} / ${filteredFavorites.size} 首",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = SonicColors.Accent,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val allSelected = filteredFavorites.isNotEmpty() && selectedSongKeys.size == filteredFavorites.size
                                    Text(
                                        if (allSelected) "取消全选" else "全选(${filteredFavorites.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SonicColors.TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.clickable {
                                            selectedSongKeys = if (allSelected) emptySet() else filteredFavorites.map { it.uniqueKey }.toSet()
                                        }
                                    )
                                    Text(
                                        "反选",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SonicColors.TextSecondary,
                                        modifier = Modifier.clickable {
                                            val allKeys = filteredFavorites.map { it.uniqueKey }.toSet()
                                            selectedSongKeys = allKeys - selectedSongKeys
                                        }
                                    )
                                    Text(
                                        "完成",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SonicColors.Accent,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { exitBatchMode() }
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 播放全部
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(SonicColors.Accent)
                                        .clickable {
                                            if (filteredFavorites.isNotEmpty()) app.playerController.playQueue(filteredFavorites, 0)
                                        }
                                        .padding(horizontal = 13.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = SonicColors.OnAccent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("播放", color = SonicColors.OnAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                                // 随机播放
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(SonicColors.SurfaceCard)
                                        .clickable {
                                            if (filteredFavorites.isNotEmpty()) app.playerController.playShuffled(filteredFavorites)
                                        }
                                        .padding(horizontal = 11.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Shuffle, null, tint = SonicColors.TextPrimary, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("随机", color = SonicColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
                                }
                                // 一键批量下载（全部 / 当前筛选）
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(SonicColors.SurfaceCard)
                                        .clickable {
                                            if (filteredFavorites.isNotEmpty()) {
                                                val label = if (favFilterState.activeFilterCount > 0 || favFilterQuery.isNotBlank()) {
                                                    "下载当前筛选 (${filteredFavorites.size} 首)"
                                                } else {
                                                    "一键下载全部收藏"
                                                }
                                                pendingBatchDownload = label to filteredFavorites
                                            }
                                        }
                                        .padding(horizontal = 11.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Download, null, tint = SonicColors.Accent, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("下载", color = SonicColors.TextPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                                }
                                // 批量多选模式
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(SonicColors.SurfaceCard)
                                        .clickable {
                                            favViewMode = FavViewMode.SONGS
                                            isBatchSelectMode = true
                                            selectedSongKeys = emptySet()
                                        }
                                        .padding(horizontal = 11.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Checklist, null, tint = SonicColors.TextPrimary, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("批量", color = SonicColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 2. 搜索框 + QQ 音乐同款右侧「歌曲 / 歌手 / 专辑 / 分类筛选」图标栏
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                LibraryInlineSearchBox(
                                    query = favFilterQuery,
                                    onQueryChange = { favFilterQuery = it },
                                    placeholder = "搜 ${favorites.size} 首收藏、歌手..."
                                )
                            }
                            if (!isBatchSelectMode) {
                                // 三视角切换：歌曲 / 歌手 / 专辑
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SonicColors.SurfaceCard)
                                        .padding(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(
                                        FavViewMode.SONGS to Icons.AutoMirrored.Rounded.FormatListBulleted,
                                        FavViewMode.ARTISTS to Icons.Rounded.Person,
                                        FavViewMode.ALBUMS to Icons.Rounded.Album
                                    ).forEach { (mode, icon) ->
                                        val active = favViewMode == mode
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                                .background(if (active) SonicColors.SurfaceElevated else Color.Transparent)
                                                .clickable { favViewMode = mode },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = mode.label,
                                                tint = if (active) SonicColors.Accent else SonicColors.TextMuted,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }
                                }
                                // QQ 音乐同款漏斗「多维分类筛选」按钮（含激活计数角标）
                                val filterActive = !favFilterState.isDefault
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (filterActive) SonicColors.Accent.copy(alpha = 0.16f) else SonicColors.SurfaceCard)
                                        .clickable { showFilterSheet = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FilterList,
                                        contentDescription = "语种与风格分类筛选",
                                        tint = if (filterActive) SonicColors.Accent else SonicColors.TextPrimary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                    if (favFilterState.activeFilterCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(top = 3.dp, end = 3.dp)
                                                .size(14.dp)
                                                .clip(CircleShape)
                                                .background(SonicColors.Accent),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                favFilterState.activeFilterCount.toString(),
                                                color = SonicColors.OnAccent,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. 动态语种快捷横条 & 激活筛选标签条（一键点选 全部/国语/粤语/英语/说唱/电子，零学习成本！）
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item {
                                val allSelected = favFilterState.isDefault
                                FilterTagChip("全部 ${favorites.size}", allSelected) {
                                    favFilterState = FavFilterState()
                                }
                            }
                            listOf("国语", "粤语", "英语", "日语").forEach { lang ->
                                item(key = "ql_$lang") {
                                    val selected = favFilterState.selectedLanguages.contains(lang)
                                    FilterTagChip(lang, selected) {
                                        val next = if (selected) favFilterState.selectedLanguages - lang else setOf(lang)
                                        favFilterState = favFilterState.copy(selectedLanguages = next)
                                    }
                                }
                            }
                            listOf("流行", "说唱", "摇滚", "电子音乐", "节奏布鲁斯", "影视原声").forEach { genre ->
                                item(key = "qg_$genre") {
                                    val selected = favFilterState.selectedGenres.contains(genre)
                                    FilterTagChip(genre, selected) {
                                        val next = if (selected) favFilterState.selectedGenres - genre else setOf(genre)
                                        favFilterState = favFilterState.copy(selectedGenres = next)
                                    }
                                }
                            }
                            item {
                                FilterTagChip("更多分类 ▾", false) { showFilterSheet = true }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 结果计数提示行
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val summaryText = when (favViewMode) {
                                FavViewMode.SONGS -> if (favFilterState.isDefault && favFilterQuery.isBlank()) {
                                    "共 ${favorites.size} 首 · ${completedDownloadKeys.count { favoriteKeys.contains(it) }} 首已离线"
                                } else {
                                    "筛选出 ${filteredFavorites.size} / ${favorites.size} 首 · ${favFilterState.sort.label}"
                                }
                                FavViewMode.ARTISTS -> "共 ${artistBuckets.size} 位歌手（${filteredFavorites.size} 首）"
                                FavViewMode.ALBUMS -> "共 ${albumBuckets.size} 张专辑（${filteredFavorites.size} 首）"
                            }
                            Text(summaryText, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary)
                            if (!favFilterState.isDefault) {
                                Text(
                                    "清空筛选",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SonicColors.Accent,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { favFilterState = FavFilterState() }
                                )
                            }
                        }

                        // 4. 按视角渲染列表
                        when (favViewMode) {
                            FavViewMode.SONGS -> {
                                LazyColumn(contentPadding = PaddingValues(bottom = if (isBatchSelectMode) 170.dp else 135.dp)) {
                                    if (filteredFavorites.isEmpty()) {
                                        item {
                                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("当前筛选条件下暂无匹配歌曲", color = SonicColors.TextSecondary)
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        "点击重置全部筛选条件",
                                                        color = SonicColors.Accent,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        modifier = Modifier.clickable {
                                                            favFilterQuery = ""
                                                            favFilterState = FavFilterState()
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    items(filteredFavorites, key = { it.uniqueKey }, contentType = { "song" }) { song ->
                                        SongRowItem(
                                            song = song,
                                            isPlayingThis = playbackState.currentSong?.uniqueKey == song.uniqueKey,
                                            isAudioPlaying = playbackState.isPlaying,
                                            isFavorite = true,
                                            isOfflineDownloaded = completedDownloadKeys.contains(song.uniqueKey),
                                            selectionMode = isBatchSelectMode,
                                            isSelected = selectedSongKeys.contains(song.uniqueKey),
                                            onToggleSelect = { toggleSelectSong(song.uniqueKey) },
                                            playlists = playlists,
                                            onPlay = { app.playerController.playSong(song, newQueue = filteredFavorites) },
                                            onPlayNext = {
                                                app.playerController.playNext(song)
                                                onShowToast("已设为下一首播放")
                                            },
                                            onToggleFavorite = {
                                                app.repository.setFavorite(song, false)
                                                onShowToast("已移出我喜欢")
                                            },
                                            onDownload = {
                                                app.downloader.enqueueDownload(song)
                                                onShowToast("加入无损下载：《${song.title}》")
                                            },
                                            onAddToPlaylist = { pl ->
                                                app.repository.addSongToPlaylist(pl.id, song)
                                                onShowToast("已加入「${pl.name}」")
                                            },
                                            onLearnSong = {
                                                app.playerController.playSong(song, newQueue = filteredFavorites)
                                                onOpenPlayerWithMode(PlayerViewMode.LEARN_STUDIO)
                                            },
                                            onWatchMv = { onWatchMv(song) },
                                            onAskAi = { onJumpToAi("赏析《${song.title}》-${song.artist}并推荐同风格歌曲") }
                                        )
                                    }
                                }
                            }

                            FavViewMode.ARTISTS -> {
                                SongGroupBucketList(
                                    buckets = artistBuckets,
                                    isArtistMode = true,
                                    onOpenBucket = { activeGroupBucket = it },
                                    onPlayBucket = { bucket ->
                                        if (bucket.songs.isNotEmpty()) app.playerController.playQueue(bucket.songs, 0)
                                    },
                                    onDownloadBucket = { bucket ->
                                        pendingBatchDownload = "下载「${bucket.title}」全部收藏" to bucket.songs
                                    }
                                )
                            }

                            FavViewMode.ALBUMS -> {
                                SongGroupBucketList(
                                    buckets = albumBuckets,
                                    isArtistMode = false,
                                    onOpenBucket = { activeGroupBucket = it },
                                    onPlayBucket = { bucket ->
                                        if (bucket.songs.isNotEmpty()) app.playerController.playQueue(bucket.songs, 0)
                                    },
                                    onDownloadBucket = { bucket ->
                                        pendingBatchDownload = "下载专辑「${bucket.title}」" to bucket.songs
                                    }
                                )
                            }
                        }
                    }
                }

                // ==================== Tab 1: 自建歌单 ====================
                1 -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 130.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(SonicColors.SurfaceCard)
                                    .clickable { onJumpToAi("根据我的收藏和听歌记录，帮我创建一份专属精选歌单并开始播放") }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, null, tint = SonicColors.Accent, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("让 AI 按口味定制歌单", style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary)
                                    Text("自动结合收藏与播放历史编排曲目", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary)
                                }
                            }
                        }
                        if (playlists.isEmpty()) {
                            item { EmptyMinimalState("暂无自建歌单", "点击右上角 + 或云端导入即可新建") }
                        } else {
                            items(playlists, key = { it.id }) { pl ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(SonicColors.SurfaceCard)
                                        .clickable { activePlaylist = pl }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SongArtwork(coverUrl = pl.coverUrl, size = 56.dp, cornerRadius = 10.dp)
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(pl.name, style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary)
                                            if (pl.createdByAi) QualityBadge("AI")
                                        }
                                        Spacer(Modifier.height(3.dp))
                                        Text("${pl.songCount} 首", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                                    }
                                    IconButton(onClick = { pendingDeletePlaylist = pl }) {
                                        Icon(Icons.Rounded.DeleteOutline, "删除", tint = SonicColors.TextMuted, modifier = Modifier.size(19.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // ==================== Tab 2: 离线下载队列中心 ====================
                2 -> {
                    val liveDownloadProgress by app.downloader.liveProgress.collectAsState()
                    if (downloads.isEmpty()) {
                        EmptyMinimalState("暂无离线下载", "在「喜欢」页点「下载」可一键批量无损离线全部 700+ 首歌曲")
                    } else {
                        val completedItems = downloads.filter { it.status == DownloadStatus.COMPLETED }
                        val downloadingCount = downloads.count { it.status == DownloadStatus.DOWNLOADING }
                        val queuedCount = downloads.count { it.status == DownloadStatus.QUEUED }
                        val failedCount = downloads.count { it.status == DownloadStatus.FAILED }
                        val completedSongs = completedItems.map { it.song }

                        val shownDownloads = remember(downloads, offlineStatusFilter) {
                            when (offlineStatusFilter) {
                                1 -> downloads.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
                                2 -> downloads.filter { it.status == DownloadStatus.COMPLETED }
                                3 -> downloads.filter { it.status == DownloadStatus.FAILED }
                                else -> downloads
                            }
                        }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 135.dp)
                        ) {
                            item(key = "dl_dashboard") {
                                DownloadQueueDashboard(
                                    totalCount = downloads.size,
                                    completedCount = completedItems.size,
                                    downloadingCount = downloadingCount,
                                    queuedCount = queuedCount,
                                    failedCount = failedCount,
                                    isPaused = isDownloadPaused,
                                    onTogglePauseResume = {
                                        if (isDownloadPaused) {
                                            app.downloader.resumeQueue()
                                            onShowToast("已恢复批量下载队列")
                                        } else {
                                            app.downloader.pauseQueue()
                                            onShowToast("已暂停下载队列")
                                        }
                                    },
                                    onRetryAllFailed = {
                                        val n = app.downloader.retryAllFailed()
                                        onShowToast("已重新排队 $n 首失败曲目")
                                    },
                                    onCancelAllQueued = {
                                        app.downloader.cancelAllQueued()
                                        onShowToast("已清空剩余排队任务")
                                    }
                                )
                            }

                            item(key = "dl_filter_row") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(
                                            0 to "全部",
                                            1 to "排队(${downloadingCount + queuedCount})",
                                            2 to "完成(${completedItems.size})",
                                            3 to "失败($failedCount)"
                                        ).forEach { (idx, label) ->
                                            FilterTagChip(label, offlineStatusFilter == idx) { offlineStatusFilter = idx }
                                        }
                                    }
                                }
                            }

                            if (completedSongs.isNotEmpty() && (offlineStatusFilter == 0 || offlineStatusFilter == 2)) {
                                item(key = "dl_play_all") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("已离线 ${completedSongs.size} 首", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                                        Text(
                                            "播放全部离线",
                                            color = SonicColors.Accent,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.clickable { app.playerController.playQueue(completedSongs, 0) }
                                        )
                                    }
                                }
                            }

                            items(shownDownloads, key = { it.uniqueKey }) { item ->
                                val liveProg = liveDownloadProgress[item.uniqueKey] ?: item.progress
                                val sizeMb = String.format("%.1f MB", item.fileSizeBytes / (1024f * 1024f))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(SonicColors.SurfaceCard)
                                        .clickable {
                                            when (item.status) {
                                                DownloadStatus.COMPLETED -> app.playerController.playSong(item.song, newQueue = completedSongs)
                                                DownloadStatus.FAILED -> {
                                                    app.downloader.enqueueDownload(item.song, item.requestedQuality)
                                                    onShowToast("正在重新下载：《${item.song.title}》")
                                                }
                                                DownloadStatus.QUEUED -> {
                                                    app.downloader.resumeQueue()
                                                }
                                                else -> Unit
                                            }
                                        }
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SongArtwork(coverUrl = item.song.coverUrl, size = 48.dp, cornerRadius = 9.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.song.title, style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Spacer(Modifier.height(3.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                QualityBadge(
                                                    when (item.status) {
                                                        DownloadStatus.COMPLETED -> "${item.actualBitrateKbps}k · $sizeMb"
                                                        DownloadStatus.DOWNLOADING -> "${(liveProg * 100).toInt()}%"
                                                        DownloadStatus.QUEUED -> if (isDownloadPaused) "已暂停" else "排队中"
                                                        DownloadStatus.FAILED -> "失败 · 点重试"
                                                    }
                                                )
                                                Text(item.song.artist, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary, maxLines = 1)
                                            }
                                        }
                                        if (item.status == DownloadStatus.FAILED) {
                                            IconButton(onClick = {
                                                app.downloader.enqueueDownload(item.song, item.requestedQuality)
                                                onShowToast("正在重新下载：《${item.song.title}》")
                                            }) {
                                                Icon(Icons.Rounded.Refresh, "重试", tint = SonicColors.Accent, modifier = Modifier.size(19.dp))
                                            }
                                        }
                                        IconButton(onClick = {
                                            app.repository.deleteDownload(item.uniqueKey)
                                            onShowToast("已移除离线任务")
                                        }) {
                                            Icon(Icons.Rounded.DeleteOutline, "删除", tint = SonicColors.TextMuted, modifier = Modifier.size(19.dp))
                                        }
                                    }
                                    if (item.status == DownloadStatus.DOWNLOADING) {
                                        Spacer(Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = { liveProg },
                                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                                            color = SonicColors.Accent,
                                            trackColor = SonicColors.Hairline
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ==================== Tab 3: 最近播放 ====================
                3 -> {
                    if (recentHistory.isEmpty()) {
                        EmptyMinimalState("还没有最近播放记录", "在发现页或搜索页播放歌曲后会自动记录在这里")
                    } else {
                        if (recentHistory.size >= 8) {
                            LibraryInlineSearchBox(
                                query = recentFilterQuery,
                                onQueryChange = { recentFilterQuery = it },
                                placeholder = "筛选最近听过的 ${recentHistory.size} 首歌曲..."
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        LazyColumn(contentPadding = PaddingValues(bottom = 130.dp)) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("最近听过 ${filteredRecent.size} 首", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                                    Text(
                                        "播放全部",
                                        color = SonicColors.Accent,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable {
                                            if (filteredRecent.isNotEmpty()) app.playerController.playQueue(filteredRecent, 0)
                                        }
                                    )
                                }
                            }
                            items(filteredRecent, key = { it.uniqueKey }, contentType = { "song" }) { song ->
                                SongRowItem(
                                    song = song,
                                    isPlayingThis = playbackState.currentSong?.uniqueKey == song.uniqueKey,
                                    isAudioPlaying = playbackState.isPlaying,
                                    isFavorite = favoriteKeys.contains(song.uniqueKey),
                                    isOfflineDownloaded = completedDownloadKeys.contains(song.uniqueKey),
                                    playlists = playlists,
                                    onPlay = { app.playerController.playSong(song, newQueue = filteredRecent) },
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
                                        onShowToast("加入无损下载：《${song.title}》")
                                    },
                                    onAddToPlaylist = { pl ->
                                        app.repository.addSongToPlaylist(pl.id, song)
                                        onShowToast("已加入「${pl.name}」")
                                    },
                                    onLearnSong = {
                                        app.playerController.playSong(song, newQueue = filteredRecent)
                                        onOpenPlayerWithMode(PlayerViewMode.LEARN_STUDIO)
                                    },
                                    onWatchMv = { onWatchMv(song) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 批量多选底部悬浮 Dock（贴在迷你播放条上方，操作顺手）
        if (isBatchSelectMode && selectedTab == 0 && activePlaylist == null && activeGroupBucket == null) {
            val selectedSongs = remember(filteredFavorites, selectedSongKeys) {
                filteredFavorites.filter { selectedSongKeys.contains(it.uniqueKey) }
            }
            BatchSelectionActionDock(
                selectedCount = selectedSongs.size,
                showRemoveFavorite = true,
                onBatchDownload = {
                    pendingBatchDownload = "批量下载已选中的歌曲" to selectedSongs
                },
                onBatchAddToPlaylist = {
                    if (playlists.isEmpty()) {
                        showCreatePlaylistDialog = true
                        onShowToast("请先新建一个歌单")
                    } else {
                        showBatchPlaylistPicker = true
                    }
                },
                onBatchPlayQueue = {
                    if (selectedSongs.isNotEmpty()) {
                        app.playerController.playQueue(selectedSongs, 0)
                        onShowToast("已开始播放选中的 ${selectedSongs.size} 首歌曲")
                        exitBatchMode()
                    }
                },
                onBatchRemove = { showBatchRemoveConfirm = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            )
        }
    }

    // ==================== 弹窗 1：QQ 音乐同款多维分类筛选抽屉 ====================
    if (showFilterSheet) {
        FavoritesFilterBottomSheet(
            allSongs = favorites,
            currentFilter = favFilterState,
            filteredResultCount = filteredFavorites.size,
            onUpdateFilter = { favFilterState = it },
            onDismiss = { showFilterSheet = false }
        )
    }

    // ==================== 弹窗 2：批量下载确认与音质体积选择 ====================
    val batchDl = pendingBatchDownload
    if (batchDl != null) {
        BatchDownloadConfirmDialog(
            title = batchDl.first,
            songs = batchDl.second,
            completedDownloadKeys = completedDownloadKeys,
            defaultQuality = preferredQuality,
            onDismiss = { pendingBatchDownload = null },
            onConfirmDownload = { toDl, quality, skipCompleted ->
                val count = app.downloader.enqueueBatchDownload(toDl, quality, skipCompleted = skipCompleted)
                pendingBatchDownload = null
                exitBatchMode()
                if (count > 0) {
                    onShowToast("已加入批量下载队列 $count 首（${quality.badge}），可在「离线」查看进度")
                    selectedTab = 2
                } else {
                    onShowToast("所选曲目均已离线完成或在下载队列中")
                }
            }
        )
    }

    // ==================== 弹窗 3：批量加入歌单 ====================
    if (showBatchPlaylistPicker) {
        val selectedSongs = filteredFavorites.filter { selectedSongKeys.contains(it.uniqueKey) }
        AlertDialog(
            onDismissRequest = { showBatchPlaylistPicker = false },
            containerColor = SonicColors.SurfaceCard,
            title = { Text("批量加入歌单 (${selectedSongs.size} 首)", color = SonicColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    playlists.forEach { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SonicColors.SurfaceElevated)
                                .clickable {
                                    app.repository.bulkAddSongsToPlaylist(pl.id, selectedSongs)
                                    showBatchPlaylistPicker = false
                                    exitBatchMode()
                                    onShowToast("已将 ${selectedSongs.size} 首加入「${pl.name}」")
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
            confirmButton = { TextButton(onClick = { showBatchPlaylistPicker = false }) { Text("关闭") } }
        )
    }

    // ==================== 弹窗 4：批量取消收藏二次确认 ====================
    if (showBatchRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchRemoveConfirm = false },
            containerColor = SonicColors.SurfaceCard,
            title = { Text("确认批量移出我喜欢？", color = SonicColors.TextPrimary) },
            text = {
                Text(
                    "将从「我喜欢」移出已选中的 ${selectedSongKeys.size} 首歌曲（不影响已离线音频文件，也可在设置页重新同步 QQ 歌单）。",
                    color = SonicColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = app.repository.bulkRemoveFavorites(selectedSongKeys)
                    showBatchRemoveConfirm = false
                    exitBatchMode()
                    onShowToast("已移出 $removed 首歌曲")
                }) { Text("确认移出", color = SonicColors.RoseFavorite) }
            },
            dismissButton = { TextButton(onClick = { showBatchRemoveConfirm = false }) { Text("取消") } }
        )
    }

    // ==================== 弹窗 4.5：删除歌单二次确认（防误触永久删单） ====================
    val delPl = pendingDeletePlaylist
    if (delPl != null) {
        AlertDialog(
            onDismissRequest = { pendingDeletePlaylist = null },
            containerColor = SonicColors.SurfaceCard,
            title = { Text("删除歌单「${delPl.name}」？", color = SonicColors.TextPrimary) },
            text = {
                Text(
                    "该歌单内共有 ${delPl.songCount} 首歌曲，删除歌单不会影响「我喜欢」收藏与已离线音频文件。",
                    color = SonicColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.repository.deletePlaylist(delPl.id)
                    pendingDeletePlaylist = null
                    onShowToast("已删除歌单「${delPl.name}」")
                }) { Text("确认删除", color = SonicColors.RoseFavorite) }
            },
            dismissButton = { TextButton(onClick = { pendingDeletePlaylist = null }) { Text("取消") } }
        )
    }

    // ==================== 弹窗 5：导入外部歌单 ====================
    if (showImportPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { if (!isImportingPlaylist) showImportPlaylistDialog = false },
            containerColor = SonicColors.SurfaceCard,
            title = { Text("导入 QQ 音乐 / 网易云歌单", color = SonicColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "支持直接粘贴 QQ 音乐分享短链（c6.y.qq.com）、网页歌单链接或网易云歌单链接",
                        style = MaterialTheme.typography.labelSmall,
                        color = SonicColors.TextSecondary
                    )
                    OutlinedTextField(
                        value = importLinkInput,
                        onValueChange = { importLinkInput = it },
                        label = { Text("粘贴分享链接或歌单 ID") },
                        singleLine = false,
                        maxLines = 3,
                        enabled = !isImportingPlaylist,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(true to "加入「我喜欢」", false to "存为新歌单").forEach { (toFav, label) ->
                            val active = importIntoFavorites == toFav
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (active) SonicColors.Accent.copy(alpha = 0.16f) else SonicColors.SurfaceElevated)
                                    .clickable(enabled = !isImportingPlaylist) { importIntoFavorites = toFav }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) SonicColors.Accent else SonicColors.TextSecondary,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    if (isImportingPlaylist || importProgressText.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isImportingPlaylist) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SonicColors.Accent)
                            }
                            Text(importProgressText, style = MaterialTheme.typography.labelSmall, color = SonicColors.Accent)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isImportingPlaylist && importLinkInput.isNotBlank(),
                    onClick = {
                        isImportingPlaylist = true
                        importProgressText = "正在解析链接..."
                        scope.launch {
                            val res = app.playlistImporter.importFromLinkOrId(importLinkInput) { status ->
                                importProgressText = status
                            }
                            res.onSuccess { imported ->
                                if (importIntoFavorites) {
                                    val added = app.repository.bulkAddFavorites(imported.songs, overwriteExisting = false)
                                    onShowToast("已导入「${imported.playlistName}」：新增 $added 首到我喜欢")
                                } else {
                                    val pl = app.repository.createPlaylist(imported.playlistName, "导入自${imported.sourceLabel}")
                                    app.repository.bulkAddSongsToPlaylist(pl.id, imported.songs)
                                    onShowToast("已创建歌单「${pl.name}」(${imported.songs.size} 首)")
                                }
                                importLinkInput = ""
                                importProgressText = ""
                                isImportingPlaylist = false
                                showImportPlaylistDialog = false
                            }.onFailure { err ->
                                importProgressText = err.message ?: "导入失败"
                                isImportingPlaylist = false
                            }
                        }
                    }
                ) { Text(if (isImportingPlaylist) "导入中..." else "开始导入") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isImportingPlaylist,
                    onClick = {
                        showImportPlaylistDialog = false
                        importProgressText = ""
                    }
                ) { Text("取消") }
            }
        )
    }

    // ==================== 弹窗 6：新建歌单 ====================
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            containerColor = SonicColors.SurfaceElevated,
            title = { Text("新建歌单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = newPlName, onValueChange = { newPlName = it }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(value = newPlDesc, onValueChange = { newPlDesc = it }, label = { Text("简介（可选）") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPlName.isNotBlank()) {
                        app.repository.createPlaylist(newPlName, newPlDesc)
                        newPlName = ""
                        newPlDesc = ""
                        showCreatePlaylistDialog = false
                        onShowToast("已创建歌单")
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun LibraryInlineSearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall, color = SonicColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = SonicColors.TextSecondary, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, "清空", tint = SonicColors.TextSecondary, modifier = Modifier.size(16.dp))
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
        )
    )
}

@Composable
private fun EmptyMinimalState(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize().padding(bottom = 100.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = SonicColors.TextMuted)
        }
    }
}
