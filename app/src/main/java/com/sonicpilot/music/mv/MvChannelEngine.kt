package com.sonicpilot.music.mv

import com.sonicpilot.music.data.model.MvStream
import com.sonicpilot.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 大陆直连多渠道免费高清 MV 引擎（网易云 1080P → 酷狗高清 MV → Apple iTunes 官方 MV 兜底）
 * - 全部实测零鉴权、免会员直出 MP4 视频流
 * - 内置解析结果缓存，二次秒开
 */
class MvChannelEngine(
    private val httpClient: OkHttpClient
) {
    private val mvCache = ConcurrentHashMap<String, Pair<Long, MvStream>>()

    companion object {
        private const val CACHE_TTL_MS = 20 * 60 * 1000L
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * 清洗歌曲名中的括号副标题（如 “(Live)” / “（《爱情转移》粤语版）”），大幅提升跨源 MV 命中率
         */
        fun cleanSearchTitle(rawTitle: String): String =
            rawTitle.replace(Regex("""[（(【\[][^）)】\]]*[）)】\]]"""), "").trim().ifBlank { rawTitle.trim() }
    }

    suspend fun resolveMvForSong(song: Song): MvStream? = withContext(Dispatchers.IO) {
        val cacheKey = "${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}|${song.mvId}"
        mvCache[cacheKey]?.let { (ts, cached) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext cached
        }

        // 1. 优先：网易云官方高清 MV（最高 1080P MP4，无水印、大陆直连秒开）
        val neteaseMv = resolveFromNetease(song)
        if (neteaseMv != null) {
            mvCache[cacheKey] = System.currentTimeMillis() to neteaseMv
            return@withContext neteaseMv
        }

        // 2. 备选：酷狗音乐高清 MV 搜索与直链（覆盖周杰伦等独家曲目！）
        val kugouMv = resolveFromKugou(song)
        if (kugouMv != null) {
            mvCache[cacheKey] = System.currentTimeMillis() to kugouMv
            return@withContext kugouMv
        }

        // 3. 全球兜底：iTunes 官方 Music Video 预览流（欧美 / 日韩曲库极全）
        val itunesMv = resolveFromItunes(song)
        if (itunesMv != null) {
            mvCache[cacheKey] = System.currentTimeMillis() to itunesMv
            return@withContext itunesMv
        }

        null
    }

    private fun getText(url: String, referer: String = "https://music.163.com/"): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.string()
        }
    }.getOrNull()

    private fun resolveFromNetease(song: Song): MvStream? {
        var mvId = song.mvId.trim().takeIf { it.isNotBlank() && it != "0" }.orEmpty()

        // 若歌曲本身是网易云 ID 但没带 mvId，先查 song/detail
        if (mvId.isBlank() && song.sourceKey.equals("netease", true) && song.rawTrackId.all(Char::isDigit)) {
            val detail = getText("https://music.163.com/api/song/detail/?ids=[${song.rawTrackId}]")
            mvId = runCatching {
                JSONObject(detail ?: "")
                    .optJSONArray("songs")
                    ?.optJSONObject(0)
                    ?.optLong("mvid", 0L)
                    ?.takeIf { it > 0L }
                    ?.toString()
                    .orEmpty()
            }.getOrDefault("")
        }

        // 若还没有 mvId，用清洗后的歌名+歌手在网易云 cloudsearch/pc 搜一轮带有 mv 的同歌版本（避免 get/web 405 频控）
        if (mvId.isBlank()) {
            val cleanTitle = cleanSearchTitle(song.title)
            val kw = "$cleanTitle ${song.artist}".trim()
            val searchBody = runCatching {
                val form = FormBody.Builder()
                    .add("s", kw)
                    .add("type", "1")
                    .add("limit", "8")
                    .build()
                val req = Request.Builder()
                    .url("https://music.163.com/api/cloudsearch/pc")
                    .header("User-Agent", MOBILE_UA)
                    .header("Referer", "https://music.163.com/")
                    .post(form)
                    .build()
                httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull()
            mvId = runCatching {
                val songsArr = JSONObject(searchBody ?: "")
                    .optJSONObject("result")
                    ?.optJSONArray("songs") ?: return@runCatching ""
                var found = ""
                for (i in 0 until songsArr.length()) {
                    val o = songsArr.optJSONObject(i) ?: continue
                    val idVal = o.optLong("mv", o.optLong("mvid", 0L))
                    if (idVal > 0L) {
                        val name = o.optString("name", "")
                        if (name.contains(cleanTitle, true) || cleanTitle.contains(name, true)) {
                            found = idVal.toString()
                            break
                        }
                        if (found.isBlank()) found = idVal.toString()
                    }
                }
                found
            }.getOrDefault("")
        }

        if (mvId.isBlank()) return null

        val body = getText("https://music.163.com/api/mv/detail?id=$mvId&type=mp4") ?: return null
        return runCatching {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val brs = data.optJSONObject("brs") ?: return null
            val ordered = LinkedHashMap<String, String>()
            listOf("1080" to "1080P", "720" to "720P", "480" to "480P", "240" to "240P").forEach { (k, label) ->
                val u = brs.optString(k, "").trim()
                if (u.startsWith("http")) ordered[label] = u
            }
            if (ordered.isEmpty()) return null
            MvStream(
                mvId = mvId,
                title = data.optString("name").ifBlank { song.title },
                artist = data.optString("artistName").ifBlank { song.artist },
                coverUrl = data.optString("cover", song.coverUrl).replace("http://", "https://"),
                sourceLabel = "网易云高清 MV",
                qualityUrls = ordered
            )
        }.getOrNull()
    }

    private fun resolveFromKugou(song: Song): MvStream? {
        val cleanTitle = cleanSearchTitle(song.title)
        val kw = URLEncoder.encode("$cleanTitle ${song.artist}".trim(), "UTF-8")
        val searchBody = getText(
            "http://mvsearch.kugou.com/mv_search?keyword=$kw&page=1&pagesize=5&platform=WebFilter",
            referer = "https://www.kugou.com/"
        ) ?: return null

        val candidates = runCatching {
            val lists = JSONObject(searchBody).optJSONObject("data")?.optJSONArray("lists") ?: return null
            buildList {
                for (i in 0 until minOf(lists.length(), 4)) {
                    val item = lists.optJSONObject(i) ?: continue
                    val hash = item.optString("MvHash", "").trim()
                    if (hash.isNotBlank()) {
                        add(
                            Triple(
                                hash,
                                item.optString("MvName", song.title).replace(Regex("<[^>]+>"), ""),
                                item.optString("SingerName", song.artist).replace(Regex("<[^>]+>"), "")
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())

        for ((hash, mvTitle, mvArtist) in candidates) {
            val detailBody = getText(
                "http://m.kugou.com/app/i/mv.php?cmd=100&hash=$hash&ismp3=1&ext=mp4",
                referer = "https://www.kugou.com/"
            ) ?: continue
            val parsed = runCatching {
                val root = JSONObject(detailBody)
                val mvdata = root.optJSONObject("mvdata") ?: return@runCatching null
                val ordered = LinkedHashMap<String, String>()
                listOf("sq" to "1080P", "rq" to "720P", "le" to "480P").forEach { (k, label) ->
                    val u = mvdata.optJSONObject(k)?.optString("downurl", "").orEmpty().trim()
                    if (u.startsWith("http")) ordered[label] = u
                }
                if (ordered.isEmpty()) return@runCatching null
                val cover = root.optString("mvicon", song.coverUrl).replace("{size}", "400").replace("http://", "https://")
                MvStream(
                    mvId = hash,
                    title = mvTitle.ifBlank { song.title },
                    artist = mvArtist.ifBlank { song.artist },
                    coverUrl = cover,
                    sourceLabel = "酷狗高清 MV",
                    qualityUrls = ordered
                )
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun resolveFromItunes(song: Song): MvStream? {
        val cleanTitle = cleanSearchTitle(song.title)
        val kw = URLEncoder.encode("$cleanTitle ${song.artist}".trim(), "UTF-8")
        val body = getText(
            "https://itunes.apple.com/search?term=$kw&entity=musicVideo&limit=3",
            referer = "https://music.apple.com/"
        ) ?: return null
        return runCatching {
            val arr = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val preview = item.optString("previewUrl", "").trim()
                if (preview.startsWith("http")) {
                    val ordered = LinkedHashMap<String, String>()
                    ordered["官方高清"] = preview
                    return MvStream(
                        mvId = item.optLong("trackId").toString(),
                        title = item.optString("trackName").ifBlank { song.title },
                        artist = item.optString("artistName").ifBlank { song.artist },
                        coverUrl = item.optString("artworkUrl100", song.coverUrl).replace("100x100", "600x600"),
                        sourceLabel = "Apple 官方 MV",
                        qualityUrls = ordered
                    )
                }
            }
            null
        }.getOrNull()
    }
}
