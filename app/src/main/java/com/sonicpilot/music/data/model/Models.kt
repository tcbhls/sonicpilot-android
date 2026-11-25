package com.sonicpilot.music.data.model

import java.util.UUID

/**
 * 音质档位枚举（由高到低自动优选降级）
 */
enum class AudioQuality(
    val code: String,
    val gdBr: Int,
    val displayName: String,
    val badge: String,
    val preferredExt: String
) {
    HIRES("hires_999", 999, "Hi-Res 母带无损", "Hi-Res", "flac"),
    LOSSLESS("flac_740", 740, "FLAC 无损音质", "FLAC", "flac"),
    HIGH("hq_320", 320, "极高音质 320k", "320K", "mp3"),
    MEDIUM("mq_192", 192, "高品音质 192k", "192K", "mp3"),
    STANDARD("sq_128", 128, "标准音质 128k", "128K", "mp3");

    companion object {
        fun fromCode(code: String?): AudioQuality =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: HIRES

        fun fromBitrate(br: Int): AudioQuality = when {
            br >= 999 -> HIRES
            br >= 700 -> LOSSLESS
            br >= 300 -> HIGH
            br >= 180 -> MEDIUM
            else -> STANDARD
        }
    }
}

/**
 * 渠道协议类型：内置支持四大开源开放协议，后续可无限扩展
 */
enum class ChannelType(val label: String, val description: String) {
    GDSTUDIO("GDStudio 无损源", "支持网易云/JOOX 等高解析无损 FLAC（含 24-bit 母带）"),
    METING("Meting 聚合源", "兼容开源 meting-api 公益或自建节点"),
    CUSTOM_REST("自定义 JSON 源", "兼容 go-music-dl / LX 中转 / 自定义 REST API 模板"),
    SUBSONIC("Subsonic/Navidrome", "接入个人 NAS 或私人无损云曲库");

    companion object {
        fun fromName(name: String?): ChannelType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GDSTUDIO
    }
}

/**
 * 音乐渠道配置实体
 */
data class MusicChannel(
    val id: String,
    val name: String,
    val type: ChannelType,
    val baseUrl: String,
    val sourceKey: String = "netease",
    val enabled: Boolean = true,
    val isPrimary: Boolean = false,
    val isBuiltIn: Boolean = false,
    val priority: Int = 10,
    val description: String = "",
    val apiKey: String = "",
    val username: String = "",
    val searchPathTemplate: String = "",
    val streamPathTemplate: String = "",
    val lastLatencyMs: Long = -1L
)

/**
 * 统一歌曲实体（跨渠道、本地下载、收藏与播放队列共享）
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val channelId: String,
    val sourceKey: String = "netease",
    val rawTrackId: String = id,
    val picId: String = "",
    val lyricId: String = "",
    val mvId: String = "",
    val coverUrl: String = "",
    val durationMs: Long = 0L,
    val localFilePath: String? = null,
    val actualBitrateKbps: Int = 0,
    val qualityBadge: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val language: String = "",
    val genre: String = ""
) {
    val uniqueKey: String get() = "$channelId:$rawTrackId"
}

/**
 * 歌词语言显示模式（方便英文歌双语对照、纯英文沉浸或仅看译文）
 */
enum class LyricDisplayMode(val label: String) {
    BILINGUAL("双语"),
    ORIGINAL_ONLY("仅原文"),
    TRANSLATION_ONLY("仅译文");

    fun next(): LyricDisplayMode = when (this) {
        BILINGUAL -> ORIGINAL_ONLY
        ORIGINAL_ONLY -> TRANSLATION_ONLY
        TRANSLATION_ONLY -> BILINGUAL
    }
}

/**
 * 高清 MV 视频流解析结果
 */
data class MvStream(
    val mvId: String,
    val title: String,
    val artist: String,
    val coverUrl: String = "",
    val sourceLabel: String = "",
    val qualityUrls: Map<String, String> = emptyMap()
) {
    val bestUrl: String get() = qualityUrls.values.firstOrNull().orEmpty()
    val bestQualityLabel: String get() = qualityUrls.keys.firstOrNull().orEmpty()
}

/**
 * 解析后的真实音频直链结果
 */
data class ResolvedStream(
    val url: String,
    val actualBitrateKbps: Int,
    val fileSizeBytes: Long = 0L,
    val formatExt: String = "flac",
    val resolvedByChannelId: String,
    val headers: Map<String, String> = emptyMap()
) {
    val quality: AudioQuality get() = AudioQuality.fromBitrate(actualBitrateKbps)
}

/**
 * 粤语注音显示模式（对标 QQ 音乐：标准带调粤拼 / 零门槛普通话谐音 / 关闭）
 */
enum class CantonesePhoneticMode(val label: String) {
    JYUTPING("粤拼"),
    HOMOPHONE("谐音"),
    OFF("注音关");

    fun next(): CantonesePhoneticMode = when (this) {
        JYUTPING -> HOMOPHONE
        HOMOPHONE -> OFF
        OFF -> JYUTPING
    }
}

/**
 * 双语/注音逐行歌词条目
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String = "",
    val romaji: String = ""
)

data class LyricData(
    val rawLrc: String = "",
    val rawTransLrc: String = "",
    val lines: List<LyricLine> = emptyList()
)

/**
 * 外观主题模式（支持跟随系统 / 纯净浅色 / 极夜深色）
 */
enum class AppThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("纯净浅色"),
    DARK("极夜深色")
}

/**
 * 经典主题强调色（致敬网易云音乐 / QQ 音乐 / 黑胶轻奢 / 极光蓝）
 */
enum class AppAccentStyle(val label: String, val hexColor: Long) {
    CLOUD_RED("网易云绯红", 0xFFE63946),
    QQ_GREEN("翡翠青绿", 0xFF00C476),
    VINYL_GOLD("黑胶香槟金", 0xFFD9A441),
    OCEAN_BLUE("清透海蓝", 0xFF2563EB)
}

/**
 * 全球与华语官方热门榜单分类
 */
enum class ChartCategory(val id: String, val title: String, val subtitle: String, val neteasePlaylistId: String) {
    HOT_CN("hot_cn", "热歌榜", "华语与全网热门", "3778678"),
    DOUYIN("douyin", "抖音热歌", "短视频爆款神曲", "2250011882"),
    SOARING("soaring", "飙升榜", "今日上升最快", "19723756"),
    BILLBOARD("billboard", "Billboard", "全球欧美流行", "60198"),
    NEW_SONGS("new_songs", "新歌榜", "最新发行首发", "3779629"),
    JP_ORICON("jp_oricon", "日本 Oricon", "日系 / ACG 精选", "60131")
}

/**
 * 自建歌单
 */
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val createdByAi: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val songCount: Int = 0,
    val coverUrl: String = ""
)

/**
 * 下载状态与任务模型
 */
enum class DownloadStatus {
    QUEUED, DOWNLOADING, COMPLETED, FAILED
}

data class DownloadItem(
    val uniqueKey: String,
    val song: Song,
    val requestedQuality: AudioQuality,
    val actualBitrateKbps: Int = 0,
    val filePath: String = "",
    val exportedUri: String = "",
    val fileSizeBytes: Long = 0L,
    val progress: Float = 0f,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 播放循环模式
 */
enum class PlayMode(val label: String) {
    SEQUENCE("列表循环"),
    SINGLE("单曲循环"),
    SHUFFLE("随机播放")
}

/**
 * 声乐实时音高采样点
 */
data class PitchSample(
    val timeMs: Long,
    val frequencyHz: Float,
    val confidence: Float,
    val rmsDb: Float,
    val midiNote: Float,
    val noteName: String,
    val centsOffset: Float
)

/**
 * 唱功多维度声学评测报告（端侧 DSP 真实计算，供 UI 展示与 AI 声乐导师深度点评）
 */
data class SingingEvaluation(
    val id: String = UUID.randomUUID().toString(),
    val songTitle: String,
    val songArtist: String = "",
    val songId: String = "",
    val audioFilePath: String,
    val durationMs: Long,
    val overallScore: Int,
    val pitchAccuracyScore: Int,
    val pitchStabilityScore: Int,
    val rhythmScore: Int,
    val breathControlScore: Int,
    val vocalRangeSemitones: Int,
    val lowestNote: String,
    val highestNote: String,
    val estimatedKey: String,
    val vibratoDetected: Boolean,
    val inTuneRatioPercent: Int,
    val medianAbsCentsError: Float,
    val offPitchMoments: List<String> = emptyList(),
    val aiFeedback: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 本地离线大模型目录规格（ModelScope 魔搭国内直连高速下载）
 */
data class LocalModelSpec(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeMb: Int,
    val minRamGb: Int,
    val downloadUrl: String,
    val fallbackUrl: String = "",
    val description: String,
    val recommended: Boolean = false
)

/**
 * 云端 AI 提供商预设（开箱内置大陆直连永久免费 API + 付费 API 扩展位）
 */
data class CloudAiPreset(
    val id: String,
    val name: String,
    val tag: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyHelpUrl: String,
    val description: String,
    val isFreeRecommended: Boolean = false
)

/**
 * AI 引擎路由模式
 */
enum class AiEngineMode(val label: String, val description: String) {
    AUTO_CLOUD_THEN_LOCAL("智能双模（优先免费API，离线自动切本地）", "联网时使用高智商免费/付费API，断网或未配Key时无缝切本地AI"),
    LOCAL_ONLY("纯本地离线 AI（llama.cpp GGUF）", "100% 端侧离线运行，零费用、零隐私上传"),
    CLOUD_ONLY("仅云端 API（免费 / 自定义付费）", "始终使用配置的 OpenAI 兼容云端模型")
}

/**
 * 全局 AI 配置
 */
data class AiConfig(
    val engineMode: AiEngineMode = AiEngineMode.AUTO_CLOUD_THEN_LOCAL,
    val cloudPresetId: String = "zhipu_glm47",
    val cloudBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val cloudApiKey: String = "",
    val cloudModel: String = "glm-4.7",
    val selectedLocalModelPath: String = "",
    val selectedLocalModelName: String = "内置轻量意图引擎（未加载 GGUF）",
    val localThreads: Int = 6,
    val localContextSize: Int = 2048,
    val enableWebSearchForAi: Boolean = true
)

/**
 * AI 执行 App 内部工具的透明审计记录
 */
data class ToolExecutionLog(
    val toolName: String,
    val displayTitle: String,
    val argumentsSummary: String,
    val resultSummary: String,
    val success: Boolean = true
)

/**
 * AI 对话消息
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant"
    val content: String,
    val engineBadge: String = "",
    val toolLogs: List<ToolExecutionLog> = emptyList(),
    val recommendedSongs: List<Song> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 用户听歌画像统计（供曲库展示及 AI 个性化荐歌）
 */
data class ListeningProfile(
    val totalPlays: Int,
    val favoriteCount: Int,
    val downloadCount: Int,
    val playlistCount: Int,
    val topArtists: List<Pair<String, Int>>,
    val recentSongs: List<Song>
)
