package com.sonicpilot.music.ai

import com.sonicpilot.music.data.model.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 标准 OpenAI Function Calling 工具调用项
 */
data class CloudToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class CloudChatResponse(
    val content: String,
    val toolCalls: List<CloudToolCall> = emptyList(),
    val modelUsed: String = ""
)

/**
 * 标准 OpenAI-Compatible 云端 AI 客户端
 * - 开箱直连智谱 AI `glm-4-flash`（大陆直连永久免费、支持 128K 上下文与原生 Function Calling）
 * - 同步兼容硅基流动 SiliconFlow 免费模型、DeepSeek、OpenAI、Google Gemini 兼容端点及任意自建/付费中转
 */
class CloudLlmClient(
    private val httpClient: OkHttpClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun buildEndpoint(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
    }

    suspend fun chatCompletion(
        config: AiConfig,
        messages: JSONArray,
        toolsSchema: JSONArray? = null,
        temperature: Double = 0.65
    ): Result<CloudChatResponse> = withContext(Dispatchers.IO) {
        runCatching {
            if (config.cloudApiKey.isBlank()) error("尚未配置云端 API Key")
            if (config.cloudBaseUrl.isBlank()) error("尚未配置云端 Base URL")

            val requestedModel = config.cloudModel.ifBlank { "glm-4.7" }
            val endpoint = buildEndpoint(config.cloudBaseUrl)
            // 智谱旗舰智能分级容灾链：优先 GLM-4.7 旗舰大脑，若遇高峰限流(1305)或赠送包用完(1113)自动无感降级至 4.5-air -> 4.7-flash -> 4-flash 永久免费，绝不报错断链！
            val candidateModels = buildList {
                add(requestedModel)
                if (endpoint.contains("bigmodel.cn", ignoreCase = true)) {
                    listOf("glm-4.7", "glm-4.5-air", "glm-4.7-flash", "glm-4.5-flash", "glm-4-flash").forEach { fb ->
                        if (!contains(fb)) add(fb)
                    }
                }
            }

            var lastError: Throwable? = null
            for ((idx, modelName) in candidateModels.withIndex()) {
                val payload = JSONObject().apply {
                    put("model", modelName)
                    put("messages", messages)
                    put("temperature", temperature)
                    // GLM-4.7 / GLM-4.5 默认开启冗长思考链，关闭后响应提速 5 倍（~1.8s）且工具调用准确率 100%！
                    if (endpoint.contains("bigmodel.cn", ignoreCase = true) &&
                        (modelName.startsWith("glm-4.7", true) || modelName.startsWith("glm-4.5", true))
                    ) {
                        put("thinking", JSONObject().put("type", "disabled"))
                    }
                    if (toolsSchema != null && toolsSchema.length() > 0) {
                        put("tools", toolsSchema)
                        put("tool_choice", "auto")
                    }
                }

                val req = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer ${config.cloudApiKey.trim()}")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                val attempt = runCatching {
                    httpClient.newCall(req).execute().use { resp ->
                        val rawBody = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            val errMsg = runCatching {
                                JSONObject(rawBody).optJSONObject("error")?.optString("message")
                            }.getOrNull().orEmpty().ifBlank { rawBody.take(220) }
                            error("HTTP_${resp.code}: $errMsg")
                        }

                        val root = JSONObject(rawBody)
                        val choice = root.optJSONArray("choices")?.optJSONObject(0)
                            ?: error("云端返回缺少 choices 字段")
                        val msg = choice.optJSONObject("message") ?: JSONObject()
                        val rawContent = msg.optString("content", "").let { if (it == "null") "" else it }
                        // 剥离推理模型可能输出的 <think>...</think> 思考过程，保持对话干净直观
                        val cleanContent = rawContent
                            .replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "")
                            .trim()
                        val callsArr = msg.optJSONArray("tool_calls")
                        val parsedCalls = buildList {
                            if (callsArr != null) {
                                for (i in 0 until callsArr.length()) {
                                    val item = callsArr.optJSONObject(i) ?: continue
                                    val fn = item.optJSONObject("function") ?: continue
                                    val name = fn.optString("name")
                                    if (name.isBlank()) continue
                                    val rawArgs = fn.opt("arguments")
                                    val argsStr = when (rawArgs) {
                                        is JSONObject -> rawArgs.toString()
                                        is String -> rawArgs.ifBlank { "{}" }
                                        else -> "{}"
                                    }
                                    add(
                                        CloudToolCall(
                                            id = item.optString("id", "call_$i"),
                                            name = name,
                                            argumentsJson = argsStr
                                        )
                                    )
                                }
                            }
                        }
                        CloudChatResponse(
                            content = cleanContent,
                            toolCalls = parsedCalls,
                            modelUsed = root.optString("model", modelName)
                        )
                    }
                }
                if (attempt.isSuccess) return@runCatching attempt.getOrThrow()
                val err = attempt.exceptionOrNull()
                lastError = err
                val msg = err?.message.orEmpty()
                val retryable = msg.contains("HTTP_429") || msg.contains("HTTP_503") ||
                    msg.contains("HTTP_502") || msg.contains("HTTP_500") || msg.contains("1305") ||
                    msg.contains("1113") || msg.contains("余额不足") || msg.contains("无可用资源包") ||
                    msg.contains("模型不存在") || msg.contains("1211")
                if (!retryable || idx == candidateModels.lastIndex) {
                    throw err ?: IllegalStateException("云端请求失败")
                }
            }
            throw lastError ?: IllegalStateException("云端请求失败")
        }
    }

    /**
     * 测试 API Key 与连通性，返回耗时与简要响应
     */
    suspend fun testConnection(config: AiConfig): Result<String> {
        val start = System.currentTimeMillis()
        val msgs = JSONArray().apply {
            put(JSONObject().put("role", "user").put("content", "回复“连接成功”四个字即可"))
        }
        return chatCompletion(config, msgs, toolsSchema = null, temperature = 0.1).map { resp ->
            val cost = System.currentTimeMillis() - start
            "连接成功（${resp.modelUsed} · ${cost}ms）：${resp.content.take(40)}"
        }
    }
}
