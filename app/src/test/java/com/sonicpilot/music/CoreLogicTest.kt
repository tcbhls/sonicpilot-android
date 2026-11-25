package com.sonicpilot.music

import androidx.compose.ui.graphics.Color
import com.sonicpilot.music.ai.AppAgentToolRegistry
import com.sonicpilot.music.ai.SonicAiAgent
import com.sonicpilot.music.ui.components.parseInlineMarkdown
import com.sonicpilot.music.ui.components.parseMarkdownBlocks
import com.sonicpilot.music.channel.GdStudioChannelProvider
import com.sonicpilot.music.channel.LrcParser
import com.sonicpilot.music.channel.PlaylistImporter
import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.LyricDisplayMode
import com.sonicpilot.music.data.model.LyricLine
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.data.model.SongClassifier
import com.sonicpilot.music.lyrics.CantoneseJyutpingEngine
import com.sonicpilot.music.mv.MvChannelEngine
import com.sonicpilot.music.update.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    @Test
    fun parseBilingualLrcCorrectly() {
        val raw = """
            [00:01.20]Fever dream high in the quiet of the night
            [00:05.50]You know that I caught it
        """.trimIndent()
        val trans = """
            [00:01.25]寂静夜色里炽热迷离的梦
            [00:05.52]你知道我深陷其中
        """.trimIndent()
        val data = LrcParser.parse(raw, trans)
        assertEquals(2, data.lines.size)
        assertEquals(1200L, data.lines[0].timeMs)
        assertEquals("寂静夜色里炽热迷离的梦", data.lines[0].translation)
        assertEquals("你知道我深陷其中", data.lines[1].translation)
    }

    @Test
    fun parseLocalLlmToolCallTags() {
        val llmOut = """
            好的，我马上帮你搜索并播放。
            <tool_call>{"name":"play_song","arguments":{"song_id_or_query":"Taylor Swift Cruel Summer","quality":"hires_999"}}</tool_call>
        """.trimIndent()
        val calls = SonicAiAgent.parseLocalToolCalls(llmOut)
        assertEquals(1, calls.size)
        assertEquals("play_song", calls[0].first)
        assertTrue(calls[0].second.contains("Cruel Summer"))
    }

    @Test
    fun bitrateMappingResolvesExpectedQuality() {
        assertEquals(AudioQuality.HIRES, AudioQuality.fromBitrate(3221))
        assertEquals(AudioQuality.HIRES, AudioQuality.fromBitrate(1654))
        assertEquals(AudioQuality.LOSSLESS, AudioQuality.fromBitrate(740))
        assertEquals(AudioQuality.HIGH, AudioQuality.fromBitrate(320))
        assertEquals(AudioQuality.STANDARD, AudioQuality.fromBitrate(128))
    }

    @Test
    fun cantoneseJyutpingAnnotatesCharactersAndPhrases() {
        val sampleDict = mapOf(
            '我' to "ngo5",
            '当' to "dong1",
            '你' to "nei5",
            '一' to "jat1",
            '秒' to "miu5"
        )
        val engine = CantoneseJyutpingEngine(preloadedDict = sampleDict)
        val tokens = engine.annotateLine("朋友 我当你一秒朋友")
        val nonSpace = tokens.filter { it.text != " " }
        assertEquals("朋", nonSpace[0].text)
        assertEquals("pang4", nonSpace[0].jyutping)
        assertEquals("旁", nonSpace[0].homophone)
        assertEquals("友", nonSpace[1].text)
        assertEquals("jau5", nonSpace[1].jyutping)
        assertEquals("油", nonSpace[1].homophone)
        assertEquals("我", nonSpace[2].text)
        assertEquals("ngo5", nonSpace[2].jyutping)
        assertEquals("哦", nonSpace[2].homophone)

        assertTrue(
            CantoneseJyutpingEngine.isLikelyCantoneseSong(
                title = "爱与诚",
                artist = "古巨基",
                lines = listOf(LyricLine(0L, "若有空 难道有空可接吻", romaji = "yeok yau hung"))
            )
        )
        assertTrue(
            CantoneseJyutpingEngine.isMostlyEnglish(
                listOf(
                    LyricLine(0L, "Fever dream high in the quiet of the night"),
                    LyricLine(4000L, "You know that I caught it")
                )
            )
        )
    }

    @Test
    fun versionComparisonAndPlaceholderDetectionWork() {
        assertTrue(AppUpdater.compareVersions("1.3.0", "1.2.0") > 0)
        assertTrue(AppUpdater.compareVersions("1.3.1", "1.3.0") > 0)
        assertEquals(0, AppUpdater.compareVersions("1.3.0", "1.3.0"))
        assertTrue(AppUpdater.compareVersions("1.2.9", "1.3.0") < 0)

        assertTrue(GdStudioChannelProvider.isPlaceholderCoverUrl("https://p1.music.126.net/6y-UleORITEDbvrOLV0Q8A==/5639395138885805.jpg"))
        assertFalse(GdStudioChannelProvider.isPlaceholderCoverUrl("https://p2.music.126.net/realCover123/109951169999999999.jpg"))

        assertEquals(LyricDisplayMode.ORIGINAL_ONLY, LyricDisplayMode.BILINGUAL.next())
        assertEquals(LyricDisplayMode.TRANSLATION_ONLY, LyricDisplayMode.ORIGINAL_ONLY.next())
        assertEquals(LyricDisplayMode.BILINGUAL, LyricDisplayMode.TRANSLATION_ONLY.next())

        assertEquals("富士山下", MvChannelEngine.cleanSearchTitle("富士山下 (《爱情转移》粤语版)"))
        assertEquals("最佳损友", MvChannelEngine.cleanSearchTitle("最佳损友 (Live)"))
    }

    @Test
    fun playlistLinkParserRecognizesQqAndNetease() {
        val qq = PlaylistImporter.parsePlaylistLink(
            "分享歌单 https://y.qq.com/n/ryqq_v2/playlist/9812532341"
        )
        assertEquals("qq" to "9812532341", qq)

        val ne = PlaylistImporter.parsePlaylistLink(
            "https://music.163.com/#/playlist?id=3778678"
        )
        assertEquals("netease" to "3778678", ne)

        val qqShortResolved = PlaylistImporter.parsePlaylistLink(
            "https://c6.y.qq.com/base/fcgi-bin/u?__=KxyhAbhJqOYI",
            resolvedUrl = "https://y.qq.com/n/ryqq_v2/playlist/9812532341?foo=1"
        )
        assertEquals("qq" to "9812532341", qqShortResolved)
    }

    @Test
    fun songClassifierResolvesLanguageGenreAndBatchSize() {
        val explicit = Song(id = "1", title = "体面", artist = "于文文", channelId = "gd_joox", language = "国语", genre = "流行")
        assertEquals("国语", SongClassifier.resolveLanguage(explicit))
        assertEquals("流行", SongClassifier.resolveGenre(explicit))

        val inferredCanto = Song(id = "2", title = "任我行", artist = "陈奕迅", channelId = "gd_netease")
        assertEquals("粤语", SongClassifier.resolveLanguage(inferredCanto))

        val inferredRap = Song(id = "3", title = "Stan (Explicit)", artist = "Eminem / Dido", channelId = "gd_netease")
        assertEquals("英语", SongClassifier.resolveLanguage(inferredRap))
        assertEquals("说唱", SongClassifier.resolveGenre(inferredRap))

        val inferredJp = Song(id = "4", title = "夜に駆ける", artist = "YOASOBI", channelId = "gd_netease")
        assertEquals("日语", SongClassifier.resolveLanguage(inferredJp))

        assertTrue(SongClassifier.estimateBatchSizeLabel(792, AudioQuality.LOSSLESS).contains("GB"))
        assertTrue(SongClassifier.estimateBatchSizeLabel(10, AudioQuality.HIGH).contains("MB"))
    }

    @Test
    fun aiZeroMissToolGuardCatchesAllCriticalIntents() {
        val knownArtists = setOf("周杰伦", "陈奕迅", "林俊杰", "于文文", "Eminem")

        val t1 = SonicAiAgent.detectActionableToolIntent("把我收藏里的粤语歌找出来随机放一首", knownArtists)
        assertEquals("query_favorites", t1?.first)
        assertTrue(t1!!.second.contains("粤语"))
        assertTrue(t1.second.contains("\"play\""))
        assertTrue(t1.second.contains("\"shuffle\":true"))

        val t2 = SonicAiAgent.detectActionableToolIntent("把我收藏里的粤语歌全部无损下载下来", knownArtists)
        assertEquals("query_favorites", t2?.first)
        assertTrue(t2!!.second.contains("粤语"))
        assertTrue(t2.second.contains("\"download\""))

        val t3 = SonicAiAgent.detectActionableToolIntent("把我收藏里的周杰伦打包成一个叫「杰伦经典」的歌单", knownArtists)
        assertEquals("query_favorites", t3?.first)
        assertTrue(t3!!.second.contains("周杰伦"))
        assertTrue(t3.second.contains("\"create_playlist\""))
        assertTrue(t3.second.contains("杰伦经典"))

        val t4 = SonicAiAgent.detectActionableToolIntent("结合我的收藏推荐 5 首我还没收藏过的粤语新歌", knownArtists)
        assertEquals("recommend_songs", t4?.first)
        assertTrue(t4!!.second.contains("\"exclude_favorites\":true"))

        val t5 = SonicAiAgent.detectActionableToolIntent("我想看陈奕迅 浮夸 的 MV", knownArtists)
        assertEquals("play_mv", t5?.first)
        assertTrue(t5!!.second.contains("陈奕迅"))

        // 纯乐理闲聊不应误触发工具
        val chat = SonicAiAgent.detectActionableToolIntent("什么是五声音阶？", knownArtists)
        assertEquals(null, chat)
    }

    @Test
    fun aiBasicMusicCommandsAndOriginalRankingWork() {
        val knownArtists = setOf("周杰伦", "陈奕迅")

        // 1. 睡眠定时（半小时 & 取消）
        val timer30 = SonicAiAgent.detectActionableToolIntent("半小时后自动停止播放", knownArtists)
        assertEquals("set_sleep_timer", timer30?.first)
        assertTrue(timer30!!.second.contains("30"))

        val timerCancel = SonicAiAgent.detectActionableToolIntent("取消睡眠定时关闭", knownArtists)
        assertEquals("set_sleep_timer", timerCancel?.first)
        assertTrue(timerCancel!!.second.contains("0"))

        // 2. 下一首插队 vs 普通下一首切歌
        val queueNext = SonicAiAgent.detectActionableToolIntent("把陈奕迅的《富士山下》加到下一首播放", knownArtists)
        assertEquals("play_next", queueNext?.first)
        assertTrue(queueNext!!.second.contains("富士山下"))

        val skipNext = SonicAiAgent.detectActionableToolIntent("下一首", knownArtists)
        assertEquals("control_playback", skipNext?.first)
        assertTrue(skipNext!!.second.contains("next"))

        // 3. 学歌倍速 & 打开学歌室
        val learnSlow = SonicAiAgent.detectActionableToolIntent("0.75倍速慢放并打开学歌室", knownArtists)
        assertEquals("control_learning_playback", learnSlow?.first)
        assertTrue(learnSlow!!.second.contains("0.75"))
        assertTrue(learnSlow.second.contains("learn"))

        // 4. 界面主题 / 仅英文歌词 / 粤语谐音
        val uiCmd = SonicAiAgent.detectActionableToolIntent("切换成浅色模式并只留英文歌词", knownArtists)
        assertEquals("control_app_ui", uiCmd?.first)
        assertTrue(uiCmd!!.second.contains("light"))
        assertTrue(uiCmd.second.contains("original_only"))

        // 5. 批量收藏刚才推荐的歌曲
        val batchFav = SonicAiAgent.detectActionableToolIntent("把刚才推荐的这几首歌全部收藏并加到歌单「深夜治愈」", knownArtists)
        assertEquals("batch_favorite_recent", batchFav?.first)
        assertTrue(batchFav!!.second.contains("深夜治愈"))

        // 6. 原唱防翻唱打分引擎：真实录音室原唱必须稳赢假冒歌手、DJ、伴奏、翻唱和 Live
        val fakeUploader = Song(id = "f1", title = "晴天", artist = "周杰伦.", album = "热歌", channelId = "gd_netease")
        val djRemix = Song(id = "f2", title = "晴天 (DJ热播版)", artist = "周杰伦", album = "DJ", channelId = "gd_netease")
        val coverVer = Song(id = "f3", title = "晴天 (翻唱)", artist = "某翻唱博主", album = "吉他弹唱", channelId = "gd_netease")
        val instVer = Song(id = "f4", title = "晴天 (伴奏)", artist = "周杰伦", album = "叶惠美", channelId = "gd_netease")
        val liveVer = Song(id = "f5", title = "晴天 (Live)", artist = "周杰伦", album = "地表最强演唱会", channelId = "gd_joox")
        val studioOrig = Song(id = "ok", title = "晴天", artist = "周杰伦", album = "叶惠美", channelId = "gd_joox", mvId = "12345", coverUrl = "https://real.cover/1.jpg")

        val picked = AppAgentToolRegistry.pickBestOriginalCandidate(
            candidates = listOf(fakeUploader, djRemix, coverVer, instVer, liveVer, studioOrig),
            targetTitle = "晴天",
            targetArtist = "周杰伦"
        )
        assertEquals("ok", picked?.id)
    }

    @Test
    fun richMarkdownParserStripsRawSymbolsCleanly() {
        val sample = """
            ### 为你精选的失恋治愈女声
            1. **《说散就散》** - 袁娅维
            - 副歌爆发力极强，适合 `Hi-Res` 聆听
            > 提示：已从第一首开始播放
        """.trimIndent()
        val blocks = parseMarkdownBlocks(sample)
        assertEquals(4, blocks.size)

        val inline = parseInlineMarkdown("**《说散就散》** · `FLAC`", Color.Red, Color.Gray)
        assertFalse(inline.text.contains("**"))
        assertFalse(inline.text.contains("`"))
        assertTrue(inline.text.contains("《说散就散》"))
        assertTrue(inline.text.contains("FLAC"))
    }
}
