package com.sonicpilot.music.data.settings

import android.content.Context
import com.sonicpilot.music.BuildConfig
import com.sonicpilot.music.data.model.AiConfig
import com.sonicpilot.music.data.model.AiEngineMode
import com.sonicpilot.music.data.model.AppAccentStyle
import com.sonicpilot.music.data.model.AppThemeMode
import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.CantonesePhoneticMode
import com.sonicpilot.music.data.model.CloudAiPreset
import com.sonicpilot.music.data.model.LocalModelSpec
import com.sonicpilot.music.data.model.LyricDisplayMode
import com.sonicpilot.music.data.model.PlayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 响应式应用配置与预设目录中心
 * 内置：
 * 1. 最适合安卓音乐 App 的本地离线 GGUF 模型目录（魔搭 ModelScope 国内高速直连）
 * 2. 最适合大陆直连的免费 API AI 预设（智谱 GLM-4-Flash 永久免费 + 硅基流动免费模型）与付费 AI 标准扩展模板
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("sonicpilot_settings", Context.MODE_PRIVATE)

    private val _preferredQuality = MutableStateFlow(
        AudioQuality.fromCode(prefs.getString("preferred_quality", AudioQuality.HIRES.code))
    )
    val preferredQuality: StateFlow<AudioQuality> = _preferredQuality.asStateFlow()

    private val _playMode = MutableStateFlow(
        runCatching { PlayMode.valueOf(prefs.getString("play_mode", PlayMode.SEQUENCE.name)!!) }
            .getOrDefault(PlayMode.SEQUENCE)
    )
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    private val _aiConfig = MutableStateFlow(loadAiConfig())
    val aiConfig: StateFlow<AiConfig> = _aiConfig.asStateFlow()

    private val _onboardingCompleted = MutableStateFlow(prefs.getBoolean("onboarding_completed", false))
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    private val _themeMode = MutableStateFlow(
        runCatching { AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)!!) }
            .getOrDefault(AppThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _accentStyle = MutableStateFlow(
        runCatching { AppAccentStyle.valueOf(prefs.getString("accent_style", AppAccentStyle.CLOUD_RED.name)!!) }
            .getOrDefault(AppAccentStyle.CLOUD_RED)
    )
    val accentStyle: StateFlow<AppAccentStyle> = _accentStyle.asStateFlow()

    private val _searchHistory = MutableStateFlow(loadSearchHistory())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _lyricDisplayMode = MutableStateFlow(
        runCatching { LyricDisplayMode.valueOf(prefs.getString("lyric_display_mode", LyricDisplayMode.BILINGUAL.name)!!) }
            .getOrDefault(LyricDisplayMode.BILINGUAL)
    )
    val lyricDisplayMode: StateFlow<LyricDisplayMode> = _lyricDisplayMode.asStateFlow()

    private val _autoCantoneseJyutping = MutableStateFlow(prefs.getBoolean("auto_cantonese_jyutping", true))
    val autoCantoneseJyutping: StateFlow<Boolean> = _autoCantoneseJyutping.asStateFlow()

    private val _cantonesePhoneticMode = MutableStateFlow(
        runCatching { CantonesePhoneticMode.valueOf(prefs.getString("cantonese_phonetic_mode", CantonesePhoneticMode.JYUTPING.name)!!) }
            .getOrDefault(CantonesePhoneticMode.JYUTPING)
    )
    val cantonesePhoneticMode: StateFlow<CantonesePhoneticMode> = _cantonesePhoneticMode.asStateFlow()

    private val _githubUpdateToken = MutableStateFlow(prefs.getString("github_update_token", "").orEmpty())
    val githubUpdateToken: StateFlow<String> = _githubUpdateToken.asStateFlow()

    fun setLyricDisplayMode(mode: LyricDisplayMode) {
        prefs.edit().putString("lyric_display_mode", mode.name).apply()
        _lyricDisplayMode.value = mode
    }

    fun setAutoCantoneseJyutping(enabled: Boolean) {
        prefs.edit().putBoolean("auto_cantonese_jyutping", enabled).apply()
        _autoCantoneseJyutping.value = enabled
    }

    fun setCantonesePhoneticMode(mode: CantonesePhoneticMode) {
        prefs.edit().putString("cantonese_phonetic_mode", mode.name).apply()
        _cantonesePhoneticMode.value = mode
    }

    fun setGithubUpdateToken(token: String) {
        val clean = token.trim()
        prefs.edit().putString("github_update_token", clean).apply()
        _githubUpdateToken.value = clean
    }

    fun getQqFavoritesSeedVersion(): Int = prefs.getInt("qq_favorites_seed_version", 0)

    fun setQqFavoritesSeedVersion(version: Int) {
        prefs.edit().putInt("qq_favorites_seed_version", version).apply()
    }

    fun getLastPlayedSongJson(): String = prefs.getString("last_played_song_json", "").orEmpty()

    fun saveLastPlayedSongJson(json: String) {
        prefs.edit().putString("last_played_song_json", json).apply()
    }

    fun setOnboardingCompleted(done: Boolean) {
        prefs.edit().putBoolean("onboarding_completed", done).apply()
        _onboardingCompleted.value = done
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    fun setAccentStyle(style: AppAccentStyle) {
        prefs.edit().putString("accent_style", style.name).apply()
        _accentStyle.value = style
    }

    fun recordSearchHistory(keyword: String) {
        val clean = keyword.trim()
        if (clean.isBlank()) return
        val next = (listOf(clean) + _searchHistory.value.filterNot { it.equals(clean, ignoreCase = true) }).take(12)
        prefs.edit().putString("search_history_joined", next.joinToString("\n")).apply()
        _searchHistory.value = next
    }

    fun clearSearchHistory() {
        prefs.edit().remove("search_history_joined").apply()
        _searchHistory.value = emptyList()
    }

    private fun loadSearchHistory(): List<String> =
        prefs.getString("search_history_joined", "").orEmpty()
            .lines().map { it.trim() }.filter { it.isNotBlank() }

    private fun loadAiConfig(): AiConfig {
        val mode = runCatching {
            AiEngineMode.valueOf(prefs.getString("ai_mode", AiEngineMode.AUTO_CLOUD_THEN_LOCAL.name)!!)
        }.getOrDefault(AiEngineMode.AUTO_CLOUD_THEN_LOCAL)

        val savedBaseUrl = prefs.getString("cloud_base_url", "https://open.bigmodel.cn/api/paas/v4").orEmpty()
        val rawSavedModel = prefs.getString("cloud_model", "glm-4.7").orEmpty()
        // v1.9.0 起自动将老版本默认智谱 Flash 升级至旗舰智商 GLM-4.7（若额度用尽会自动无缝回落至永久免费 GLM-4-Flash）
        val upgradedModel = if (
            savedBaseUrl.contains("bigmodel.cn", true) &&
            (rawSavedModel == "glm-4-flash" || rawSavedModel == "glm-4-flash-250414") &&
            prefs.getInt("ai_model_migration_ver", 0) < 2
        ) {
            prefs.edit()
                .putString("cloud_model", "glm-4.7")
                .putString("cloud_preset_id", "zhipu_glm47")
                .putInt("ai_model_migration_ver", 2)
                .apply()
            "glm-4.7"
        } else rawSavedModel

        return AiConfig(
            engineMode = mode,
            cloudPresetId = prefs.getString("cloud_preset_id", "zhipu_glm47").orEmpty(),
            cloudBaseUrl = savedBaseUrl,
            // 公开源码版默认空字符串（在「设置 -> AI 引擎」填入免费或自购 API Key，或直接下载本地离线 Qwen3 模型）
            cloudApiKey = prefs.getString("cloud_api_key", DEFAULT_ZHIPU_FREE_API_KEY).orEmpty(),
            cloudModel = upgradedModel,
            selectedLocalModelPath = prefs.getString("local_model_path", "").orEmpty(),
            selectedLocalModelName = prefs.getString("local_model_name", "内置轻量意图引擎（未加载 GGUF）").orEmpty(),
            localThreads = prefs.getInt("local_threads", 4),
            localContextSize = prefs.getInt("local_context_size", 2048),
            enableWebSearchForAi = prefs.getBoolean("ai_web_search", true)
        )
    }

    fun setPreferredQuality(quality: AudioQuality) {
        prefs.edit().putString("preferred_quality", quality.code).apply()
        _preferredQuality.value = quality
    }

    fun setPlayMode(mode: PlayMode) {
        prefs.edit().putString("play_mode", mode.name).apply()
        _playMode.value = mode
    }

    fun updateAiConfig(transform: (AiConfig) -> AiConfig) {
        val next = transform(_aiConfig.value)
        prefs.edit()
            .putString("ai_mode", next.engineMode.name)
            .putString("cloud_preset_id", next.cloudPresetId)
            .putString("cloud_base_url", next.cloudBaseUrl)
            .putString("cloud_api_key", next.cloudApiKey)
            .putString("cloud_model", next.cloudModel)
            .putString("local_model_path", next.selectedLocalModelPath)
            .putString("local_model_name", next.selectedLocalModelName)
            .putInt("local_threads", next.localThreads)
            .putInt("local_context_size", next.localContextSize)
            .putBoolean("ai_web_search", next.enableWebSearchForAi)
            .apply()
        _aiConfig.value = next
    }

    companion object {
        // 由 local.properties 或 CI 环境变量 ZHIPU_FREE_API_KEY 注入，源码仓库不保存明文密钥
        private val DEFAULT_ZHIPU_FREE_API_KEY: String get() = BuildConfig.DEFAULT_ZHIPU_API_KEY

        /**
         * 端侧离线 AI 模型目录（支持公共目录 Download/LocalAI 零拷贝共享复用）
         * 均走魔搭社区 ModelScope 国内免梯子高速直连，支持断点续传与 GGUF 魔数头校验
         */
        val LOCAL_MODEL_CATALOG: List<LocalModelSpec> = listOf(
            LocalModelSpec(
                id = "qwen3_8b_q4",
                name = "Qwen3-8B (Q4_K_M · 旗舰满血)",
                fileName = "Qwen3-8B-Q4_K_M.gguf",
                sizeMb = 4795,
                minRamGb = 12,
                downloadUrl = "https://www.modelscope.cn/api/v1/models/Qwen/Qwen3-8B-GGUF/repo?Revision=master&FilePath=Qwen3-8B-Q4_K_M.gguf",
                fallbackUrl = "https://www.modelscope.cn/api/v1/models/unsloth/Qwen3-8B-GGUF/repo?Revision=master&FilePath=Qwen3-8B-Q4_K_M.gguf",
                description = "【12GB+ 旗舰首选】端侧智商天花板，复杂音乐指令编排、乐评文笔与多语翻译全能离线运行。",
                recommended = true
            ),
            LocalModelSpec(
                id = "qwen3_4b_q4",
                name = "Qwen3-4B (Q4_K_M · 全能甜点)",
                fileName = "Qwen3-4B-Q4_K_M.gguf",
                sizeMb = 2382,
                minRamGb = 8,
                downloadUrl = "https://www.modelscope.cn/api/v1/models/Qwen/Qwen3-4B-GGUF/repo?Revision=master&FilePath=Qwen3-4B-Q4_K_M.gguf",
                fallbackUrl = "https://www.modelscope.cn/api/v1/models/unsloth/Qwen3-4B-GGUF/repo?Revision=master&FilePath=Qwen3-4B-Q4_K_M.gguf",
                description = "【速度与智商最佳平衡】约 2.4GB，响应飞快发热低，8GB 机型离线主力首选。"
            ),
            LocalModelSpec(
                id = "qwen3_1_7b_q4",
                name = "Qwen3-1.7B (Q4_K_M · 轻快省电)",
                fileName = "Qwen3-1.7B-Q4_K_M.gguf",
                sizeMb = 1056,
                minRamGb = 6,
                downloadUrl = "https://www.modelscope.cn/api/v1/models/unsloth/Qwen3-1.7B-GGUF/repo?Revision=master&FilePath=Qwen3-1.7B-Q4_K_M.gguf",
                fallbackUrl = "https://hf-mirror.com/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                description = "【轻量均衡】仅 1.06GB，秒级响应、超低功耗，日常离线控歌与对话流畅稳健。"
            ),
            LocalModelSpec(
                id = "qwen3_0_6b_q4",
                name = "Qwen3-0.6B (Q4_K_M · 极速轻量)",
                fileName = "Qwen3-0.6B-Q4_K_M.gguf",
                sizeMb = 378,
                minRamGb = 4,
                downloadUrl = "https://www.modelscope.cn/api/v1/models/unsloth/Qwen3-0.6B-GGUF/repo?Revision=master&FilePath=Qwen3-0.6B-Q4_K_M.gguf",
                fallbackUrl = "https://hf-mirror.com/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
                description = "【极速兜底】仅 378MB，Wi-Fi 十余秒下完，存储空间紧张时首选。"
            )
        )

        /**
         * 云端 AI 预设清单：首位为大陆直连永久免费 API，后续预留主流免费与付费 OpenAI 兼容接口
         */
        val CLOUD_AI_PRESETS: List<CloudAiPreset> = listOf(
            CloudAiPreset(
                id = "zhipu_glm47",
                name = "智谱 AI · GLM-4.7（旗舰大脑 · 默认推荐）",
                tag = "大陆直连 · 旗舰智商",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModel = "glm-4.7",
                keyHelpUrl = "https://open.bigmodel.cn/",
                description = "智谱旗舰 GLM-4.7，音乐品味荐歌、多步指令与声乐点评智商断层领先；遇限流或余额耗尽自动无感切免费模型。",
                isFreeRecommended = true
            ),
            CloudAiPreset(
                id = "zhipu_glm45_air",
                name = "智谱 AI · GLM-4.5-Air（高速高智商）",
                tag = "大陆直连 · 极速高智商",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModel = "glm-4.5-air",
                keyHelpUrl = "https://open.bigmodel.cn/",
                description = "复用同一个智谱 API Key，兼顾极速响应与高工具命中率。",
                isFreeRecommended = true
            ),
            CloudAiPreset(
                id = "zhipu_glm47_flash",
                name = "智谱 AI · GLM-4.7-Flash（4.7 免费版）",
                tag = "大陆直连 · 永久免费",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModel = "glm-4.7-flash",
                keyHelpUrl = "https://open.bigmodel.cn/",
                description = "GLM-4.7 永久免费档，高峰若遇限流自动瞬切备选模型不断链。",
                isFreeRecommended = true
            ),
            CloudAiPreset(
                id = "zhipu_free",
                name = "智谱 AI · GLM-4-Flash（永久免费稳速兜底）",
                tag = "大陆直连 · 永久免费",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModel = "glm-4-flash",
                keyHelpUrl = "https://open.bigmodel.cn/",
                description = "永久免费零限流，适合高频日常秒控 App。",
                isFreeRecommended = true
            ),
            CloudAiPreset(
                id = "siliconflow_free",
                name = "硅基流动 SiliconFlow（免费开源模型池）",
                tag = "大陆直连 · 免费档",
                baseUrl = "https://api.siliconflow.cn/v1",
                defaultModel = "Qwen/Qwen2.5-7B-Instruct",
                keyHelpUrl = "https://cloud.siliconflow.cn/",
                description = "大陆高速推理平台，内置 Qwen2.5-7B / GLM-4-9B 等长期免费模型，也支持随时切 DeepSeek。",
                isFreeRecommended = true
            ),
            CloudAiPreset(
                id = "deepseek_paid",
                name = "DeepSeek（付费自接）",
                tag = "大陆直连 · 付费扩展",
                baseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-chat",
                keyHelpUrl = "https://platform.deepseek.com/",
                description = "高性价比推理与中文乐评旗舰，填上自己的 API Key 即可切换。"
            ),
            CloudAiPreset(
                id = "openai_paid",
                name = "OpenAI / 兼容中转（付费自接）",
                tag = "OpenAI 协议",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o-mini",
                keyHelpUrl = "https://platform.openai.com/",
                description = "标准 OpenAI 协议，可自由填写任意中转域名或 Claude/Gemini 兼容网关。"
            ),
            CloudAiPreset(
                id = "gemini_compat",
                name = "Google Gemini（OpenAI 兼容端点）",
                tag = "免费额度 / 付费",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                defaultModel = "gemini-2.5-flash",
                keyHelpUrl = "https://aistudio.google.com/",
                description = "Google AI Studio 兼容接口，免费额度慷慨，Function Calling 表现极佳。"
            ),
            CloudAiPreset(
                id = "custom_openai",
                name = "自定义 OpenAI 兼容 API",
                tag = "完全自由",
                baseUrl = "https://your-api-endpoint.com/v1",
                defaultModel = "your-model-name",
                keyHelpUrl = "",
                description = "支持 Ollama 局域网服务、One-API、New-API、Kimi、通义千问等一切 OpenAI 格式接口。"
            )
        )
    }
}
