package com.sonicpilot.music.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sonicpilot.music.channel.GdStudioChannelProvider
import com.sonicpilot.music.data.db.SonicDatabaseHelper
import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.LyricData
import com.sonicpilot.music.data.model.PlayMode
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.data.repository.MusicRepository
import com.sonicpilot.music.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * 高频播放进度流（与主 UI 状态彻底隔离！避免每 200ms 触发全 App 重组导致界面卡顿）
 */
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

/**
 * 低频播放器核心状态快照（仅在切歌、播放/暂停、音质、歌词、模式或定时器变更时刷新）
 */
data class PlaybackUiState(
    val currentSong: Song? = null,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val loadingStage: String = "",
    val actualBitrateKbps: Int = 0,
    val qualityLabel: String = "",
    val activeChannelName: String = "",
    val isOfflineLocal: Boolean = false,
    val lyrics: LyricData = LyricData(),
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val playbackSpeed: Float = 1.0f,
    val loopLineIndex: Int? = null,
    val sleepTimerRemainingMinutes: Int = 0,
    val errorMessage: String? = null
)

/**
 * 高性能无损音乐播放控制器
 * - 进度高频状态彻底剥离，根治滑屏卡顿
 * - 内置下一首后台静默预解析，切歌秒播
 * - 支持「下一首播放」插队、原调变速慢放、单句循环复读与睡眠定时关闭
 */
class PlayerController(
    private val context: Context,
    private val repository: MusicRepository,
    private val settings: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activePlayer: Player? = null
    private var mediaController: MediaController? = null
    private var fallbackExoPlayer: ExoPlayer? = null
    private var progressJob: Job? = null
    private var loadTrackJob: Job? = null
    private var prefetchJob: Job? = null
    private var sleepTimerJob: Job? = null
    // 记录当前曲目已自动重试次数，避免直链过期/403 时直接红字报错，也防止无限重试死循环
    private var lastAutoRetryTrackKey: String = ""

    private val _state = MutableStateFlow(PlaybackUiState(playMode = settings.playMode.value))
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    init {
        restoreLastPlayedSong()
        connectToService()
        startProgressTicker()
    }

    /**
     * 冷启动自动恢复上次播放曲目（暂停待播状态，迷你播放条不丢失，点播放即刻接续）
     */
    private fun restoreLastPlayedSong() {
        val raw = settings.getLastPlayedSongJson()
        if (raw.isBlank()) return
        val restored = SonicDatabaseHelper.songFromJson(raw) ?: return
        _progress.value = PlaybackProgress(positionMs = 0L, durationMs = restored.durationMs)
        _state.value = _state.value.copy(
            currentSong = restored,
            queue = listOf(restored),
            currentIndex = 0,
            isPlaying = false,
            qualityLabel = restored.qualityBadge
        )
    }

    private fun connectToService() {
        scope.launch {
            val controller = runCatching {
                val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
                MediaController.Builder(context, token).buildAsync().await()
            }.getOrNull()

            if (controller != null) {
                mediaController = controller
                // 根治冷启动双音轨幽灵播放 Bug：若连接服务前临时 fallbackExoPlayer 已开始播放，无缝迁移至正式 MediaController 并立即销毁临时实例
                val oldFallback = fallbackExoPlayer
                if (oldFallback != null) {
                    val wasPlaying = oldFallback.isPlaying || oldFallback.playWhenReady
                    val oldItem = oldFallback.currentMediaItem
                    val oldPos = oldFallback.currentPosition.coerceAtLeast(0L)
                    val oldParams = oldFallback.playbackParameters
                    runCatching {
                        oldFallback.stop()
                        oldFallback.release()
                    }
                    fallbackExoPlayer = null
                    attachPlayer(controller)
                    if (oldItem != null) {
                        controller.setMediaItem(oldItem)
                        controller.playbackParameters = oldParams
                        controller.prepare()
                        if (oldPos > 0L) controller.seekTo(oldPos)
                        if (wasPlaying) controller.play()
                    }
                } else {
                    attachPlayer(controller)
                }
            } else if (activePlayer == null) {
                val localExo = buildFastLocalExoPlayer()
                fallbackExoPlayer = localExo
                attachPlayer(localExo)
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun buildFastLocalExoPlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val fastLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 45_000, 400, 1_000)
            .build()
        return ExoPlayer.Builder(context)
            .setLoadControl(fastLoadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private fun attachPlayer(player: Player) {
        activePlayer = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val buffering = playbackState == Player.STATE_BUFFERING
                _state.value = _state.value.copy(
                    isBuffering = buffering,
                    loadingStage = if (buffering) "正在缓冲音频..." else ""
                )
                val dur = player.duration
                if (dur > 0) {
                    _progress.value = _progress.value.copy(durationMs = dur)
                }
                if (playbackState == Player.STATE_ENDED) {
                    onTrackCompleted()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cur = _state.value.currentSong
                // 自动防失效重试 1 次：清除过期直链缓存并降级/换源重播，大幅减少“缓冲中断”体验断层！
                if (cur != null && cur.channelId != "local_recording" && lastAutoRetryTrackKey != cur.uniqueKey) {
                    lastAutoRetryTrackKey = cur.uniqueKey
                    GdStudioChannelProvider.invalidateStreamCache(cur)
                    _state.value = _state.value.copy(
                        isBuffering = true,
                        loadingStage = "音源波动，正在自动换源重试...",
                        errorMessage = null
                    )
                    loadAndPlay(
                        song = cur,
                        queue = _state.value.queue.ifEmpty { listOf(cur) },
                        index = _state.value.currentIndex.coerceAtLeast(0),
                        overrideQuality = AudioQuality.HIGH
                    )
                    return
                }
                _state.value = _state.value.copy(
                    isBuffering = false,
                    isPlaying = false,
                    loadingStage = "",
                    errorMessage = "当前音源暂时不可用，点播放重试或切换音质"
                )
            }
        })
    }

    private fun ensurePlayer(): Player {
        activePlayer?.let { return it }
        val localExo = buildFastLocalExoPlayer()
        fallbackExoPlayer = localExo
        attachPlayer(localExo)
        return localExo
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val p = activePlayer
                if (p != null && p.isPlaying) {
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    val dur = p.duration.takeIf { it > 0 } ?: _progress.value.durationMs
                    // 学歌模式：单句循环复读（A-B 自动回跳）
                    val loopIdx = _state.value.loopLineIndex
                    val lines = _state.value.lyrics.lines
                    if (loopIdx != null && loopIdx in lines.indices) {
                        val startMs = lines[loopIdx].timeMs
                        val endMs = if (loopIdx + 1 < lines.size) {
                            lines[loopIdx + 1].timeMs
                        } else {
                            (startMs + 6000L).coerceAtMost(dur.coerceAtLeast(startMs + 2000L))
                        }
                        if (pos >= endMs - 120L || pos < startMs - 400L) {
                            p.seekTo(startMs)
                        }
                    }
                    // 仅推送高频 progress 流，完全不触动主 state 流！
                    _progress.value = PlaybackProgress(positionMs = pos, durationMs = dur)
                }
                delay(220L)
            }
        }
    }

    fun playSong(
        song: Song,
        newQueue: List<Song>? = null,
        overrideQuality: AudioQuality? = null
    ) {
        val queue = when {
            newQueue != null && newQueue.isNotEmpty() -> newQueue
            _state.value.queue.any { it.uniqueKey == song.uniqueKey } -> _state.value.queue
            else -> _state.value.queue + song
        }
        val idx = queue.indexOfFirst { it.uniqueKey == song.uniqueKey }.coerceAtLeast(0)
        loadAndPlay(song, queue, idx, overrideQuality)
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val safeIdx = startIndex.coerceIn(0, songs.lastIndex)
        loadAndPlay(songs[safeIdx], songs, safeIdx, null)
    }

    /**
     * 一键随机打乱并立即从随机曲目开播（大歌单 700+ 首体验极佳）
     */
    fun playShuffled(songs: List<Song>) {
        if (songs.isEmpty()) return
        val shuffled = songs.shuffled()
        setPlayMode(PlayMode.SHUFFLE)
        loadAndPlay(shuffled[0], shuffled, 0, null)
    }

    /**
     * 对标 QQ音乐 / 网易云的「下一首播放」插队功能
     */
    fun playNext(song: Song) {
        val currentQueue = _state.value.queue.toMutableList()
        if (currentQueue.isEmpty()) {
            playSong(song, listOf(song))
            return
        }
        val curIdx = _state.value.currentIndex.coerceIn(0, currentQueue.lastIndex)
        val existingIdx = currentQueue.indexOfFirst { it.uniqueKey == song.uniqueKey }
        if (existingIdx == curIdx) return
        if (existingIdx >= 0) {
            currentQueue.removeAt(existingIdx)
        }
        val adjustedCurIdx = currentQueue.indexOfFirst { it.uniqueKey == _state.value.currentSong?.uniqueKey }
            .coerceAtLeast(0)
        val insertPos = (adjustedCurIdx + 1).coerceAtMost(currentQueue.size)
        currentQueue.add(insertPos, song)
        _state.value = _state.value.copy(queue = currentQueue, currentIndex = adjustedCurIdx)
    }

    fun removeFromQueue(index: Int) {
        val q = _state.value.queue.toMutableList()
        if (index !in q.indices || q.size <= 1) return
        val removedCurrent = index == _state.value.currentIndex
        q.removeAt(index)
        val newIdx = when {
            removedCurrent -> index.coerceAtMost(q.lastIndex)
            index < _state.value.currentIndex -> (_state.value.currentIndex - 1).coerceAtLeast(0)
            else -> _state.value.currentIndex
        }
        if (removedCurrent) {
            loadAndPlay(q[newIdx], q, newIdx, null)
        } else {
            _state.value = _state.value.copy(queue = q, currentIndex = newIdx)
        }
    }

    /**
     * 睡眠定时关闭（传入 0 取消）
     */
    fun setSleepTimerMinutes(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _state.value = _state.value.copy(sleepTimerRemainingMinutes = 0)
            return
        }
        _state.value = _state.value.copy(sleepTimerRemainingMinutes = minutes)
        sleepTimerJob = scope.launch {
            var left = minutes
            while (left > 0 && isActive) {
                delay(60_000L)
                left -= 1
                _state.value = _state.value.copy(sleepTimerRemainingMinutes = left)
            }
            if (isActive) {
                pause()
            }
        }
    }

    private fun loadAndPlay(
        song: Song,
        queue: List<Song>,
        index: Int,
        overrideQuality: AudioQuality?
    ) {
        loadTrackJob?.cancel()
        prefetchJob?.cancel()
        if (lastAutoRetryTrackKey != song.uniqueKey) {
            lastAutoRetryTrackKey = ""
        }
        val preferredQuality = overrideQuality ?: settings.preferredQuality.value
        _progress.value = PlaybackProgress(positionMs = 0L, durationMs = song.durationMs)
        _state.value = _state.value.copy(
            currentSong = song,
            queue = queue,
            currentIndex = index,
            isBuffering = true,
            loadingStage = "正在解析高音质直链...",
            lyrics = LyricData(),
            loopLineIndex = null,
            errorMessage = null
        )

        loadTrackJob = scope.launch {
            // 立即并行拉取高清专辑封面与双语歌词（0.4秒内先出真封面+完整歌词，不等音频流解析完！）
            launch(Dispatchers.IO) {
                val cover = runCatching { repository.channelManager.fetchCoverUrl(song) }.getOrDefault("")
                val lyrics = runCatching { repository.channelManager.fetchLyrics(song) }.getOrDefault(LyricData())
                val enriched = song.copy(coverUrl = cover.ifBlank { song.coverUrl })
                runCatching { settings.saveLastPlayedSongJson(SonicDatabaseHelper.songToJson(enriched).toString()) }
                withContext(Dispatchers.Main) {
                    if (_state.value.currentSong?.uniqueKey == song.uniqueKey) {
                        val updatedQueue = _state.value.queue.map {
                            if (it.uniqueKey == song.uniqueKey) enriched else it
                        }
                        _state.value = _state.value.copy(
                            currentSong = enriched,
                            queue = updatedQueue,
                            lyrics = lyrics
                        )
                    }
                }
            }

            val localFile = song.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 1024 }
                ?: repository.getDownloadedSongFile(song.uniqueKey)

            val uri: Uri
            val bitrate: Int
            val qualityBadge: String
            val channelName: String
            val offline: Boolean

            if (localFile != null) {
                uri = Uri.fromFile(localFile)
                bitrate = song.actualBitrateKbps.takeIf { it > 0 } ?: 999
                qualityBadge = if (localFile.extension.equals("flac", true)) "离线无损" else "离线音频"
                channelName = "本地离线"
                offline = true
            } else {
                val stream = withContext(Dispatchers.IO) {
                    repository.channelManager.resolveStreamWithFallback(song, preferredQuality)
                }
                if (stream == null) {
                    _state.value = _state.value.copy(
                        isBuffering = false,
                        isPlaying = false,
                        loadingStage = "",
                        errorMessage = "当前渠道暂无法解析《${song.title}》，请换源或切音质重试"
                    )
                    return@launch
                }
                uri = Uri.parse(stream.url)
                bitrate = stream.actualBitrateKbps
                qualityBadge = when {
                    bitrate >= 999 -> "Hi-Res"
                    bitrate >= 700 -> "FLAC"
                    bitrate > 0 -> "${bitrate}K"
                    else -> stream.quality.badge
                }
                channelName = repository.channelManager.channels.value
                    .firstOrNull { it.id == stream.resolvedByChannelId }?.name?.substringBefore(" ·") ?: stream.resolvedByChannelId
                offline = false
            }

            val latestSong = _state.value.currentSong?.takeIf { it.uniqueKey == song.uniqueKey } ?: song
            launch(Dispatchers.IO) {
                val songWithMeta = latestSong.copy(actualBitrateKbps = bitrate, qualityBadge = qualityBadge)
                repository.recordPlay(songWithMeta)
                runCatching { settings.saveLastPlayedSongJson(SonicDatabaseHelper.songToJson(songWithMeta).toString()) }
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(latestSong.title)
                .setArtist(latestSong.artist)
                .setAlbumTitle(latestSong.album)
                .apply {
                    if (latestSong.coverUrl.startsWith("http")) setArtworkUri(Uri.parse(latestSong.coverUrl))
                }
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()

            val player = ensurePlayer()
            player.setMediaItem(mediaItem)
            player.playbackParameters = PlaybackParameters(_state.value.playbackSpeed, 1.0f)
            player.prepare()
            player.play()

            _state.value = _state.value.copy(
                actualBitrateKbps = bitrate,
                qualityLabel = qualityBadge,
                activeChannelName = channelName,
                isOfflineLocal = offline,
                loadingStage = "正在缓冲音频...",
                errorMessage = null
            )

            // 后台静默预解析队列下一首直链（切歌 0 等待！）
            prefetchNextTrack(queue, index, preferredQuality)
        }
    }

    private fun prefetchNextTrack(queue: List<Song>, currentIndex: Int, quality: AudioQuality) {
        if (queue.size <= 1) return
        val nextSong = queue[(currentIndex + 1) % queue.size]
        prefetchJob = scope.launch(Dispatchers.IO) {
            delay(2500L)
            runCatching {
                repository.channelManager.resolveStreamWithFallback(nextSong, quality)
                repository.channelManager.fetchLyrics(nextSong)
            }
        }
    }

    fun playLocalAudioFile(file: File, title: String, subtitle: String = "我的练唱录音") {
        if (!file.exists()) return
        val tempSong = Song(
            id = "rec_${file.name}",
            title = title,
            artist = subtitle,
            channelId = "local_recording",
            rawTrackId = file.name,
            localFilePath = file.absolutePath,
            actualBitrateKbps = 705,
            qualityBadge = "PCM WAV"
        )
        val player = ensurePlayer()
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(subtitle).build())
            .build()
        player.setMediaItem(item)
        player.playbackParameters = PlaybackParameters(1.0f, 1.0f)
        player.prepare()
        player.play()
        _progress.value = PlaybackProgress(0L, 0L)
        _state.value = _state.value.copy(
            currentSong = tempSong,
            isBuffering = false,
            actualBitrateKbps = 705,
            qualityLabel = "声乐 WAV",
            activeChannelName = "声乐棚",
            isOfflineLocal = true,
            playbackSpeed = 1.0f,
            loopLineIndex = null,
            errorMessage = null
        )
    }

    fun togglePlayPause() {
        val p = activePlayer
        val cur = _state.value.currentSong
        if (p == null || p.mediaItemCount == 0) {
            if (cur != null) playSong(cur)
            return
        }
        if (p.isPlaying) p.pause() else p.play()
    }

    fun play() {
        val p = activePlayer
        val cur = _state.value.currentSong
        if (p == null || p.mediaItemCount == 0) {
            if (cur != null) playSong(cur)
            return
        }
        p.play()
    }

    fun pause() { activePlayer?.pause() }

    fun seekTo(positionMs: Long, preserveLoop: Boolean = false) {
        val target = positionMs.coerceAtLeast(0L)
        if (!preserveLoop && _state.value.loopLineIndex != null) {
            _state.value = _state.value.copy(loopLineIndex = null)
        }
        activePlayer?.seekTo(target)
        _progress.value = _progress.value.copy(positionMs = target)
    }

    fun nextTrack() {
        val q = _state.value.queue
        if (q.isEmpty()) return
        val nextIdx = when (_state.value.playMode) {
            PlayMode.SHUFFLE -> if (q.size > 1) {
                var r = Random.nextInt(q.size)
                if (r == _state.value.currentIndex) r = (r + 1) % q.size
                r
            } else 0
            else -> (_state.value.currentIndex + 1) % q.size
        }
        loadAndPlay(q[nextIdx], q, nextIdx, null)
    }

    fun previousTrack() {
        val q = _state.value.queue
        if (q.isEmpty()) return
        val prevIdx = if (_state.value.currentIndex <= 0) q.lastIndex else _state.value.currentIndex - 1
        loadAndPlay(q[prevIdx], q, prevIdx, null)
    }

    fun cyclePlayMode(): PlayMode {
        val next = when (_state.value.playMode) {
            PlayMode.SEQUENCE -> PlayMode.SINGLE
            PlayMode.SINGLE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENCE
        }
        setPlayMode(next)
        return next
    }

    fun setPlayMode(mode: PlayMode) {
        settings.setPlayMode(mode)
        _state.value = _state.value.copy(playMode = mode)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 1.5f)
        activePlayer?.playbackParameters = PlaybackParameters(clamped, 1.0f)
        _state.value = _state.value.copy(playbackSpeed = clamped)
    }

    fun toggleLineLoop(lineIndex: Int?) {
        val lines = _state.value.lyrics.lines
        if (lineIndex == null || lineIndex !in lines.indices || _state.value.loopLineIndex == lineIndex) {
            _state.value = _state.value.copy(loopLineIndex = null)
            return
        }
        _state.value = _state.value.copy(loopLineIndex = lineIndex)
        seekTo(lines[lineIndex].timeMs, preserveLoop = true)
        play()
    }

    fun stepLyricLine(delta: Int) {
        val lines = _state.value.lyrics.lines
        if (lines.isEmpty()) return
        val curIdx = lines.indexOfLast { it.timeMs <= _progress.value.positionMs + 250L }.coerceAtLeast(0)
        val target = (curIdx + delta).coerceIn(0, lines.lastIndex)
        if (_state.value.loopLineIndex != null) {
            _state.value = _state.value.copy(loopLineIndex = target)
        }
        seekTo(lines[target].timeMs, preserveLoop = true)
        play()
    }

    fun switchQualityAndReload(quality: AudioQuality) {
        settings.setPreferredQuality(quality)
        val song = _state.value.currentSong ?: return
        if (song.channelId == "local_recording") return
        val currentPos = _progress.value.positionMs
        loadAndPlay(song, _state.value.queue, _state.value.currentIndex.coerceAtLeast(0), quality)
        scope.launch {
            delay(1000L)
            if (currentPos > 1000L && _state.value.currentSong?.uniqueKey == song.uniqueKey) {
                seekTo(currentPos)
            }
        }
    }

    private fun onTrackCompleted() {
        val song = _state.value.currentSong ?: return
        if (song.channelId == "local_recording") return
        when (_state.value.playMode) {
            PlayMode.SINGLE -> {
                activePlayer?.seekTo(0L)
                activePlayer?.play()
            }
            else -> nextTrack()
        }
    }

    fun release() {
        progressJob?.cancel()
        loadTrackJob?.cancel()
        prefetchJob?.cancel()
        sleepTimerJob?.cancel()
        mediaController?.release()
        fallbackExoPlayer?.release()
    }
}
