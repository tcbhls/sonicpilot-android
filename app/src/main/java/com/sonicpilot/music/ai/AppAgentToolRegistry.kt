package com.sonicpilot.music.ai

import com.sonicpilot.music.data.model.AppAccentStyle
import com.sonicpilot.music.data.model.AppThemeMode
import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.CantonesePhoneticMode
import com.sonicpilot.music.data.model.ChannelType
import com.sonicpilot.music.data.model.ChartCategory
import com.sonicpilot.music.data.model.DownloadStatus
import com.sonicpilot.music.data.model.LyricDisplayMode
import com.sonicpilot.music.data.model.MusicChannel
import com.sonicpilot.music.data.model.PlayMode
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.data.model.SongClassifier
import com.sonicpilot.music.data.model.ToolExecutionLog
import com.sonicpilot.music.data.repository.MusicRepository
import com.sonicpilot.music.data.settings.SettingsStore
import com.sonicpilot.music.player.PlayerController
import com.sonicpilot.music.player.SongDownloader
import com.sonicpilot.music.singing.SingingCoachEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 单次工具执行结果（包含给 LLM 看的结构化文本 + 给 UI 渲染的审计记录与附带歌曲卡片）
 */
data class ToolExecutionOutcome(
    val toolOutputForLlm: String,
    val auditLog: ToolExecutionLog,
    val surfacedSongs: List<Song> = emptyList(),
    val navigateToTab: String? = null,
    val openMvSong: Song? = null,
    val openPlayerMode: String? = null
)

/**
 * 音枢 App 专属 AI 权限与工具注册中心（25 项 App 内全权限沙箱）
 *
 * 严格权限隔离：
 * - 允许：AI 策展歌单、原唱防翻唱搜歌、联网寻曲、播放/插队/队列/变速/复读/睡眠定时/音质/模式控制、
 *         收藏与批量收藏、歌单管理、无损批量下载、榜单拉取、多渠道插件增删改测速、双语歌词读取、
 *         深浅外观与粤拼/双语歌词界面控制、听歌画像分析、声乐棚 DSP 唱功评测与点评写回。
 * - 禁止：不暴露任何系统通讯录、短信、通话、定位、外部文件系统或系统设置 API。
 */
class AppAgentToolRegistry(
    private val repository: MusicRepository,
    private val playerController: PlayerController,
    private val downloader: SongDownloader,
    private val settings: SettingsStore,
    private val singingCoach: SingingCoachEngine,
    private val webSearcher: WebMusicSearcher
) {
    // 近期工具检索出的歌曲缓存，便于 AI 随后直接按 song_id / 标题 播放、收藏或下载
    private val recentSongCache = ConcurrentHashMap<String, Song>()

    // 最近一次展示/推荐给用户的歌曲清单，支持“把刚才推荐的那几首歌全都收藏/加到歌单”
    @Volatile
    private var lastSurfacedSongs: List<Song> = emptyList()

    private fun cacheSongs(songs: List<Song>, recordAsLastSurfaced: Boolean = false) {
        songs.forEach {
            recentSongCache[it.id] = it
            recentSongCache[it.uniqueKey] = it
            recentSongCache[it.rawTrackId] = it
        }
        if (recordAsLastSurfaced && songs.isNotEmpty()) {
            lastSurfacedSongs = songs.distinctBy { it.uniqueKey }.take(12)
        }
    }

    fun getLastSurfacedSongs(): List<Song> = lastSurfacedSongs

    private fun findSongInContext(songIdOrQuery: String?): Song? {
        if (songIdOrQuery.isNullOrBlank()) return playerController.state.value.currentSong
        recentSongCache[songIdOrQuery]?.let { return it }
        playerController.state.value.currentSong?.takeIf {
            it.id == songIdOrQuery || it.rawTrackId == songIdOrQuery || it.title.equals(songIdOrQuery, true)
        }?.let { return it }
        playerController.state.value.queue.firstOrNull {
            it.id == songIdOrQuery || it.rawTrackId == songIdOrQuery || it.title.contains(songIdOrQuery, true)
        }?.let { return it }
        repository.favorites.value.firstOrNull {
            it.id == songIdOrQuery || it.rawTrackId == songIdOrQuery || it.title.contains(songIdOrQuery, true)
        }?.let { return it }
        return recentSongCache.values.firstOrNull {
            it.title.contains(songIdOrQuery, true) || "${it.title} ${it.artist}".contains(songIdOrQuery, true)
        }
    }

    /**
     * 跨渠道原唱优先精准解析：先主渠道搜，若命中翻唱/假冒上传者或无匹配（如周杰伦在网易云无版权），自动补查 JOOX 音源并择优返回原唱
     */
    private suspend fun resolveBestOriginalTrack(
        title: String,
        artist: String = "",
        rawQuery: String = "",
        preferFavorites: Boolean = false
    ): Pair<Song?, List<Song>> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val searchQuery = rawQuery.trim().ifBlank {
            listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")
        }
        if (searchQuery.isBlank()) return null to emptyList()

        if (preferFavorites && cleanTitle.isNotBlank()) {
            val favCandidates = repository.favorites.value.filter { s ->
                s.title.contains(cleanTitle, ignoreCase = true) &&
                    (cleanArtist.isBlank() || s.artist.contains(cleanArtist, ignoreCase = true))
            }
            if (favCandidates.isNotEmpty()) {
                val bestFav = pickBestOriginalCandidate(favCandidates, cleanTitle, cleanArtist, searchQuery)
                if (bestFav != null) return bestFav to favCandidates
            }
        }

        val primaryResults = repository.channelManager.searchSongs(searchQuery, limit = 8)
        val combined = mutableListOf<Song>().apply { addAll(primaryResults) }
        val primaryBest = pickBestOriginalCandidate(primaryResults, cleanTitle.ifBlank { searchQuery }, cleanArtist, searchQuery)
        val primaryScore = primaryBest?.let { scoreOriginalCandidate(it, cleanTitle.ifBlank { searchQuery }, cleanArtist, searchQuery) } ?: Int.MIN_VALUE

        // 若主渠道没搜到或得分偏低（常见于周杰伦/腾讯版权曲目在网易云仅有翻唱），自动并发补查 JOOX 无损源
        val hasJoox = repository.channelManager.channels.value.any { it.id == "gd_joox" && it.enabled }
        if (hasJoox && (primaryBest == null || primaryScore < 65)) {
            val jooxResults = runCatching {
                repository.channelManager.searchSongs(searchQuery, channelId = "gd_joox", limit = 6)
            }.getOrDefault(emptyList())
            combined.addAll(jooxResults)
        }

        val ranked = rankOriginalsFirst(
            combined.distinctBy { it.uniqueKey },
            cleanTitle.ifBlank { searchQuery },
            cleanArtist,
            searchQuery
        )
        cacheSongs(ranked)
        return ranked.firstOrNull() to ranked
    }

    /**
     * 导出符合 OpenAI Function Calling 规范的 25 项工具 JSON Schema
     */
    fun buildOpenAiToolsSchema(): JSONArray = JSONArray().apply {
        put(toolDef("curate_song_list", "【AI 音乐策展核心工具】当你根据用户的风格、情绪、场景、相似歌曲（如“像《体面》那样”）、特定年代/流派或指定多首连播来推荐/选歌时，必须用你自己的音乐品味列出精准的真实原唱歌曲列表，并调用本工具由 App 并行匹配真实高音质音源、自动过滤翻唱/DJ并生成可播放歌曲卡片！严禁只用纯文字罗列歌名而不调用本工具！") {
            propSongList("songs", "你精心挑选的歌曲数组（建议 3~8 首），每项包含 title（歌名）、artist（原唱歌手名）和 reason（一句走心的音乐推荐理由）", required = true)
            prop("theme", "string", "可选，本次策展的主题名称")
            prop("auto_play", "boolean", "可选，true 表示立即从第 1 首开始播放并把整批歌加入播放队列")
            prop("exclude_favorites", "boolean", "可选，true 表示自动剔除用户已收藏过的歌曲（找没听过的新歌时设为 true）")
            prop("save_playlist_name", "string", "可选，若填写则自动保存为用户的 AI 专属歌单")
        })
        put(toolDef("search_songs", "在 App 音乐渠道中搜索歌曲（自动原唱优先排序，支持华语、欧美、日韩、ACG 等全球曲库）") {
            prop("query", "string", "歌名、歌手或关键词", required = true)
            prop("channel_id", "string", "可选，指定渠道 ID；不填则默认走主渠道并自动补全")
            prop("limit", "integer", "返回数量，默认 8，最多 20")
        })
        put(toolDef("search_by_lyrics_or_web", "联网按模糊歌词片段、影视配乐描述或全球曲库情报帮用户找歌") {
            prop("query", "string", "歌词片段、模糊记忆或全球曲目描述", required = true)
        })
        put(toolDef("play_song", "【仅纯音频】以最高可用音质播放指定歌曲的纯音频（自动优先匹配原唱录音室版本）。注意：如果用户提到想看 MV / 播放 MV / 视频，禁止调用本工具，必须改用 play_mv！") {
            prop("song_id_or_query", "string", "歌曲 ID 或「歌名 歌手」关键词", required = true)
            prop("quality", "string", "可选音质：hires_999 / flac_740 / hq_320 / sq_128")
        })
        put(toolDef("play_next", "将指定歌曲插队到当前播放队列的「下一首播放」（不打断当前正在放的歌）") {
            prop("song_id_or_query", "string", "歌曲 ID 或「歌名 歌手」关键词", required = true)
        })
        put(toolDef("play_mv", "【看 MV / 视频专用】当用户想看某首歌的 MV、音乐视频或录影带时必须调用本工具，自动打开全屏 1080P/720P 高清 MV 播放器") {
            prop("song_id_or_query", "string", "可选，歌曲 ID 或「歌名 歌手」；不填默认播放当前正在听的歌的 MV")
        })
        put(toolDef("control_playback", "控制播放器：播放、暂停、下一首、上一首、跳转进度或切换循环模式") {
            prop("action", "string", "操作：play / pause / toggle / next / previous / seek / mode_sequence / mode_single / mode_shuffle", required = true)
            prop("position_sec", "integer", "仅当 action=seek 时有效，目标秒数")
        })
        put(toolDef("set_sleep_timer", "设置或取消睡眠定时自动停止播放（例如 15/30/60 分钟后停，传 0 表示取消定时）") {
            prop("minutes", "integer", "多少分钟后暂停播放（0 表示取消定时器）", required = true)
        })
        put(toolDef("manage_queue", "查看或管理当前播放队列：列出队列歌曲、跳播队列第几首、移除某首或随机打乱当前队列") {
            prop("action", "string", "操作：list / play_index / remove_index / shuffle_queue", required = true)
            prop("index", "integer", "仅当 play_index / remove_index 时必填，从 1 开始计数")
        })
        put(toolDef("control_learning_playback", "学歌与沉浸歌词控制：原调变速慢放（0.5x~1.5x）、上一句/下一句歌词微调、开启/关闭当前句 A-B 单句循环复读，或直达全屏黑胶/沉浸歌词/学歌室界面") {
            prop("speed", "number", "可选，播放倍速（0.5 ~ 1.5，如 0.75 慢速学歌，1.0 恢复原速）")
            prop("step_line", "integer", "可选，歌词句步进：-1 回到上一句，1 跳到下一句")
            prop("toggle_loop_current", "boolean", "可选，true 开启当前句循环复读，false 关闭单句循环")
            prop("open_player_mode", "string", "可选，打开全屏播放器视图：vinyl（黑胶）/ lyrics（全屏沉浸歌词）/ learn（学歌室）")
        })
        put(toolDef("set_audio_quality", "切换全局默认播放与下载音质，并对当前歌曲立即生效") {
            prop("quality", "string", "音质代码：hires_999（母带无损）/ flac_740（FLAC 无损）/ hq_320（320K 极高）/ sq_128（标准）", required = true)
        })
        put(toolDef("get_playback_state", "获取当前正在播放的歌曲、音质、倍速、定时关、进度、播放模式与播放队列") {})
        put(toolDef("get_lyrics", "获取当前播放歌曲或指定歌曲的双语 LRC 歌词，用于赏析、翻译或指导演唱") {
            prop("song_id_or_query", "string", "可选，不填默认取当前正在播放歌曲")
        })
        put(toolDef("toggle_favorite", "收藏或取消收藏当前歌曲或指定歌曲") {
            prop("song_id_or_query", "string", "可选，歌曲 ID 或歌名，不填则操作当前正在播放的歌曲")
            prop("favorite", "boolean", "true 表示收藏，false 表示取消收藏；不填则反转状态")
        })
        put(toolDef("batch_favorite_recent", "将刚才推荐/展示给用户的那批歌曲一次性全部收藏（或取消收藏），也可顺手打包加入指定歌单") {
            prop("favorite", "boolean", "可选，true 批量收藏（默认），false 批量取消收藏")
            prop("playlist_name", "string", "可选，若填写则同时把这批歌加入该自建歌单（不存在则自动创建）")
            prop("count", "integer", "可选，仅操作前几首；不填则操作刚才展示的全部歌曲")
        })
        put(toolDef("control_app_ui", "控制 App 界面外观、歌词显示模式、粤语注音方式或直接跳转页面（深浅主题/配色/仅英文歌词/双语歌词/粤拼或谐音注音/切页面）") {
            prop("theme_mode", "string", "可选外观主题：light（纯净浅色）/ dark（极夜深色）/ system（跟随系统）")
            prop("accent_style", "string", "可选主题色：cloud_red（绯红）/ qq_green（翡翠绿）/ vinyl_gold（黑胶金）/ ocean_blue（海蓝）")
            prop("lyric_display_mode", "string", "可选歌词语言模式：bilingual（中英双语）/ original_only（仅英文或原文）/ translation_only（仅中文译文）")
            prop("cantonese_phonetic_mode", "string", "可选粤语注音模式：jyutping（标准粤拼）/ homophone（普通话谐音）/ off（关闭注音）")
            prop("navigate_tab", "string", "可选跳转底部页面：discover（发现）/ library（曲库）/ ai（AI管家）/ singing（声乐棚）")
            prop("open_player_mode", "string", "可选直接展开全屏播放页：vinyl（黑胶）/ lyrics（全屏沉浸歌词）/ learn（学歌室）")
        })
        put(toolDef("query_favorites", "【大曲库核心工具】按语种、流派、歌手、关键词或离线状态精准检索用户全部收藏（700+首全量扫描不截断），并可直接一步完成播放、批量无损下载或打包创建歌单！") {
            prop("language", "string", "可选语种筛选：国语 / 粤语 / 英语 / 日语 / 韩语 / 闽南语 / 纯音乐 / 俄语")
            prop("genre", "string", "可选流派筛选：流行 / 说唱 / 电子音乐 / 摇滚 / 民谣 / 节奏布鲁斯 / 影视原声 / 国风 / 乡村乐 / 爵士 / 古典音乐")
            prop("artist", "string", "可选歌手名筛选，如：陈奕迅 / 周杰伦 / 林俊杰 / Eminem")
            prop("keyword", "string", "可选歌名或专辑关键词")
            prop("downloaded_only", "boolean", "可选，true 仅看已离线歌曲")
            prop("not_downloaded_only", "boolean", "可选，true 仅看未离线歌曲")
            prop("has_mv", "boolean", "可选，true 仅看含官方 MV 的歌曲")
            prop("shuffle", "boolean", "可选，true 表示随机打乱筛选结果（随机播/随机抽几首时设为 true）")
            prop("limit", "integer", "列表返回条数，默认 12，最多 30")
            prop("action", "string", "执行动作：list（仅查询统计，默认）/ play（立即播放这批收藏）/ download（批量无损下载这批收藏）/ create_playlist（将这批收藏打包为新歌单）")
            prop("playlist_name", "string", "仅当 action=create_playlist 时的歌单名称")
            prop("quality", "string", "仅当 action=download 时的音质代码：hires_999 / flac_740 / hq_320 / mq_192")
        })
        put(toolDef("list_favorites", "查看收藏概览与分类统计（也可传 language/genre/artist/action 筛选，等同于 query_favorites）") {
            prop("language", "string", "可选语种：国语 / 粤语 / 英语 / 日语 / 韩语 / 纯音乐")
            prop("genre", "string", "可选流派：流行 / 说唱 / 电子音乐 / 摇滚 / 民谣 / 节奏布鲁斯 / 影视原声")
            prop("artist", "string", "可选歌手名")
            prop("action", "string", "可选：list / play / download / create_playlist")
            prop("shuffle", "boolean", "可选，是否随机")
        })
        put(toolDef("manage_download_queue", "控制大批量下载队列：查看总进度、暂停队列、恢复继续下载、一键重试全部失败或清空排队") {
            prop("action", "string", "操作：status / pause / resume / retry_failed / cancel_queued", required = true)
        })
        put(toolDef("manage_playlist", "管理自建歌单：查看、创建、加歌、删歌或直接播放某个歌单") {
            prop("action", "string", "操作：list / create / add_song / remove_song / play / delete", required = true)
            prop("playlist_name", "string", "歌单名称")
            prop("description", "string", "创建歌单时的简介")
            prop("song_id_or_query", "string", "加歌或删歌时的歌曲 ID 或歌名")
        })
        put(toolDef("download_song", "下载当前歌曲或指定歌曲（优先无损 FLAC/最高音质）到本地离线曲库并导出到 Music/SonicPilot") {
            prop("song_id_or_query", "string", "可选，歌曲 ID 或歌名，不填则下载当前播放歌曲")
            prop("quality", "string", "可选音质：hires_999 / flac_740 / hq_320")
        })
        put(toolDef("list_downloads", "查看已下载的本地离线歌曲列表、音质码率与文件大小") {})
        put(toolDef("get_charts", "获取全球与华语官方热门榜单") {
            prop("chart", "string", "榜单：hot_cn（热歌榜）/ douyin（抖音热歌榜）/ soaring（飙升榜）/ billboard（全球 Billboard）/ new_songs（新歌榜）/ jp_oricon（日本Oricon榜）", required = true)
            prop("limit", "integer", "返回条数，默认 10")
        })
        put(toolDef("recommend_songs", "按榜单或宽泛口味快速拉取热门新歌（默认避开已收藏）。注意：若用户提出具体风格/情绪/相似曲目需求，请优先使用更精准的 curate_song_list！") {
            prop("mood_or_scene", "string", "心情、场景、语种或榜单，如：抖音热歌、飙升榜、猜你喜欢", required = true)
            prop("count", "integer", "推荐数量，默认 6")
            prop("auto_play", "boolean", "是否立即开始播放第一首并加入队列")
            prop("exclude_favorites", "boolean", "是否自动排除用户已收藏的歌曲，默认 true")
            prop("save_playlist_name", "string", "可选，若填写则自动创建并保存为 AI 专属歌单")
        })
        put(toolDef("list_channels", "查看当前 App 内全部音乐渠道配置、主渠道与启用状态") {})
        put(toolDef("manage_channel", "管理音乐渠道插件：添加新渠道、切换主渠道、启用/禁用、测速或删除自定义渠道") {
            prop("action", "string", "操作：add / set_primary / enable / disable / test / delete", required = true)
            prop("channel_id", "string", "已有渠道 ID（非 add 时必填）")
            prop("name", "string", "新增渠道名称")
            prop("type", "string", "新增渠道协议：GDSTUDIO / METING / CUSTOM_REST / SUBSONIC")
            prop("base_url", "string", "新增渠道 API 基础地址")
            prop("source_key", "string", "平台标识如 netease / joox / kuwo / tencent")
        })
        put(toolDef("evaluate_singing", "读取声乐棚最新或指定一次练唱录音的端侧 DSP 五维评测数据（音准、音分误差、节奏、气息、音域、调性、颤音与跑调时间点），用于给用户做专业声乐点评；若无录音可引导跳转声乐棚") {
            prop("evaluation_id", "string", "可选，评测记录 ID；不填默认取最新一条录音评测")
            prop("save_feedback", "string", "可选，将你生成的点评摘要写回该评测档案保存")
            prop("open_studio", "boolean", "可选，为 true 时引导跳转至声乐棚开始新录音")
        })
        put(toolDef("get_listening_profile", "读取用户的听歌统计画像：总播放数、最常听歌手、最近播放曲目与收藏数量") {})
    }

    /**
     * 为本地端侧 GGUF 模型生成的紧凑工具目录摘要（节省 Token，高工具命中率）
     */
    fun buildCompactLocalToolSpec(): String = """
可用 App 内部工具（严格使用 <tool_call>{"name":"工具名","arguments":{...}}</tool_call> 格式调用）：
1. curate_song_list(songs:[{title,artist,reason}], auto_play?, exclude_favorites?, save_playlist_name?) - AI 精准策展真实可播歌单
2. search_songs(query, limit?) - 原唱优先搜歌
3. search_by_lyrics_or_web(query) - 按歌词片段或描述联网找歌
4. play_song(song_id_or_query, quality?) - 搜并立即高音质播放原唱
5. play_next(song_id_or_query) - 插队到下一首播放
6. play_mv(song_id_or_query?) - 打开全屏高清 MV 播放器
7. control_playback(action) - play/pause/toggle/next/previous/mode_sequence/mode_single/mode_shuffle
8. set_sleep_timer(minutes) - 睡眠定时关闭（0取消）
9. manage_queue(action, index?) - list/play_index/remove_index/shuffle_queue
10. control_learning_playback(speed?, step_line?, toggle_loop_current?, open_player_mode?) - 变速/逐句复读/打开歌词学歌室
11. set_audio_quality(quality) - hires_999/flac_740/hq_320/sq_128
12. get_playback_state() - 查看播放状态
13. get_lyrics(song_id_or_query?) - 读取双语歌词
14. toggle_favorite(song_id_or_query?, favorite?) - 单曲收藏
15. batch_favorite_recent(favorite?, playlist_name?) - 一键批量收藏刚才推荐的歌
16. control_app_ui(theme_mode?, accent_style?, lyric_display_mode?, cantonese_phonetic_mode?, navigate_tab?, open_player_mode?) - 控制外观、双语/仅英文歌词与粤拼/谐音
17. query_favorites(language?, genre?, artist?, keyword?, shuffle?, action?, playlist_name?, quality?) - 全量筛选收藏并播放/下载/建歌单
18. manage_download_queue(action) - status/pause/resume/retry_failed/cancel_queued
19. manage_playlist(action, playlist_name?, song_id_or_query?) - 歌单增删播
20. download_song(song_id_or_query?, quality?) - 无损下载单曲
21. get_charts(chart, limit?) - 热门榜单
22. recommend_songs(mood_or_scene, count?, auto_play?, exclude_favorites?) - 榜单/热门新歌
23. evaluate_singing(evaluation_id?, open_studio?) - 声乐唱功评测
    """.trimIndent()

    /**
     * 执行单个 App 内部工具
     */
    suspend fun execute(toolName: String, argsJson: String): ToolExecutionOutcome = withContext(Dispatchers.IO) {
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        try {
            when (toolName) {
                "curate_song_list" -> handleCurateSongList(args)
                "search_songs" -> handleSearchSongs(args)
                "search_by_lyrics_or_web" -> handleWebSearch(args)
                "play_song" -> handlePlaySong(args)
                "play_next" -> handlePlayNext(args)
                "play_mv" -> handlePlayMv(args)
                "control_playback" -> handleControlPlayback(args)
                "set_sleep_timer" -> handleSetSleepTimer(args)
                "manage_queue" -> handleManageQueue(args)
                "control_learning_playback" -> handleControlLearningPlayback(args)
                "set_audio_quality" -> handleSetQuality(args)
                "get_playback_state" -> handleGetPlaybackState()
                "get_lyrics" -> handleGetLyrics(args)
                "toggle_favorite" -> handleToggleFavorite(args)
                "batch_favorite_recent" -> handleBatchFavoriteRecent(args)
                "control_app_ui" -> handleControlAppUi(args)
                "query_favorites", "list_favorites" -> handleQueryFavorites(args)
                "manage_download_queue" -> handleManageDownloadQueue(args)
                "manage_playlist" -> handleManagePlaylist(args)
                "download_song" -> handleDownloadSong(args)
                "list_downloads" -> handleListDownloads()
                "get_charts" -> handleGetCharts(args)
                "recommend_songs" -> handleRecommendSongs(args)
                "list_channels" -> handleListChannels()
                "manage_channel" -> handleManageChannel(args)
                "evaluate_singing" -> handleEvaluateSinging(args)
                "get_listening_profile" -> handleListeningProfile()
                else -> ToolExecutionOutcome(
                    toolOutputForLlm = "未知工具：$toolName（超出 App 权限白名单，已拒绝）",
                    auditLog = ToolExecutionLog(toolName, "权限拦截", argsJson, "工具不在 App 沙箱白名单内", false)
                )
            }
        } catch (e: Exception) {
            ToolExecutionOutcome(
                toolOutputForLlm = "工具 $toolName 执行异常：${e.message}",
                auditLog = ToolExecutionLog(toolName, "执行异常", argsJson, e.message ?: "未知错误", false)
            )
        }
    }

    private suspend fun handleCurateSongList(args: JSONObject): ToolExecutionOutcome {
        val rawArr = args.optJSONArray("songs") ?: JSONArray()
        val theme = args.optString("theme").trim()
        val autoPlay = args.optBoolean("auto_play", false)
        val excludeFavorites = args.optBoolean("exclude_favorites", false)
        val savePlaylistName = args.optString("save_playlist_name").trim()

        data class CuratedSpec(val title: String, val artist: String, val reason: String)

        val specs = buildList {
            for (i in 0 until minOf(rawArr.length(), 10)) {
                val item = rawArr.opt(i)
                when (item) {
                    is JSONObject -> {
                        val t = item.optString("title").ifBlank { item.optString("name") }.trim()
                        val a = item.optString("artist").ifBlank { item.optString("singer") }.trim()
                        val r = item.optString("reason").trim()
                        if (t.isNotBlank()) add(CuratedSpec(t, a, r))
                    }
                    is String -> {
                        val parts = item.split(" - ", "-", "—", "/", limit = 2).map { it.trim() }
                        if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                            add(CuratedSpec(parts[0], parts.getOrElse(1) { "" }, ""))
                        }
                    }
                }
            }
        }

        if (specs.isEmpty()) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "curate_song_list 未收到有效歌曲清单，请提供 songs 数组（包含 title 与 artist）。",
                auditLog = ToolExecutionLog("curate_song_list", "AI 品味策展", theme.ifBlank { "空清单" }, "缺少候选曲目", false)
            )
        }

        val favKeys = repository.favoriteKeys.value
        val favTitleArtist = repository.favorites.value.map {
            "${normMatchText(it.title)}|${normMatchText(it.artist.split("/", "、", ",").firstOrNull().orEmpty())}"
        }.toHashSet()

        // 并发解析全部策展曲目（主渠道 + JOOX 自动防翻唱兜底，4~8 首歌 ~1.5s 全部就绪！）
        val resolvedPairs = coroutineScope {
            specs.map { spec ->
                async(Dispatchers.IO) {
                    val (song, _) = resolveBestOriginalTrack(
                        title = spec.title,
                        artist = spec.artist,
                        preferFavorites = !excludeFavorites
                    )
                    spec to song
                }
            }.awaitAll()
        }

        val matchedAll = resolvedPairs.mapNotNull { (spec, song) -> song?.let { spec to it } }
            .distinctBy { it.second.uniqueKey }
        val filtered = if (excludeFavorites) {
            matchedAll.filterNot { (_, s) ->
                val primaryArtist = s.artist.split("/", "、", ",").firstOrNull().orEmpty()
                favKeys.contains(s.uniqueKey) || favTitleArtist.contains("${normMatchText(s.title)}|${normMatchText(primaryArtist)}")
            }
        } else matchedAll

        val finalPairs = if (filtered.isNotEmpty()) filtered else matchedAll
        val finalSongs = finalPairs.map { it.second }
        cacheSongs(finalSongs, recordAsLastSurfaced = true)

        if (finalSongs.isEmpty()) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "已尝试匹配 ${specs.size} 首策展歌曲，但当前音乐渠道暂未返回可播音源，建议换一批经典曲目或调用 recommend_songs。",
                auditLog = ToolExecutionLog("curate_song_list", "AI 品味策展", theme.ifBlank { "${specs.size} 首" }, "未匹配到可播音源", false)
            )
        }

        if (savePlaylistName.isNotBlank()) {
            val pl = repository.createPlaylist(
                savePlaylistName,
                theme.ifBlank { "由音枢 AI (GLM-4.7) 专属品味策展" },
                createdByAi = true
            )
            repository.bulkAddSongsToPlaylist(pl.id, finalSongs)
        }
        if (autoPlay) {
            withContext(Dispatchers.Main) {
                playerController.playQueue(finalSongs, 0)
            }
        }

        val skippedCount = specs.size - finalSongs.size
        val text = buildString {
            appendLine("已为你真实匹配到 ${finalSongs.size} 首高音质原唱曲目${if (theme.isNotBlank()) "（主题：$theme）" else ""}：")
            finalPairs.forEachIndexed { idx, (spec, song) ->
                val reasonPart = if (spec.reason.isNotBlank()) "｜推荐理由：${spec.reason}" else ""
                appendLine("${idx + 1}. 《${song.title}》 - ${song.artist} [渠道:${song.channelId}, id=${song.id}]$reasonPart")
            }
            if (skippedCount > 0) {
                appendLine("（另有 $skippedCount 首因渠道无原唱或已收藏自动过滤）")
            }
            if (savePlaylistName.isNotBlank()) appendLine("已自动保存至自建歌单「$savePlaylistName」。")
            if (autoPlay) appendLine("已立即从第 1 首《${finalSongs.first().title}》开播并按顺序排入队列。")
        }

        return ToolExecutionOutcome(
            toolOutputForLlm = text,
            auditLog = ToolExecutionLog(
                "curate_song_list",
                if (autoPlay) "AI 策展并开播" else "AI 品味策展",
                theme.ifBlank { "${specs.size} 首定制选曲" },
                "匹配原唱 ${finalSongs.size} 首${if (autoPlay) " · 已开播" else ""}",
                true
            ),
            surfacedSongs = finalSongs.take(8)
        )
    }

    private suspend fun handleSearchSongs(args: JSONObject): ToolExecutionOutcome {
        val query = args.optString("query").trim()
        val channelId = args.optString("channel_id").takeIf { it.isNotBlank() }
        val limit = args.optInt("limit", 8).coerceIn(1, 20)
        val rawSongs = repository.channelManager.searchSongs(query, channelId, limit)
        val songs = rankOriginalsFirst(rawSongs, query, "", query)
        cacheSongs(songs, recordAsLastSurfaced = true)
        val summary = if (songs.isEmpty()) {
            "未搜索到与「$query」匹配的歌曲。"
        } else {
            songs.mapIndexed { idx, s ->
                "${idx + 1}. 《${s.title}》 - ${s.artist} | 专辑:${s.album} | id=${s.id} | 渠道=${s.channelId}"
            }.joinToString("\n")
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = summary,
            auditLog = ToolExecutionLog("search_songs", "联网搜歌", "关键词: $query", "找到 ${songs.size} 首歌曲", songs.isNotEmpty()),
            surfacedSongs = songs.take(6)
        )
    }

    private suspend fun handleWebSearch(args: JSONObject): ToolExecutionOutcome {
        val query = args.optString("query").trim()
        if (!settings.aiConfig.value.enableWebSearchForAi) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "用户在设置中关闭了 AI 联网搜歌权限。",
                auditLog = ToolExecutionLog("search_by_lyrics_or_web", "联网寻曲", query, "AI 联网检索已关闭", false)
            )
        }
        val intel = webSearcher.searchMusicWeb(query)
        // 同时用主渠道尝试抓几首可直接播放的候选曲
        val playable = rankOriginalsFirst(repository.channelManager.searchSongs(query, limit = 5), query, "", query)
        cacheSongs(playable, recordAsLastSurfaced = true)
        return ToolExecutionOutcome(
            toolOutputForLlm = "$intel\n\n可直接播放候选：\n" + playable.joinToString("\n") {
                "- 《${it.title}》 - ${it.artist} (id=${it.id})"
            },
            auditLog = ToolExecutionLog("search_by_lyrics_or_web", "联网歌词/全网寻曲", query, "已检索全球曲库与歌词线索", true),
            surfacedSongs = playable
        )
    }

    private suspend fun handlePlaySong(args: JSONObject): ToolExecutionOutcome {
        val target = args.optString("song_id_or_query").ifBlank { args.optString("query") }.trim()
        val quality = args.optString("quality").takeIf { it.isNotBlank() }?.let { AudioQuality.fromCode(it) }
        var song = findSongInContext(target)
        var relatedList: List<Song> = emptyList()
        if (song == null && target.isNotBlank()) {
            val (best, ranked) = resolveBestOriginalTrack(title = "", artist = "", rawQuery = target)
            song = best
            relatedList = ranked
        }
        if (song == null) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "未找到「$target」，无法播放。",
                auditLog = ToolExecutionLog("play_song", "播放歌曲", target, "未找到匹配曲目", false)
            )
        }
        cacheSongs(listOf(song), recordAsLastSurfaced = true)
        withContext(Dispatchers.Main) {
            playerController.playSong(song, newQueue = relatedList.takeIf { it.isNotEmpty() }, overrideQuality = quality)
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = "已开始以 ${(quality ?: settings.preferredQuality.value).displayName} 播放：《${song.title}》 - ${song.artist}（渠道：${song.channelId}）。",
            auditLog = ToolExecutionLog("play_song", "立即播放", "《${song.title}》 - ${song.artist}", "已触发高音质解析与播放", true),
            surfacedSongs = listOf(song)
        )
    }

    private suspend fun handlePlayNext(args: JSONObject): ToolExecutionOutcome {
        val target = args.optString("song_id_or_query").ifBlank { args.optString("query") }.trim()
        if (target.isBlank()) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "请告诉我你想插队到下一首播放哪首歌。",
                auditLog = ToolExecutionLog("play_next", "下一首播放", "未指定曲目", "缺少歌名", false)
            )
        }
        var song = findSongInContext(target)
        if (song == null) {
            val (best, _) = resolveBestOriginalTrack(title = "", artist = "", rawQuery = target, preferFavorites = true)
            song = best
        }
        if (song == null) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "未找到「$target」，无法加入下一首播放。",
                auditLog = ToolExecutionLog("play_next", "下一首播放", target, "未找到匹配曲目", false)
            )
        }
        cacheSongs(listOf(song), recordAsLastSurfaced = true)
        withContext(Dispatchers.Main) {
            playerController.playNext(song)
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = "已将《${song.title}》 - ${song.artist} 插入到当前播放队列的下一首，播完当前这首就会无缝接续！",
            auditLog = ToolExecutionLog("play_next", "下一首插队播放", "《${song.title}》 - ${song.artist}", "已插队至下一首", true),
            surfacedSongs = listOf(song)
        )
    }

    private suspend fun handleSetSleepTimer(args: JSONObject): ToolExecutionOutcome {
        val minutes = args.optInt("minutes", -1)
        if (minutes < 0) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "请提供有效的分钟数（例如 15、30、60，或 0 表示取消）。",
                auditLog = ToolExecutionLog("set_sleep_timer", "睡眠定时", "无效分钟数", "参数无效", false)
            )
        }
        val clamped = minutes.coerceIn(0, 360)
        withContext(Dispatchers.Main) {
            playerController.setSleepTimerMinutes(clamped)
        }
        val msg = if (clamped == 0) {
            "已取消睡眠定时关闭，音乐将继续正常播放。"
        } else {
            "已开启睡眠定时：$clamped 分钟后将自动暂停播放，安心休息吧。"
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = msg,
            auditLog = ToolExecutionLog("set_sleep_timer", if (clamped == 0) "取消定时关闭" else "睡眠定时关闭", "${clamped} 分钟", msg, true)
        )
    }

    private suspend fun handleManageQueue(args: JSONObject): ToolExecutionOutcome {
        val action = args.optString("action", "list").lowercase().trim()
        val st = playerController.state.value
        val queue = st.queue
        if (queue.isEmpty()) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "当前播放队列为空，可以先点一首歌或让我帮你策展一份歌单。",
                auditLog = ToolExecutionLog("manage_queue", "管理播放队列", action, "队列为空", true)
            )
        }
        return when (action) {
            "play_index" -> {
                val idx = (args.optInt("index", 1) - 1).coerceIn(0, queue.lastIndex)
                val target = queue[idx]
                withContext(Dispatchers.Main) { playerController.playQueue(queue, idx) }
                ToolExecutionOutcome(
                    toolOutputForLlm = "已跳播队列第 ${idx + 1} 首：《${target.title}》 - ${target.artist}。",
                    auditLog = ToolExecutionLog("manage_queue", "队列跳播", "第 ${idx + 1} 首", "《${target.title}》", true),
                    surfacedSongs = listOf(target)
                )
            }
            "remove_index" -> {
                val idx = args.optInt("index", -1) - 1
                if (idx !in queue.indices) {
                    ToolExecutionOutcome(
                        toolOutputForLlm = "队列共有 ${queue.size} 首，序号 ${idx + 1} 超出范围。",
                        auditLog = ToolExecutionLog("manage_queue", "移除队列歌曲", "序号 ${idx + 1}", "序号越界", false)
                    )
                } else {
                    val removed = queue[idx]
                    withContext(Dispatchers.Main) { playerController.removeFromQueue(idx) }
                    ToolExecutionOutcome(
                        toolOutputForLlm = "已从播放队列移除第 ${idx + 1} 首《${removed.title}》 - ${removed.artist}。",
                        auditLog = ToolExecutionLog("manage_queue", "移除队列歌曲", "《${removed.title}》", "已移出队列", true)
                    )
                }
            }
            "shuffle_queue" -> {
                withContext(Dispatchers.Main) { playerController.playShuffled(queue) }
                val first = playerController.state.value.currentSong
                ToolExecutionOutcome(
                    toolOutputForLlm = "已将当前队列全部 ${queue.size} 首歌曲随机打乱并切换为随机播放${first?.let { "，正在播放《${it.title}》" }.orEmpty()}。",
                    auditLog = ToolExecutionLog("manage_queue", "打乱播放队列", "${queue.size} 首", "已随机重排", true)
                )
            }
            else -> {
                val curIdx = st.currentIndex
                val start = (curIdx - 2).coerceAtLeast(0)
                val end = (start + 15).coerceAtMost(queue.size)
                val lines = (start until end).joinToString("\n") { i ->
                    val s = queue[i]
                    val mark = if (i == curIdx) "▶ " else "  "
                    "$mark${i + 1}. 《${s.title}》 - ${s.artist}"
                }
                ToolExecutionOutcome(
                    toolOutputForLlm = "当前播放队列共 ${queue.size} 首（当前第 ${curIdx + 1} 首 · 模式：${st.playMode.label}）：\n$lines",
                    auditLog = ToolExecutionLog("manage_queue", "查看播放队列", "共 ${queue.size} 首", "当前第 ${curIdx + 1} 首", true),
                    surfacedSongs = queue.subList(start, minOf(start + 6, queue.size))
                )
            }
        }
    }

    private suspend fun handleControlLearningPlayback(args: JSONObject): ToolExecutionOutcome {
        val actionsDone = mutableListOf<String>()
        var targetMode: String? = null
        withContext(Dispatchers.Main) {
            if (args.has("speed")) {
                val spd = args.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 1.5f)
                playerController.setPlaybackSpeed(spd)
                actionsDone += "播放速度已调至 ${String.format("%.2f", spd).trimEnd('0').trimEnd('.')}x（原调不变声）"
            }
            if (args.has("step_line")) {
                val delta = args.optInt("step_line", 0)
                if (delta != 0) {
                    playerController.stepLyricLine(delta)
                    actionsDone += if (delta < 0) "已回退上一句歌词" else "已跳至下一句歌词"
                }
            }
            if (args.has("toggle_loop_current")) {
                val enable = args.optBoolean("toggle_loop_current", true)
                if (!enable) {
                    playerController.toggleLineLoop(null)
                    actionsDone += "已关闭单句循环复读"
                } else {
                    val lines = playerController.state.value.lyrics.lines
                    if (lines.isNotEmpty()) {
                        val pos = playerController.progress.value.positionMs
                        val curIdx = lines.indexOfLast { it.timeMs <= pos + 250L }.coerceAtLeast(0)
                        playerController.toggleLineLoop(curIdx)
                        actionsDone += "已开启当前句「${lines[curIdx].text.take(18)}」A-B 循环复读"
                    } else {
                        actionsDone += "当前歌曲暂无逐行时间轴歌词，暂无法单句循环"
                    }
                }
            }
        }
        val rawMode = args.optString("open_player_mode").lowercase().trim()
        if (rawMode.isNotBlank()) {
            targetMode = when {
                rawMode.contains("learn") || rawMode.contains("学") -> "learn"
                rawMode.contains("lyric") || rawMode.contains("词") -> "lyrics"
                else -> "vinyl"
            }
            actionsDone += when (targetMode) {
                "learn" -> "已为你展开「学歌室」逐句跟学界面"
                "lyrics" -> "已为你展开全屏「沉浸歌词」界面"
                else -> "已为你展开「黑胶播放器」"
            }
        }
        val msg = if (actionsDone.isEmpty()) "未检测到具体的学歌或播放控制参数。" else actionsDone.joinToString("；") + "。"
        return ToolExecutionOutcome(
            toolOutputForLlm = msg,
            auditLog = ToolExecutionLog("control_learning_playback", "学歌与播放控制", actionsDone.firstOrNull() ?: "控制", msg, actionsDone.isNotEmpty()),
            openPlayerMode = targetMode
        )
    }

    private suspend fun handleBatchFavoriteRecent(args: JSONObject): ToolExecutionOutcome {
        val favorite = args.optBoolean("favorite", true)
        val playlistName = args.optString("playlist_name").trim()
        val limit = args.optInt("count", 0)
        val pool = lastSurfacedSongs.ifEmpty { playerController.state.value.queue.take(6) }
        val targets = if (limit > 0) pool.take(limit) else pool
        if (targets.isEmpty()) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "当前对话里还没有近期展示或推荐过的歌曲列表，请先让我推荐或搜索几首歌。",
                auditLog = ToolExecutionLog("batch_favorite_recent", "批量收藏近期歌曲", "无候选曲目", "列表为空", false)
            )
        }
        targets.forEach { repository.setFavorite(it, favorite) }
        var plInfo = ""
        if (playlistName.isNotBlank()) {
            val pl = repository.findPlaylistByName(playlistName)
                ?: repository.createPlaylist(playlistName, "由音枢 AI 一键整理", createdByAi = true)
            repository.bulkAddSongsToPlaylist(pl.id, targets)
            plInfo = "，并同步加入歌单「${pl.name}」"
        }
        val verb = if (favorite) "批量收藏" else "批量取消收藏"
        val names = targets.joinToString("、") { "《${it.title}》" }
        val msg = "已为你${verb}共 ${targets.size} 首歌曲$plInfo：$names。"
        return ToolExecutionOutcome(
            toolOutputForLlm = msg,
            auditLog = ToolExecutionLog("batch_favorite_recent", verb, "${targets.size} 首歌曲", "已完成${verb}${plInfo}", true),
            surfacedSongs = targets.take(6)
        )
    }

    private fun handleControlAppUi(args: JSONObject): ToolExecutionOutcome {
        val changes = mutableListOf<String>()
        var navTab: String? = null
        var playerMode: String? = null

        val themeRaw = args.optString("theme_mode").lowercase().trim()
        if (themeRaw.isNotBlank()) {
            val mode = when {
                themeRaw.contains("light") || themeRaw.contains("浅") || themeRaw.contains("白") -> AppThemeMode.LIGHT
                themeRaw.contains("dark") || themeRaw.contains("深") || themeRaw.contains("黑") || themeRaw.contains("夜") -> AppThemeMode.DARK
                else -> AppThemeMode.SYSTEM
            }
            settings.setThemeMode(mode)
            changes += "外观主题已切换为「${mode.label}」"
        }

        val accentRaw = args.optString("accent_style").lowercase().trim()
        if (accentRaw.isNotBlank()) {
            val style = when {
                accentRaw.contains("green") || accentRaw.contains("qq") || accentRaw.contains("绿") -> AppAccentStyle.QQ_GREEN
                accentRaw.contains("gold") || accentRaw.contains("vinyl") || accentRaw.contains("金") -> AppAccentStyle.VINYL_GOLD
                accentRaw.contains("blue") || accentRaw.contains("ocean") || accentRaw.contains("蓝") -> AppAccentStyle.OCEAN_BLUE
                else -> AppAccentStyle.CLOUD_RED
            }
            settings.setAccentStyle(style)
            changes += "主题强调色已切换为「${style.label}」"
        }

        val lyricRaw = args.optString("lyric_display_mode").lowercase().trim()
        if (lyricRaw.isNotBlank()) {
            val lm = when {
                lyricRaw.contains("orig") || lyricRaw.contains("仅英") || lyricRaw.contains("仅原") || lyricRaw.contains("纯英") -> LyricDisplayMode.ORIGINAL_ONLY
                lyricRaw.contains("trans") || lyricRaw.contains("仅中") || lyricRaw.contains("仅译") -> LyricDisplayMode.TRANSLATION_ONLY
                else -> LyricDisplayMode.BILINGUAL
            }
            settings.setLyricDisplayMode(lm)
            changes += "歌词语言显示已切为「${lm.label}」"
        }

        val cantoRaw = args.optString("cantonese_phonetic_mode").lowercase().trim()
        if (cantoRaw.isNotBlank()) {
            val cm = when {
                cantoRaw.contains("homo") || cantoRaw.contains("谐音") -> CantonesePhoneticMode.HOMOPHONE
                cantoRaw.contains("off") || cantoRaw.contains("关") -> CantonesePhoneticMode.OFF
                else -> CantonesePhoneticMode.JYUTPING
            }
            settings.setCantonesePhoneticMode(cm)
            if (cm != CantonesePhoneticMode.OFF) settings.setAutoCantoneseJyutping(true)
            changes += "粤语注音已设为「${cm.label}」"
        }

        val tabRaw = args.optString("navigate_tab").lowercase().trim()
        if (tabRaw.isNotBlank()) {
            navTab = when {
                tabRaw.contains("lib") || tabRaw.contains("曲库") || tabRaw.contains("收藏") -> "library"
                tabRaw.contains("sing") || tabRaw.contains("声乐") -> "singing"
                tabRaw.contains("ai") -> "ai"
                else -> "discover"
            }
            val label = mapOf("discover" to "发现", "library" to "曲库", "ai" to "AI 管家", "singing" to "声乐棚")[navTab] ?: navTab
            changes += "已跳转至「$label」页"
        }

        val pmRaw = args.optString("open_player_mode").lowercase().trim()
        if (pmRaw.isNotBlank()) {
            playerMode = when {
                pmRaw.contains("learn") || pmRaw.contains("学") -> "learn"
                pmRaw.contains("lyric") || pmRaw.contains("词") -> "lyrics"
                else -> "vinyl"
            }
            val label = mapOf("vinyl" to "黑胶播放页", "lyrics" to "全屏沉浸歌词", "learn" to "学歌室")[playerMode] ?: "播放页"
            changes += "已打开「$label」"
        }

        val msg = if (changes.isEmpty()) "未检测到需要更改的界面或歌词显示选项。" else changes.joinToString("；") + "。"
        return ToolExecutionOutcome(
            toolOutputForLlm = msg,
            auditLog = ToolExecutionLog("control_app_ui", "界面与歌词设置", changes.firstOrNull() ?: "界面设置", msg, changes.isNotEmpty()),
            navigateToTab = navTab,
            openPlayerMode = playerMode
        )
    }

    private suspend fun handlePlayMv(args: JSONObject): ToolExecutionOutcome {
        val target = args.optString("song_id_or_query").ifBlank { args.optString("query") }.trim()
        var song = findSongInContext(target.ifBlank { null })
        if (song == null && target.isNotBlank()) {
            val (best, ranked) = resolveBestOriginalTrack(title = "", artist = "", rawQuery = target)
            song = ranked.firstOrNull { it.mvId.isNotBlank() } ?: best
        }
        if (song == null) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "当前没有正在播放的歌曲，也没有搜到「$target」的曲目。请告诉我你想看哪首歌的 MV（例如：看周杰伦《晴天》的 MV）。",
                auditLog = ToolExecutionLog("play_mv", "播放 MV", target.ifBlank { "当前歌曲" }, "未定位目标曲目", false)
            )
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = "已为你打开《${song.title}》 - ${song.artist} 的全屏高清 MV 播放器，正在自动匹配 1080P/720P 免费视频流。",
            auditLog = ToolExecutionLog("play_mv", "播放高清 MV", "《${song.title}》 - ${song.artist}", "已唤起全屏 MV 播放器", true),
            surfacedSongs = listOf(song),
            openMvSong = song
        )
    }

    private suspend fun handleControlPlayback(args: JSONObject): ToolExecutionOutcome {
        val action = args.optString("action").lowercase().trim()
        val msg = withContext(Dispatchers.Main) {
            when (action) {
                "play" -> { playerController.play(); "已继续播放" }
                "pause" -> { playerController.pause(); "已暂停播放" }
                "toggle" -> { playerController.togglePlayPause(); "已切换播放/暂停" }
                "next" -> { playerController.nextTrack(); "已切换至下一首" }
                "previous", "prev" -> { playerController.previousTrack(); "已切换至上一首" }
                "seek" -> {
                    val sec = args.optLong("position_sec", 0L).coerceAtLeast(0L)
                    playerController.seekTo(sec * 1000L)
                    "已跳转到 ${sec} 秒"
                }
                "mode_sequence" -> { playerController.setPlayMode(PlayMode.SEQUENCE); "已切换为列表循环" }
                "mode_single" -> { playerController.setPlayMode(PlayMode.SINGLE); "已切换为单曲循环" }
                "mode_shuffle" -> { playerController.setPlayMode(PlayMode.SHUFFLE); "已切换为随机播放" }
                else -> "未识别的播放指令：$action"
            }
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = msg,
            auditLog = ToolExecutionLog("control_playback", "播控操作", action, msg, !msg.startsWith("未识别"))
        )
    }

    private suspend fun handleSetQuality(args: JSONObject): ToolExecutionOutcome {
        val q = AudioQuality.fromCode(args.optString("quality"))
        withContext(Dispatchers.Main) {
            playerController.switchQualityAndReload(q)
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = "已将首选音质切换为：${q.displayName}（${q.badge}），当前播放与后续下载均优先请求该音质。",
            auditLog = ToolExecutionLog("set_audio_quality", "切换音质", q.displayName, "已更新全局首选音质", true)
        )
    }

    private fun handleGetPlaybackState(): ToolExecutionOutcome {
        val st = playerController.state.value
        val prog = playerController.progress.value
        val cur = st.currentSong
        val timerPart = if (st.sleepTimerRemainingMinutes > 0) " | 睡眠定时：剩余 ${st.sleepTimerRemainingMinutes} 分钟" else ""
        val speedPart = if (st.playbackSpeed != 1.0f) " | 倍速：${st.playbackSpeed}x" else ""
        val text = if (cur == null) {
            "当前播放器空闲，队列共有 ${st.queue.size} 首歌曲。首选音质：${settings.preferredQuality.value.displayName}$timerPart。"
        } else {
            val posSec = prog.positionMs / 1000
            val durSec = prog.durationMs / 1000
            "正在${if (st.isPlaying) "播放" else "暂停"}：《${cur.title}》 - ${cur.artist}（专辑：${cur.album}）\n" +
                "进度：${posSec}s / ${durSec}s | 实际音质：${st.qualityLabel} | 渠道：${st.activeChannelName}$speedPart$timerPart\n" +
                "播放模式：${st.playMode.label} | 是否已收藏：${repository.isFavorite(cur)} | 队列总数：${st.queue.size}"
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = text,
            auditLog = ToolExecutionLog("get_playback_state", "读取播放状态", "当前播放器快照", cur?.title ?: "空闲", true)
        )
    }

    private suspend fun handleGetLyrics(args: JSONObject): ToolExecutionOutcome {
        val target = args.optString("song_id_or_query").takeIf { it.isNotBlank() }
        val song = findSongInContext(target)
            ?: target?.let { repository.channelManager.searchSongs(it, limit = 1).firstOrNull() }
            ?: return ToolExecutionOutcome(
                toolOutputForLlm = "没有找到可读取歌词的目标歌曲。",
                auditLog = ToolExecutionLog("get_lyrics", "读取歌词", target ?: "当前歌曲", "无目标曲目", false)
            )
        val lrc = if (playerController.state.value.currentSong?.uniqueKey == song.uniqueKey &&
            playerController.state.value.lyrics.lines.isNotEmpty()
        ) {
            playerController.state.value.lyrics
        } else {
            repository.channelManager.fetchLyrics(song)
        }
        val text = if (lrc.lines.isEmpty()) {
            "《${song.title}》暂无逐行歌词。"
        } else {
            "《${song.title}》 - ${song.artist} 歌词（节选前 45 行）：\n" +
                lrc.lines.take(45).joinToString("\n") { line ->
                    val sec = line.timeMs / 1000
                    val ts = String.format("[%02d:%02d]", sec / 60, sec % 60)
                    if (line.translation.isNotBlank()) "$ts ${line.text} / ${line.translation}" else "$ts ${line.text}"
                }
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = text,
            auditLog = ToolExecutionLog("get_lyrics", "读取双语歌词", "《${song.title}》", "共 ${lrc.lines.size} 行", lrc.lines.isNotEmpty())
        )
    }

    private suspend fun handleToggleFavorite(args: JSONObject): ToolExecutionOutcome {
        val target = args.optString("song_id_or_query").takeIf { it.isNotBlank() }
        val song = findSongInContext(target)
            ?: target?.let { repository.channelManager.searchSongs(it, limit = 1).firstOrNull() }
            ?: return ToolExecutionOutcome(
                toolOutputForLlm = "当前没有正在播放或指定的歌曲可供收藏。",
                auditLog = ToolExecutionLog("toggle_favorite", "收藏操作", target ?: "当前歌曲", "未找到目标歌曲", false)
            )
        val newState = if (args.has("favorite")) {
            repository.setFavorite(song, args.optBoolean("favorite", true))
        } else {
            repository.toggleFavorite(song)
        }
        val desc = if (newState) "已将《${song.title}》加入「我喜欢的」收藏" else "已将《${song.title}》移出收藏"
        return ToolExecutionOutcome(
            toolOutputForLlm = desc,
            auditLog = ToolExecutionLog("toggle_favorite", if (newState) "收藏歌曲" else "取消收藏", "《${song.title}》", desc, true)
        )
    }

    /**
     * 获取用户收藏中出现的全部歌手名（供 AI 意图护航引擎精准识别歌手过滤条件）
     */
    fun getKnownFavoriteArtists(): Set<String> = repository.favorites.value
        .flatMap { it.artist.split("/", "、", ",") }
        .map { it.trim() }
        .filter { it.length >= 2 }
        .toSet()

    /**
     * 实时生成 App 当前全量状态与曲库分布快照（每次对话注入 System Prompt，让 AI 睁眼即掌握 792 首收藏全貌！）
     */
    fun buildLiveContextSnapshot(): String {
        val st = playerController.state.value
        val cur = st.currentSong
        val favs = repository.favorites.value
        val pls = repository.playlists.value
        val dls = repository.downloads.value
        val completedDl = dls.count { it.status == DownloadStatus.COMPLETED }
        val activeDl = dls.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }

        val langCounts = LinkedHashMap<String, Int>()
        val genreCounts = LinkedHashMap<String, Int>()
        val artistCounts = LinkedHashMap<String, Int>()
        favs.forEach { s ->
            val l = SongClassifier.resolveLanguage(s)
            langCounts[l] = (langCounts[l] ?: 0) + 1
            val g = SongClassifier.resolveGenre(s)
            genreCounts[g] = (genreCounts[g] ?: 0) + 1
            val a = s.artist.split("/", "、", ",").firstOrNull()?.trim().orEmpty()
            if (a.isNotBlank()) artistCounts[a] = (artistCounts[a] ?: 0) + 1
        }
        val langStr = langCounts.entries.sortedByDescending { it.value }.take(8).joinToString("、") { "${it.key}(${it.value})" }
        val genreStr = genreCounts.entries.sortedByDescending { it.value }.take(8).joinToString("、") { "${it.key}(${it.value})" }
        val artistStr = artistCounts.entries.sortedByDescending { it.value }.take(12).joinToString("、") { "${it.key}(${it.value})" }
        val plStr = if (pls.isEmpty()) "暂无自建歌单" else pls.take(6).joinToString("、") { "${it.name}(${it.songCount}首)" }

        return buildString {
            appendLine("【当前 App 实时上下文（真实数据，绝不编造）】")
            val speedStr = if (st.playbackSpeed != 1.0f) " · ${st.playbackSpeed}x" else ""
            val timerStr = if (st.sleepTimerRemainingMinutes > 0) " · 定时关剩余${st.sleepTimerRemainingMinutes}分" else ""
            appendLine("- 正在播放：${if (cur != null) "《${cur.title}》- ${cur.artist}（${if (st.isPlaying) "播放中" else "已暂停"} · ${st.qualityLabel.ifBlank { settings.preferredQuality.value.badge }}$speedStr$timerStr · 队列${st.queue.size}首）" else "空闲未播放"}")
            appendLine("- 收藏曲库总计：${favs.size} 首")
            if (favs.isNotEmpty()) {
                appendLine("- 收藏语种分布：$langStr")
                appendLine("- 收藏流派分布：$genreStr")
                appendLine("- 收藏最多歌手 Top12：$artistStr")
            }
            appendLine("- 自建歌单：$plStr | 离线下载：已完成 $completedDl 首，进行/排队 $activeDl 首 | 首选音质：${settings.preferredQuality.value.displayName}")
            appendLine("- 界面与学歌设置：主题[${settings.themeMode.value.label}/${settings.accentStyle.value.label}] · 歌词[${settings.lyricDisplayMode.value.label}] · 粤语注音[${settings.cantonesePhoneticMode.value.label}]" +
                if (lastSurfacedSongs.isNotEmpty()) " · 上轮已展示${lastSurfacedSongs.size}首候选曲(支持一键批量收藏)" else "")
        }.trim()
    }

    private fun normalizeFilterLanguage(raw: String): String {
        val s = raw.trim().lowercase()
        if (s.isBlank()) return ""
        return when {
            s.contains("粤") || s.contains("广东") || s.contains("港") || s.contains("canto") -> "粤语"
            s.contains("英") || s.contains("欧美") || s.contains("english") -> "英语"
            s.contains("国语") || s.contains("普通话") || s.contains("华语") || s.contains("中文") || s.contains("mandarin") -> "国语"
            s.contains("日") || s.contains("jp") || s.contains("japan") -> "日语"
            s.contains("韩") || s.contains("kpop") || s.contains("korea") -> "韩语"
            s.contains("闽南") || s.contains("台语") -> "闽南语"
            s.contains("纯音乐") || s.contains("伴奏") || s.contains("钢琴") || s.contains("instrumental") -> "纯音乐"
            s.contains("俄") -> "俄语"
            else -> raw.trim()
        }
    }

    private fun normalizeFilterGenre(raw: String): String {
        val s = raw.trim().lowercase()
        if (s.isBlank()) return ""
        return when {
            s.contains("说唱") || s.contains("rap") || s.contains("hip") || s.contains("嘻哈") -> "说唱"
            s.contains("电子") || s.contains("电音") || s.contains("edm") || s.contains("dance") || s.contains("dj") -> "电子音乐"
            s.contains("摇滚") || s.contains("rock") || s.contains("乐队") -> "摇滚"
            s.contains("民谣") || s.contains("folk") -> "民谣"
            s.contains("r&b") || s.contains("rnb") || s.contains("布鲁斯") || s.contains("蓝调节奏") -> "节奏布鲁斯"
            s.contains("影视") || s.contains("ost") || s.contains("原声") || s.contains("电影") || s.contains("插曲") -> "影视原声"
            s.contains("国风") || s.contains("古风") -> "国风"
            s.contains("乡村") || s.contains("country") -> "乡村乐"
            s.contains("古典") || s.contains("classical") -> "古典音乐"
            s.contains("爵士") || s.contains("jazz") -> "爵士"
            s.contains("金属") || s.contains("metal") -> "金属"
            s.contains("流行") || s.contains("pop") -> "流行"
            else -> raw.trim()
        }
    }

    private suspend fun handleQueryFavorites(args: JSONObject): ToolExecutionOutcome {
        val favs = repository.favorites.value
        val completedKeys = repository.completedDownloadKeys.value
        var rawLang = args.optString("language").trim()
        var rawGenre = args.optString("genre").trim()
        // 防御性参数纠偏：部分模型会把“粤语/英语”误传到 genre，或把“说唱/摇滚”误传到 language
        val knownLanguages = setOf("国语", "粤语", "英语", "日语", "韩语", "闽南语", "纯音乐", "俄语")
        val knownGenres = setOf("流行", "说唱", "电子音乐", "摇滚", "民谣", "节奏布鲁斯", "影视原声", "国风", "乡村乐", "古典音乐", "爵士", "金属")
        if (rawLang.isBlank() && rawGenre.isNotBlank()) {
            val asLang = normalizeFilterLanguage(rawGenre)
            if (asLang in knownLanguages) {
                rawLang = asLang
                rawGenre = ""
            }
        }
        if (rawGenre.isBlank() && rawLang.isNotBlank()) {
            val asGenre = normalizeFilterGenre(rawLang)
            if (asGenre in knownGenres) {
                rawGenre = asGenre
                rawLang = ""
            }
        }
        val lang = normalizeFilterLanguage(rawLang)
        val genre = normalizeFilterGenre(rawGenre)
        val artist = args.optString("artist").trim()
        val keyword = args.optString("keyword").trim()
        val downloadedOnly = args.optBoolean("downloaded_only", false)
        val notDownloadedOnly = args.optBoolean("not_downloaded_only", false)
        val hasMv = args.optBoolean("has_mv", false)
        val shuffle = args.optBoolean("shuffle", false)
        val limit = args.optInt("limit", 12).coerceIn(1, 30)
        val action = args.optString("action", "list").lowercase().trim().ifBlank { "list" }

        val hasAnyFilter = lang.isNotBlank() || genre.isNotBlank() || artist.isNotBlank() ||
            keyword.isNotBlank() || downloadedOnly || notDownloadedOnly || hasMv

        var matched = favs.filter { s ->
            if (lang.isNotBlank() && SongClassifier.resolveLanguage(s) != lang) return@filter false
            if (genre.isNotBlank() && SongClassifier.resolveGenre(s) != genre) return@filter false
            if (artist.isNotBlank() && !s.artist.contains(artist, ignoreCase = true)) return@filter false
            if (keyword.isNotBlank() && !s.title.contains(keyword, true) && !s.artist.contains(keyword, true) && !s.album.contains(keyword, true)) return@filter false
            if (downloadedOnly && !completedKeys.contains(s.uniqueKey)) return@filter false
            if (notDownloadedOnly && completedKeys.contains(s.uniqueKey)) return@filter false
            if (hasMv && s.mvId.isBlank()) return@filter false
            true
        }
        if (shuffle && matched.size > 1) {
            matched = matched.shuffled()
        }
        cacheSongs(matched.take(40), recordAsLastSurfaced = true)

        val filterDesc = buildList {
            if (lang.isNotBlank()) add("语种:$lang")
            if (genre.isNotBlank()) add("流派:$genre")
            if (artist.isNotBlank()) add("歌手:$artist")
            if (keyword.isNotBlank()) add("关键词:$keyword")
            if (downloadedOnly) add("仅已离线")
            if (notDownloadedOnly) add("仅未离线")
            if (hasMv) add("有MV")
        }.joinToString(" · ").ifBlank { "全部收藏" }

        if (matched.isEmpty()) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "在用户的 ${favs.size} 首收藏中，按条件【$filterDesc】未找到匹配曲目（0 首）。如果用户想听这类歌曲，请直接调用 recommend_songs 或 search_songs 联网为其找歌并展示，不要干巴巴反问。",
                auditLog = ToolExecutionLog("query_favorites", "检索收藏曲库", filterDesc, "0 首匹配", true),
                surfacedSongs = emptyList()
            )
        }

        val topArtistsInMatch = matched
            .groupingBy { it.artist.split("/", "、", ",").firstOrNull()?.trim().orEmpty().ifBlank { "未知" } }
            .eachCount()
            .entries.sortedByDescending { it.value }.take(6)
            .joinToString("、") { "${it.key}(${it.value}首)" }

        return when (action) {
            "play" -> {
                withContext(Dispatchers.Main) {
                    if (shuffle) playerController.playShuffled(matched) else playerController.playQueue(matched, 0)
                }
                val first = matched.first()
                val text = buildString {
                    appendLine("已从收藏中筛选出【$filterDesc】共 ${matched.size} 首，并已${if (shuffle) "随机" else "顺序"}加入播放队列立即开播第一首：《${first.title}》 - ${first.artist}！")
                    appendLine("代表歌手：$topArtistsInMatch")
                    appendLine("队列前 ${minOf(limit, matched.size)} 首：")
                    matched.take(limit).forEachIndexed { i, s ->
                        appendLine("${i + 1}. 《${s.title}》 - ${s.artist} [${SongClassifier.resolveLanguage(s)}/${SongClassifier.resolveGenre(s)}]")
                    }
                }
                ToolExecutionOutcome(
                    toolOutputForLlm = text,
                    auditLog = ToolExecutionLog("query_favorites", "播放收藏筛选结果", filterDesc, "已开播 ${matched.size} 首（首曲：${first.title}）", true),
                    surfacedSongs = matched.take(6)
                )
            }

            "download" -> {
                val q = args.optString("quality").takeIf { it.isNotBlank() }?.let { AudioQuality.fromCode(it) }
                    ?: settings.preferredQuality.value
                val queuedCount = downloader.enqueueBatchDownload(matched, q, skipCompleted = true)
                val skipped = matched.size - queuedCount
                val est = SongClassifier.estimateBatchSizeLabel(queuedCount, q)
                val text = buildString {
                    appendLine("已触发批量下载【$filterDesc】：匹配共 ${matched.size} 首，实际新增入队 $queuedCount 首（自动跳过 $skipped 首已离线或排队中）。")
                    appendLine("音质：${q.displayName}（预估约 $est），后台 3 路并发稳速下载并导出至 Music/SonicPilot，可在「曲库 → 离线」随时暂停或查看进度。")
                }
                ToolExecutionOutcome(
                    toolOutputForLlm = text,
                    auditLog = ToolExecutionLog("query_favorites", "批量下载收藏", "$filterDesc (${q.badge})", "已入队 $queuedCount 首", true),
                    surfacedSongs = matched.take(6),
                    navigateToTab = "library"
                )
            }

            "create_playlist" -> {
                val plName = args.optString("playlist_name").trim().ifBlank {
                    "我的${lang.ifBlank { genre.ifBlank { artist.ifBlank { "精选" } } }}歌单"
                }
                val pl = repository.createPlaylist(plName, "由音枢 AI 从收藏按【$filterDesc】自动整理", createdByAi = true)
                repository.bulkAddSongsToPlaylist(pl.id, matched)
                val text = buildString {
                    appendLine("已自动创建歌单「${pl.name}」，并将收藏中符合【$filterDesc】的全部 ${matched.size} 首歌曲一次性加入！")
                    appendLine("代表曲目：" + matched.take(8).joinToString("、") { "《${it.title}》-${it.artist}" })
                }
                ToolExecutionOutcome(
                    toolOutputForLlm = text,
                    auditLog = ToolExecutionLog("query_favorites", "从收藏创建歌单", "${pl.name} ($filterDesc)", "已收录 ${matched.size} 首", true),
                    surfacedSongs = matched.take(6)
                )
            }

            else -> {
                val text = buildString {
                    appendLine("在你的 ${favs.size} 首收藏中，【$filterDesc】共找到 ${matched.size} 首（代表歌手：$topArtistsInMatch）：")
                    matched.take(limit).forEachIndexed { i, s ->
                        appendLine("${i + 1}. 《${s.title}》 - ${s.artist} [${SongClassifier.resolveLanguage(s)}/${SongClassifier.resolveGenre(s)}] (id=${s.id})")
                    }
                    if (matched.size > limit) {
                        appendLine("（其余 ${matched.size - limit} 首已就绪，可直接让我播放、批量下载或打包成歌单）")
                    }
                }
                ToolExecutionOutcome(
                    toolOutputForLlm = text,
                    auditLog = ToolExecutionLog("query_favorites", "筛选收藏曲库", filterDesc, "共 ${matched.size} 首", true),
                    // 仅当有明确筛选条件（或用户就是要看收藏列表）时才展示对应卡片，避免无关曲目污染聊天气泡
                    surfacedSongs = if (hasAnyFilter) matched.take(6) else matched.take(6)
                )
            }
        }
    }

    private fun handleManageDownloadQueue(args: JSONObject): ToolExecutionOutcome {
        val action = args.optString("action", "status").lowercase().trim()
        val dls = repository.downloads.value
        val completed = dls.count { it.status == DownloadStatus.COMPLETED }
        val downloading = dls.count { it.status == DownloadStatus.DOWNLOADING }
        val queued = dls.count { it.status == DownloadStatus.QUEUED }
        val failed = dls.count { it.status == DownloadStatus.FAILED }
        return when (action) {
            "pause" -> {
                downloader.pauseQueue()
                ToolExecutionOutcome("已暂停批量下载队列（已完成 $completed 首，剩余 $queued 首排队暂停）。", ToolExecutionLog("manage_download_queue", "暂停下载队列", "pause", "已暂停", true))
            }
            "resume" -> {
                val n = downloader.resumeQueue()
                ToolExecutionOutcome("已恢复批量下载队列（拉起 $n 首排队任务继续 3 路并发下载）。", ToolExecutionLog("manage_download_queue", "继续下载队列", "resume", "恢复 $n 首", true))
            }
            "retry_failed" -> {
                val n = downloader.retryAllFailed()
                ToolExecutionOutcome(if (n > 0) "已重新将全部 $n 首失败曲目加入下载队列。" else "当前没有下载失败的曲目。", ToolExecutionLog("manage_download_queue", "重试失败下载", "retry_failed", "重试 $n 首", true))
            }
            "cancel_queued" -> {
                downloader.cancelAllQueued()
                ToolExecutionOutcome("已清空所有剩余排队任务（已完成的 $completed 首离线歌曲保持不变）。", ToolExecutionLog("manage_download_queue", "清空下载排队", "cancel_queued", "已清空", true))
            }
            else -> {
                val stateStr = if (downloader.isQueuePaused.value) "已暂停" else if (downloading + queued > 0) "运行中" else "空闲"
                ToolExecutionOutcome(
                    "下载队列状态：$stateStr｜已完成 $completed 首 · 下载中 $downloading 首 · 排队中 $queued 首 · 失败 $failed 首。",
                    ToolExecutionLog("manage_download_queue", "查看下载队列", "status", "完成 $completed / 排队 $queued", true)
                )
            }
        }
    }

    private suspend fun handleManagePlaylist(args: JSONObject): ToolExecutionOutcome {
        val action = args.optString("action", "list").lowercase()
        val plName = args.optString("playlist_name").trim()
        return when (action) {
            "list" -> {
                val pls = repository.playlists.value
                val txt = if (pls.isEmpty()) "目前还没有自建歌单。" else {
                    pls.joinToString("\n") { "- ${it.name}（${it.songCount} 首，${if (it.createdByAi) "AI创建" else "自建"}）" }
                }
                ToolExecutionOutcome(txt, ToolExecutionLog("manage_playlist", "查看歌单", "list", "共 ${pls.size} 个歌单", true))
            }
            "create" -> {
                val pl = repository.createPlaylist(plName.ifBlank { "AI 精选歌单" }, args.optString("description"), createdByAi = true)
                ToolExecutionOutcome(
                    "已创建新歌单「${pl.name}」。",
                    ToolExecutionLog("manage_playlist", "创建歌单", pl.name, "创建成功", true)
                )
            }
            "add_song" -> {
                val pl = repository.findPlaylistByName(plName)
                    ?: repository.createPlaylist(plName.ifBlank { "AI 精选歌单" }, "由音枢 AI 创建", createdByAi = true)
                val target = args.optString("song_id_or_query").takeIf { it.isNotBlank() }
                val song = findSongInContext(target)
                    ?: target?.let { repository.channelManager.searchSongs(it, limit = 1).firstOrNull() }
                    ?: return ToolExecutionOutcome(
                        "未找到要添加的歌曲。",
                        ToolExecutionLog("manage_playlist", "歌单加歌", pl.name, "未找到目标歌曲", false)
                    )
                repository.addSongToPlaylist(pl.id, song)
                ToolExecutionOutcome(
                    "已将《${song.title}》添加到歌单「${pl.name}」。",
                    ToolExecutionLog("manage_playlist", "歌单加歌", "${pl.name} <- 《${song.title}》", "添加成功", true)
                )
            }
            "remove_song" -> {
                val pl = repository.findPlaylistByName(plName) ?: return ToolExecutionOutcome(
                    "未找到歌单「$plName」。",
                    ToolExecutionLog("manage_playlist", "移出歌单", plName, "歌单不存在", false)
                )
                val song = findSongInContext(args.optString("song_id_or_query")) ?: return ToolExecutionOutcome(
                    "未找到要移除的歌曲。",
                    ToolExecutionLog("manage_playlist", "移出歌单", plName, "歌曲未找到", false)
                )
                repository.removeSongFromPlaylist(pl.id, song.uniqueKey)
                ToolExecutionOutcome(
                    "已从歌单「${pl.name}」移出《${song.title}》。",
                    ToolExecutionLog("manage_playlist", "移出歌单", "《${song.title}》", "已移出", true)
                )
            }
            "play" -> {
                val pl = repository.findPlaylistByName(plName) ?: repository.playlists.value.firstOrNull()
                    ?: return ToolExecutionOutcome("暂无可播放歌单。", ToolExecutionLog("manage_playlist", "播放歌单", plName, "无歌单", false))
                val songs = repository.getPlaylistSongs(pl.id)
                if (songs.isEmpty()) {
                    ToolExecutionOutcome("歌单「${pl.name}」为空。", ToolExecutionLog("manage_playlist", "播放歌单", pl.name, "歌单为空", false))
                } else {
                    withContext(Dispatchers.Main) { playerController.playQueue(songs, 0) }
                    ToolExecutionOutcome(
                        "已开始播放歌单「${pl.name}」（共 ${songs.size} 首）。",
                        ToolExecutionLog("manage_playlist", "播放歌单", pl.name, "开始播放 ${songs.size} 首", true),
                        surfacedSongs = songs.take(5)
                    )
                }
            }
            "delete" -> {
                val pl = repository.findPlaylistByName(plName) ?: return ToolExecutionOutcome(
                    "未找到歌单「$plName」。",
                    ToolExecutionLog("manage_playlist", "删除歌单", plName, "歌单不存在", false)
                )
                repository.deletePlaylist(pl.id)
                ToolExecutionOutcome("已删除歌单「${pl.name}」。", ToolExecutionLog("manage_playlist", "删除歌单", pl.name, "已删除", true))
            }
            else -> ToolExecutionOutcome("未知歌单操作：$action", ToolExecutionLog("manage_playlist", "歌单操作", action, "无效动作", false))
        }
    }

    private suspend fun handleDownloadSong(args: JSONObject): ToolExecutionOutcome {
        val target = args.optString("song_id_or_query").takeIf { it.isNotBlank() }
        val quality = args.optString("quality").takeIf { it.isNotBlank() }?.let { AudioQuality.fromCode(it) }
            ?: settings.preferredQuality.value
        val song = findSongInContext(target)
            ?: target?.let { repository.channelManager.searchSongs(it, limit = 1).firstOrNull() }
            ?: return ToolExecutionOutcome(
                "没有找到可下载的目标歌曲。",
                ToolExecutionLog("download_song", "下载无损歌曲", target ?: "当前歌曲", "未找到目标歌曲", false)
            )
        downloader.enqueueDownload(song, quality)
        return ToolExecutionOutcome(
            "已开始在后台下载《${song.title}》 - ${song.artist}（请求音质：${quality.displayName}），下载完成后将自动离线入库并导出至 Music/SonicPilot。",
            ToolExecutionLog("download_song", "开始无损下载", "《${song.title}》 (${quality.badge})", "已加入下载队列", true)
        )
    }

    private fun handleListDownloads(): ToolExecutionOutcome {
        val items = repository.downloads.value
        val text = if (items.isEmpty()) {
            "目前尚未下载任何离线歌曲。"
        } else {
            "本地离线下载共 ${items.size} 条：\n" + items.take(20).joinToString("\n") {
                val mb = String.format("%.1fMB", it.fileSizeBytes / (1024f * 1024f))
                "- 《${it.song.title}》 - ${it.song.artist} [${it.status.name}, ${it.actualBitrateKbps}kbps, $mb]"
            }
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = text,
            auditLog = ToolExecutionLog("list_downloads", "查看离线下载", "下载列表", "共 ${items.size} 首", true),
            surfacedSongs = items.map { it.song }.take(5)
        )
    }

    private suspend fun handleGetCharts(args: JSONObject): ToolExecutionOutcome {
        val code = args.optString("chart", "hot_cn")
        val limit = args.optInt("limit", 10).coerceIn(1, 30)
        val cat = ChartCategory.entries.firstOrNull { it.id.equals(code, true) } ?: ChartCategory.HOT_CN
        val songs = repository.loadChart(cat, limit)
        cacheSongs(songs, recordAsLastSurfaced = true)
        val text = if (songs.isEmpty()) {
            "拉取「${cat.title}」失败。"
        } else {
            "【${cat.title}】Top ${songs.size}：\n" + songs.mapIndexed { idx, s ->
                "${idx + 1}. 《${s.title}》 - ${s.artist} (id=${s.id})"
            }.joinToString("\n")
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = text,
            auditLog = ToolExecutionLog("get_charts", "获取热门榜单", cat.title, "返回 ${songs.size} 首热歌", songs.isNotEmpty()),
            surfacedSongs = songs.take(6)
        )
    }

    private suspend fun handleRecommendSongs(args: JSONObject): ToolExecutionOutcome {
        val mood = args.optString("mood_or_scene", "全球热门精选").trim()
        val count = args.optInt("count", 6).coerceIn(3, 12)
        val autoPlay = args.optBoolean("auto_play", false)
        val excludeFavorites = args.optBoolean("exclude_favorites", true)
        val savePlaylistName = args.optString("save_playlist_name").trim()

        val profile = repository.getListeningProfile()
        val favKeys = repository.favoriteKeys.value
        val favTitleArtist = repository.favorites.value.map { "${it.title.lowercase()}|${it.artist.lowercase()}" }.toHashSet()
        val hasHistory = profile.totalPlays > 0 || profile.favoriteCount > 0
        val collected = mutableListOf<Song>()

        val directChart: ChartCategory? = when {
            mood.contains("抖音") || mood.contains("短视频") || mood.contains("网红") -> ChartCategory.DOUYIN
            mood.contains("飙升") || mood.contains("最近火") -> ChartCategory.SOARING
            mood.contains("新歌") && !mood.contains("粤语") && !mood.contains("英语") && !mood.contains("说唱") -> ChartCategory.NEW_SONGS
            mood.contains("欧美") || mood.contains("英文") || mood.contains("英语") || mood.contains("billboard", true) -> ChartCategory.BILLBOARD
            mood.contains("日") || mood.contains("动漫") || mood.contains("acg", true) -> ChartCategory.JP_ORICON
            mood.contains("热歌") || mood.contains("热门") -> ChartCategory.HOT_CN
            else -> null
        }

        val isGenericPersonalizedPrompt = listOf("听歌记录", "猜你喜欢", "口味", "偏好", "好歌", "高音质", "随便").any { mood.contains(it) } &&
            !listOf("粤语", "港乐", "说唱", "摇滚", "电子", "民谣", "健身", "深夜", "欧美", "英文", "日语").any { mood.contains(it) }

        if (directChart != null) {
            collected += repository.loadChart(directChart, limit = count + 14).shuffled()
        } else if (isGenericPersonalizedPrompt) {
            if (hasHistory && profile.topArtists.isNotEmpty()) {
                profile.topArtists.shuffled().take(2).forEach { (favArtist, _) ->
                    collected += repository.channelManager.searchSongs(favArtist, limit = 8)
                }
            }
            collected += repository.loadChart(ChartCategory.HOT_CN, limit = 20).shuffled()
            collected += repository.loadChart(ChartCategory.SOARING, limit = 15).shuffled()
        } else {
            val queries: List<String> = when {
                mood.contains("粤语") || mood.contains("港乐") -> listOf("张敬轩 粤语", "林家谦 卫兰", "陈奕迅 粤语", "杨千嬅 粤语")
                mood.contains("说唱") || mood.contains("rap", true) || mood.contains("嘻哈") -> listOf("马思唯 谢帝", "王以太 艾热", "Kendrick Lamar Eminem")
                mood.contains("燃") || mood.contains("健身") || mood.contains("运动") || mood.contains("电子") -> listOf("Imagine Dragons", "Alan Walker Avicii")
                mood.contains("摇滚") -> listOf("Coldplay Linkin Park", "五月天 告五人")
                mood.contains("深夜") || mood.contains("治愈") || mood.contains("安静") || mood.contains("emo", true) -> listOf("毛不易 陈粒", "方大同 陶喆")
                else -> listOf(mood.ifBlank { profile.topArtists.firstOrNull()?.first ?: "陈奕迅" })
            }
            for (q in queries.shuffled().take(2)) {
                collected += repository.channelManager.searchSongs(q, limit = 10)
            }
            if (collected.size < count) {
                collected += repository.loadChart(ChartCategory.HOT_CN, 15).shuffled()
            }
        }

        val deduped = collected.distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
        val filteredFresh = if (excludeFavorites) {
            deduped.filterNot { favKeys.contains(it.uniqueKey) || favTitleArtist.contains("${it.title.lowercase()}|${it.artist.lowercase()}") }
        } else deduped
        val finalSongs = (if (filteredFresh.size >= count) filteredFresh else deduped).take(count)
        cacheSongs(finalSongs, recordAsLastSurfaced = true)

        if (savePlaylistName.isNotBlank() && finalSongs.isNotEmpty()) {
            val pl = repository.createPlaylist(savePlaylistName, "音枢 AI 按「$mood」为你定制", createdByAi = true)
            repository.bulkAddSongsToPlaylist(pl.id, finalSongs)
        }
        if (autoPlay && finalSongs.isNotEmpty()) {
            withContext(Dispatchers.Main) { playerController.playQueue(finalSongs, 0) }
        }

        val text = buildString {
            if (!hasHistory && isGenericPersonalizedPrompt) {
                appendLine("你最近还没有听歌或收藏记录，先为你挑选了 ${finalSongs.size} 首高音质好歌：")
            } else {
                val taste = profile.topArtists.take(3).joinToString("、") { it.first }.ifBlank { "全网口碑精选" }
                val freshHint = if (excludeFavorites && filteredFresh.size >= count) "（已自动避开你已收藏的 ${favKeys.size} 首老歌）" else ""
                appendLine("结合「$mood」与你的常听偏好（$taste），为你挑选了 ${finalSongs.size} 首高音质曲目$freshHint：")
            }
            if (finalSongs.isEmpty()) {
                appendLine("暂时未能拉取到在线曲目，请检查手机网络连接或前往「设置 → 音乐渠道」测速。")
            } else {
                finalSongs.forEachIndexed { i, s ->
                    appendLine("${i + 1}. 《${s.title}》 - ${s.artist} (id=${s.id})")
                }
            }
            if (savePlaylistName.isNotBlank() && finalSongs.isNotEmpty()) appendLine("已自动保存至歌单「$savePlaylistName」。")
            if (autoPlay && finalSongs.isNotEmpty()) appendLine("已自动开始播放第一首《${finalSongs.first().title}》。")
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = text,
            auditLog = ToolExecutionLog(
                "recommend_songs",
                "AI 智能荐歌",
                if (!hasHistory && isGenericPersonalizedPrompt) "无历史记录 → 精选热歌补位" else mood,
                if (finalSongs.isNotEmpty()) "精选 ${finalSongs.size} 首可播曲目" else "未拉取到曲目",
                finalSongs.isNotEmpty()
            ),
            surfacedSongs = finalSongs
        )
    }

    private fun handleListChannels(): ToolExecutionOutcome {
        val list = repository.channelManager.channels.value
        val text = list.joinToString("\n") { ch ->
            val tags = buildList {
                if (ch.isPrimary) add("主渠道")
                add(if (ch.enabled) "已启用" else "已禁用")
                add(ch.type.label)
                if (ch.lastLatencyMs > 0) add("${ch.lastLatencyMs}ms")
            }.joinToString(" · ")
            "- [${ch.id}] ${ch.name} ($tags) | ${ch.baseUrl} (source=${ch.sourceKey})"
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = text,
            auditLog = ToolExecutionLog("list_channels", "查看音乐渠道", "全部渠道", "共 ${list.size} 个渠道", true)
        )
    }

    private suspend fun handleManageChannel(args: JSONObject): ToolExecutionOutcome {
        val action = args.optString("action").lowercase()
        val chId = args.optString("channel_id")
        return when (action) {
            "add" -> {
                val name = args.optString("name").ifBlank { "自定义音乐渠道" }
                val baseUrl = args.optString("base_url").trim()
                if (!baseUrl.startsWith("http")) {
                    return ToolExecutionOutcome(
                        "添加失败：base_url 必须以 http:// 或 https:// 开头。",
                        ToolExecutionLog("manage_channel", "添加渠道", name, "无效 URL", false)
                    )
                }
                val ch = repository.channelManager.addOrUpdateChannel(
                    MusicChannel(
                        id = "",
                        name = name,
                        type = ChannelType.fromName(args.optString("type", "CUSTOM_REST")),
                        baseUrl = baseUrl,
                        sourceKey = args.optString("source_key", "netease"),
                        enabled = true,
                        isPrimary = false,
                        isBuiltIn = false,
                        description = "由 AI 管家动态添加"
                    )
                )
                ToolExecutionOutcome(
                    "已成功添加新音乐渠道「${ch.name}」(id=${ch.id}, type=${ch.type.label}, url=${ch.baseUrl})。",
                    ToolExecutionLog("manage_channel", "新增音乐渠道", ch.name, "添加成功", true)
                )
            }
            "set_primary" -> {
                val ok = repository.channelManager.setPrimaryChannel(chId)
                ToolExecutionOutcome(
                    if (ok) "已将渠道 $chId 设为主渠道。" else "未找到渠道 $chId。",
                    ToolExecutionLog("manage_channel", "切换主渠道", chId, if (ok) "已设为主渠道" else "未找到", ok)
                )
            }
            "enable", "disable" -> {
                val enable = action == "enable"
                val ok = repository.channelManager.toggleChannelEnabled(chId, enable)
                ToolExecutionOutcome(
                    if (ok) "已${if (enable) "启用" else "禁用"}渠道 $chId。" else "未找到渠道 $chId。",
                    ToolExecutionLog("manage_channel", if (enable) "启用渠道" else "禁用渠道", chId, if (ok) "已更新" else "失败", ok)
                )
            }
            "test" -> {
                val ms = repository.channelManager.testChannelLatency(chId)
                ToolExecutionOutcome(
                    if (ms >= 0) "渠道 $chId 连通正常，延迟 ${ms}ms。" else "渠道 $chId 当前不可达。",
                    ToolExecutionLog("manage_channel", "渠道测速", chId, if (ms >= 0) "${ms}ms" else "超时/不可用", ms >= 0)
                )
            }
            "delete" -> {
                val ok = repository.channelManager.deleteChannel(chId)
                ToolExecutionOutcome(
                    if (ok) "已删除自定义渠道 $chId。" else "删除失败（内置核心渠道不允许删除，可选择禁用）。",
                    ToolExecutionLog("manage_channel", "删除渠道", chId, if (ok) "已删除" else "不可删除", ok)
                )
            }
            else -> ToolExecutionOutcome("未知渠道操作：$action", ToolExecutionLog("manage_channel", "渠道管理", action, "无效操作", false))
        }
    }

    private fun handleEvaluateSinging(args: JSONObject): ToolExecutionOutcome {
        if (args.optBoolean("open_studio", false)) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "已准备跳转至声乐录音棚，请提示用户点击大录音按钮清唱或边听伴奏边唱，录完后我会立刻分析音准、节奏、气息并打分！",
                auditLog = ToolExecutionLog("evaluate_singing", "打开声乐评测棚", "准备开始练唱评测", "已切换到声乐棚", true),
                navigateToTab = "singing"
            )
        }
        val evId = args.optString("evaluation_id").takeIf { it.isNotBlank() }
        val evaluations = repository.evaluations.value
        val target = if (evId != null) {
            evaluations.firstOrNull { it.id == evId }
        } else {
            singingCoach.liveState.value.latestEvaluation ?: evaluations.firstOrNull()
        }
        if (target == null) {
            return ToolExecutionOutcome(
                toolOutputForLlm = "用户目前还没有在声乐棚录制过唱歌音频。请温柔引导用户前往底部「声乐棚」页面录一段 10~30 秒的演唱，录完后我就能读取端侧 YIN 基频声学报告给 TA 精准打分和逐句指导！",
                auditLog = ToolExecutionLog("evaluate_singing", "读取声乐评测", "最新录音", "暂无录音记录，引导前往声乐棚", true),
                navigateToTab = "singing"
            )
        }
        val feedbackToSave = args.optString("save_feedback").trim()
        if (feedbackToSave.isNotBlank()) {
            singingCoach.updateEvaluationAiFeedback(target.id, feedbackToSave)
        }
        val report = buildString {
            appendLine("【端侧 YIN 声学 DSP 演唱评测数据】")
            appendLine("- 演唱曲目：《${target.songTitle}》${if (target.songArtist.isNotBlank()) " (${target.songArtist})" else ""}")
            appendLine("- 录音时长：${target.durationMs / 1000} 秒 | 记录 ID：${target.id}")
            appendLine("- 综合得分：${target.overallScore} / 100")
            appendLine("- 分项得分：音准 ${target.pitchAccuracyScore} | 稳定度 ${target.pitchStabilityScore} | 节奏乐句 ${target.rhythmScore} | 气息动态 ${target.breathControlScore}")
            appendLine("- 十二平均律在调率：${target.inTuneRatioPercent}% | 中位绝对音分误差：±${target.medianAbsCentsError} cents")
            appendLine("- 实测音域：${target.lowestNote} ~ ${target.highestNote}（跨度 ${target.vocalRangeSemitones} 个半音）")
            appendLine("- 估算调性：${target.estimatedKey} | 检测到自然颤音：${if (target.vibratoDetected) "是" else "否"}")
            if (target.offPitchMoments.isNotEmpty()) {
                appendLine("- 主要跑调片段：${target.offPitchMoments.joinToString("；")}")
            } else {
                appendLine("- 主要跑调片段：全程无明显跑调段！")
            }
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = report,
            auditLog = ToolExecutionLog(
                "evaluate_singing",
                "读取唱功 DSP 报告",
                "《${target.songTitle}》",
                "综合 ${target.overallScore} 分 · 音准 ${target.pitchAccuracyScore} · 音域 ${target.lowestNote}~${target.highestNote}",
                true
            )
        )
    }

    private fun handleListeningProfile(): ToolExecutionOutcome {
        val p = repository.getListeningProfile()
        val text = buildString {
            appendLine("【用户听歌画像】")
            appendLine("- 累计播放：${p.totalPlays} 次 | 收藏：${p.favoriteCount} 首 | 已下载离线：${p.downloadCount} 首 | 歌单：${p.playlistCount} 个")
            appendLine("- 最常听歌手：${p.topArtists.joinToString("、") { "${it.first}(${it.second})" }.ifBlank { "暂无统计" }}")
            appendLine("- 最近播放：${p.recentSongs.take(8).joinToString("、") { "《${it.title}》-${it.artist}" }.ifBlank { "暂无记录" }}")
            if (p.totalPlays == 0 && p.favoriteCount == 0) {
                appendLine("【重要提示】用户目前还没有任何听歌或收藏记录。请如实友好地告诉用户当前暂无历史记录，并立即调用 recommend_songs 或 get_charts 为用户推荐热门好歌，切勿只停在画像查询而不回答！")
            }
        }
        return ToolExecutionOutcome(
            toolOutputForLlm = text,
            auditLog = ToolExecutionLog("get_listening_profile", "读取听歌画像", "统计分析", if (p.totalPlays == 0) "暂无听歌记录" else "已听 ${p.totalPlays} 次 · 收藏 ${p.favoriteCount}", true)
        )
    }

    // ==================== Schema 构造 DSL ====================

    private class PropsBuilder {
        val properties = JSONObject()
        val required = JSONArray()
        fun prop(name: String, type: String, description: String, required: Boolean = false) {
            properties.put(name, JSONObject().put("type", type).put("description", description))
            if (required) this.required.put(name)
        }

        fun propSongList(name: String, description: String, required: Boolean = false) {
            val itemProps = JSONObject()
                .put("title", JSONObject().put("type", "string").put("description", "精准原唱歌名"))
                .put("artist", JSONObject().put("type", "string").put("description", "原唱歌手名"))
                .put("reason", JSONObject().put("type", "string").put("description", "简短走心的推荐理由"))
            val itemSchema = JSONObject()
                .put("type", "object")
                .put("properties", itemProps)
                .put("required", JSONArray().put("title").put("artist"))
            properties.put(
                name,
                JSONObject()
                    .put("type", "array")
                    .put("description", description)
                    .put("items", itemSchema)
            )
            if (required) this.required.put(name)
        }
    }

    private fun toolDef(name: String, description: String, block: PropsBuilder.() -> Unit): JSONObject {
        val pb = PropsBuilder().apply(block)
        val params = JSONObject()
            .put("type", "object")
            .put("properties", pb.properties)
            .put("required", pb.required)
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", params)
            )
    }

    companion object {
        private val fakeUploaderRegex = Regex("""[\u4e00-\u9fff][.\-_。，]+$""")
        private val stripBracketsRegex = Regex("""[\(（\[【][^\)）\]】]*[\)）\]】]""")
        private val normStripRegex = Regex("""[\s\p{P}\p{S}]""")

        internal fun normMatchText(raw: String): String =
            raw.lowercase().replace(normStripRegex, "")

        private fun stripParens(raw: String): String =
            raw.replace(stripBracketsRegex, "").trim()

        /**
         * 原唱防翻唱打分引擎（纯函数，可直接单元测试）：
         * 过滤网易云/公开源中常见的“假冒歌手名(周杰伦.) / 翻唱 / DJ / 加速 / 伴奏”，优先命中真正原唱录音室版本！
         */
        fun scoreOriginalCandidate(
            song: Song,
            targetTitle: String,
            targetArtist: String = "",
            userQuery: String = ""
        ): Int {
            val qLower = "$targetTitle $targetArtist $userQuery".lowercase()
            val wantLive = listOf("live", "现场", "演唱会", "音乐节").any { qLower.contains(it) }
            val wantDj = listOf("dj", "remix", "串烧", "慢摇", "加速", "sped up", "nightcore", "降调").any { qLower.contains(it) }
            val wantCover = listOf("翻唱", "cover", "弹唱", "钢琴版", "女生版", "男生版").any { qLower.contains(it) }
            val wantInst = listOf("伴奏", "instrumental", "纯音乐", "消音", "ktv").any { qLower.contains(it) }

            val songTitleLower = song.title.lowercase()
            val songArtistLower = song.artist.lowercase()
            val songAlbumLower = song.album.lowercase()
            val metaAll = "$songTitleLower $songArtistLower $songAlbumLower"

            var score = 0

            // 1. 歌名匹配度
            val normTargetTitle = normMatchText(stripParens(targetTitle))
            val normSongTitle = normMatchText(stripParens(song.title))
            if (normTargetTitle.isNotBlank()) {
                when {
                    normSongTitle == normTargetTitle -> score += 65
                    normSongTitle.contains(normTargetTitle) -> score += 35
                    normTargetTitle.contains(normSongTitle) && normSongTitle.length >= 2 -> score += 20
                    else -> {
                        // 如果是混合查询（如“陈奕迅 富士山下”），拆词检查
                        val tokens = targetTitle.split(Regex("""\s+""")).filter { it.length >= 2 }
                        val hitTitle = tokens.count { songTitleLower.contains(it.lowercase()) }
                        val hitArtist = tokens.count { songArtistLower.contains(it.lowercase()) }
                        score += hitTitle * 28 + hitArtist * 34
                    }
                }
            }

            // 2. 歌手匹配度（明确指定原唱歌手时，非原唱重罚！）
            val cleanTargetArtist = targetArtist.trim()
            if (cleanTargetArtist.isNotBlank()) {
                val targetArtists = cleanTargetArtist.split("/", "、", ",", "&").map { normMatchText(it) }.filter { it.isNotBlank() }
                val songArtists = song.artist.split("/", "、", ",", "&").map { normMatchText(it) }.filter { it.isNotBlank() }
                val exactHit = targetArtists.any { ta -> songArtists.any { sa -> sa == ta } }
                val partialHit = targetArtists.any { ta -> songArtists.any { sa -> sa.contains(ta) || ta.contains(sa) } }
                score += when {
                    exactHit -> 80
                    partialHit -> 50
                    else -> -90
                }
            }

            // 3. 假冒歌手上传者重罚（如“周杰伦.”“林俊杰_”“陈奕迅-”）
            if (fakeUploaderRegex.containsMatchIn(song.artist.trim())) {
                score -= 170
            }

            // 4. 翻唱 / DJ / 伴奏 / Live 降权（除非用户明确点名要该版本）
            if (!wantCover && listOf("翻唱", "cover", "原唱:", "原唱：", "翻自", "弹唱", "八音盒", "女生版", "男生版", "烟嗓", "ai翻唱").any { metaAll.contains(it) }) {
                score -= 120
            }
            if (!wantDj && listOf("dj", "remix", "串烧", "慢摇", "加速", "sped up", "nightcore", "0.8x", "降调", "升调", "热播版").any { metaAll.contains(it) }) {
                score -= 105
            }
            if (!wantInst && listOf("伴奏", "instrumental", "消音", "ktv", "karaoke", "纯音乐版").any { metaAll.contains(it) }) {
                score -= 130
            }
            if (!wantLive && listOf("live", "现场", "演唱会", "音乐节").any { songTitleLower.contains(it) || songAlbumLower.contains(it) }) {
                score -= 28
            }

            // 5. 正向完整度信号
            if (song.coverUrl.startsWith("http") && !song.coverUrl.contains("5639395138885805")) score += 8
            if (song.mvId.isNotBlank()) score += 6
            if (song.channelId == "gd_netease") score += 4

            return score
        }

        fun rankOriginalsFirst(
            candidates: List<Song>,
            targetTitle: String,
            targetArtist: String = "",
            userQuery: String = ""
        ): List<Song> {
            if (candidates.size <= 1) return candidates
            return candidates.sortedByDescending { scoreOriginalCandidate(it, targetTitle, targetArtist, userQuery) }
        }

        fun pickBestOriginalCandidate(
            candidates: List<Song>,
            targetTitle: String,
            targetArtist: String = "",
            userQuery: String = ""
        ): Song? = rankOriginalsFirst(candidates, targetTitle, targetArtist, userQuery).firstOrNull()

        /**
         * 面向用户的透明 AI 权限清单（展示在设置页，确保 AI 仅掌管本 App 内一切能力）
         */
        val APP_PERMISSION_MANIFEST: List<Pair<String, String>> = listOf(
            "GLM-4.7 音乐品味策展 & 原唱搜歌" to "按风格/情绪/相似曲目并行匹配真实原唱高音质歌单，自动过滤翻唱与 DJ",
            "无损播放器全能掌控" to "播放、下一首插队、队列管理、睡眠定时关、原调变速、单句复读、Hi-Res 音质与全屏 MV",
            "700+ 收藏全量筛选与批量管理" to "按语种/流派/歌手精准筛选收藏，一步开播、批量无损下载或一键打包歌单",
            "界面外观与双语/粤拼学歌联动" to "一句话切换深浅主题、主题配色、中英双语/仅英文歌词、粤拼/谐音注音与学歌室",
            "音乐渠道插件动态管理" to "增删外部开源音源、切换主渠道、启用/禁用与测速",
            "声乐棚唱功评测与点评" to "读取端侧 YIN 基频声学报告（音准/节奏/气息/音域），保存导师点评",
            "严格权限沙箱边界" to "绝不接触短信、通讯录、定位、通话或 App 之外的任何隐私数据"
        )
    }
}
