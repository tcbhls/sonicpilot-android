package com.sonicpilot.music.channel

import com.sonicpilot.music.ai.AppAgentToolRegistry
import com.sonicpilot.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

data class ImportedPlaylistResult(
    val playlistName: String,
    val sourceLabel: String,
    val songs: List<Song>,
    val totalCount: Int
)

/**
 * 外部歌单链接一键导入引擎（v2.2 并发提速版）：
 * - 支持 QQ 音乐歌单分享短链（c6.y.qq.com/...）或网页链接（y.qq.com/.../playlist/<id>）
 * - 支持网易云音乐歌单链接（music.163.com/.../playlist?id=<id>）或纯数字 ID
 * - 4 路并发匹配原唱 + 自动过滤翻唱/DJ + 自动 JOOX 补位独家歌手 + 保留官方语种与流派标签
 */
class PlaylistImporter(
    private val httpClient: OkHttpClient,
    private val channelManager: ChannelManager
) {
    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val QQ_LANG_MAP = mapOf(
            0 to "国语", 1 to "粤语", 2 to "闽南语", 3 to "日语",
            4 to "韩语", 5 to "英语", 6 to "法语", 7 to "俄语", 9 to "纯音乐"
        )

        private val QQ_GENRE_MAP = mapOf(
            1 to "流行", 2 to "流行", 3 to "说唱", 4 to "电子音乐", 5 to "摇滚",
            6 to "民谣", 7 to "节奏布鲁斯", 8 to "爵士", 9 to "蓝调", 10 to "古典音乐",
            11 to "乡村乐", 12 to "金属", 13 to "雷鬼", 14 to "新世纪", 15 to "影视原声",
            17 to "国风", 19 to "儿童音乐", 20 to "电子音乐", 21 to "说唱", 36 to "节奏布鲁斯"
        )

        /**
         * 解析分享文本或链接中的平台（qq / netease）与歌单 ID
         */
        fun parsePlaylistLink(rawInput: String, resolvedUrl: String = ""): Pair<String, String>? {
            val combined = resolvedUrl.ifBlank { rawInput }.trim()
            if (combined.isBlank()) return null
            val isQq = combined.contains("qq.com", true) || rawInput.contains("qq.com", true)
            val isNetease = combined.contains("163.com", true) || rawInput.contains("163.com", true)
            val idMatch = Regex("""(?:playlist/|disstid=|id=)(\d{5,15})""").find(combined)
                ?: Regex("""(\d{6,15})""").find(rawInput)
                ?: return null
            val platform = if (isQq && !isNetease) "qq" else "netease"
            return platform to idMatch.groupValues[1]
        }
    }

    suspend fun importFromLinkOrId(
        rawInput: String,
        onProgress: (String) -> Unit = {}
    ): Result<ImportedPlaylistResult> = withContext(Dispatchers.IO) {
        runCatching {
            val input = rawInput.trim()
            if (input.isBlank()) error("请粘贴 QQ 音乐或网易云歌单分享链接")

            val extractedUrl = Regex("""https?://[^\s]+""").find(input)?.value.orEmpty()
            var resolvedUrl = extractedUrl
            if (extractedUrl.contains("c6.y.qq.com", true) || extractedUrl.contains("163cn.tv", true)) {
                onProgress("正在解析分享短链...")
                val req = Request.Builder().url(extractedUrl).header("User-Agent", UA).build()
                httpClient.newCall(req).execute().use { resp ->
                    resolvedUrl = resp.request.url.toString()
                }
            }

            val parsed = parsePlaylistLink(input, resolvedUrl)
                ?: error("未能从链接中识别歌单 ID，请粘贴完整分享链接")
            val (platform, playlistId) = parsed

            if (platform == "qq") {
                importQqPlaylist(playlistId, onProgress)
            } else {
                importNeteasePlaylist(playlistId, onProgress)
            }
        }
    }

    private fun importNeteasePlaylist(playlistId: String, onProgress: (String) -> Unit): ImportedPlaylistResult {
        onProgress("正在读取网易云歌单 #$playlistId ...")
        // 优先走 v6 接口（含完整歌曲列表），无结果时回退 classic detail 接口
        val endpoints = listOf(
            "https://music.163.com/api/v6/playlist/detail?id=$playlistId&n=1000",
            "https://music.163.com/api/playlist/detail?id=$playlistId"
        )
        for (url in endpoints) {
            val body = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }.getOrNull() ?: continue

            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val plObj = root.optJSONObject("playlist") ?: root.optJSONObject("result") ?: continue
            val name = plObj.optString("name", "网易云导入歌单")
            val tracks = plObj.optJSONArray("tracks") ?: continue
            if (tracks.length() == 0) continue

            val songs = buildList {
                for (i in 0 until tracks.length()) {
                    val o = tracks.optJSONObject(i) ?: continue
                    val rawId = o.optLong("id").takeIf { it > 0 }?.toString() ?: continue
                    val title = o.optString("name", "未知曲目")
                    val artistsArr = o.optJSONArray("ar") ?: o.optJSONArray("artists")
                    val artist = if (artistsArr != null) {
                        (0 until artistsArr.length()).mapNotNull { artistsArr.optJSONObject(it)?.optString("name") }
                            .joinToString(" / ")
                    } else "未知歌手"
                    val albumObj = o.optJSONObject("al") ?: o.optJSONObject("album")
                    val album = albumObj?.optString("name", "").orEmpty()
                    val pic = albumObj?.optString("picUrl", "").orEmpty().replace("http://", "https://")
                    val mvId = o.optLong("mv", o.optLong("mvid", 0L)).takeIf { it > 0L }?.toString().orEmpty()
                    val duration = o.optLong("dt", o.optLong("duration", 0L))
                    add(
                        Song(
                            id = "gd_netease_$rawId",
                            title = title,
                            artist = artist.ifBlank { "未知歌手" },
                            album = album,
                            channelId = "gd_netease",
                            sourceKey = "netease",
                            rawTrackId = rawId,
                            lyricId = rawId,
                            mvId = mvId,
                            coverUrl = if (GdStudioChannelProvider.isPlaceholderCoverUrl(pic)) "" else pic,
                            durationMs = duration
                        )
                    )
                }
            }
            if (songs.isNotEmpty()) {
                return ImportedPlaylistResult(name, "网易云歌单", songs, songs.size)
            }
        }
        error("未找到该网易云歌单（请确认歌单非私密且链接有效）")
    }

    private data class QqRawTrack(
        val title: String,
        val artist: String,
        val album: String,
        val language: String,
        val genre: String
    )

    private suspend fun importQqPlaylist(disstid: String, onProgress: (String) -> Unit): ImportedPlaylistResult {
        onProgress("正在读取 QQ 音乐歌单 #$disstid ...")
        var dissName = "QQ 音乐导入歌单"
        val qqTracks = mutableListOf<QqRawTrack>()
        val jsonMedia = "application/json; charset=utf-8".toMediaType()
        for (begin in 0 until 500 step 100) {
            val payload = JSONObject().apply {
                put(
                    "req_0",
                    JSONObject().apply {
                        put("module", "music.srfDissInfo.aiDissInfo")
                        put("method", "uniform_get_Dissinfo")
                        put(
                            "param",
                            JSONObject().apply {
                                put("disstid", disstid.toLong())
                                put("tag", 1)
                                put("userinfo", 1)
                                put("song_begin", begin)
                                put("song_num", 100)
                            }
                        )
                    }
                )
            }
            val req = Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .header("User-Agent", UA)
                .header("Referer", "https://y.qq.com/")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            val body = httpClient.newCall(req).execute().use { it.body?.string().orEmpty() }
            val data = JSONObject(body).optJSONObject("req_0")?.optJSONObject("data") ?: break
            if (begin == 0) {
                dissName = data.optJSONObject("dirinfo")?.optString("title").orEmpty().ifBlank { dissName }
            }
            val list = data.optJSONArray("songlist") ?: break
            if (list.length() == 0) break
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val title = s.optString("name", s.optString("title", "")).trim()
                val singersArr = s.optJSONArray("singer")
                val firstArtist = singersArr?.optJSONObject(0)?.optString("name", "").orEmpty().trim()
                val album = s.optJSONObject("album")?.optString("name", "").orEmpty().trim()
                val langLabel = QQ_LANG_MAP[s.optInt("language", -1)].orEmpty()
                val genreLabel = QQ_GENRE_MAP[s.optInt("genre", -1)].orEmpty()
                if (title.isNotBlank()) qqTracks.add(QqRawTrack(title, firstArtist, album, langLabel, genreLabel))
            }
            if (list.length() < 100) break
        }
        if (qqTracks.isEmpty()) error("未能读取 QQ 歌单内容（请确认歌单设为公开可见）")

        val cap = minOf(qqTracks.size, 300)
        val sem = Semaphore(4)
        val doneCounter = AtomicInteger(0)
        val hasJoox = channelManager.channels.value.any { it.id == "gd_joox" && it.enabled }

        // 4 路并发原唱精准匹配（提速 4 倍，同时保留 QQ 官方语种/流派标签）
        val resolved = coroutineScope {
            qqTracks.take(cap).map { raw ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val done = doneCounter.incrementAndGet()
                        if (done == 1 || done % 5 == 0 || done == cap) {
                            onProgress("并发匹配原唱与分类标签 ($done/$cap)...")
                        }
                        val kw = "${raw.title} ${raw.artist}".trim()
                        val primary = channelManager.searchSongs(kw, channelId = "gd_netease", limit = 6)
                        val pool = mutableListOf<Song>().apply { addAll(primary) }
                        val bestPrimary = AppAgentToolRegistry.pickBestOriginalCandidate(primary, raw.title, raw.artist, kw)
                        val bestScore = bestPrimary?.let {
                            AppAgentToolRegistry.scoreOriginalCandidate(it, raw.title, raw.artist, kw)
                        } ?: Int.MIN_VALUE
                        if (hasJoox && (bestPrimary == null || bestScore < 60)) {
                            val joox = runCatching {
                                channelManager.searchSongs(kw, channelId = "gd_joox", limit = 5)
                            }.getOrDefault(emptyList())
                            pool.addAll(joox)
                        }
                        val pick = AppAgentToolRegistry.pickBestOriginalCandidate(pool, raw.title, raw.artist, kw)
                        pick?.copy(
                            language = raw.language.ifBlank { pick.language },
                            genre = raw.genre.ifBlank { pick.genre }
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }

        return ImportedPlaylistResult(dissName, "QQ 音乐歌单", resolved.distinctBy { it.uniqueKey }, qqTracks.size)
    }
}
