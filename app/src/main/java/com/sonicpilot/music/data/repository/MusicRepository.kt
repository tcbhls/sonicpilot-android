package com.sonicpilot.music.data.repository

import android.content.Context
import com.sonicpilot.music.channel.ChannelManager
import com.sonicpilot.music.data.db.SonicDatabaseHelper
import com.sonicpilot.music.data.model.ChartCategory
import com.sonicpilot.music.data.model.DownloadItem
import com.sonicpilot.music.data.model.DownloadStatus
import com.sonicpilot.music.data.model.ListeningProfile
import com.sonicpilot.music.data.model.Playlist
import com.sonicpilot.music.data.model.SingingEvaluation
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * 统一曲库、收藏、歌单、离线下载、历史记录与声乐档案仓库
 */
class MusicRepository(
    private val db: SonicDatabaseHelper,
    val channelManager: ChannelManager
) {
    private val _favorites = MutableStateFlow<List<Song>>(emptyList())
    val favorites: StateFlow<List<Song>> = _favorites.asStateFlow()

    private val _favoriteKeys = MutableStateFlow<Set<String>>(emptySet())
    val favoriteKeys: StateFlow<Set<String>> = _favoriteKeys.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _completedDownloadKeys = MutableStateFlow<Set<String>>(emptySet())
    val completedDownloadKeys: StateFlow<Set<String>> = _completedDownloadKeys.asStateFlow()

    private val _evaluations = MutableStateFlow<List<SingingEvaluation>>(emptyList())
    val evaluations: StateFlow<List<SingingEvaluation>> = _evaluations.asStateFlow()

    private val _recentHistory = MutableStateFlow<List<Song>>(emptyList())
    val recentHistory: StateFlow<List<Song>> = _recentHistory.asStateFlow()

    init {
        reloadAll()
    }

    fun reloadAll() {
        refreshFavorites()
        refreshPlaylists()
        refreshDownloads()
        refreshEvaluations()
        refreshHistory()
    }

    fun refreshFavorites() {
        val list = db.getFavorites()
        _favorites.value = list
        _favoriteKeys.value = list.map { it.uniqueKey }.toSet()
    }

    fun refreshPlaylists() {
        _playlists.value = db.getPlaylists()
    }

    fun refreshDownloads() {
        val list = db.getDownloads()
        _downloads.value = list
        _completedDownloadKeys.value = list
            .filter { it.status == DownloadStatus.COMPLETED && it.filePath.isNotBlank() }
            .map { it.uniqueKey }
            .toSet()
    }

    fun refreshEvaluations() {
        _evaluations.value = db.getSingingEvaluations()
    }

    fun refreshHistory() {
        _recentHistory.value = db.getPlayHistory(60).map { it.first }
    }

    fun isFavorite(song: Song): Boolean = _favoriteKeys.value.contains(song.uniqueKey)

    fun setFavorite(song: Song, favorite: Boolean): Boolean {
        if (favorite) db.addFavorite(song) else db.removeFavorite(song.uniqueKey)
        refreshFavorites()
        return favorite
    }

    fun toggleFavorite(song: Song): Boolean {
        val next = !isFavorite(song)
        return setFavorite(song, next)
    }

    fun bulkAddFavorites(songs: List<Song>, overwriteExisting: Boolean = false): Int {
        val count = db.bulkAddFavorites(songs, overwriteExisting)
        refreshFavorites()
        return count
    }

    fun bulkRemoveFavorites(uniqueKeys: Collection<String>): Int {
        val count = db.bulkRemoveFavorites(uniqueKeys)
        refreshFavorites()
        return count
    }

    /**
     * 后台异步导入可选内置预置收藏（若打包时未内置 `seed_favorites.json`，则静默跳过，公开开源版干净启动）
     */
    suspend fun ensureSeededQqFavorites(context: Context, settings: SettingsStore, force: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            val targetSeedVersion = 2
            val currentVersion = settings.getQqFavoritesSeedVersion()
            if (!force && currentVersion >= targetSeedVersion) {
                return@withContext 0
            }
            val hasSeedAsset = runCatching {
                context.assets.list("")?.contains("seed_favorites.json") == true
            }.getOrDefault(false)
            if (!hasSeedAsset) return@withContext 0
            val songs = runCatching {
                val raw = context.assets.open("seed_favorites.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONArray(raw)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(SonicDatabaseHelper.songFromJsonObject(it)) }
                    }
                }
            }.getOrDefault(emptyList())
            if (songs.isEmpty()) return@withContext 0
            val changed = if (!force && currentVersion == 1) {
                db.bulkMergeFavoriteMetadata(songs)
            } else {
                db.bulkAddFavorites(songs, overwriteExisting = force)
            }
            settings.setQqFavoritesSeedVersion(targetSeedVersion)
            refreshFavorites()
            changed
        }

    fun createPlaylist(name: String, description: String = "", createdByAi: Boolean = false): Playlist {
        val pl = Playlist(
            id = "pl_${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifBlank { "新建歌单" },
            description = description,
            createdByAi = createdByAi
        )
        db.upsertPlaylist(pl)
        refreshPlaylists()
        return pl
    }

    fun deletePlaylist(playlistId: String) {
        db.deletePlaylist(playlistId)
        refreshPlaylists()
    }

    fun addSongToPlaylist(playlistId: String, song: Song) {
        db.addSongToPlaylist(playlistId, song)
        refreshPlaylists()
    }

    fun bulkAddSongsToPlaylist(playlistId: String, songs: List<Song>) {
        db.bulkAddSongsToPlaylist(playlistId, songs)
        refreshPlaylists()
    }

    fun removeSongFromPlaylist(playlistId: String, uniqueKey: String) {
        db.removeSongFromPlaylist(playlistId, uniqueKey)
        refreshPlaylists()
    }

    fun getPlaylistSongs(playlistId: String): List<Song> = db.getPlaylistSongs(playlistId)

    fun findPlaylistByName(name: String): Playlist? =
        _playlists.value.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun recordPlay(song: Song) {
        db.recordPlay(song)
        refreshHistory()
    }

    fun getDownloadedSongFile(uniqueKey: String): File? {
        val item = _downloads.value.firstOrNull {
            it.uniqueKey == uniqueKey && it.status == DownloadStatus.COMPLETED && it.filePath.isNotBlank()
        } ?: return null
        val file = File(item.filePath)
        return if (file.exists() && file.length() > 1024) file else null
    }

    fun upsertDownload(item: DownloadItem) {
        db.upsertDownload(item)
        refreshDownloads()
    }

    fun bulkUpsertDownloads(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        db.bulkUpsertDownloads(items)
        refreshDownloads()
    }

    fun deleteDownload(uniqueKey: String) {
        val target = _downloads.value.firstOrNull { it.uniqueKey == uniqueKey }
        if (target != null && target.filePath.isNotBlank()) {
            runCatching { File(target.filePath).delete() }
        }
        db.deleteDownload(uniqueKey)
        refreshDownloads()
    }

    fun deleteDownloadsByStatus(status: DownloadStatus): Int {
        val removed = db.deleteDownloadsByStatus(status)
        refreshDownloads()
        return removed
    }

    fun saveSingingEvaluation(evaluation: SingingEvaluation) {
        db.upsertSingingEvaluation(evaluation)
        refreshEvaluations()
    }

    fun deleteSingingEvaluation(id: String) {
        val target = _evaluations.value.firstOrNull { it.id == id }
        if (target != null && target.audioFilePath.isNotBlank()) {
            runCatching { File(target.audioFilePath).delete() }
        }
        db.deleteSingingEvaluation(id)
        refreshEvaluations()
    }

    fun getListeningProfile(): ListeningProfile {
        val hist = db.getPlayHistory(100)
        val totalPlays = hist.sumOf { it.second }
        val artistCounts = mutableMapOf<String, Int>()
        hist.forEach { (song, count) ->
            song.artist.split("/", "、", ",").map { it.trim() }.filter { it.isNotBlank() }.forEach { a ->
                artistCounts[a] = (artistCounts[a] ?: 0) + count
            }
        }
        _favorites.value.forEach { song ->
            song.artist.split("/", "、", ",").map { it.trim() }.filter { it.isNotBlank() }.forEach { a ->
                artistCounts[a] = (artistCounts[a] ?: 0) + 2
            }
        }
        val topArtists = artistCounts.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
        return ListeningProfile(
            totalPlays = totalPlays,
            favoriteCount = _favorites.value.size,
            downloadCount = _downloads.value.count { it.status == DownloadStatus.COMPLETED },
            playlistCount = _playlists.value.size,
            topArtists = topArtists,
            recentSongs = hist.take(15).map { it.first }
        )
    }

    private val chartCache = java.util.concurrent.ConcurrentHashMap<ChartCategory, Pair<Long, List<Song>>>()

    suspend fun loadChart(category: ChartCategory, limit: Int = 30, forceRefresh: Boolean = false): List<Song> {
        if (!forceRefresh) {
            chartCache[category]?.let { (ts, cached) ->
                if (System.currentTimeMillis() - ts < 15 * 60 * 1000L && cached.isNotEmpty()) {
                    return cached.take(limit)
                }
            }
        }
        val fetched = channelManager.fetchChart(category, maxOf(limit, 30))
        if (fetched.isNotEmpty()) {
            chartCache[category] = System.currentTimeMillis() to fetched
        }
        return fetched.take(limit)
    }
}
