package com.sonicpilot.music.channel

import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.ChannelType
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
 * 通用自定义开源渠道解析器：
 * 1. ChannelType.CUSTOM_REST：支持用户添加任意自建/社区 JSON 音乐 API（如 go-music-dl / UnblockNeteaseMusic / LX 中转 API），
 *    通过 URL 模板占位符 `{query}`, `{id}`, `{br}`, `{source}` 灵活对接。
 * 2. ChannelType.SUBSONIC：原生兼容 Navidrome / Subsonic / Airsonic 个人无损曲库！
 */
class CustomRestChannelProvider(
    override val channel: MusicChannel,
    private val httpClient: OkHttpClient
) : MusicChannelProvider {

    private suspend fun getText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "SonicPilot/1.0 Android")
            if (channel.apiKey.isNotBlank() && channel.type == ChannelType.CUSTOM_REST) {
                builder.header("Authorization", "Bearer ${channel.apiKey}")
                builder.header("X-API-Key", channel.apiKey)
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    override suspend fun search(query: String, limit: Int, page: Int): List<Song> {
        val encoded = withContext(Dispatchers.IO) { URLEncoder.encode(query.trim(), "UTF-8") }
        return if (channel.type == ChannelType.SUBSONIC) {
            searchSubsonic(encoded, limit)
        } else {
            searchCustomRest(encoded, limit, page)
        }
    }

    private suspend fun searchSubsonic(encodedQuery: String, limit: Int): List<Song> {
        val base = channel.baseUrl.trimEnd('/')
        val url = "$base/rest/search3.view?query=$encodedQuery&songCount=$limit&u=${channel.username}&p=${channel.apiKey}&v=1.16.1&c=SonicPilot&f=json"
        val body = getText(url) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body)
                .optJSONObject("subsonic-response")
                ?.optJSONObject("searchResult3")
                ?.optJSONArray("song") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    val coverId = o.optString("coverArt", "")
                    val coverUrl = if (coverId.isNotBlank()) {
                        "$base/rest/getCoverArt.view?id=$coverId&u=${channel.username}&p=${channel.apiKey}&v=1.16.1&c=SonicPilot"
                    } else ""
                    add(
                        Song(
                            id = "${channel.id}_$id",
                            title = o.optString("title", "未知曲目"),
                            artist = o.optString("artist", "未知歌手"),
                            album = o.optString("album", ""),
                            channelId = channel.id,
                            sourceKey = "subsonic",
                            rawTrackId = id,
                            coverUrl = coverUrl,
                            durationMs = o.optLong("duration", 0L) * 1000L,
                            actualBitrateKbps = o.optInt("bitRate", 999)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun searchCustomRest(encodedQuery: String, limit: Int, page: Int): List<Song> {
        val template = channel.searchPathTemplate.ifBlank {
            "${channel.baseUrl.trimEnd('/')}/search?keyword={query}&source={source}&limit={limit}&page={page}"
        }
        val url = template
            .replace("{query}", encodedQuery)
            .replace("{source}", channel.sourceKey)
            .replace("{limit}", limit.toString())
            .replace("{page}", page.toString())
        val body = getText(url) ?: return emptyList()
        return runCatching {
            val trimmed = body.trim()
            val arr: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("data")
                        ?: root.optJSONArray("songs")
                        ?: root.optJSONArray("list")
                        ?: root.optJSONObject("data")?.optJSONArray("songs")
                        ?: root.optJSONObject("data")?.optJSONArray("list")
                        ?: JSONArray()
                }
            }
            buildList {
                for (i in 0 until minOf(arr.length(), limit)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val rawId = o.optString("id").ifBlank {
                        o.optString("rid").ifBlank { o.optString("songmid", "c_$i") }
                    }
                    val title = o.optString("name").ifBlank { o.optString("title", "未知曲目") }
                    val artistAny = o.opt("artist") ?: o.opt("singer") ?: o.opt("author")
                    val artist = when (artistAny) {
                        is JSONArray -> (0 until artistAny.length()).mapNotNull { artistAny.optString(it) }.joinToString(" / ")
                        is String -> artistAny
                        else -> "未知歌手"
                    }
                    add(
                        Song(
                            id = "${channel.id}_$rawId",
                            title = title,
                            artist = artist.ifBlank { "未知歌手" },
                            album = o.optString("album", ""),
                            channelId = channel.id,
                            sourceKey = channel.sourceKey,
                            rawTrackId = rawId,
                            coverUrl = o.optString("cover").ifBlank { o.optString("pic", "") },
                            durationMs = o.optLong("duration", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun resolveStream(song: Song, preferredQuality: AudioQuality): ResolvedStream? {
        if (channel.type == ChannelType.SUBSONIC) {
            val base = channel.baseUrl.trimEnd('/')
            val streamUrl = "$base/rest/stream.view?id=${song.rawTrackId}&u=${channel.username}&p=${channel.apiKey}&v=1.16.1&c=SonicPilot"
            return ResolvedStream(
                url = streamUrl,
                actualBitrateKbps = song.actualBitrateKbps.takeIf { it > 0 } ?: 999,
                formatExt = "flac",
                resolvedByChannelId = channel.id
            )
        }

        val template = channel.streamPathTemplate.ifBlank {
            "${channel.baseUrl.trimEnd('/')}/url?id={id}&source={source}&br={br}"
        }
        val url = template
            .replace("{id}", song.rawTrackId)
            .replace("{source}", channel.sourceKey)
            .replace("{br}", preferredQuality.gdBr.toString())
        val body = getText(url) ?: return null
        return runCatching {
            val trimmed = body.trim()
            val resolvedUrl = when {
                trimmed.startsWith("http") -> trimmed
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optString("url").ifBlank {
                        obj.optJSONObject("data")?.optString("url", "").orEmpty()
                    }
                }
                else -> ""
            }
            if (resolvedUrl.startsWith("http")) {
                ResolvedStream(
                    url = resolvedUrl,
                    actualBitrateKbps = preferredQuality.gdBr,
                    formatExt = preferredQuality.preferredExt,
                    resolvedByChannelId = channel.id
                )
            } else null
        }.getOrNull()
    }

    override suspend fun fetchCoverUrl(song: Song): String = song.coverUrl

    override suspend fun fetchLyrics(song: Song): LyricData {
        if (channel.type == ChannelType.SUBSONIC) {
            val base = channel.baseUrl.trimEnd('/')
            val body = getText("$base/rest/getLyricsBySongId.view?id=${song.rawTrackId}&u=${channel.username}&p=${channel.apiKey}&v=1.16.1&c=SonicPilot&f=json")
            val text = runCatching {
                JSONObject(body ?: "").optJSONObject("subsonic-response")
                    ?.optJSONObject("lyricsList")?.optJSONArray("structuredLyrics")
                    ?.optJSONObject(0)?.optJSONArray("line")
                    ?.let { lines ->
                        (0 until lines.length()).joinToString("\n") { idx ->
                            val line = lines.getJSONObject(idx)
                            val startMs = line.optLong("start", 0L)
                            val min = startMs / 60000
                            val sec = (startMs % 60000) / 1000
                            val cs = (startMs % 1000) / 10
                            String.format("[%02d:%02d.%02d]%s", min, sec, cs, line.optString("value"))
                        }
                    }.orEmpty()
            }.getOrDefault("")
            return LrcParser.parse(text)
        }
        return LyricData()
    }

    override suspend fun ping(): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val testUrl = if (channel.type == ChannelType.SUBSONIC) {
            "${channel.baseUrl.trimEnd('/')}/rest/ping.view?u=${channel.username}&p=${channel.apiKey}&v=1.16.1&c=SonicPilot&f=json"
        } else {
            channel.baseUrl
        }
        val ok = runCatching {
            httpClient.newCall(Request.Builder().url(testUrl).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (ok) System.currentTimeMillis() - start else -1L
    }
}
