package com.sonicpilot.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.sonicpilot.music.ai.AppAgentToolRegistry
import com.sonicpilot.music.ai.CloudLlmClient
import com.sonicpilot.music.ai.LocalLlmEngine
import com.sonicpilot.music.ai.SonicAiAgent
import com.sonicpilot.music.ai.WebMusicSearcher
import com.sonicpilot.music.channel.ChannelManager
import com.sonicpilot.music.channel.PlaylistImporter
import com.sonicpilot.music.data.db.SonicDatabaseHelper
import com.sonicpilot.music.data.repository.MusicRepository
import com.sonicpilot.music.data.settings.SettingsStore
import com.sonicpilot.music.lyrics.CantoneseJyutpingEngine
import com.sonicpilot.music.mv.MvChannelEngine
import com.sonicpilot.music.player.PlayerController
import com.sonicpilot.music.player.SongDownloader
import com.sonicpilot.music.singing.SingingCoachEngine
import com.sonicpilot.music.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 音枢 AI 音乐全局容器
 * - 内置高效 HTTP 连接池与合理的快速失败超时（避免慢节点拖死搜索与 AI）
 * - 实现 Coil ImageLoaderFactory：25% 内存缓存 + 120MB 磁盘缓存，列表封面丝滑秒出零掉帧
 */
class SonicPilotApp : Application(), ImageLoaderFactory {

    lateinit var httpClient: OkHttpClient
        private set
    lateinit var downloadHttpClient: OkHttpClient
        private set
    lateinit var database: SonicDatabaseHelper
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var channelManager: ChannelManager
        private set
    lateinit var repository: MusicRepository
        private set
    lateinit var playerController: PlayerController
        private set
    lateinit var downloader: SongDownloader
        private set
    lateinit var singingCoach: SingingCoachEngine
        private set
    lateinit var localLlm: LocalLlmEngine
        private set
    lateinit var cloudLlm: CloudLlmClient
        private set
    lateinit var webSearcher: WebMusicSearcher
        private set
    lateinit var toolRegistry: AppAgentToolRegistry
        private set
    lateinit var aiAgent: SonicAiAgent
        private set
    lateinit var jyutpingEngine: CantoneseJyutpingEngine
        private set
    lateinit var mvEngine: MvChannelEngine
        private set
    lateinit var playlistImporter: PlaylistImporter
        private set
    lateinit var appUpdater: AppUpdater
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        val sharedPool = ConnectionPool(12, 3, TimeUnit.MINUTES)

        // 搜索、榜单、AI 对话使用快速失败短超时（避免慢节点死等 60 秒）
        httpClient = OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(16, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        // 大文件（无损 FLAC 与 1GB GGUF 模型）下载专用长读取客户端
        downloadHttpClient = OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        database = SonicDatabaseHelper(this)
        settings = SettingsStore(this)
        channelManager = ChannelManager(database, httpClient)
        repository = MusicRepository(database, channelManager)
        playerController = PlayerController(this, repository, settings)
        downloader = SongDownloader(this, repository, settings, downloadHttpClient)
        singingCoach = SingingCoachEngine(this, repository)
        localLlm = LocalLlmEngine(this, settings, downloadHttpClient)
        cloudLlm = CloudLlmClient(httpClient)
        webSearcher = WebMusicSearcher(httpClient)
        toolRegistry = AppAgentToolRegistry(
            repository = repository,
            playerController = playerController,
            downloader = downloader,
            settings = settings,
            singingCoach = singingCoach,
            webSearcher = webSearcher
        )
        aiAgent = SonicAiAgent(
            db = database,
            settings = settings,
            localLlm = localLlm,
            cloudLlm = cloudLlm,
            toolRegistry = toolRegistry
        )
        jyutpingEngine = CantoneseJyutpingEngine(this)
        mvEngine = MvChannelEngine(httpClient)
        playlistImporter = PlaylistImporter(httpClient, channelManager)
        appUpdater = AppUpdater(this, downloadHttpClient)

        // 后台异步预热离线粤拼词库 & 检查可选预置曲库（零阻塞主线程）
        appScope.launch(Dispatchers.IO) {
            launch { jyutpingEngine.ensureLoaded() }
            launch { repository.ensureSeededQqFavorites(this@SonicPilotApp, settings) }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(httpClient)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "artwork_disk_cache"))
                .maxSizeBytes(120L * 1024L * 1024L)
                .build()
        }
        .crossfade(160)
        .respectCacheHeaders(false)
        .build()

    companion object {
        lateinit var instance: SonicPilotApp
            private set
    }
}
