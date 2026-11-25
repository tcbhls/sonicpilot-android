package com.sonicpilot.music.ai

import com.sonicpilot.music.data.db.SonicDatabaseHelper
import com.sonicpilot.music.data.model.AiConfig
import com.sonicpilot.music.data.model.AiEngineMode
import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.ChatMessage
import com.sonicpilot.music.data.model.SingingEvaluation
import com.sonicpilot.music.data.model.Song
import com.sonicpilot.music.data.model.ToolExecutionLog
import com.sonicpilot.music.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音枢 AI 音乐大管家（多轮自主 Tool-Calling Agent）
 *
 * 三层智能路由策略：
 * 1. 云端高智商引擎（智谱 GLM-4-Flash 永久免费 / 硅基流动免费模型 / 用户自接付费 OpenAI 兼容 API）—— 原生 Function Calling 多轮执行
 * 2. 本地完全离线大模型（llama.cpp ARM64 + Qwen2.5/Qwen3 GGUF）—— 结构化 `<tool_call>` 多轮执行
 * 3. 零配置端侧即时意图引擎（无需下载 GGUF 也不填 Key 即可直接搜歌、播控、换音质、收藏、下载、评唱功！）
 */
class SonicAiAgent(
    private val db: SonicDatabaseHelper,
    private val settings: SettingsStore,
    val localLlm: LocalLlmEngine,
    val cloudLlm: CloudLlmClient,
    val toolRegistry: AppAgentToolRegistry
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _thinkingStage = MutableStateFlow("")
    val thinkingStage: StateFlow<String> = _thinkingStage.asStateFlow()

    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pendingNavTab = MutableStateFlow<String?>(null)
    val pendingNavTab: StateFlow<String?> = _pendingNavTab.asStateFlow()

    private val _pendingMvSong = MutableStateFlow<Song?>(null)
    val pendingMvSong: StateFlow<Song?> = _pendingMvSong.asStateFlow()

    private val _pendingPlayerMode = MutableStateFlow<String?>(null)
    val pendingPlayerMode: StateFlow<String?> = _pendingPlayerMode.asStateFlow()

    private val inFlightLock = AtomicBoolean(false)
    private val userCancelled = AtomicBoolean(false)
    @Volatile
    private var activeJob: Job? = null

    fun cancelCurrentRequest() {
        userCancelled.set(true)
        activeJob?.cancel()
        activeJob = null
        inFlightLock.set(false)
        _isThinking.value = false
        _thinkingStage.value = ""
    }

    fun consumePendingMvSong(): Song? {
        val song = _pendingMvSong.value
        _pendingMvSong.value = null
        return song
    }

    fun consumePendingPlayerMode(): String? {
        val mode = _pendingPlayerMode.value
        _pendingPlayerMode.value = null
        return mode
    }

    init {
        val saved = db.getChatMessages()
        if (saved.isEmpty()) {
            val welcome = ChatMessage(
                role = "assistant",
                content = "你好！我是**音枢 AI 音乐管家（默认搭载智谱 GLM-4.7 旗舰大脑）**，已获得本 App 内的全部音乐控制权限。\n\n" +
                    "你可以直接一句话指挥我：\n" +
                    "• 「推荐 4 首像《体面》那样好听的华语失恋治愈女声，直接开播」\n" +
                    "• 「把我收藏里的粤语歌随机播放 / 批量无损下载 / 打包成歌单」\n" +
                    "• 「把陈奕迅的《富士山下》加到下一首播放，半小时后自动停止」\n" +
                    "• 「放慢到 0.75 倍速并打开学歌室 / 切换成浅色模式 / 只显示英文歌词」\n" +
                    "• 「把刚才推荐的这几首歌全部收藏 / 看周杰伦《晴天》的 MV」\n" +
                    "• 「评一下我刚才在声乐棚唱得怎么样」",
                engineBadge = "音枢管家就绪"
            )
            db.insertChatMessage(welcome)
            _messages.value = listOf(welcome)
        } else {
            _messages.value = saved
        }
    }

    fun consumePendingNavTab(): String? {
        val tab = _pendingNavTab.value
        _pendingNavTab.value = null
        return tab
    }

    fun clearHistory() {
        db.clearChatMessages()
        _messages.value = emptyList()
    }

    /**
     * 供声乐棚一键触发（运行在应用级独立协程中，切页或界面重组绝不会中断！）
     */
    fun submitSingingCritique(evaluation: SingingEvaluation) {
        val prompt = "请调用 evaluate_singing(evaluation_id=\"${evaluation.id}\") 读取我刚刚唱的《${evaluation.songTitle}》声学评测数据，" +
            "从音准、音高稳定性、节奏、气息、音域与跑调时间点给我一份专业、诚恳又有针对性的声乐导师点评，并推荐 2 首适合我当前音域练习的歌曲！"
        submitUserMessage(prompt)
    }

    /**
     * 供 UI 调用的非挂起提交入口：运行在 App 级 agentScope，彻底根治跳转传参/切 Tab 导致 AI 请求被取消而沉默不回的 Bug！
     */
    fun submitUserMessage(userText: String) {
        val clean = userText.trim()
        if (clean.isBlank()) return
        if (!inFlightLock.compareAndSet(false, true)) return
        userCancelled.set(false)
        activeJob = agentScope.launch {
            executeMessagePipeline(clean)
        }
    }

    suspend fun requestSingingCritique(evaluation: SingingEvaluation) {
        submitSingingCritique(evaluation)
        activeJob?.join()
    }

    suspend fun sendUserMessage(userText: String) {
        submitUserMessage(userText)
        activeJob?.join()
    }

    private suspend fun executeMessagePipeline(clean: String) {
        val userMsg = ChatMessage(role = "user", content = clean)
        db.insertChatMessage(userMsg)
        _messages.value = _messages.value + userMsg
        _isThinking.value = true
        _thinkingStage.value = "正在理解你的需求..."

        try {
            val cfg = settings.aiConfig.value
            val reply = when (cfg.engineMode) {
                AiEngineMode.CLOUD_ONLY -> {
                    if (cfg.cloudApiKey.isNotBlank()) {
                        runCatching { withTimeout(48_000L) { runCloudAgentLoop(cfg, clean) } }.getOrElse { err ->
                            if (err is CancellationException && userCancelled.get()) throw err
                            runInstantOnDeviceFallback(clean, reason = "云端遇到异常（${err.message?.take(40) ?: "请求超时"}），已自动切端侧完成回复")
                        }
                    } else {
                        runInstantOnDeviceFallback(clean, reason = "尚未填写云端 API Key，已由端侧引擎立即执行")
                    }
                }
                AiEngineMode.LOCAL_ONLY -> {
                    if (localLlm.isReady() || cfg.selectedLocalModelPath.isNotBlank() || localLlm.listDownloadedGgufFiles().isNotEmpty()) {
                        runCatching { withTimeout(150_000L) { runLocalGgufAgentLoop(clean) } }.getOrElse { err ->
                            if (err is CancellationException && userCancelled.get()) throw err
                            runInstantOnDeviceFallback(clean, reason = "本地模型暂未就绪（${err.message?.take(48) ?: "未加载"}），已由内置端侧引擎执行")
                        }
                    } else {
                        runInstantOnDeviceFallback(clean, reason = "尚未下载本地 GGUF 模型，已由内置端侧引擎执行")
                    }
                }
                AiEngineMode.AUTO_CLOUD_THEN_LOCAL -> {
                    when {
                        cfg.cloudApiKey.isNotBlank() -> {
                            runCatching { withTimeout(48_000L) { runCloudAgentLoop(cfg, clean) } }.getOrElse { err ->
                                if (err is CancellationException && userCancelled.get()) throw err
                                if (localLlm.isReady() || localLlm.listDownloadedGgufFiles().isNotEmpty()) {
                                    runCatching { withTimeout(150_000L) { runLocalGgufAgentLoop(clean) } }.getOrElse {
                                        runInstantOnDeviceFallback(clean, reason = "云端与本地模型均超时，已由内置端侧引擎兜底执行")
                                    }
                                } else {
                                    runInstantOnDeviceFallback(clean, reason = "云端响应异常（${err.message?.take(36) ?: "网络波动"}），已无缝切端侧秒回")
                                }
                            }
                        }
                        localLlm.isReady() || cfg.selectedLocalModelPath.isNotBlank() || localLlm.listDownloadedGgufFiles().isNotEmpty() -> {
                            runCatching { withTimeout(150_000L) { runLocalGgufAgentLoop(clean) } }.getOrElse { err ->
                                if (err is CancellationException && userCancelled.get()) throw err
                                runInstantOnDeviceFallback(clean, reason = "本地模型响应异常（${err.message?.take(48) ?: "超时"}），已切内置端侧引擎回复")
                            }
                        }
                        else -> runInstantOnDeviceFallback(clean)
                    }
                }
            }
            val safeReply = if (reply.content.isBlank()) {
                reply.copy(content = "我已收到你的请求，但未能拿到有效内容。你可以换一种说法，或点击右上角设置检查 AI 与网络状态。")
            } else reply
            db.insertChatMessage(safeReply)
            _messages.value = _messages.value + safeReply
        } catch (ce: CancellationException) {
            val notice = ChatMessage(
                role = "assistant",
                content = if (userCancelled.get()) "已按你的操作停止本次生成。" else "本次请求因系统中断未能完成，请重试或换个指令。",
                engineBadge = "状态提示"
            )
            db.insertChatMessage(notice)
            _messages.value = _messages.value + notice
        } catch (e: Throwable) {
            val fallbackErr = ChatMessage(
                role = "assistant",
                content = "处理你的指令时遇到了一点状况：${e.message ?: "网络连接或解析失败"}。\n\n你可以直接重试，或去右上角「设置 → AI 引擎」测试连接 / 切换模式。",
                engineBadge = "异常反馈"
            )
            db.insertChatMessage(fallbackErr)
            _messages.value = _messages.value + fallbackErr
        } finally {
            activeJob = null
            userCancelled.set(false)
            inFlightLock.set(false)
            _isThinking.value = false
            _thinkingStage.value = ""
        }
    }

    // ==================== 1. 云端 OpenAI-Compatible 原生多轮 Tool Calling ====================

    private suspend fun runCloudAgentLoop(cfg: AiConfig, latestUserText: String): ChatMessage {
        val liveSnapshot = toolRegistry.buildLiveContextSnapshot()
        val systemPrompt = """
你是「音枢 AI 音乐」的专属全权音乐大管家兼专业声乐导师，拥有操作本 App 全部 25 项内部功能的沙箱工具权限。

$liveSnapshot

【铁律执行规范（必须严格遵守）】
1. **懂音乐、真策展，严禁干聊空列歌名**：
   - 当用户按情绪、风格、场景、相似歌曲（如“像《体面》那样”“类似周杰伦的中国风”“适合深夜写代码的 R&B”）、或一次点名多首歌连播时，**必须发挥你的音乐品位，直接调用 `curate_song_list(songs=[{title, artist, reason}, ...], ...)`**！
   - App 会并发把你策展的每首歌解析成真实可播放的高音质原唱卡片（自动过滤翻唱/DJ）。**绝对禁止只用纯文本报歌名而不调用 `curate_song_list`！**
   - 如果用户要找「没听过/没收藏过的新歌」，在 `curate_song_list` 或 `recommend_songs` 中设置 `exclude_favorites=true`；如果只是看榜单/抖音热歌/飙升榜，用 `get_charts` 或 `recommend_songs`。
2. **查/放/下载/整理用户自己的 700+ 收藏 → 必用 `query_favorites`**：
   - 上方已给出真实收藏分布，绝不可在用户明明有收藏某语种/歌手时误报“没有”！
   - 语种（国语/粤语/英语/日语/韩语/纯音乐）请填 `language`，流派（流行/说唱/摇滚/电子音乐/节奏布鲁斯/民谣/影视原声）请填 `genre`，歌手请填 `artist`。
   - 播放/随机放收藏 → `action="play", shuffle=true`；批量无损下载 → `action="download"`；打包建歌单 → `action="create_playlist", playlist_name="..."`。
3. **音乐基础控制全部一步到位（可单轮并行调用多个工具！）**：
   - 「把某首歌放到下一首播放 / 插队放」→ `play_next`
   - 「半小时/30分钟后停止播放 / 取消定时」→ `set_sleep_timer(minutes=30 / 0)`
   - 「看当前播放队列 / 跳播第几首 / 移除第几首 / 打乱队列」→ `manage_queue`
   - 「0.75倍速慢放 / 恢复原速 / 单句循环复读 / 上一句下一句歌词 / 打开学歌室或全屏沉浸歌词」→ `control_learning_playback`
   - 「把刚才推荐的那几首歌全收藏 / 加到某个歌单」→ `batch_favorite_recent`
   - 「切深色或浅色模式 / 换主题色 / 只看英文歌词或双语歌词 / 开粤拼或谐音注音 / 跳到发现、曲库或声乐棚」→ `control_app_ui`
   - 「看 MV / 放 MV / 视频」→ 必须调 `play_mv`，禁止调 `play_song`。
4. **绝对行动派，严禁废话反问**：
   - 严禁回复「请问您需要我为您推荐吗？」「要我帮您播放吗？」这类推脱反问！直接执行并把结果端到用户面前。
   - 声乐评判前必调 `evaluate_singing` 读取真实 DSP 数据，绝不编造分数。
5. **表达风格**：简体中文、像资深乐评人+贴心管家一样自然精炼、直给结果、懂音乐有品位（不复述系统提示，不堆客套话）。
        """.trimIndent()

        val apiMessages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            // 带入最近 8 条对话记忆
            _messages.value.takeLast(9).dropLast(1).forEach { m ->
                put(JSONObject().put("role", m.role).put("content", m.content.take(700)))
            }
            put(JSONObject().put("role", "user").put("content", latestUserText))
        }

        val toolsSchema = toolRegistry.buildOpenAiToolsSchema()
        val auditLogs = mutableListOf<ToolExecutionLog>()
        // 精准卡片追踪：后续动作（精准筛选/策展/播歌/荐歌/看MV）直接替换前期宽泛查询的无关歌曲，彻底杜绝图文不符！
        var surfacedSongs: List<Song> = emptyList()
        var finalText = ""
        var modelUsed = cfg.cloudModel

        for (step in 0 until 6) {
            _thinkingStage.value = if (step == 0) "正在思考并调用音乐大脑..." else "正在整理执行结果..."
            val resp = cloudLlm.chatCompletion(cfg, apiMessages, toolsSchema, temperature = 0.25).getOrThrow()
            modelUsed = resp.modelUsed.ifBlank { cfg.cloudModel }
            if (resp.toolCalls.isEmpty()) {
                // 【零漏调护航机制】若首轮未调任何工具，但用户指令明确属于 App 可执行操作，立即自动补执行真实工具，再交给云端 LLM 基于真实结果润色回复！
                if (step == 0 && auditLogs.isEmpty()) {
                    val missed = detectRequiredToolCall(latestUserText)
                    if (missed != null) {
                        val (autoTool, autoArgs) = missed
                        _thinkingStage.value = "正在执行：${friendlyToolLabel(autoTool)}..."
                        val outcome = toolRegistry.execute(autoTool, autoArgs)
                        auditLogs += outcome.auditLog
                        if (outcome.surfacedSongs.isNotEmpty()) {
                            surfacedSongs = outcome.surfacedSongs
                        } else if (autoTool == "query_favorites" || autoTool == "list_favorites") {
                            surfacedSongs = emptyList()
                        }
                        outcome.navigateToTab?.let { _pendingNavTab.value = it }
                        outcome.openMvSong?.let { _pendingMvSong.value = it }
                        outcome.openPlayerMode?.let { _pendingPlayerMode.value = it }

                        // 若查收藏命中 0 首且用户想听/推荐，再自动联网补一轮推荐，一步到位！
                        val extraText = if ((autoTool == "query_favorites" || autoTool == "list_favorites") &&
                            outcome.surfacedSongs.isEmpty() &&
                            (latestUserText.contains("推荐") || latestUserText.contains("放") || latestUserText.contains("听"))
                        ) {
                            val recArgs = JSONObject()
                                .put("mood_or_scene", latestUserText)
                                .put("count", 6)
                                .put("auto_play", latestUserText.contains("播放") || latestUserText.contains("放"))
                                .put("exclude_favorites", true)
                                .toString()
                            val recOutcome = toolRegistry.execute("recommend_songs", recArgs)
                            auditLogs += recOutcome.auditLog
                            if (recOutcome.surfacedSongs.isNotEmpty()) surfacedSongs = recOutcome.surfacedSongs
                            "\n\n已同时为你联网补充推荐：\n${recOutcome.toolOutputForLlm}"
                        } else ""

                        apiMessages.put(
                            JSONObject().put("role", "user").put(
                                "content",
                                "【系统已为你真实执行 App 工具 $autoTool，真实结果如下】\n${outcome.toolOutputForLlm}$extraText\n\n" +
                                    "请直接基于以上真实执行结果，用自然、懂音乐、简洁的简体中文回复用户（严禁说“请稍等”或“无法提供视频”，也不要编造列表之外的歌曲）。"
                            )
                        )
                        val polished = runCatching {
                            cloudLlm.chatCompletion(cfg, apiMessages, toolsSchema = null, temperature = 0.45).getOrNull()
                        }.getOrNull()
                        finalText = polished?.content?.takeIf { it.isNotBlank() } ?: (outcome.toolOutputForLlm + extraText)
                        break
                    }
                }
                finalText = resp.content
                break
            }
            val callsJson = JSONArray().apply {
                resp.toolCalls.forEach { tc ->
                    put(
                        JSONObject()
                            .put("id", tc.id)
                            .put("type", "function")
                            .put("function", JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson))
                    )
                }
            }
            apiMessages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", resp.content)
                    .put("tool_calls", callsJson)
            )
            val userExplicitlyAskedMv = latestUserText.contains("mv", ignoreCase = true) ||
                latestUserText.contains("音乐视频") || latestUserText.contains("录影带")
            for (tc in resp.toolCalls) {
                val effectiveToolName = if (userExplicitlyAskedMv && tc.name == "play_song") "play_mv" else tc.name
                // 【参数防呆补全】防止模型调 list_favorites({}) 漏填用户明确提到的语种/流派/歌手/动作，根治误判！
                val effectiveArgs = enrichToolArgsFromUserIntent(effectiveToolName, tc.argumentsJson, latestUserText)
                _thinkingStage.value = "正在执行：${friendlyToolLabel(effectiveToolName)}..."
                val outcome = toolRegistry.execute(effectiveToolName, effectiveArgs)
                auditLogs += outcome.auditLog
                if (outcome.surfacedSongs.isNotEmpty()) {
                    surfacedSongs = outcome.surfacedSongs
                } else if (effectiveToolName == "query_favorites" || effectiveToolName == "list_favorites") {
                    surfacedSongs = emptyList()
                }
                outcome.navigateToTab?.let { _pendingNavTab.value = it }
                outcome.openMvSong?.let { _pendingMvSong.value = it }
                outcome.openPlayerMode?.let { _pendingPlayerMode.value = it }
                apiMessages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", tc.id)
                        .put("name", tc.name)
                        .put("content", outcome.toolOutputForLlm)
                )
            }
        }

        if (finalText.isBlank()) {
            finalText = if (auditLogs.isNotEmpty()) {
                "已为你完成操作：\n" + auditLogs.joinToString("\n") { "• ${it.displayTitle}：${it.resultSummary}" }
            } else {
                "已完成处理。"
            }
        }
        return ChatMessage(
            role = "assistant",
            content = finalText,
            engineBadge = "云端 · $modelUsed",
            toolLogs = auditLogs,
            recommendedSongs = surfacedSongs.distinctBy { it.uniqueKey }.take(8)
        )
    }

    private fun friendlyToolLabel(name: String): String = when (name) {
        "curate_song_list" -> "AI 策展原唱歌单"
        "query_favorites", "list_favorites" -> "检索收藏曲库"
        "search_songs" -> "原唱优先搜歌"
        "search_by_lyrics_or_web" -> "联网歌词寻曲"
        "play_song" -> "解析高音质并播放"
        "play_next" -> "加入下一首播放"
        "play_mv" -> "匹配高清 MV"
        "set_sleep_timer" -> "设定睡眠定时"
        "manage_queue" -> "管理播放队列"
        "control_learning_playback" -> "学歌与倍速控制"
        "batch_favorite_recent" -> "批量收藏推荐歌曲"
        "control_app_ui" -> "调整界面与歌词设置"
        "recommend_songs" -> "智能挑选新歌"
        "manage_playlist" -> "管理歌单"
        "download_song" -> "加入无损下载"
        "manage_download_queue" -> "控制下载队列"
        "evaluate_singing" -> "分析声乐评测报告"
        "get_lyrics" -> "读取双语歌词"
        else -> name
    }

    /**
     * 当模型调用 query_favorites / list_favorites 时漏传了用户明确说出的语种/流派/歌手/动作，自动补齐参数，根治只扫前几首的误判！
     */
    private fun enrichToolArgsFromUserIntent(toolName: String, rawArgsJson: String, userText: String): String {
        if (toolName != "query_favorites" && toolName != "list_favorites") return rawArgsJson
        val obj = runCatching { JSONObject(rawArgsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val hints = extractFavoritesQueryHints(userText, toolRegistry.getKnownFavoriteArtists())
        if (obj.optString("language").isBlank() && hints.language.isNotBlank()) obj.put("language", hints.language)
        if (obj.optString("genre").isBlank() && hints.genre.isNotBlank()) obj.put("genre", hints.genre)
        if (obj.optString("artist").isBlank() && hints.artist.isNotBlank()) obj.put("artist", hints.artist)
        if (!obj.has("shuffle") && hints.shuffle) obj.put("shuffle", true)
        if (obj.optString("action").isBlank() || obj.optString("action") == "list") {
            if (hints.action != "list") obj.put("action", hints.action)
        }
        if (obj.optString("playlist_name").isBlank() && hints.playlistName.isNotBlank()) {
            obj.put("playlist_name", hints.playlistName)
        }
        return obj.toString()
    }

    /**
     * 零漏调护航意图检测：仅当用户明确要求执行 App 内动作而免费模型漏调工具时触发，纯闲聊/乐理问答返回 null 放行自由对话
     */
    private fun detectRequiredToolCall(userText: String): Pair<String, String>? {
        val knownArtists = toolRegistry.getKnownFavoriteArtists()
        return detectActionableToolIntent(userText, knownArtists)
    }

    // ==================== 2. 本地 llama.cpp GGUF 离线多轮 Agent（专为 4B/8B 手机端侧提速优化） ====================

    private fun isMetaAiModelQuestion(userText: String): Boolean {
        val l = userText.lowercase()
        val mentionsModel = listOf("模型", "ai", "本地", "qwen", "glm", "llama", "8b", "4b", "1.7b", "gguf", "离线").any { l.contains(it) }
        val asksIdentity = listOf("用的是", "用的什么", "哪个", "是不是", "刚刚下载", "下载的", "当前", "正在用", "是谁", "什么版本", "状态").any { l.contains(it) }
        return mentionsModel && asksIdentity
    }

    private suspend fun runLocalGgufAgentLoop(userText: String): ChatMessage {
        val loadedName = localLlm.status.value.loadedModelName
            .ifBlank { settings.aiConfig.value.selectedLocalModelName }
            .ifBlank { localLlm.listDownloadedGgufFiles().maxByOrNull { it.length() }?.name ?: "本地 GGUF 模型" }
        val loadedPath = localLlm.status.value.loadedModelPath
            .ifBlank { settings.aiConfig.value.selectedLocalModelPath }
        val sharedTag = if (loadedPath.contains("Download/LocalAI", ignoreCase = true)) "共享目录 Download/LocalAI" else "本机端侧"
        val playingSong = toolRegistry.execute("get_playback_state", "{}").toolOutputForLlm.take(120)

        val auditLogs = mutableListOf<ToolExecutionLog>()
        val surfacedSongs = mutableListOf<Song>()

        // 1) 若命中明确的 App 执行指令（排除“你现在用的是我下载的AI模型吗”这类元问答），先秒速执行工具，再让 8B 模型基于极短结果润色，省掉一整轮 prefill！
        val isMetaQ = isMetaAiModelQuestion(userText)
        val preDetected = if (!isMetaQ) detectRequiredToolCall(userText) else null
        if (preDetected != null) {
            val (toolName, toolArgs) = preDetected
            _thinkingStage.value = "正在执行：${friendlyToolLabel(toolName)}..."
            val outcome = toolRegistry.execute(toolName, toolArgs)
            auditLogs += outcome.auditLog
            surfacedSongs += outcome.surfacedSongs
            outcome.navigateToTab?.let { _pendingNavTab.value = it }
            outcome.openMvSong?.let { _pendingMvSong.value = it }
            outcome.openPlayerMode?.let { _pendingPlayerMode.value = it }

            _thinkingStage.value = "本地 $loadedName 总结回复中..."
            val shortSys = "你是「音枢」的本地离线音乐管家（当前运行模型：$loadedName，位于$sharedTag）。请用 2~3 句自然简洁的简体中文基于工具结果直接回复，不要客套。/no_think"
            val shortUser = "用户指令：$userText\n已执行结果：${outcome.toolOutputForLlm.take(500)}\n请简短回复："
            val out = localLlm.generate(shortSys, shortUser, maxTokens = 220).getOrThrow()
            val finalText = out.ifBlank { outcome.toolOutputForLlm }
            val tps = localLlm.status.value.lastTokensPerSec
            val badge = if (tps > 0f) "本地离线 · $loadedName (${String.format("%.1f", tps)} tok/s)" else "本地离线 · $loadedName"
            return ChatMessage(
                role = "assistant",
                content = finalText,
                engineBadge = badge,
                toolLogs = auditLogs,
                recommendedSongs = surfacedSongs.distinctBy { it.uniqueKey }.take(8)
            )
        }

        // 2) 极简高密度端侧系统提示（约 130~200 tokens，相比原 1200 tokens 提速 5 倍，8B 在骁龙 8 Gen 3 上数秒出字！）
        val sysPrompt = if (isMetaQ) {
            "你是「音枢 AI 音乐」的本地离线 AI 音乐管家。当前真实运行在你手机上的模型是：$loadedName（来源：$sharedTag，原生 ARM64 llama.cpp 离线运行）。请直接、诚实、简洁地用简体中文回答用户。/no_think"
        } else {
            """
你是「音枢 AI 音乐」的本地离线 AI 管家（运行模型：$loadedName）。当前状态：$playingSong
纯聊天/问答请直接用简洁简体中文回答；仅当用户明确要求操控 App 时才输出一行：
<tool_call>{"name":"工具名","arguments":{...}}</tool_call>
常用工具：play_song(song_id_or_query)、search_songs(query)、curate_song_list(songs:[{title,artist,reason}],auto_play)、query_favorites(language?,genre?,artist?,action:"play"|"list"|"download",shuffle?)、play_mv(song_id_or_query?)、control_playback(action)、download_song(song_id_or_query?)、set_sleep_timer(minutes)、evaluate_singing()。/no_think
            """.trimIndent()
        }

        var workingPrompt = userText
        var finalText = ""

        for (step in 0 until 2) {
            _thinkingStage.value = if (step == 0) "本地 $loadedName 推理中..." else "本地 $loadedName 整理结果..."
            val out = localLlm.generate(sysPrompt, workingPrompt, maxTokens = 280).getOrThrow()
            val parsedCalls = if (isMetaQ) emptyList() else parseLocalToolCalls(out)
            if (parsedCalls.isEmpty()) {
                finalText = out
                break
            }
            val toolResultsBuilder = StringBuilder()
            for ((name, argsJson) in parsedCalls) {
                _thinkingStage.value = "正在执行：${friendlyToolLabel(name)}..."
                val outcome = toolRegistry.execute(name, argsJson)
                auditLogs += outcome.auditLog
                surfacedSongs += outcome.surfacedSongs
                outcome.navigateToTab?.let { _pendingNavTab.value = it }
                outcome.openMvSong?.let { _pendingMvSong.value = it }
                outcome.openPlayerMode?.let { _pendingPlayerMode.value = it }
                toolResultsBuilder.appendLine("$name 结果：${outcome.toolOutputForLlm.take(360)}")
            }
            workingPrompt = "用户指令：$userText\n工具结果：$toolResultsBuilder\n请直接用简洁简体中文回复，不要再输出 <tool_call>。"
        }

        if (finalText.isBlank()) {
            finalText = "本地 AI 已完成处理。"
        }
        val modelName = localLlm.status.value.loadedModelName.ifBlank { loadedName }
        val tps = localLlm.status.value.lastTokensPerSec
        val badge = if (tps > 0f) "本地离线 · $modelName (${String.format("%.1f", tps)} tok/s)" else "本地离线 · $modelName"
        return ChatMessage(
            role = "assistant",
            content = finalText,
            engineBadge = badge,
            toolLogs = auditLogs,
            recommendedSongs = surfacedSongs.distinctBy { it.uniqueKey }.take(8)
        )
    }

    // ==================== 3. 零配置端侧即时意图引擎（开箱即用兜底） ====================

    private suspend fun runInstantOnDeviceFallback(userText: String, reason: String? = null): ChatMessage =
        withContext(Dispatchers.IO) {
            val lower = userText.lowercase()
            val logs = mutableListOf<ToolExecutionLog>()
            val songs = mutableListOf<Song>()
            val reply = StringBuilder()

            val directIntent = detectActionableToolIntent(userText, toolRegistry.getKnownFavoriteArtists())
            if (directIntent != null && directIntent.first in setOf(
                    "set_sleep_timer", "play_next", "batch_favorite_recent",
                    "control_learning_playback", "control_app_ui", "manage_queue"
                )
            ) {
                val o = toolRegistry.execute(directIntent.first, directIntent.second)
                logs += o.auditLog; songs += o.surfacedSongs
                o.navigateToTab?.let { _pendingNavTab.value = it }
                o.openMvSong?.let { _pendingMvSong.value = it }
                o.openPlayerMode?.let { _pendingPlayerMode.value = it }
                reply.append(o.toolOutputForLlm)
            } else when {
                // 0. 询问当前运行的 AI 模型/引擎状态（防止包含“下载的模型”被误触发成下载歌曲！）
                isMetaAiModelQuestion(userText) -> {
                    val st = localLlm.status.value
                    val cfg = settings.aiConfig.value
                    reply.appendLine("当前 AI 引擎状态：")
                    reply.appendLine("• **运行模式**：${cfg.engineMode.label}")
                    if (st.isModelLoaded) {
                        reply.appendLine("• **本地已载入模型**：`${st.loadedModelName}`（路径：`${st.loadedModelPath}`）")
                        if (st.lastTokensPerSec > 0f) {
                            reply.appendLine("• **最近端侧速度**：${String.format("%.1f", st.lastTokensPerSec)} tokens/s")
                        }
                    } else {
                        reply.appendLine("• **本地模型**：尚未载入内存（可在右上角设置 → AI 引擎点击加载）")
                    }
                    reply.append("• **云端配置**：${if (cfg.cloudApiKey.isNotBlank()) cfg.cloudModel else "未配置 Key"}")
                }

                // 1. 评唱功 / 声乐点评
                listOf("唱的", "唱得", "声乐", "音准", "练唱", "评测", "打分").any { lower.contains(it) } -> {
                    val evIdMatch = Regex("""evaluation_id="([^"]+)"""").find(userText)?.groupValues?.get(1)
                    val args = JSONObject().apply {
                        if (!evIdMatch.isNullOrBlank()) put("evaluation_id", evIdMatch)
                    }
                    val outcome = toolRegistry.execute("evaluate_singing", args.toString())
                    logs += outcome.auditLog
                    outcome.navigateToTab?.let { _pendingNavTab.value = it }
                    reply.appendLine(outcome.toolOutputForLlm)
                    if (!outcome.toolOutputForLlm.contains("还没有")) {
                        reply.appendLine("\n🎤 **端侧声乐导师快速建议**：")
                        reply.appendLine("• 优先对照上方标注的跑调时间点，放慢速度做 3 遍哼鸣（闭口 Hum）找准中心音高；")
                        reply.appendLine("• 气息发声保持腹部微撑，尾音收稳不掉音；")
                        reply.appendLine("• 在右上角「设置 → AI 引擎」配置云端 API Key 或下载 Qwen3 本地离线模型，即可解锁逐句深度声乐乐评！")
                    }
                }

                // 1.5 看 MV
                lower.contains("mv") || (lower.contains("视频") && (lower.contains("看") || lower.contains("放") || lower.contains("播"))) -> {
                    val kw = extractKeyword(userText, listOf("帮我放", "帮我看", "播放", "打开", "看这首歌的", "看当前", "看", "这首歌的", "的mv", "mv", "视频"))
                    val args = JSONObject().apply { if (kw.isNotBlank()) put("song_id_or_query", kw) }
                    val o = toolRegistry.execute("play_mv", args.toString())
                    logs += o.auditLog; songs += o.surfacedSongs
                    o.openMvSong?.let { _pendingMvSong.value = it }
                    reply.append(o.toolOutputForLlm)
                }

                // 2. 暂停 / 继续 / 切歌 / 播放模式
                lower.contains("暂停") -> {
                    val o = toolRegistry.execute("control_playback", """{"action":"pause"}""")
                    logs += o.auditLog; reply.append(o.toolOutputForLlm)
                }
                lower.contains("下一首") || lower.contains("切歌") -> {
                    val o = toolRegistry.execute("control_playback", """{"action":"next"}""")
                    logs += o.auditLog; reply.append(o.toolOutputForLlm)
                }
                lower.contains("上一首") -> {
                    val o = toolRegistry.execute("control_playback", """{"action":"previous"}""")
                    logs += o.auditLog; reply.append(o.toolOutputForLlm)
                }
                lower.contains("单曲循环") -> {
                    val o = toolRegistry.execute("control_playback", """{"action":"mode_single"}""")
                    logs += o.auditLog; reply.append(o.toolOutputForLlm)
                }
                lower.contains("随机播放") -> {
                    val o = toolRegistry.execute("control_playback", """{"action":"mode_shuffle"}""")
                    logs += o.auditLog; reply.append(o.toolOutputForLlm)
                }

                // 2.5 推荐歌曲（优先级高于“音质”关键词，避免“推荐高音质好歌”误触发切音质）
                lower.contains("推荐") || lower.contains("猜你喜欢") || lower.contains("来点") || lower.contains("听什么") -> {
                    val autoPlay = lower.contains("播放") || lower.contains("放")
                    val o = toolRegistry.execute(
                        "recommend_songs",
                        JSONObject().put("mood_or_scene", userText).put("count", 6).put("auto_play", autoPlay).toString()
                    )
                    logs += o.auditLog; songs += o.surfacedSongs; reply.append(o.toolOutputForLlm)
                }

                // 3. 切换音质（仅当用户明确要求设置/切换/改音质时触发）
                (lower.contains("切换") || lower.contains("设为") || lower.contains("改") || lower.contains("调成") || lower.contains("开")) &&
                    (lower.contains("无损") || lower.contains("hi-res") || lower.contains("hires") || lower.contains("flac") || lower.contains("音质")) -> {
                    val q = when {
                        lower.contains("128") || lower.contains("标准") -> AudioQuality.STANDARD.code
                        lower.contains("320") -> AudioQuality.HIGH.code
                        lower.contains("740") -> AudioQuality.LOSSLESS.code
                        else -> AudioQuality.HIRES.code
                    }
                    if (lower.contains("下载")) {
                        val o = toolRegistry.execute("download_song", JSONObject().put("quality", q).toString())
                        logs += o.auditLog; reply.append(o.toolOutputForLlm)
                    } else {
                        val o = toolRegistry.execute("set_audio_quality", JSONObject().put("quality", q).toString())
                        logs += o.auditLog; reply.append(o.toolOutputForLlm)
                    }
                }

                // 4.5 大曲库收藏智能路由（支持「放收藏里的粤语歌」「下载收藏里的说唱」「把收藏里的周杰伦做成歌单」等）
                (lower.contains("收藏") || lower.contains("我喜欢")) && (
                    listOf("粤语", "广东", "国语", "英语", "英文", "日语", "韩语", "纯音乐", "说唱", "摇滚", "电子", "民谣", "布鲁斯", "原声", "全部", "所有").any { lower.contains(it) } ||
                        lower.contains("查看") || lower.contains("列表") || lower.contains("有哪些") || lower.contains("多少首") ||
                        lower.contains("歌单") || lower.contains("下载") || lower.contains("播放") || lower.contains("放")
                    ) -> {
                    val lang = when {
                        lower.contains("粤语") || lower.contains("广东") || lower.contains("港乐") -> "粤语"
                        lower.contains("英语") || lower.contains("英文") || lower.contains("欧美") -> "英语"
                        lower.contains("国语") || lower.contains("中文") -> "国语"
                        lower.contains("日语") || lower.contains("日文") -> "日语"
                        lower.contains("韩语") -> "韩语"
                        lower.contains("纯音乐") -> "纯音乐"
                        else -> ""
                    }
                    val genre = when {
                        lower.contains("说唱") || lower.contains("rap") || lower.contains("嘻哈") -> "说唱"
                        lower.contains("摇滚") -> "摇滚"
                        lower.contains("电子") || lower.contains("电音") -> "电子音乐"
                        lower.contains("民谣") -> "民谣"
                        lower.contains("布鲁斯") || lower.contains("r&b") -> "节奏布鲁斯"
                        lower.contains("原声") || lower.contains("ost") -> "影视原声"
                        else -> ""
                    }
                    val action = when {
                        lower.contains("下载") -> "download"
                        lower.contains("歌单") && (lower.contains("建") || lower.contains("打包") || lower.contains("整理")) -> "create_playlist"
                        lower.contains("播放") || lower.contains("放") || lower.contains("听") -> "play"
                        else -> "list"
                    }
                    val args = JSONObject().apply {
                        if (lang.isNotBlank()) put("language", lang)
                        if (genre.isNotBlank()) put("genre", genre)
                        put("action", action)
                        put("shuffle", lower.contains("随机"))
                    }
                    val o = toolRegistry.execute("query_favorites", args.toString())
                    logs += o.auditLog; songs += o.surfacedSongs; reply.append(o.toolOutputForLlm)
                }

                // 4. 下载歌曲 / 下载队列控制
                lower.contains("下载") && (lower.contains("暂停") || lower.contains("继续") || lower.contains("恢复") || lower.contains("重试") || lower.contains("清空")) -> {
                    val act = when {
                        lower.contains("暂停") -> "pause"
                        lower.contains("重试") -> "retry_failed"
                        lower.contains("清空") -> "cancel_queued"
                        else -> "resume"
                    }
                    val o = toolRegistry.execute("manage_download_queue", JSONObject().put("action", act).toString())
                    logs += o.auditLog; reply.append(o.toolOutputForLlm)
                }
                lower.contains("下载") && !listOf("模型", "ai", "gguf", "qwen", "本地").any { lower.contains(it) } -> {
                    val kw = extractKeyword(userText, listOf("帮我下载", "下载当前", "下载这首", "下载歌曲", "下载"))
                    val args = JSONObject().apply {
                        if (kw.isNotBlank() && !kw.contains("当前") && !kw.contains("这首")) put("song_id_or_query", kw)
                    }
                    val o = toolRegistry.execute("download_song", args.toString())
                    logs += o.auditLog; reply.append(o.toolOutputForLlm)
                }

                // 5. 收藏单曲
                lower.contains("收藏") -> {
                    val fav = !lower.contains("取消")
                    val kw = extractKeyword(userText, listOf("取消收藏", "帮我收藏", "收藏这首", "收藏当前", "收藏"))
                    val args = JSONObject().put("favorite", fav).apply {
                        if (kw.isNotBlank() && !kw.contains("当前") && !kw.contains("这首")) put("song_id_or_query", kw)
                    }
                    val o = toolRegistry.execute("toggle_favorite", args.toString())
                    logs += o.auditLog; reply.append(o.toolOutputForLlm)
                }

                // 6. 榜单
                lower.contains("榜") || lower.contains("billboard") || lower.contains("热门") -> {
                    val chart = when {
                        lower.contains("billboard") || lower.contains("欧美") || lower.contains("全球") -> "billboard"
                        lower.contains("飙升") -> "soaring"
                        lower.contains("新歌") -> "new_songs"
                        lower.contains("日") || lower.contains("oricon") -> "jp_oricon"
                        else -> "hot_cn"
                    }
                    val o = toolRegistry.execute("get_charts", JSONObject().put("chart", chart).put("limit", 8).toString())
                    logs += o.auditLog; songs += o.surfacedSongs; reply.append(o.toolOutputForLlm)
                }

                // 7. 渠道管理
                lower.contains("渠道") || lower.contains("音源") -> {
                    val o = toolRegistry.execute("list_channels", "{}")
                    logs += o.auditLog; reply.append(o.toolOutputForLlm)
                }

                // 9. 歌词找歌
                lower.contains("歌词") && (lower.contains("找") || lower.contains("搜") || lower.contains("什么歌")) -> {
                    val kw = extractKeyword(userText, listOf("歌词里有", "歌词是", "帮我找", "找一下", "歌词", "什么歌"))
                    val o = toolRegistry.execute("search_by_lyrics_or_web", JSONObject().put("query", kw.ifBlank { userText }).toString())
                    logs += o.auditLog; songs += o.surfacedSongs; reply.append(o.toolOutputForLlm)
                }

                // 10. 播放或搜歌（默认智能意图）
                lower.contains("播放") || lower.startsWith("放") || lower.contains("听") -> {
                    val kw = extractKeyword(userText, listOf("帮我搜并播放", "帮我播放", "给我放一首", "放一首", "播放", "我想听", "听一下", "听"))
                    val o = toolRegistry.execute("play_song", JSONObject().put("song_id_or_query", kw.ifBlank { userText }).toString())
                    logs += o.auditLog; songs += o.surfacedSongs; reply.append(o.toolOutputForLlm)
                }
                else -> {
                    val kw = extractKeyword(userText, listOf("帮我搜一下", "帮我搜", "搜索歌曲", "搜一下", "搜索", "搜", "找歌"))
                    val o = toolRegistry.execute("search_songs", JSONObject().put("query", kw.ifBlank { userText }).put("limit", 8).toString())
                    logs += o.auditLog; songs += o.surfacedSongs
                    reply.append("已帮你全渠道检索「${kw.ifBlank { userText }}」：\n${o.toolOutputForLlm}")
                }
            }

            if (!reason.isNullOrBlank()) {
                reply.append("\n\n> ℹ️ $reason")
            }

            ChatMessage(
                role = "assistant",
                content = reply.toString().trim(),
                engineBadge = "端侧即时引擎（零配置可用）",
                toolLogs = logs,
                recommendedSongs = songs.distinctBy { it.uniqueKey }.take(6)
            )
        }

    private fun extractKeyword(raw: String, prefixes: List<String>): String {
        var text = raw.trim()
        prefixes.forEach { p -> text = text.replace(p, " ") }
        return text.replace(Regex("""[《》“”"'，。！？,.!?]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    companion object {
        private val localToolRegex = Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)

        data class FavQueryHints(
            val language: String = "",
            val genre: String = "",
            val artist: String = "",
            val action: String = "list",
            val shuffle: Boolean = false,
            val playlistName: String = ""
        )

        fun extractFavoritesQueryHints(userText: String, knownArtists: Collection<String> = emptyList()): FavQueryHints {
            val lower = userText.lowercase()
            val lang = when {
                lower.contains("粤语") || lower.contains("广东歌") || lower.contains("港乐") -> "粤语"
                lower.contains("英语") || lower.contains("英文") || lower.contains("欧美") -> "英语"
                lower.contains("国语") || lower.contains("普通话") -> "国语"
                lower.contains("日语") || lower.contains("日文") -> "日语"
                lower.contains("韩语") || lower.contains("韩文") -> "韩语"
                lower.contains("闽南") -> "闽南语"
                lower.contains("纯音乐") -> "纯音乐"
                else -> ""
            }
            val genre = when {
                lower.contains("说唱") || lower.contains("rap") || lower.contains("嘻哈") -> "说唱"
                lower.contains("电子") || lower.contains("电音") || lower.contains("edm") -> "电子音乐"
                lower.contains("摇滚") || lower.contains("rock") -> "摇滚"
                lower.contains("民谣") -> "民谣"
                lower.contains("节奏布鲁斯") || lower.contains("r&b") || lower.contains("rnb") -> "节奏布鲁斯"
                lower.contains("影视原声") || lower.contains("电影插曲") || lower.contains("ost") -> "影视原声"
                lower.contains("乡村") -> "乡村乐"
                lower.contains("国风") || lower.contains("古风") -> "国风"
                lower.contains("流行") -> "流行"
                else -> ""
            }
            // 从用户真实收藏歌手名单中精确匹配被提到的歌手（优先匹配名字更长的，如“邓紫棋”/“陈奕迅”/“周杰伦”）
            val matchedArtist = knownArtists
                .filter { it.length >= 2 }
                .sortedByDescending { it.length }
                .firstOrNull { lower.contains(it.lowercase()) }
                .orEmpty()
            val action = when {
                lower.contains("下载") -> "download"
                lower.contains("歌单") && (lower.contains("建") || lower.contains("打包") || lower.contains("整理") || lower.contains("存成")) -> "create_playlist"
                lower.contains("播放") || lower.contains("放一首") || lower.contains("随机放") || lower.contains("给我放") || lower.contains("听收藏") || lower.contains("放收藏") -> "play"
                else -> "list"
            }
            val plName = Regex("""[「《“"]([^」》”"]{1,20})[」》”"]""").find(userText)?.groupValues?.get(1).orEmpty()
            return FavQueryHints(
                language = lang,
                genre = genre,
                artist = matchedArtist,
                action = action,
                shuffle = lower.contains("随机"),
                playlistName = plName
            )
        }

        /**
         * 纯函数版意图护航检测（支持单元测试验证，零误触发纯聊天）：
         * 当免费云端模型在首轮漏调工具时，精准补齐真正应该执行的工具名与参数。
         */
        fun detectActionableToolIntent(userText: String, knownArtists: Collection<String> = emptyList()): Pair<String, String>? {
            val lower = userText.lowercase().trim()
            if (lower.isBlank()) return null

            // 1. 看 MV（最高优先级，绝不让模型说“无法提供视频”）
            if (lower.contains("mv") || (lower.contains("视频") && (lower.contains("看") || lower.contains("放") || lower.contains("播")))) {
                val kw = userText
                    .replace(Regex("""(帮我|给我|我想|我要|请|播放|打开|看这首歌的|看当前|看|放|这首歌的|的mv|的\s*mv|mv|音乐视频|视频)""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""[《》“”"'，。！？,.!?]"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                val args = JSONObject().apply { if (kw.isNotBlank()) put("song_id_or_query", kw) }
                return "play_mv" to args.toString()
            }

            // 2. 唱功评测
            if (listOf("唱得怎么样", "唱的怎么样", "评一下我", "声乐评测", "录音评测", "打分").any { lower.contains(it) }) {
                return "evaluate_singing" to "{}"
            }

            // 2.2 睡眠定时关闭（半小时后停 / 30分钟后关 / 取消定时）
            val asksSleepTimer = listOf("定时关", "睡眠定时", "自动停止", "自动暂停", "后停", "后关", "后自动停", "后自动关").any { lower.contains(it) } &&
                (lower.contains("分钟") || lower.contains("小时") || lower.contains("定时") || lower.contains("取消") || lower.contains("半"))
            if (asksSleepTimer) {
                val mins = when {
                    lower.contains("取消") || lower.contains("关掉定时") || lower.contains("关闭定时") -> 0
                    lower.contains("半小时") || lower.contains("半个小时") -> 30
                    else -> {
                        val h = Regex("""(\d+)\s*(?:个)?小时""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                            ?: if (lower.contains("一小时") || lower.contains("一个小时")) 1 else 0
                        val m = Regex("""(\d+)\s*分钟""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val total = h * 60 + m
                        if (total > 0) total else 30
                    }
                }
                return "set_sleep_timer" to JSONObject().put("minutes", mins).toString()
            }

            // 2.4 下一首插队播放（必须在普通“下一首”切歌之前判断！）
            if (lower.contains("插队") || lower.contains("加到下一首") || lower.contains("放到下一首") ||
                lower.contains("设为下一首") || lower.contains("下一首放") || lower.contains("下一首播")
            ) {
                val kw = userText
                    .replace(Regex("""(请|帮我|给我|把|将|加到下一首播放|放到下一首播放|设为下一首播放|加到下一首|放到下一首|设为下一首|下一首播放|下一首放|下一首播|插队播放|插队放|插队播|插队|这首歌|的歌)"""), " ")
                    .replace(Regex("""[《》“”"'，。！？,.!?]"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (kw.isNotBlank() && kw != "播放") {
                    return "play_next" to JSONObject().put("song_id_or_query", kw).toString()
                }
            }

            // 2.5 批量收藏刚才推荐/展示的歌曲
            if (listOf("刚才", "上面", "这几首", "那几首", "推荐的这").any { lower.contains(it) } &&
                (lower.contains("收藏") || lower.contains("喜欢") || lower.contains("加到歌单") || lower.contains("加入歌单"))
            ) {
                val fav = !lower.contains("取消")
                val plName = Regex("""[「《“"]([^」》”"]{1,20})[」》”"]""").find(userText)?.groupValues?.get(1).orEmpty()
                val args = JSONObject().put("favorite", fav).apply {
                    if (plName.isNotBlank()) put("playlist_name", plName)
                }
                return "batch_favorite_recent" to args.toString()
            }

            // 2.6 学歌控制：倍速慢放 / 单句循环 / 打开学歌室或全屏歌词
            val speedMatch = Regex("""([01](?:\.\d+)?)\s*倍速""").find(lower)?.groupValues?.get(1)?.toFloatOrNull()
            val asksSlowOrNormal = speedMatch != null || lower.contains("放慢") || lower.contains("慢放") || lower.contains("恢复原速") || lower.contains("正常倍速")
            val asksLineLoop = lower.contains("单句循环") || lower.contains("循环这一句") || lower.contains("复读这句") || lower.contains("单句复读")
            val asksOpenLearnOrLyrics = listOf("打开学歌室", "进入学歌室", "去学歌室", "打开沉浸歌词", "全屏歌词", "看歌词界面").any { lower.contains(it) }
            if (asksSlowOrNormal || asksLineLoop || asksOpenLearnOrLyrics) {
                val args = JSONObject().apply {
                    if (speedMatch != null) put("speed", speedMatch.coerceIn(0.5f, 1.5f).toDouble())
                    else if (lower.contains("恢复原速") || lower.contains("正常倍速")) put("speed", 1.0)
                    else if (lower.contains("放慢") || lower.contains("慢放")) put("speed", 0.75)

                    if (asksLineLoop) put("toggle_loop_current", !(lower.contains("取消") || lower.contains("关闭")))
                    if (lower.contains("学歌室")) put("open_player_mode", "learn")
                    else if (lower.contains("沉浸歌词") || lower.contains("全屏歌词") || lower.contains("歌词界面")) put("open_player_mode", "lyrics")
                }
                return "control_learning_playback" to args.toString()
            }

            // 2.7 App 外观主题 / 双语与仅英文歌词 / 粤语注音切换
            val themeVal = when {
                lower.contains("浅色模式") || lower.contains("浅色主题") || lower.contains("白色模式") || lower.contains("亮色模式") -> "light"
                lower.contains("深色模式") || lower.contains("深色主题") || lower.contains("暗黑模式") || lower.contains("夜间模式") -> "dark"
                lower.contains("跟随系统") && (lower.contains("主题") || lower.contains("模式") || lower.contains("浅") || lower.contains("深")) -> "system"
                else -> ""
            }
            val lyricModeVal = when {
                lower.contains("仅英文") || lower.contains("只留英文") || lower.contains("只看英文") || lower.contains("纯英文歌词") || lower.contains("仅原文") -> "original_only"
                lower.contains("双语歌词") || lower.contains("中英双语") || lower.contains("显示翻译") -> "bilingual"
                lower.contains("仅译文") || lower.contains("只看中文歌词") -> "translation_only"
                else -> ""
            }
            val cantoModeVal = when {
                (lower.contains("谐音") && (lower.contains("粤") || lower.contains("注音") || lower.contains("歌词"))) -> "homophone"
                (lower.contains("粤拼") || lower.contains("粤语注音") || lower.contains("粤语音标")) && (lower.contains("关") || lower.contains("隐藏")) -> "off"
                (lower.contains("粤拼") || lower.contains("粤语注音") || lower.contains("粤语音标")) && (lower.contains("开") || lower.contains("切") || lower.contains("显示") || lower.contains("换")) -> "jyutping"
                else -> ""
            }
            if (themeVal.isNotBlank() || lyricModeVal.isNotBlank() || cantoModeVal.isNotBlank()) {
                val args = JSONObject().apply {
                    if (themeVal.isNotBlank()) put("theme_mode", themeVal)
                    if (lyricModeVal.isNotBlank()) put("lyric_display_mode", lyricModeVal)
                    if (cantoModeVal.isNotBlank()) put("cantonese_phonetic_mode", cantoModeVal)
                }
                return "control_app_ui" to args.toString()
            }

            // 2.8 播放队列查看 / 打乱
            if ((lower.contains("播放队列") || lower.contains("当前队列") || lower.contains("待播列表")) &&
                (lower.contains("看") || lower.contains("有哪些") || lower.contains("打乱") || lower.contains("随机"))
            ) {
                val act = if (lower.contains("打乱") || lower.contains("随机")) "shuffle_queue" else "list"
                return "manage_queue" to JSONObject().put("action", act).toString()
            }

            // 3. 明确涉及「没收藏过的新歌 / 推荐新歌 / 猜你喜欢」
            val asksFreshRecommend = (lower.contains("推荐") || lower.contains("猜你喜欢") || lower.contains("来几首") || lower.contains("新歌")) &&
                (lower.contains("没收藏") || lower.contains("没听过") || lower.contains("新歌") || !lower.contains("收藏里"))
            if (asksFreshRecommend) {
                val count = Regex("""(\d+)\s*首""").find(userText)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(3, 12) ?: 6
                val args = JSONObject()
                    .put("mood_or_scene", userText)
                    .put("count", count)
                    .put("auto_play", lower.contains("播放") || lower.contains("放第一首"))
                    .put("exclude_favorites", true)
                return "recommend_songs" to args.toString()
            }

            // 4. 操作「我收藏里的 / 我喜欢的」大曲库（查询 / 播放 / 批量下载 / 打包歌单）
            if (lower.contains("收藏") || lower.contains("我喜欢")) {
                val hints = extractFavoritesQueryHints(userText, knownArtists)
                val isFavLibraryQuery = hints.language.isNotBlank() || hints.genre.isNotBlank() ||
                    hints.artist.isNotBlank() || hints.action != "list" ||
                    lower.contains("有哪些") || lower.contains("多少首") || lower.contains("找") || lower.contains("看看")
                if (isFavLibraryQuery) {
                    val args = JSONObject().apply {
                        if (hints.language.isNotBlank()) put("language", hints.language)
                        if (hints.genre.isNotBlank()) put("genre", hints.genre)
                        if (hints.artist.isNotBlank()) put("artist", hints.artist)
                        put("action", hints.action)
                        put("shuffle", hints.shuffle)
                        if (hints.playlistName.isNotBlank()) put("playlist_name", hints.playlistName)
                    }
                    return "query_favorites" to args.toString()
                }
            }

            // 5. 下载队列控制
            if (lower.contains("下载") && (lower.contains("暂停") || lower.contains("继续") || lower.contains("恢复") || lower.contains("重试") || lower.contains("清空"))) {
                val act = when {
                    lower.contains("暂停") -> "pause"
                    lower.contains("重试") -> "retry_failed"
                    lower.contains("清空") -> "cancel_queued"
                    else -> "resume"
                }
                return "manage_download_queue" to JSONObject().put("action", act).toString()
            }

            // 6. 播控指令
            when {
                lower == "暂停" || lower.contains("暂停播放") -> return "control_playback" to """{"action":"pause"}"""
                lower.contains("下一首") || lower.contains("切歌") -> return "control_playback" to """{"action":"next"}"""
                lower.contains("上一首") -> return "control_playback" to """{"action":"previous"}"""
                lower.contains("单曲循环") -> return "control_playback" to """{"action":"mode_single"}"""
            }

            // 7. 明确要播放某首歌
            if (lower.startsWith("播放") || lower.startsWith("帮我播放") || lower.startsWith("给我放") || lower.startsWith("放一首") || lower.contains("帮我放")) {
                val kw = userText
                    .replace(Regex("""(帮我搜并播放|帮我播放|给我放一首|给我放|帮我放|放一首|播放)"""), " ")
                    .replace(Regex("""[《》“”"'，。！？,.!?]"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (kw.isNotBlank()) return "play_song" to JSONObject().put("song_id_or_query", kw).toString()
            }

            // 8. 歌词找歌
            if (lower.contains("歌词") && (lower.contains("哪首歌") || lower.contains("什么歌") || lower.contains("找"))) {
                return "search_by_lyrics_or_web" to JSONObject().put("query", userText).toString()
            }

            return null
        }

        /**
         * 解析本地 GGUF 模型输出的 `<tool_call>{"name":..., "arguments":...}</tool_call>`
         */
        fun parseLocalToolCalls(text: String): List<Pair<String, String>> {
            val matches = localToolRegex.findAll(text).toList()
            val results = mutableListOf<Pair<String, String>>()
            for (m in matches) {
                runCatching {
                    val obj = JSONObject(m.groupValues[1])
                    val name = obj.optString("name").ifBlank { return@runCatching }
                    val args = obj.opt("arguments")
                    val argsStr = when (args) {
                        is JSONObject -> args.toString()
                        is String -> args
                        else -> "{}"
                    }
                    results += name to argsStr
                }
            }
            return results
        }
    }
}
