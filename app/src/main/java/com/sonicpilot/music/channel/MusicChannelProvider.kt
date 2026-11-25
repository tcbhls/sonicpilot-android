package com.sonicpilot.music.channel

import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.ChartCategory
import com.sonicpilot.music.data.model.LyricData
import com.sonicpilot.music.data.model.LyricLine
import com.sonicpilot.music.data.model.MusicChannel
import com.sonicpilot.music.data.model.ResolvedStream
import com.sonicpilot.music.data.model.Song

/**
 * 插件化音乐渠道协议接口
 * 任何新渠道（GDStudio / Meting / 自定义 REST / Subsonic / 未来扩展）实现此接口即可无缝接入搜索、播放、歌词与下载。
 */
interface MusicChannelProvider {
    val channel: MusicChannel

    suspend fun search(query: String, limit: Int = 20, page: Int = 1): List<Song>

    suspend fun resolveStream(song: Song, preferredQuality: AudioQuality): ResolvedStream?

    suspend fun fetchCoverUrl(song: Song): String

    suspend fun fetchLyrics(song: Song): LyricData

    suspend fun fetchChart(category: ChartCategory, limit: Int = 30): List<Song> = emptyList()

    /**
     * 健康与延迟探测，返回延迟毫秒数；失败返回 -1L
     */
    suspend fun ping(): Long
}

/**
 * LRC 双语歌词解析器（支持 [mm:ss.xx] / [mm:ss.xxx] 多时间标签与中文翻译时间轴对齐）
 */
object LrcParser {
    private val timeTagRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(rawLrc: String, rawTransLrc: String = "", rawRomaLrc: String = ""): LyricData {
        if (rawLrc.isBlank()) return LyricData()
        val mainMap = parseSingle(rawLrc)
        val transMap = if (rawTransLrc.isNotBlank()) parseSingle(rawTransLrc) else emptyMap()
        val romaMap = if (rawRomaLrc.isNotBlank()) parseSingle(rawRomaLrc) else emptyMap()

        val sortedTimes = mainMap.keys.sorted()
        val lines = sortedTimes.mapNotNull { time ->
            val text = mainMap[time]?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            // 允许 450ms 误差匹配对应翻译与官方音译歌词
            val trans = transMap[time]
                ?: transMap.entries.minByOrNull { kotlin.math.abs(it.key - time) }
                    ?.takeIf { kotlin.math.abs(it.key - time) <= 450L }
                    ?.value
                    .orEmpty()
            val roma = romaMap[time]
                ?: romaMap.entries.minByOrNull { kotlin.math.abs(it.key - time) }
                    ?.takeIf { kotlin.math.abs(it.key - time) <= 450L }
                    ?.value
                    .orEmpty()
            LyricLine(timeMs = time, text = text, translation = trans.trim(), romaji = roma.trim())
        }
        return LyricData(rawLrc = rawLrc, rawTransLrc = rawTransLrc, lines = lines)
    }

    private fun parseSingle(raw: String): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        raw.lines().forEach { line ->
            val matches = timeTagRegex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val content = line.replace(timeTagRegex, "").trim()
            if (content.isBlank()) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = m.groupValues[3]
                val ms = when (fracStr.length) {
                    0 -> 0L
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    else -> fracStr.take(3).toLongOrNull() ?: 0L
                }
                val timestamp = min * 60_000L + sec * 1_000L + ms
                result[timestamp] = content
            }
        }
        return result
    }
}
