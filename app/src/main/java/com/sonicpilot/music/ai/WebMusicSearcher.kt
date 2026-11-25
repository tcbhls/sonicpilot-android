package com.sonicpilot.music.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * AI 专属联网音乐检索引擎（大陆直连）
 * 用于支持用户以模糊歌词、影视剧配乐、歌手热曲、全球流派线索等方式让 AI 联网找歌！
 */
class WebMusicSearcher(
    private val httpClient: OkHttpClient
) {
    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private suspend fun getText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Referer", "https://music.163.com/")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private suspend fun searchNeteaseLyrics(query: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val form = FormBody.Builder()
                .add("s", query)
                .add("type", "1006")
                .add("offset", "0")
                .add("limit", "5")
                .add("total", "true")
                .build()
            val req = Request.Builder()
                .url("https://music.163.com/api/cloudsearch/pc")
                .header("User-Agent", ua)
                .header("Referer", "https://music.163.com/")
                .post(form)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /**
     * 多路联网找歌：
     * 1. 网易云歌词全文检索（cloudsearch/pc type=1006，免 405 频控，记一句歌词就能找原曲）
     * 2. Apple iTunes 全球曲库元数据检索（覆盖欧美/日韩/海外冷门原声）
     */
    suspend fun searchMusicWeb(query: String, limit: Int = 8): String = withContext(Dispatchers.IO) {
        val clean = query.trim()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val sb = StringBuilder()

        // 1. 歌词精准找歌
        val lyricJson = searchNeteaseLyrics(clean)
            ?: getText("https://music.163.com/api/search/get/web?csrf_token=&s=$encoded&type=1006&offset=0&total=true&limit=5")
        if (!lyricJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONObject(lyricJson).optJSONObject("result")?.optJSONArray("songs")
                if (arr != null && arr.length() > 0) {
                    sb.appendLine("【歌词线索匹配结果】")
                    for (i in 0 until minOf(arr.length(), 5)) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("name")
                        val artistsArr = o.optJSONArray("ar") ?: o.optJSONArray("artists")
                        val artists = artistsArr?.let { a ->
                            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString("/")
                        }.orEmpty()
                        val album = (o.optJSONObject("al") ?: o.optJSONObject("album"))?.optString("name").orEmpty()
                        sb.appendLine("- 《$name》 - $artists (专辑: $album, neteaseId=${o.optLong("id")})")
                    }
                }
            }
        }

        // 2. 全球 iTunes 曲库元数据补充（全球英文/日韩/影视原声极全）
        val itunesJson = getText(
            "https://itunes.apple.com/search?term=$encoded&entity=song&limit=$limit&country=us"
        )
        if (!itunesJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONObject(itunesJson).optJSONArray("results")
                if (arr != null && arr.length() > 0) {
                    sb.appendLine("【全球 iTunes 曲库情报】")
                    for (i in 0 until minOf(arr.length(), limit)) {
                        val o = arr.optJSONObject(i) ?: continue
                        val track = o.optString("trackName")
                        val artist = o.optString("artistName")
                        val genre = o.optString("primaryGenreName")
                        val year = o.optString("releaseDate").take(4)
                        sb.appendLine("- 《$track》 - $artist [$genre, $year]")
                    }
                }
            }
        }

        if (sb.isEmpty()) {
            "联网检索暂未返回额外线索，建议直接使用 search_songs 在内置渠道中尝试精简关键词。"
        } else {
            sb.toString().trim()
        }
    }
}
