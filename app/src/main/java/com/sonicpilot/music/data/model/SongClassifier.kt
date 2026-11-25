package com.sonicpilot.music.data.model

/**
 * 离线音乐语种与流派多维分类引擎（对标 QQ 音乐「喜欢」多维筛选体系）
 * - 优先使用曲目自带的官方 `language` / `genre` 标签（预置 792 首已全量写入 QQ 音乐官方标签）
 * - 对用户新收藏/搜索/导入的无标签曲目，毫秒级纯端侧推断语种与风格，开箱即用零等待
 */
object SongClassifier {

    val LANGUAGE_TAGS: List<String> = listOf(
        "国语", "英语", "粤语", "日语", "韩语", "纯音乐", "闽南语", "俄语"
    )

    val GENRE_TAGS: List<String> = listOf(
        "流行", "电子音乐", "说唱", "摇滚", "民谣", "节奏布鲁斯",
        "国风", "影视原声", "古典音乐", "爵士", "金属", "乡村乐", "蓝调", "雷鬼", "新世纪", "儿童音乐"
    )

    private val CANTONESE_ARTISTS = setOf(
        "陈奕迅", "张学友", "张国荣", "古巨基", "杨千嬅", "容祖儿", "张敬轩", "陈慧娴",
        "李克勤", "谭咏麟", "陈百强", "谢霆锋", "林峯", "林峰", "卫兰", "侧田", "周柏豪",
        "连诗雅", "吴雨霏", "谢安琪", "薛凯琪", "郑秀文", "郭富城", "黎明", "刘德华",
        "郑融", "陈小春", "许志安", "苏永康", "梁汉文", "何韵诗", "卢巧音", "陈柏宇",
        "林奕匡", "洪卓立", "麦浚龙", "beyond", "黄家驹", "twins", "达明一派", "郑伊健",
        "郑中基", "林子祥", "叶倩文", "许冠杰", "梅艳芳", "王菲", "草蜢", "dear jane",
        "rubberband", "supper moment", "陈蕾", "林家谦", "姜涛", "泳儿", "李悦君", "吴若希"
    )

    private val RAP_ARTISTS = setOf(
        "eminem", "drake", "kendrick lamar", "kanye west", "travis scott", "post malone",
        "nelly", "macklemore", "juice wrld", "xxxtentacion", "lil", "tyga", "wiz khalifa",
        "snoop dogg", "2pac", "jay-z", "nicki minaj", "cardi b", "doja cat", "jack harlow",
        "马思唯", "knowknow", "higher brothers", "王以太", "艾热", "李佳隆", "gai", "周延",
        "jony j", "pg one", "vava", "热狗", "mc hotdog", "顽童", "瘦子", "法老", "杨和苏",
        "早安", "盛宇", "功夫胖", "刘聪", "姜云升", "沙一汀", "队长", "王齐铭", "谢帝",
        "满舒克", "tt", "你的男孩", "弹壳", "贝贝", "小鬼", "乃万", "capper", "陈泽希", "h3r3"
    )

    private val EDM_ARTISTS = setOf(
        "alan walker", "avicii", "marshmello", "the chainsmokers", "martin garrix",
        "calvin harris", "david guetta", "kygo", "zedd", "vicetone", "k-391", "jonas blue",
        "tiesto", "hardwell", "skrillex", "illenium", "porter robinson", "madeon", "flume",
        "tobu", "ahrix", "thefatrat", "elektronomia", "deorro", "steve aoki", "alesso",
        "galantis", "clean bandit", "sigala", "robin schulz", "lost frequencies", "r3hab"
    )

    private val ROCK_ARTISTS = setOf(
        "linkin park", "coldplay", "imagine dragons", "onerepublic", "maroon 5", "queen",
        "nirvana", "green day", "bon jovi", "guns n' roses", "ac/dc", "metallica", "muse",
        "radiohead", "oasis", "fall out boy", "panic! at the disco", "twenty one pilots",
        "one ok rock", "radwimps", "五月天", "beyond", "信乐团", "动力火车", "逃跑计划",
        "草东没有派对", "万能青年旅店", "新裤子", "痛仰", "回春丹", "告五人", "八三夭",
        "茄子蛋", "落日飞车", "伍佰", "许巍", "朴树", "郑钧", "汪峰", "黑豹乐队", "唐朝乐队"
    )

    private val FOLK_ARTISTS = setOf(
        "赵雷", "陈粒", "房东的猫", "好妹妹", "宋冬野", "马頔", "尧十三", "花粥",
        "谢春花", "程璧", "陈鸿宇", "隔壁老樊", "焦迈奇", "柳爽", "丢火车", "鹿先森",
        "老狼", "水木年华", "李健", "毛不易", "ed sheeran", "taylor swift"
    )

    private val RNB_ARTISTS = setOf(
        "陶喆", "方大同", "王力宏", "周杰伦", "袁娅维", "胡彦斌", "李荣浩", "林俊杰",
        "the weeknd", "bruno mars", "chris brown", "usher", "alicia keys", "frank ocean",
        "sza", "daniel caesar", "khalid", "keshi", "ne-yo", "john legend", "charlie puth"
    )

    /**
     * 解析曲目语种标签（优先官方标签，缺失时纯本地高精度推断）
     */
    fun resolveLanguage(song: Song): String {
        val explicit = song.language.trim()
        if (explicit.isNotBlank()) return normalizeLanguageLabel(explicit)

        val combined = "${song.title} ${song.album}"
        val low = combined.lowercase()
        if (low.contains("纯音乐") || low.contains("instrumental") || low.contains("钢琴曲") || low.contains("伴奏")) {
            return "纯音乐"
        }
        if (low.contains("粤语") || low.contains("cantonese")) return "粤语"
        if (low.contains("闽南") || low.contains("台语")) return "闽南语"
        if (low.contains("国语")) return "国语"

        var cjk = 0
        var kana = 0
        var hangul = 0
        var cyrillic = 0
        var latin = 0
        for (ch in song.title) {
            when {
                ch in '\u3040'..'\u30ff' -> kana++
                ch in '\uac00'..'\ud7af' -> hangul++
                ch in '\u0400'..'\u04ff' -> cyrillic++
                ch in '\u4e00'..'\u9fff' -> cjk++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }
        if (hangul >= 1) return "韩语"
        if (kana >= 1) return "日语"
        if (cyrillic >= 2) return "俄语"
        if (cjk == 0 && latin >= 2) return "英语"

        val artistLow = song.artist.lowercase()
        if (CANTONESE_ARTISTS.any { artistLow.contains(it) }) {
            // 知名粤语歌手的中文曲目默认优先识别为粤语（除非标注国语版）
            if (!low.contains("国语")) return "粤语"
        }
        return if (cjk > 0) "国语" else "英语"
    }

    /**
     * 解析曲目风格流派标签（优先官方标签，缺失时结合歌手与曲名特征推断）
     */
    fun resolveGenre(song: Song): String {
        val explicit = song.genre.trim()
        if (explicit.isNotBlank()) return explicit

        val text = "${song.title} ${song.album}".lowercase()
        val artistLow = song.artist.lowercase()

        if (text.contains("电影") || text.contains("电视") || text.contains("插曲") ||
            text.contains("片尾曲") || text.contains("片头曲") || text.contains("主题曲") ||
            text.contains("ost") || text.contains("原声")
        ) return "影视原声"
        if (text.contains("说唱") || text.contains("rap") || text.contains("hip-hop") ||
            text.contains("cypher") || text.contains("freestyle") ||
            RAP_ARTISTS.any { artistLow.contains(it) }
        ) return "说唱"
        if (text.contains("remix") || text.contains("edm") || text.contains("dj") ||
            text.contains("电子") || EDM_ARTISTS.any { artistLow.contains(it) }
        ) return "电子音乐"
        if (text.contains("摇滚") || text.contains("rock") || ROCK_ARTISTS.any { artistLow.contains(it) }) {
            return "摇滚"
        }
        if (text.contains("民谣") || text.contains("folk") || FOLK_ARTISTS.any { artistLow.contains(it) }) {
            return "民谣"
        }
        if (text.contains("r&b") || text.contains("节奏布鲁斯") || RNB_ARTISTS.any { artistLow.contains(it) }) {
            return "节奏布鲁斯"
        }
        if (text.contains("古风") || text.contains("国风") || text.contains("戏腔")) return "国风"
        if (text.contains("爵士") || text.contains("jazz")) return "爵士"
        if (text.contains("蓝调") || text.contains("blues")) return "蓝调"
        if (text.contains("古典") || text.contains("交响") || text.contains("奏鸣曲") || text.contains("钢琴曲")) {
            return "古典音乐"
        }
        if (text.contains("金属") || text.contains("metal")) return "金属"
        if (text.contains("乡村") || text.contains("country")) return "乡村乐"
        return "流行"
    }

    private fun normalizeLanguageLabel(raw: String): String = when {
        raw.contains("纯音乐") -> "纯音乐"
        raw.contains("国语") || raw.contains("普通话") -> "国语"
        raw.contains("粤语") || raw.contains("广东") -> "粤语"
        raw.contains("英") -> "英语"
        raw.contains("日") -> "日语"
        raw.contains("韩") -> "韩语"
        raw.contains("俄") -> "俄语"
        raw.contains("闽南") || raw.contains("台语") -> "闽南语"
        else -> raw
    }

    /**
     * 批量下载体积估算（帮助用户 700+ 首批量下载前直观选择合适音质）
     */
    fun estimateBatchSizeLabel(count: Int, quality: AudioQuality): String {
        if (count <= 0) return "0 MB"
        val mbPerSong = when (quality) {
            AudioQuality.HIRES -> 42.0
            AudioQuality.LOSSLESS -> 28.0
            AudioQuality.HIGH -> 9.2
            AudioQuality.MEDIUM -> 5.6
            AudioQuality.STANDARD -> 3.8
        }
        val totalMb = count * mbPerSong
        return if (totalMb >= 1024.0) {
            String.format("约 %.1f GB", totalMb / 1024.0)
        } else {
            String.format("约 %d MB", totalMb.toInt())
        }
    }
}
