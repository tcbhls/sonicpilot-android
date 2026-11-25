package com.sonicpilot.music.channel

import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.ChartCategory
import com.sonicpilot.music.data.model.LyricData
import com.sonicpilot.music.data.model.MusicChannel
import com.sonicpilot.music.data.model.ResolvedStream
import com.sonicpilot.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Meting 开源协议兼容渠道
 * 支持公益节点（如 https://api.injahow.cn/meting/）以及用户使用 Docker / Cloudflare Worker 自建的 meting-api
 */
class MetingChannelProvider(
    override val channel: MusicChannel,
    private val httpClient: OkHttpClient
) : MusicChannelProvider {

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private suspend fun getText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", ua).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun endpoint(): String = channel.baseUrl.trimEnd('/')

    override suspend fun search(query: String, limit: Int, page: Int): List<Song> {
        val server = channel.sourceKey.ifBlank { "netease" }
        // 若为网易云后端，利用公开搜索拿 ID 再通过 Meting 端点播放/拿词
        val encoded = withContext(Dispatchers.IO) { URLEncoder.encode(query.trim(), "UTF-8") }
        val directSearchUrl = "${endpoint()}/?server=$server&type=search&id=$encoded"
        val directBody = getText(directSearchUrl)
        if (directBody != null && directBody.trimStart().startsWith("[")) {
            val parsed = parseMetingArray(directBody, server, limit)
            if (parsed.isNotEmpty()) return parsed
        }
        // 回退至网易云公开搜索接口拿到真实 ID，交给 Meting 节点解析直链
        if (server.equals("netease", ignoreCase = true)) {
            val neteaseBody = getText(
                "https://music.163.com/api/search/get/web?csrf_token=&s=$encoded&type=1&offset=0&total=true&limit=$limit"
            ) ?: return emptyList()
            return runCatching {
                val arr = JSONObject(neteaseBody).optJSONObject("result")?.optJSONArray("songs")
                    ?: return emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val rawId = o.optLong("id").takeIf { it > 0 }?.toString() ?: continue
                        val title = o.optString("name", "未知曲目")
                        val artists = o.optJSONArray("artists")
                        val artist = if (artists != null) {
                            (0 until artists.length()).mapNotNull {
                                artists.optJSONObject(it)?.optString("name")
                            }.joinToString(" / ")
                        } else "未知歌手"
                        val album = o.optJSONObject("album")?.optString("name", "").orEmpty()
                        val pic = o.optJSONObject("album")?.optString("picUrl", "").orEmpty()
                            .replace("http://", "https://")
                        add(
                            Song(
                                id = "${channel.id}_$rawId",
                                title = title,
                                artist = artist.ifBlank { "未知歌手" },
                                album = album,
                                channelId = channel.id,
                                sourceKey = server,
                                rawTrackId = rawId,
                                lyricId = rawId,
                                coverUrl = pic,
                                durationMs = o.optLong("duration", 0L)
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    private fun parseMetingArray(body: String, server: String, limit: Int): List<Song> = runCatching {
        val arr = JSONArray(body)
        val n = minOf(arr.length(), limit)
        buildList {
            for (i in 0 until n) {
                val o = arr.optJSONObject(i) ?: continue
                val urlStr = o.optString("url", "")
                val idFromUrl = Regex("""[?&]id=([^&]+)""").find(urlStr)?.groupValues?.get(1)
                val rawId = o.optString("id").ifBlank { idFromUrl ?: "m_$i" }
                add(
                    Song(
                        id = "${channel.id}_$rawId",
                        title = o.optString("name", o.optString("title", "未知曲目")),
                        artist = o.optString("artist", o.optString("author", "未知歌手")),
                        album = o.optString("album", ""),
                        channelId = channel.id,
                        sourceKey = server,
                        rawTrackId = rawId,
                        lyricId = rawId,
                        coverUrl = o.optString("pic", "")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    override suspend fun resolveStream(song: Song, preferredQuality: AudioQuality): ResolvedStream? {
        val server = channel.sourceKey.ifBlank { "netease" }
        val url = "${endpoint()}/?server=$server&type=url&id=${song.rawTrackId}"
        // 校验可访问性（追踪重定向拿真实音频地址）
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).header("User-Agent", ua).head().build()
                httpClient.newCall(req).execute().use { resp ->
                    val finalUrl = resp.request.url.toString()
                    val ct = resp.header("Content-Type").orEmpty()
                    if (resp.isSuccessful && (ct.contains("audio") || ct.contains("octet-stream") || finalUrl != url)) {
                        val isFlac = finalUrl.contains(".flac", true) || ct.contains("flac", true)
                        ResolvedStream(
                            url = finalUrl,
                            actualBitrateKbps = if (isFlac) 999 else 320,
                            fileSizeBytes = resp.header("Content-Length")?.toLongOrNull() ?: 0L,
                            formatExt = if (isFlac) "flac" else "mp3",
                            resolvedByChannelId = channel.id
                        )
                    } else null
                }
            }.getOrNull()
        }
    }

    override suspend fun fetchCoverUrl(song: Song): String {
        if (song.coverUrl.startsWith("http")) return song.coverUrl
        val server = channel.sourceKey.ifBlank { "netease" }
        return "${endpoint()}/?server=$server&type=pic&id=${song.rawTrackId}"
    }

    override suspend fun fetchLyrics(song: Song): LyricData {
        val server = channel.sourceKey.ifBlank { "netease" }
        val body = getText("${endpoint()}/?server=$server&type=lrc&id=${song.rawTrackId}") ?: return LyricData()
        return LrcParser.parse(body)
    }

    override suspend fun fetchChart(category: ChartCategory, limit: Int): List<Song> {
        val server = channel.sourceKey.ifBlank { "netease" }
        val body = getText("${endpoint()}/?server=$server&type=playlist&id=${category.neteasePlaylistId}")
            ?: return emptyList()
        return parseMetingArray(body, server, limit)
    }

    override suspend fun ping(): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val body = getText("${endpoint()}/?server=${channel.sourceKey.ifBlank { "netease" }}&type=song&id=186016")
        if (body != null && body.trimStart().startsWith("[")) System.currentTimeMillis() - start else -1L
    }
}
