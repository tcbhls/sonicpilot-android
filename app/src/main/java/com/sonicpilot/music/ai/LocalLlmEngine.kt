package com.sonicpilot.music.ai

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.sonicpilot.music.data.model.LocalModelSpec
import com.sonicpilot.music.data.settings.SettingsStore
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 本地离线大模型运行、共享目录与下载状态
 */
data class LocalLlmStatus(
    val isModelLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val loadedModelName: String = "",
    val loadedModelPath: String = "",
    val lastTokensPerSec: Float = 0f,
    val downloadingModelId: String? = null,
    val downloadProgress: Float = 0f,
    val downloadedMb: Float = 0f,
    val totalMb: Float = 0f,
    val downloadSpeedMbps: Float = 0f,
    val sharedStorageGranted: Boolean = false,
    val activeModelsDir: String = "",
    val storageRefreshTick: Int = 0,
    val statusMessage: String = "尚未加载 GGUF 模型（当前使用内置轻量意图引擎）",
    val systemHardwareInfo: String = ""
)

/**
 * 端侧完全离线 AI 推理引擎（基于预编译 llama.cpp Android ARM64 AAR）
 * - 支持公共目录 `/storage/emulated/0/Download/LocalAI/`（可与手机上其它本地 AI 应用零拷贝共用 GGUF 模型）
 * - 支持 5GB+ 大模型（Qwen3-8B / Qwen3-4B）HTTP Range 断点续传、GGUF 魔数头校验与主备源自动回退
 * - 自动处理 Qwen3 `/no_think` 极速输出与 `<think>` 标签清理
 */
class LocalLlmEngine(
    private val context: Context,
    private val settings: SettingsStore,
    private val httpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inferenceMutex = Mutex()

    @Volatile
    private var activeModel: LlamaModel? = null
    private var downloadJob: Job? = null

    private val _status = MutableStateFlow(LocalLlmStatus())
    val status: StateFlow<LocalLlmStatus> = _status.asStateFlow()

    /** 本 App 私有兜底目录（未授共享存储权限时自动使用） */
    val privateModelsDir: File by lazy {
        File(context.getExternalFilesDir(null), "local_llm_models").apply { if (!exists()) mkdirs() }
    }

    /** 跨应用统一共享目录：手机 Download/LocalAI */
    val sharedModelsDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "LocalAI"
        )

    /** 判断当前是否已获 Android 11+ 所有文件访问权限（支持跨应用零拷贝共享 Download/LocalAI） */
    fun hasSharedStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /** 当前新下载/导入模型优先写入的目录（已授权走共享目录，否则回退私有目录） */
    val modelsDir: File
        get() {
            if (hasSharedStorageAccess()) {
                val dir = sharedModelsDir
                if (dir.exists() || dir.mkdirs()) {
                    if (dir.canWrite()) return dir
                }
            }
            return privateModelsDir
        }

    init {
        val hwInfo = runCatching { Llama.getSystemInfo() }.getOrDefault("ARM64 CPU (llama.cpp)")
        _status.value = _status.value.copy(
            systemHardwareInfo = hwInfo,
            sharedStorageGranted = hasSharedStorageAccess(),
            activeModelsDir = modelsDir.absolutePath
        )
    }

    /** 返回应用或授权后刷新共享目录可见状态 */
    fun refreshStorageStatus() {
        val granted = hasSharedStorageAccess()
        val dir = modelsDir
        _status.value = _status.value.copy(
            sharedStorageGranted = granted,
            activeModelsDir = dir.absolutePath,
            storageRefreshTick = _status.value.storageRefreshTick + 1
        )
    }

    /** 按优先级查找模型文件（优先命中共享目录 Download/LocalAI，其次检查私有目录） */
    fun findModelFile(fileName: String): File? {
        if (hasSharedStorageAccess()) {
            val shared = File(sharedModelsDir, fileName)
            if (shared.isFile && shared.length() > 50L * 1024L * 1024L) return shared
        }
        val priv = File(privateModelsDir, fileName)
        if (priv.isFile && priv.length() > 50L * 1024L * 1024L) return priv
        return null
    }

    fun getLocalModelFile(spec: LocalModelSpec): File =
        findModelFile(spec.fileName) ?: File(modelsDir, spec.fileName)

    fun isModelDownloaded(spec: LocalModelSpec): Boolean = findModelFile(spec.fileName) != null

    fun isFileInSharedDir(file: File): Boolean =
        file.absolutePath.startsWith(sharedModelsDir.absolutePath)

    /** 列出共享目录 + 私有目录全部可用 GGUF 模型（按文件名去重，优先保留共享目录） */
    fun listDownloadedGgufFiles(): List<File> {
        val byName = linkedMapOf<String, File>()
        if (hasSharedStorageAccess()) {
            sharedModelsDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("gguf", true) && it.length() > 10L * 1024L * 1024L }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { byName[it.name] = it }
        }
        privateModelsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", true) && it.length() > 10L * 1024L * 1024L }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { byName.putIfAbsent(it.name, it) }
        return byName.values.toList()
    }

    private fun hasValidGgufHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        return runCatching {
            FileInputStream(file).use { fis ->
                val h = ByteArray(4)
                fis.read(h) == 4 &&
                    h[0] == 'G'.code.toByte() &&
                    h[1] == 'G'.code.toByte() &&
                    h[2] == 'U'.code.toByte() &&
                    h[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    /**
     * 一键从魔搭 ModelScope 国内高速直连下载 GGUF 模型（支持 5GB 断点续传与主备源自动回退）
     */
    fun downloadModel(spec: LocalModelSpec) {
        if (_status.value.downloadingModelId != null) return
        val existing = findModelFile(spec.fileName)
        if (existing != null) {
            scope.launch { loadModel(existing.absolutePath, spec.name) }
            return
        }

        val targetDir = modelsDir
        val destFile = File(targetDir, spec.fileName)
        val partFile = File(targetDir, "${spec.fileName}.part")

        downloadJob = scope.launch {
            _status.value = _status.value.copy(
                downloadingModelId = spec.id,
                downloadProgress = 0.01f,
                downloadedMb = 0f,
                totalMb = spec.sizeMb.toFloat(),
                downloadSpeedMbps = 0f,
                sharedStorageGranted = hasSharedStorageAccess(),
                activeModelsDir = targetDir.absolutePath,
                statusMessage = "正在连接魔搭 ModelScope 高速下载 ${spec.name}..."
            )
            try {
                val urls = listOfNotNull(
                    spec.downloadUrl.takeIf { it.isNotBlank() },
                    spec.fallbackUrl.takeIf { it.isNotBlank() }
                )
                var lastErr: Exception? = null
                var ok = false
                for (url in urls) {
                    try {
                        downloadWithResume(url, partFile, spec)
                        ok = true
                        break
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ex: Exception) {
                        lastErr = ex
                    }
                }
                if (!ok) throw (lastErr ?: IllegalStateException("所有下载源均失败"))

                if (!hasValidGgufHeader(partFile) || partFile.length() < 10L * 1024L * 1024L) {
                    runCatching { partFile.delete() }
                    error("下载文件校验未通过（非有效 GGUF 权重），请重试")
                }

                if (destFile.exists()) destFile.delete()
                if (!partFile.renameTo(destFile)) {
                    error("模型写入重命名失败，请检查存储空间")
                }
                _status.value = _status.value.copy(
                    downloadingModelId = null,
                    downloadProgress = 1f,
                    downloadSpeedMbps = 0f,
                    storageRefreshTick = _status.value.storageRefreshTick + 1,
                    statusMessage = "${spec.name} 下载完成，正在加载进端侧引擎..."
                )
                loadModel(destFile.absolutePath, spec.name)
            } catch (e: CancellationException) {
                // 保留有效 .part 文件以便下次续传，避免 5GB 模型从头重下
                _status.value = _status.value.copy(
                    downloadingModelId = null,
                    downloadProgress = 0f,
                    downloadSpeedMbps = 0f,
                    statusMessage = "已暂停下载（已保留断点进度，再次点击可续传）"
                )
            } catch (e: Exception) {
                _status.value = _status.value.copy(
                    downloadingModelId = null,
                    downloadProgress = 0f,
                    downloadSpeedMbps = 0f,
                    statusMessage = "模型下载失败：${e.message}"
                )
            }
        }
    }

    private fun downloadWithResume(url: String, partFile: File, spec: LocalModelSpec) {
        var resumeOffset = 0L
        if (partFile.exists()) {
            if (hasValidGgufHeader(partFile)) {
                resumeOffset = partFile.length()
            } else {
                partFile.delete()
            }
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) SonicPilot/2.1")
        if (resumeOffset > 0L) {
            reqBuilder.header("Range", "bytes=$resumeOffset-")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val appendMode = (resp.code == 206 && resumeOffset > 0L)
            if (resp.code == 416 && hasValidGgufHeader(partFile)) {
                return
            }
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("空响应体")

            if (!appendMode) {
                resumeOffset = 0L
            }
            val contentLen = body.contentLength()
            val totalBytes = when {
                appendMode && contentLen > 0L -> resumeOffset + contentLen
                contentLen > 0L -> contentLen
                else -> spec.sizeMb.toLong() * 1024L * 1024L
            }

            var written = resumeOffset
            val sessionStartWritten = resumeOffset
            val startMs = System.currentTimeMillis()
            var lastEmit = 0L
            var headerChecked = appendMode

            body.byteStream().use { inp ->
                FileOutputStream(partFile, appendMode).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val read = inp.read(buf)
                        if (read == -1) break
                        if (!headerChecked && read >= 4) {
                            if (buf[0] != 'G'.code.toByte() || buf[1] != 'G'.code.toByte() ||
                                buf[2] != 'U'.code.toByte() || buf[3] != 'F'.code.toByte()
                            ) {
                                error("远端返回的不是有效 GGUF 模型文件")
                            }
                            headerChecked = true
                        }
                        out.write(buf, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 350L) {
                            lastEmit = now
                            val prog = (written.toFloat() / totalBytes.toFloat()).coerceIn(0.01f, 0.99f)
                            val elapsedSec = ((now - startMs) / 1000f).coerceAtLeast(0.25f)
                            val speedMbps = ((written - sessionStartWritten) / (1024f * 1024f)) / elapsedSec
                            _status.value = _status.value.copy(
                                downloadProgress = prog,
                                downloadedMb = written / (1024f * 1024f),
                                totalMb = totalBytes / (1024f * 1024f),
                                downloadSpeedMbps = speedMbps,
                                statusMessage = "下载中 ${spec.name} (${(prog * 100).toInt()}% · ${String.format("%.1f", speedMbps)} MB/s)"
                            )
                        }
                    }
                    out.flush()
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /**
     * 通过 Android SAF 导入手机里已有的 `.gguf` 模型文件（已授权时写入公共共享目录 Download/LocalAI）
     */
    suspend fun importModelFromUri(uri: Uri, displayName: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = displayName.ifBlank { "custom_${System.currentTimeMillis()}.gguf" }
                .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
            val dest = File(modelsDir, safeName)
            _status.value = _status.value.copy(statusMessage = "正在导入本地模型 $safeName ...")
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(dest).use { out -> inp.copyTo(out, 128 * 1024) }
            } ?: error("无法读取所选文件")
            if (!hasValidGgufHeader(dest)) {
                runCatching { dest.delete() }
                error("所选文件不是有效的 GGUF 模型")
            }
            _status.value = _status.value.copy(
                storageRefreshTick = _status.value.storageRefreshTick + 1,
                statusMessage = "已导入 $safeName，准备加载"
            )
            loadModel(dest.absolutePath, safeName)
            dest
        }
    }

    fun deleteModelFile(file: File) {
        if (_status.value.loadedModelPath == file.absolutePath) {
            unloadModel()
        }
        runCatching { file.delete() }
        runCatching { File(file.parentFile, "${file.name}.part").delete() }
        _status.value = _status.value.copy(storageRefreshTick = _status.value.storageRefreshTick + 1)
    }

    suspend fun loadModel(modelPath: String, displayName: String): Result<Unit> = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            runCatching {
                val file = File(modelPath)
                if (!file.exists()) error("模型文件不存在：$modelPath")
                _status.value = _status.value.copy(
                    isLoading = true,
                    statusMessage = "正在将 $displayName 载入端侧推理引擎..."
                )
                activeModel?.let { runCatching { Llama.releaseModel(it) } }
                activeModel = null

                val cfg = settings.aiConfig.value
                val defaultThreads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
                val threads = cfg.localThreads.coerceIn(2, defaultThreads.coerceAtLeast(6))
                val model = Llama.loadModel(
                    modelPath = modelPath,
                    config = LlamaConfig(
                        contextSize = cfg.localContextSize.coerceIn(1024, 4096),
                        threads = threads,
                        gpuLayers = 0,
                        temperature = 0.6f,
                        topP = 0.9f,
                        topK = 40
                    )
                )
                activeModel = model
                settings.updateAiConfig {
                    it.copy(
                        selectedLocalModelPath = modelPath,
                        selectedLocalModelName = displayName
                    )
                }
                val sharedTag = if (isFileInSharedDir(file)) "（共享目录）" else ""
                _status.value = _status.value.copy(
                    isModelLoaded = true,
                    isLoading = false,
                    loadedModelName = displayName,
                    loadedModelPath = modelPath,
                    statusMessage = "本地离线模型已就绪$sharedTag：$displayName"
                )
            }.onFailure { err ->
                _status.value = _status.value.copy(
                    isModelLoaded = false,
                    isLoading = false,
                    statusMessage = "加载本地模型失败：${err.message}"
                )
            }
        }
    }

    fun unloadModel() {
        scope.launch {
            inferenceMutex.withLock {
                activeModel?.let { runCatching { Llama.releaseModel(it) } }
                activeModel = null
                _status.value = _status.value.copy(
                    isModelLoaded = false,
                    loadedModelName = "",
                    loadedModelPath = "",
                    statusMessage = "已释放本地模型内存（切回轻量意图引擎）"
                )
            }
        }
    }

    fun isReady(): Boolean = activeModel?.isLoaded == true

    private fun stripThinkTags(raw: String): String {
        return raw
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^<think>[\\s\\S]*", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * 执行本地离线推理（若尚未加载会自动优先加载已选模型或共享目录内已下载的最强模型）
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 512
    ): Result<String> = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            runCatching {
                var model = activeModel
                if (model == null || !model.isLoaded) {
                    val cfg = settings.aiConfig.value
                    val candidateFile = cfg.selectedLocalModelPath
                        .takeIf { it.isNotBlank() }
                        ?.let { File(it) }
                        ?.takeIf { it.exists() }
                        ?: listDownloadedGgufFiles().maxByOrNull { it.length() }

                    if (candidateFile != null && candidateFile.exists()) {
                        val threads = cfg.localThreads.coerceIn(2, 6)
                        model = Llama.loadModel(
                            modelPath = candidateFile.absolutePath,
                            config = LlamaConfig(
                                contextSize = cfg.localContextSize.coerceIn(1024, 4096),
                                threads = threads,
                                temperature = 0.6f
                            )
                        )
                        activeModel = model
                        _status.value = _status.value.copy(
                            isModelLoaded = true,
                            loadedModelName = cfg.selectedLocalModelName.ifBlank { candidateFile.name },
                            loadedModelPath = candidateFile.absolutePath
                        )
                    }
                }
                val loaded = model ?: error("本地 GGUF 模型未加载")
                val modelNameLower = _status.value.loadedModelName.lowercase() + _status.value.loadedModelPath.lowercase()
                val isQwen3 = modelNameLower.contains("qwen3")
                val effectiveSys = if (isQwen3 && !systemPrompt.contains("/no_think")) {
                    if (systemPrompt.isBlank()) "/no_think" else "$systemPrompt\n/no_think"
                } else {
                    systemPrompt
                }
                val effectivePrompt = if (isQwen3 && !userPrompt.contains("/no_think")) {
                    "$userPrompt\n/no_think"
                } else {
                    userPrompt
                }
                val result = Llama.complete(
                    model = loaded,
                    prompt = effectivePrompt,
                    systemPrompt = effectiveSys,
                    maxTokens = maxTokens
                )
                _status.value = _status.value.copy(lastTokensPerSec = result.tokensPerSecond)
                stripThinkTags(result.text).ifBlank { result.text.trim() }
            }
        }
    }
}
