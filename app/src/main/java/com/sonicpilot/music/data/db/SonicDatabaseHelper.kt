package com.sonicpilot.music.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.ChannelType
import com.sonicpilot.music.data.model.ChatMessage
import com.sonicpilot.music.data.model.DownloadItem
import com.sonicpilot.music.data.model.DownloadStatus
import com.sonicpilot.music.data.model.MusicChannel
import com.sonicpilot.music.data.model.Playlist
import com.sonicpilot.music.data.model.SingingEvaluation
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.data.model.ToolExecutionLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 音枢 SQLite 本地持久化中心（零反射、事务安全）
 * 涵盖：音乐渠道、收藏曲库、歌单与曲目、离线下载、播放历史、声乐评测历史、AI 对话记忆
 */
class SonicDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "sonicpilot_music.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 开启 WAL 预写日志模式：大批量并发下载写库时绝不阻塞主界面读曲库！
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE channels (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                base_url TEXT NOT NULL,
                source_key TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                is_primary INTEGER NOT NULL DEFAULT 0,
                is_built_in INTEGER NOT NULL DEFAULT 0,
                priority INTEGER NOT NULL DEFAULT 10,
                description TEXT,
                api_key TEXT,
                username TEXT,
                search_path_template TEXT,
                stream_path_template TEXT,
                last_latency_ms INTEGER NOT NULL DEFAULT -1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE favorites (
                unique_key TEXT PRIMARY KEY,
                song_json TEXT NOT NULL,
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE playlists (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                created_by_ai INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                cover_url TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE playlist_songs (
                playlist_id TEXT NOT NULL,
                unique_key TEXT NOT NULL,
                song_json TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, unique_key)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE downloads (
                unique_key TEXT PRIMARY KEY,
                song_json TEXT NOT NULL,
                requested_quality TEXT NOT NULL,
                actual_bitrate INTEGER NOT NULL DEFAULT 0,
                file_path TEXT,
                exported_uri TEXT,
                file_size INTEGER NOT NULL DEFAULT 0,
                progress REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                error_message TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE play_history (
                unique_key TEXT PRIMARY KEY,
                song_json TEXT NOT NULL,
                play_count INTEGER NOT NULL DEFAULT 1,
                last_played_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE singing_evaluations (
                id TEXT PRIMARY KEY,
                song_title TEXT NOT NULL,
                song_artist TEXT,
                song_id TEXT,
                audio_file_path TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                overall_score INTEGER NOT NULL,
                pitch_accuracy_score INTEGER NOT NULL,
                pitch_stability_score INTEGER NOT NULL,
                rhythm_score INTEGER NOT NULL,
                breath_control_score INTEGER NOT NULL,
                vocal_range_semitones INTEGER NOT NULL,
                lowest_note TEXT,
                highest_note TEXT,
                estimated_key TEXT,
                vibrato_detected INTEGER NOT NULL DEFAULT 0,
                in_tune_ratio_percent INTEGER NOT NULL,
                median_abs_cents_error REAL NOT NULL,
                off_pitch_moments_json TEXT,
                ai_feedback TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE chat_messages (
                id TEXT PRIMARY KEY,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                engine_badge TEXT,
                tool_logs_json TEXT,
                recommended_songs_json TEXT,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createIndexes(db)
        }
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_favorites_added_at ON favorites(added_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_status ON downloads(status, created_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_played_at ON play_history(last_played_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_playlist_songs_pl ON playlist_songs(playlist_id, added_at DESC)")
    }

    // ==================== 渠道 CRUD ====================

    fun getAllChannels(): List<MusicChannel> = readableDatabase.rawQuery(
        "SELECT * FROM channels ORDER BY is_primary DESC, priority ASC",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    MusicChannel(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        type = ChannelType.fromName(c.getString(c.getColumnIndexOrThrow("type"))),
                        baseUrl = c.getString(c.getColumnIndexOrThrow("base_url")),
                        sourceKey = c.getString(c.getColumnIndexOrThrow("source_key")),
                        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
                        isPrimary = c.getInt(c.getColumnIndexOrThrow("is_primary")) == 1,
                        isBuiltIn = c.getInt(c.getColumnIndexOrThrow("is_built_in")) == 1,
                        priority = c.getInt(c.getColumnIndexOrThrow("priority")),
                        description = c.str("description"),
                        apiKey = c.str("api_key"),
                        username = c.str("username"),
                        searchPathTemplate = c.str("search_path_template"),
                        streamPathTemplate = c.str("stream_path_template"),
                        lastLatencyMs = c.getLong(c.getColumnIndexOrThrow("last_latency_ms"))
                    )
                )
            }
        }
    }

    fun upsertChannel(ch: MusicChannel) {
        val cv = ContentValues().apply {
            put("id", ch.id)
            put("name", ch.name)
            put("type", ch.type.name)
            put("base_url", ch.baseUrl)
            put("source_key", ch.sourceKey)
            put("enabled", if (ch.enabled) 1 else 0)
            put("is_primary", if (ch.isPrimary) 1 else 0)
            put("is_built_in", if (ch.isBuiltIn) 1 else 0)
            put("priority", ch.priority)
            put("description", ch.description)
            put("api_key", ch.apiKey)
            put("username", ch.username)
            put("search_path_template", ch.searchPathTemplate)
            put("stream_path_template", ch.streamPathTemplate)
            put("last_latency_ms", ch.lastLatencyMs)
        }
        writableDatabase.insertWithOnConflict("channels", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteChannel(id: String) {
        writableDatabase.delete("channels", "id = ?", arrayOf(id))
    }

    // ==================== 收藏 CRUD ====================

    fun getFavorites(): List<Song> = readableDatabase.rawQuery(
        "SELECT song_json FROM favorites ORDER BY added_at DESC",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                songFromJson(c.getString(0))?.let { add(it) }
            }
        }
    }

    fun isFavorite(uniqueKey: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM favorites WHERE unique_key = ? LIMIT 1",
        arrayOf(uniqueKey)
    ).use { it.moveToFirst() }

    fun addFavorite(song: Song) {
        val cv = ContentValues().apply {
            put("unique_key", song.uniqueKey)
            put("song_json", songToJson(song).toString())
            put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("favorites", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 事务级批量写入收藏（796 首曲目约 25ms 完成，零主线程阻塞，严格保留歌单原有顺序）
     */
    fun bulkAddFavorites(songs: List<Song>, overwriteExisting: Boolean = false): Int {
        if (songs.isEmpty()) return 0
        val db = writableDatabase
        val baseTime = System.currentTimeMillis()
        val strategy = if (overwriteExisting) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE
        var inserted = 0
        db.beginTransaction()
        try {
            songs.forEachIndexed { idx, song ->
                val cv = ContentValues().apply {
                    put("unique_key", song.uniqueKey)
                    put("song_json", songToJson(song).toString())
                    // 倒序时间戳保证 ORDER BY added_at DESC 与原歌单顺序完全一致
                    put("added_at", baseTime - idx * 10L)
                }
                val rowId = db.insertWithOnConflict("favorites", null, cv, strategy)
                if (rowId != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun removeFavorite(uniqueKey: String) {
        writableDatabase.delete("favorites", "unique_key = ?", arrayOf(uniqueKey))
    }

    /**
     * 事务级批量取消收藏（多选批量移除用）
     */
    fun bulkRemoveFavorites(uniqueKeys: Collection<String>): Int {
        if (uniqueKeys.isEmpty()) return 0
        val db = writableDatabase
        var removed = 0
        db.beginTransaction()
        try {
            uniqueKeys.forEach { key ->
                removed += db.delete("favorites", "unique_key = ?", arrayOf(key))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return removed
    }

    /**
     * 增量补全已有收藏曲目的语种与流派标签（版本升级时无损合并不破坏用户增删记录）
     */
    fun bulkMergeFavoriteMetadata(songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val byKey = songs.associateBy { it.uniqueKey }
        val existing = readableDatabase.rawQuery(
            "SELECT unique_key, song_json, added_at FROM favorites",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Triple(c.getString(0), c.getString(1), c.getLong(2)))
                }
            }
        }
        if (existing.isEmpty()) return bulkAddFavorites(songs, overwriteExisting = false)
        val db = writableDatabase
        var updated = 0
        db.beginTransaction()
        try {
            existing.forEach { (key, rawJson, addedAt) ->
                val ref = byKey[key] ?: return@forEach
                val cur = songFromJson(rawJson) ?: return@forEach
                val needLang = cur.language.isBlank() && ref.language.isNotBlank()
                val needGenre = cur.genre.isBlank() && ref.genre.isNotBlank()
                val needCover = cur.coverUrl.isBlank() && ref.coverUrl.isNotBlank()
                if (needLang || needGenre || needCover) {
                    val merged = cur.copy(
                        language = cur.language.ifBlank { ref.language },
                        genre = cur.genre.ifBlank { ref.genre },
                        coverUrl = cur.coverUrl.ifBlank { ref.coverUrl }
                    )
                    val cv = ContentValues().apply {
                        put("unique_key", key)
                        put("song_json", songToJson(merged).toString())
                        put("added_at", addedAt)
                    }
                    db.insertWithOnConflict("favorites", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    updated++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    // ==================== 歌单 CRUD ====================

    fun getPlaylists(): List<Playlist> = readableDatabase.rawQuery(
        """
        SELECT p.*, (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlist_id = p.id) AS cnt
        FROM playlists p ORDER BY p.created_at DESC
        """.trimIndent(),
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Playlist(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        description = c.str("description"),
                        createdByAi = c.getInt(c.getColumnIndexOrThrow("created_by_ai")) == 1,
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        songCount = c.getInt(c.getColumnIndexOrThrow("cnt")),
                        coverUrl = c.str("cover_url")
                    )
                )
            }
        }
    }

    fun upsertPlaylist(playlist: Playlist) {
        val cv = ContentValues().apply {
            put("id", playlist.id)
            put("name", playlist.name)
            put("description", playlist.description)
            put("created_by_ai", if (playlist.createdByAi) 1 else 0)
            put("created_at", playlist.createdAt)
            put("cover_url", playlist.coverUrl)
        }
        writableDatabase.insertWithOnConflict("playlists", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deletePlaylist(playlistId: String) {
        writableDatabase.delete("playlist_songs", "playlist_id = ?", arrayOf(playlistId))
        writableDatabase.delete("playlists", "id = ?", arrayOf(playlistId))
    }

    fun addSongToPlaylist(playlistId: String, song: Song) {
        val cv = ContentValues().apply {
            put("playlist_id", playlistId)
            put("unique_key", song.uniqueKey)
            put("song_json", songToJson(song).toString())
            put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("playlist_songs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        if (song.coverUrl.isNotBlank()) {
            writableDatabase.execSQL(
                "UPDATE playlists SET cover_url = ? WHERE id = ? AND (cover_url IS NULL OR cover_url = '')",
                arrayOf(song.coverUrl, playlistId)
            )
        }
    }

    fun bulkAddSongsToPlaylist(playlistId: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            songs.forEachIndexed { idx, song ->
                val cv = ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("unique_key", song.uniqueKey)
                    put("song_json", songToJson(song).toString())
                    put("added_at", now - idx * 5L)
                }
                db.insertWithOnConflict("playlist_songs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val firstCover = songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl
            if (!firstCover.isNullOrBlank()) {
                db.execSQL(
                    "UPDATE playlists SET cover_url = ? WHERE id = ? AND (cover_url IS NULL OR cover_url = '')",
                    arrayOf(firstCover, playlistId)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removeSongFromPlaylist(playlistId: String, uniqueKey: String) {
        writableDatabase.delete(
            "playlist_songs",
            "playlist_id = ? AND unique_key = ?",
            arrayOf(playlistId, uniqueKey)
        )
    }

    fun getPlaylistSongs(playlistId: String): List<Song> = readableDatabase.rawQuery(
        "SELECT song_json FROM playlist_songs WHERE playlist_id = ? ORDER BY added_at DESC",
        arrayOf(playlistId)
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                songFromJson(c.getString(0))?.let { add(it) }
            }
        }
    }

    // ==================== 下载记录 CRUD ====================

    fun getDownloads(): List<DownloadItem> = readableDatabase.rawQuery(
        "SELECT * FROM downloads ORDER BY created_at DESC",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val song = songFromJson(c.str("song_json")) ?: continue
                add(
                    DownloadItem(
                        uniqueKey = c.str("unique_key"),
                        song = song,
                        requestedQuality = AudioQuality.fromCode(c.str("requested_quality")),
                        actualBitrateKbps = c.getInt(c.getColumnIndexOrThrow("actual_bitrate")),
                        filePath = c.str("file_path"),
                        exportedUri = c.str("exported_uri"),
                        fileSizeBytes = c.getLong(c.getColumnIndexOrThrow("file_size")),
                        progress = c.getFloat(c.getColumnIndexOrThrow("progress")),
                        status = runCatching { DownloadStatus.valueOf(c.str("status")) }
                            .getOrDefault(DownloadStatus.FAILED),
                        errorMessage = c.str("error_message"),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
    }

    fun upsertDownload(item: DownloadItem) {
        val cv = ContentValues().apply {
            put("unique_key", item.uniqueKey)
            put("song_json", songToJson(item.song).toString())
            put("requested_quality", item.requestedQuality.code)
            put("actual_bitrate", item.actualBitrateKbps)
            put("file_path", item.filePath)
            put("exported_uri", item.exportedUri)
            put("file_size", item.fileSizeBytes)
            put("progress", item.progress)
            put("status", item.status.name)
            put("error_message", item.errorMessage)
            put("created_at", item.createdAt)
        }
        writableDatabase.insertWithOnConflict("downloads", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun bulkUpsertDownloads(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                val cv = ContentValues().apply {
                    put("unique_key", item.uniqueKey)
                    put("song_json", songToJson(item.song).toString())
                    put("requested_quality", item.requestedQuality.code)
                    put("actual_bitrate", item.actualBitrateKbps)
                    put("file_path", item.filePath)
                    put("exported_uri", item.exportedUri)
                    put("file_size", item.fileSizeBytes)
                    put("progress", item.progress)
                    put("status", item.status.name)
                    put("error_message", item.errorMessage)
                    put("created_at", item.createdAt)
                }
                db.insertWithOnConflict("downloads", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteDownload(uniqueKey: String) {
        writableDatabase.delete("downloads", "unique_key = ?", arrayOf(uniqueKey))
    }

    fun deleteDownloadsByStatus(status: DownloadStatus): Int =
        writableDatabase.delete("downloads", "status = ?", arrayOf(status.name))

    // ==================== 播放历史 CRUD ====================

    fun recordPlay(song: Song) {
        val existingCount = readableDatabase.rawQuery(
            "SELECT play_count FROM play_history WHERE unique_key = ?",
            arrayOf(song.uniqueKey)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

        val cv = ContentValues().apply {
            put("unique_key", song.uniqueKey)
            put("song_json", songToJson(song).toString())
            put("play_count", existingCount + 1)
            put("last_played_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("play_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getPlayHistory(limit: Int = 50): List<Pair<Song, Int>> = readableDatabase.rawQuery(
        "SELECT song_json, play_count FROM play_history ORDER BY last_played_at DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val song = songFromJson(c.getString(0)) ?: continue
                add(song to c.getInt(1))
            }
        }
    }

    // ==================== 声乐评测 CRUD ====================

    fun upsertSingingEvaluation(ev: SingingEvaluation) {
        val momentsArr = JSONArray().apply { ev.offPitchMoments.forEach { put(it) } }
        val cv = ContentValues().apply {
            put("id", ev.id)
            put("song_title", ev.songTitle)
            put("song_artist", ev.songArtist)
            put("song_id", ev.songId)
            put("audio_file_path", ev.audioFilePath)
            put("duration_ms", ev.durationMs)
            put("overall_score", ev.overallScore)
            put("pitch_accuracy_score", ev.pitchAccuracyScore)
            put("pitch_stability_score", ev.pitchStabilityScore)
            put("rhythm_score", ev.rhythmScore)
            put("breath_control_score", ev.breathControlScore)
            put("vocal_range_semitones", ev.vocalRangeSemitones)
            put("lowest_note", ev.lowestNote)
            put("highest_note", ev.highestNote)
            put("estimated_key", ev.estimatedKey)
            put("vibrato_detected", if (ev.vibratoDetected) 1 else 0)
            put("in_tune_ratio_percent", ev.inTuneRatioPercent)
            put("median_abs_cents_error", ev.medianAbsCentsError)
            put("off_pitch_moments_json", momentsArr.toString())
            put("ai_feedback", ev.aiFeedback)
            put("created_at", ev.createdAt)
        }
        writableDatabase.insertWithOnConflict("singing_evaluations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSingingEvaluations(limit: Int = 30): List<SingingEvaluation> = readableDatabase.rawQuery(
        "SELECT * FROM singing_evaluations ORDER BY created_at DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val moments = runCatching {
                    val arr = JSONArray(c.str("off_pitch_moments_json").ifBlank { "[]" })
                    (0 until arr.length()).map { arr.optString(it) }
                }.getOrDefault(emptyList())
                add(
                    SingingEvaluation(
                        id = c.str("id"),
                        songTitle = c.str("song_title"),
                        songArtist = c.str("song_artist"),
                        songId = c.str("song_id"),
                        audioFilePath = c.str("audio_file_path"),
                        durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
                        overallScore = c.getInt(c.getColumnIndexOrThrow("overall_score")),
                        pitchAccuracyScore = c.getInt(c.getColumnIndexOrThrow("pitch_accuracy_score")),
                        pitchStabilityScore = c.getInt(c.getColumnIndexOrThrow("pitch_stability_score")),
                        rhythmScore = c.getInt(c.getColumnIndexOrThrow("rhythm_score")),
                        breathControlScore = c.getInt(c.getColumnIndexOrThrow("breath_control_score")),
                        vocalRangeSemitones = c.getInt(c.getColumnIndexOrThrow("vocal_range_semitones")),
                        lowestNote = c.str("lowest_note"),
                        highestNote = c.str("highest_note"),
                        estimatedKey = c.str("estimated_key"),
                        vibratoDetected = c.getInt(c.getColumnIndexOrThrow("vibrato_detected")) == 1,
                        inTuneRatioPercent = c.getInt(c.getColumnIndexOrThrow("in_tune_ratio_percent")),
                        medianAbsCentsError = c.getFloat(c.getColumnIndexOrThrow("median_abs_cents_error")),
                        offPitchMoments = moments,
                        aiFeedback = c.str("ai_feedback"),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
    }

    fun deleteSingingEvaluation(id: String) {
        writableDatabase.delete("singing_evaluations", "id = ?", arrayOf(id))
    }

    // ==================== AI 对话记录 CRUD ====================

    fun insertChatMessage(msg: ChatMessage) {
        val logsArr = JSONArray().apply {
            msg.toolLogs.forEach { log ->
                put(
                    JSONObject().apply {
                        put("toolName", log.toolName)
                        put("displayTitle", log.displayTitle)
                        put("argumentsSummary", log.argumentsSummary)
                        put("resultSummary", log.resultSummary)
                        put("success", log.success)
                    }
                )
            }
        }
        val songsArr = JSONArray().apply {
            msg.recommendedSongs.forEach { put(songToJson(it)) }
        }
        val cv = ContentValues().apply {
            put("id", msg.id)
            put("role", msg.role)
            put("content", msg.content)
            put("engine_badge", msg.engineBadge)
            put("tool_logs_json", logsArr.toString())
            put("recommended_songs_json", songsArr.toString())
            put("timestamp", msg.timestamp)
        }
        writableDatabase.insertWithOnConflict("chat_messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getChatMessages(limit: Int = 80): List<ChatMessage> = readableDatabase.rawQuery(
        "SELECT * FROM (SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT ?) ORDER BY timestamp ASC",
        arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val logs = runCatching {
                    val arr = JSONArray(c.str("tool_logs_json").ifBlank { "[]" })
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        ToolExecutionLog(
                            toolName = o.optString("toolName"),
                            displayTitle = o.optString("displayTitle"),
                            argumentsSummary = o.optString("argumentsSummary"),
                            resultSummary = o.optString("resultSummary"),
                            success = o.optBoolean("success", true)
                        )
                    }
                }.getOrDefault(emptyList())
                val songs = runCatching {
                    val arr = JSONArray(c.str("recommended_songs_json").ifBlank { "[]" })
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { songFromJsonObject(it) }
                    }
                }.getOrDefault(emptyList())
                add(
                    ChatMessage(
                        id = c.str("id"),
                        role = c.str("role"),
                        content = c.str("content"),
                        engineBadge = c.str("engine_badge"),
                        toolLogs = logs,
                        recommendedSongs = songs,
                        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"))
                    )
                )
            }
        }
    }

    fun clearChatMessages() {
        writableDatabase.delete("chat_messages", null, null)
    }

    // ==================== JSON 序列化辅助 ====================

    private fun Cursor.str(col: String): String {
        val idx = getColumnIndex(col)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else ""
    }

    companion object {
        fun songToJson(song: Song): JSONObject = JSONObject().apply {
            put("id", song.id)
            put("title", song.title)
            put("artist", song.artist)
            put("album", song.album)
            put("channelId", song.channelId)
            put("sourceKey", song.sourceKey)
            put("rawTrackId", song.rawTrackId)
            put("picId", song.picId)
            put("lyricId", song.lyricId)
            put("mvId", song.mvId)
            put("coverUrl", song.coverUrl)
            put("durationMs", song.durationMs)
            put("localFilePath", song.localFilePath ?: "")
            put("actualBitrateKbps", song.actualBitrateKbps)
            put("qualityBadge", song.qualityBadge)
            put("addedAt", song.addedAt)
            put("language", song.language)
            put("genre", song.genre)
        }

        fun songFromJson(raw: String): Song? = runCatching {
            songFromJsonObject(JSONObject(raw))
        }.getOrNull()

        fun songFromJsonObject(o: JSONObject): Song = Song(
            id = o.optString("id"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            channelId = o.optString("channelId", "gd_netease"),
            sourceKey = o.optString("sourceKey", "netease"),
            rawTrackId = o.optString("rawTrackId", o.optString("id")),
            picId = o.optString("picId"),
            lyricId = o.optString("lyricId"),
            mvId = o.optString("mvId"),
            coverUrl = o.optString("coverUrl"),
            durationMs = o.optLong("durationMs", 0L),
            localFilePath = o.optString("localFilePath").takeIf { it.isNotBlank() },
            actualBitrateKbps = o.optInt("actualBitrateKbps", 0),
            qualityBadge = o.optString("qualityBadge"),
            addedAt = o.optLong("addedAt", System.currentTimeMillis()),
            language = o.optString("language"),
            genre = o.optString("genre")
        )
    }
}
