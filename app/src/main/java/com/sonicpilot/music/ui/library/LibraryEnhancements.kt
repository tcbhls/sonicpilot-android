package com.sonicpilot.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.data.model.SongClassifier
import com.sonicpilot.music.ui.components.QualityBadge
import com.sonicpilot.music.ui.components.SongArtwork
import com.sonicpilot.music.ui.theme.SonicColors

enum class FavViewMode(val label: String) {
    SONGS("歌曲"),
    ARTISTS("歌手"),
    ALBUMS("专辑")
}

enum class FavSortMode(val label: String) {
    DEFAULT("默认收藏顺序"),
    TITLE_AZ("歌名 A-Z"),
    ARTIST_AZ("按歌手聚集"),
    DURATION_DESC("时长优先")
}

enum class FavQuickFilter(val label: String) {
    ALL("全部"),
    RECENT_MONTH("近一月"),
    NOT_DOWNLOADED("未离线"),
    DOWNLOADED("已离线"),
    HAS_MV("有高清 MV")
}

data class FavFilterState(
    val quick: FavQuickFilter = FavQuickFilter.ALL,
    val selectedLanguages: Set<String> = emptySet(),
    val selectedGenres: Set<String> = emptySet(),
    val sort: FavSortMode = FavSortMode.DEFAULT
) {
    val activeFilterCount: Int
        get() = (if (quick != FavQuickFilter.ALL) 1 else 0) +
            selectedLanguages.size +
            selectedGenres.size

    val isDefault: Boolean
        get() = activeFilterCount == 0 && sort == FavSortMode.DEFAULT
}

data class SongGroupBucket(
    val key: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val songs: List<Song>
)

/**
 * 批量下载确认与音质/体积估算弹窗（解决 700+ 首曲目一键批量离线痛点）
 */
@Composable
fun BatchDownloadConfirmDialog(
    title: String,
    songs: List<Song>,
    completedDownloadKeys: Set<String>,
    defaultQuality: AudioQuality,
    onDismiss: () -> Unit,
    onConfirmDownload: (songsToDownload: List<Song>, quality: AudioQuality, skipCompleted: Boolean) -> Unit
) {
    var selectedQuality by remember(defaultQuality) { mutableStateOf(defaultQuality) }
    var skipAlreadyDownloaded by remember { mutableStateOf(true) }

    val alreadyCount = remember(songs, completedDownloadKeys) {
        songs.count { completedDownloadKeys.contains(it.uniqueKey) }
    }
    val pendingSongs = remember(songs, completedDownloadKeys, skipAlreadyDownloaded) {
        if (skipAlreadyDownloaded) songs.filterNot { completedDownloadKeys.contains(it.uniqueKey) } else songs
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SonicColors.SurfaceCard,
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge, color = SonicColors.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (alreadyCount > 0 && skipAlreadyDownloaded) {
                        "共 ${songs.size} 首，已自动跳过 $alreadyCount 首已离线歌曲，本次入队 ${pendingSongs.size} 首（3 路并发稳速下载并导出至 Music/SonicPilot）。"
                    } else {
                        "本次将批量加入队列 ${pendingSongs.size} 首（后台 3 路并发限流稳速下载，随时可在「离线」页暂停/继续）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SonicColors.TextSecondary
                )

                if (alreadyCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SonicColors.SurfaceElevated)
                            .clickable { skipAlreadyDownloaded = !skipAlreadyDownloaded }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("自动跳过已离线曲目 ($alreadyCount 首)", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextPrimary)
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = if (skipAlreadyDownloaded) SonicColors.Accent else SonicColors.TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text("选择批量下载音质（附预估占用空间）", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AudioQuality.HIRES, AudioQuality.LOSSLESS, AudioQuality.HIGH, AudioQuality.MEDIUM).forEach { q ->
                        val active = q == selectedQuality
                        val estSize = SongClassifier.estimateBatchSizeLabel(pendingSongs.size, q)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) SonicColors.Accent.copy(alpha = 0.14f) else SonicColors.SurfaceElevated)
                                .clickable { selectedQuality = q }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    q.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (active) SonicColors.Accent else SonicColors.TextPrimary,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(estSize, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextMuted)
                            }
                            QualityBadge(q.badge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = pendingSongs.isNotEmpty(),
                onClick = { onConfirmDownload(pendingSongs, selectedQuality, skipAlreadyDownloaded) }
            ) {
                Text(if (pendingSongs.isEmpty()) "已全部离线" else "开始下载 (${pendingSongs.size} 首)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * QQ 音乐同款多维分类筛选抽屉：快捷状态 + 语种（带实时曲数）+ 风格流派（带实时曲数）+ 排序方式
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FavoritesFilterBottomSheet(
    allSongs: List<Song>,
    currentFilter: FavFilterState,
    filteredResultCount: Int,
    onUpdateFilter: (FavFilterState) -> Unit,
    onDismiss: () -> Unit
) {
    val langCounts = remember(allSongs) {
        val counts = LinkedHashMap<String, Int>()
        allSongs.forEach { s ->
            val lang = SongClassifier.resolveLanguage(s)
            counts[lang] = (counts[lang] ?: 0) + 1
        }
        // 把曲库里实际存在的语种按数量降序放前面，同时保留 QQ 音乐完整语种列表
        val ordered = LinkedHashMap<String, Int>()
        counts.entries.sortedByDescending { it.value }.forEach { ordered[it.key] = it.value }
        SongClassifier.LANGUAGE_TAGS.forEach { tag -> ordered.putIfAbsent(tag, 0) }
        ordered
    }

    val genreCounts = remember(allSongs) {
        val counts = LinkedHashMap<String, Int>()
        allSongs.forEach { s ->
            val g = SongClassifier.resolveGenre(s)
            counts[g] = (counts[g] ?: 0) + 1
        }
        val ordered = LinkedHashMap<String, Int>()
        counts.entries.sortedByDescending { it.value }.forEach { ordered[it.key] = it.value }
        SongClassifier.GENRE_TAGS.forEach { tag -> ordered.putIfAbsent(tag, 0) }
        ordered
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SonicColors.SurfaceCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("分类筛选与排序", style = MaterialTheme.typography.titleLarge, color = SonicColors.TextPrimary)
                if (!currentFilter.isDefault) {
                    Text(
                        "重置全部",
                        style = MaterialTheme.typography.labelMedium,
                        color = SonicColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onUpdateFilter(FavFilterState()) }.padding(4.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("快捷筛选", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FavQuickFilter.entries.forEach { qf ->
                    FilterTagChip(
                        label = qf.label,
                        selected = currentFilter.quick == qf,
                        onClick = { onUpdateFilter(currentFilter.copy(quick = qf)) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("语种（支持多选）", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                langCounts.forEach { (lang, cnt) ->
                    val selected = currentFilter.selectedLanguages.contains(lang)
                    FilterTagChip(
                        label = if (cnt > 0) "$lang · $cnt" else lang,
                        selected = selected,
                        dimmed = cnt == 0 && !selected,
                        onClick = {
                            val next = currentFilter.selectedLanguages.toMutableSet()
                            if (!next.add(lang)) next.remove(lang)
                            onUpdateFilter(currentFilter.copy(selectedLanguages = next))
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("风格流派（支持多选）", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                genreCounts.forEach { (genre, cnt) ->
                    val selected = currentFilter.selectedGenres.contains(genre)
                    FilterTagChip(
                        label = if (cnt > 0) "$genre · $cnt" else genre,
                        selected = selected,
                        dimmed = cnt == 0 && !selected,
                        onClick = {
                            val next = currentFilter.selectedGenres.toMutableSet()
                            if (!next.add(genre)) next.remove(genre)
                            onUpdateFilter(currentFilter.copy(selectedGenres = next))
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("列表排序", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FavSortMode.entries.forEach { sortMode ->
                    FilterTagChip(
                        label = sortMode.label,
                        selected = currentFilter.sort == sortMode,
                        onClick = { onUpdateFilter(currentFilter.copy(sort = sortMode)) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("查看结果（共 $filteredResultCount 首）", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun FilterTagChip(
    label: String,
    selected: Boolean,
    dimmed: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    selected -> SonicColors.Accent.copy(alpha = 0.16f)
                    else -> SonicColors.SurfaceElevated
                }
            )
            .then(
                if (selected) Modifier.border(1.dp, SonicColors.Accent.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                selected -> SonicColors.Accent
                dimmed -> SonicColors.TextMuted.copy(alpha = 0.65f)
                else -> SonicColors.TextPrimary
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * 批量多选悬浮底栏（批量下载 / 加入歌单 / 加入下一首 / 批量移除）
 */
@Composable
fun BatchSelectionActionDock(
    selectedCount: Int,
    showRemoveFavorite: Boolean,
    onBatchDownload: () -> Unit,
    onBatchAddToPlaylist: () -> Unit,
    onBatchPlayQueue: () -> Unit,
    onBatchRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SonicColors.SurfaceCard)
            .border(0.8.dp, SonicColors.Hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BatchDockButton(
            icon = Icons.Rounded.Download,
            label = "下载($selectedCount)",
            tint = SonicColors.Accent,
            enabled = selectedCount > 0,
            onClick = onBatchDownload
        )
        BatchDockButton(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            label = "加歌单",
            tint = SonicColors.TextPrimary,
            enabled = selectedCount > 0,
            onClick = onBatchAddToPlaylist
        )
        BatchDockButton(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            label = "播放选中",
            tint = SonicColors.TextPrimary,
            enabled = selectedCount > 0,
            onClick = onBatchPlayQueue
        )
        if (showRemoveFavorite) {
            BatchDockButton(
                icon = Icons.Rounded.DeleteOutline,
                label = "移出喜欢",
                tint = SonicColors.RoseFavorite,
                enabled = selectedCount > 0,
                onClick = onBatchRemove
            )
        }
    }
}

@Composable
private fun BatchDockButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) tint else SonicColors.TextMuted.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (enabled) tint else SonicColors.TextMuted.copy(alpha = 0.45f),
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 歌手 / 专辑聚合分组列表（对标 QQ 音乐按歌手/按专辑浏览大曲库）
 */
@Composable
fun SongGroupBucketList(
    buckets: List<SongGroupBucket>,
    isArtistMode: Boolean,
    onOpenBucket: (SongGroupBucket) -> Unit,
    onPlayBucket: (SongGroupBucket) -> Unit,
    onDownloadBucket: (SongGroupBucket) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 135.dp)
    ) {
        items(buckets, key = { it.key }) { bucket ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SonicColors.SurfaceCard)
                    .clickable { onOpenBucket(bucket) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongArtwork(
                    coverUrl = bucket.coverUrl,
                    size = 52.dp,
                    cornerRadius = if (isArtistMode) 26.dp else 10.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        bucket.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = SonicColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            imageVector = if (isArtistMode) Icons.Rounded.Person else Icons.Rounded.Album,
                            contentDescription = null,
                            tint = SonicColors.TextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            bucket.subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = SonicColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = { onDownloadBucket(bucket) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Download, "一键下载分组", tint = SonicColors.TextSecondary, modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = { onPlayBucket(bucket) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.PlayArrow, "播放分组", tint = SonicColors.Accent, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/**
 * 700+ 首大批量下载队列仪表盘（实时进度 + 暂停/继续 + 重试全部失败 + 清空排队）
 */
@Composable
fun DownloadQueueDashboard(
    totalCount: Int,
    completedCount: Int,
    downloadingCount: Int,
    queuedCount: Int,
    failedCount: Int,
    isPaused: Boolean,
    onTogglePauseResume: () -> Unit,
    onRetryAllFailed: () -> Unit,
    onCancelAllQueued: () -> Unit
) {
    val activeOrQueued = downloadingCount + queuedCount
    val ratio = if (totalCount > 0) (completedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SonicColors.SurfaceCard)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        isPaused && queuedCount > 0 -> "下载队列已暂停（剩余 $queuedCount 首排队）"
                        activeOrQueued > 0 -> "正在批量离线（下载中 $downloadingCount · 排队 $queuedCount）"
                        failedCount > 0 -> "队列已完成，有 $failedCount 首失败可重试"
                        else -> "全部 $completedCount 首均已离线就绪"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = SonicColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "已完成 $completedCount / $totalCount 首 · 同步导出至 Music/SonicPilot",
                    style = MaterialTheme.typography.labelSmall,
                    color = SonicColors.TextSecondary
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = SonicColors.Accent,
            trackColor = SonicColors.Hairline
        )

        if (activeOrQueued > 0 || failedCount > 0) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeOrQueued > 0) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(SonicColors.Accent.copy(alpha = 0.14f))
                            .clickable { onTogglePauseResume() }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = null,
                            tint = SonicColors.Accent,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isPaused) "继续队列" else "暂停队列",
                            style = MaterialTheme.typography.labelSmall,
                            color = SonicColors.Accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (failedCount > 0) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(SonicColors.SurfaceElevated)
                            .clickable { onRetryAllFailed() }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = SonicColors.TextPrimary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重试失败($failedCount)", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextPrimary)
                    }
                }
                if (queuedCount > 0) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(SonicColors.RoseFavorite.copy(alpha = 0.12f))
                            .clickable { onCancelAllQueued() }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Close, null, tint = SonicColors.RoseFavorite, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("清空排队", style = MaterialTheme.typography.labelSmall, color = SonicColors.RoseFavorite)
                    }
                }
            }
        }
    }
}
