package com.sonicpilot.music.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sonicpilot.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class ReleaseUpdateInfo(
    val latestTag: String,
    val title: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkAssetApiUrl: String = "",
    val apkSizeBytes: Long = 0L,
    val hasNewerVersion: Boolean = false
)

data class UpdateUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val statusText: String = "当前版本 v${BuildConfig.VERSION_NAME}",
    val availableUpdate: ReleaseUpdateInfo? = null
)

/**
 * 应用内检查更新 & 原地覆盖升级安装器
 * - 固定包名 `com.sonicpilot.music` + 稳定签名，下载完直接拉起系统安装界面原地覆盖更新，绝不再出现双图标！
 * - 支持对接 GitHub Releases API（支持填入可选 GitHub PAT 拉取私有库 Release，也支持任意直链 APK 一键覆盖装）
 */
class AppUpdater(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val updateDir: File by lazy {
        File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
    }

    suspend fun checkGitHubRelease(
        repoFullName: String = "tcbhls/sonicpilot-android",
        githubToken: String = ""
    ): Result<ReleaseUpdateInfo> = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(isChecking = true, statusText = "正在检测最新版本...")
        runCatching {
            val reqBuilder = Request.Builder()
                .url("https://api.github.com/repos/$repoFullName/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SonicPilot/${BuildConfig.VERSION_NAME}")
            if (githubToken.isNotBlank()) {
                reqBuilder.header("Authorization", "token ${githubToken.trim()}")
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 404 || resp.code == 401) {
                    error("暂未找到公开 Release 或接口受限，可稍后重试、在下方可选填 GitHub Token，或直接粘贴 APK 直链安装")
                }
                if (!resp.isSuccessful) error("检查更新失败：HTTP ${resp.code}")
                val root = JSONObject(resp.body?.string().orEmpty())
                val tag = root.optString("tag_name", "")
                val name = root.optString("name", tag)
                val body = root.optString("body", "")
                val assets = root.optJSONArray("assets")
                var apkBrowserUrl = ""
                var apkApiUrl = ""
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        if (a.optString("name").endsWith(".apk", true)) {
                            apkBrowserUrl = a.optString("browser_download_url", "")
                            apkApiUrl = a.optString("url", "")
                            apkSize = a.optLong("size", 0L)
                            break
                        }
                    }
                }
                val isNewer = compareVersions(tag.removePrefix("v"), BuildConfig.VERSION_NAME) > 0
                val info = ReleaseUpdateInfo(
                    latestTag = tag,
                    title = name,
                    changelog = body,
                    apkDownloadUrl = apkBrowserUrl,
                    apkAssetApiUrl = apkApiUrl,
                    apkSizeBytes = apkSize,
                    hasNewerVersion = isNewer
                )
                _state.value = _state.value.copy(
                    isChecking = false,
                    availableUpdate = info,
                    statusText = if (isNewer) "发现新版本 $tag（当前 v${BuildConfig.VERSION_NAME}）" else "已是最新版本 v${BuildConfig.VERSION_NAME}"
                )
                info
            }
        }.onFailure { err ->
            _state.value = _state.value.copy(
                isChecking = false,
                statusText = err.message ?: "检查更新失败"
            )
        }
    }

    suspend fun downloadAndInstallApk(
        url: String,
        githubToken: String = "",
        useGitHubAssetOctetStream: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(
            isDownloading = true,
            downloadProgress = 0.02f,
            statusText = "正在下载新版安装包..."
        )
        runCatching {
            val cleanUrl = url.trim()
            if (!cleanUrl.startsWith("http")) error("请输入有效的 http/https APK 地址")
            val reqBuilder = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "SonicPilot/${BuildConfig.VERSION_NAME}")
            if (githubToken.isNotBlank()) {
                reqBuilder.header("Authorization", "token ${githubToken.trim()}")
            }
            if (useGitHubAssetOctetStream) {
                reqBuilder.header("Accept", "application/octet-stream")
            }
            val dest = File(updateDir, "sonicpilot_update.apk")
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
                val body = resp.body ?: error("响应为空")
                val total = body.contentLength()
                var written = 0L
                var lastEmit = 0L
                body.byteStream().use { inp ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val r = inp.read(buf)
                            if (r == -1) break
                            out.write(buf, 0, r)
                            written += r
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 250L) {
                                lastEmit = now
                                val p = if (total > 0) (written.toFloat() / total.toFloat()).coerceIn(0.02f, 0.99f) else 0.5f
                                _state.value = _state.value.copy(
                                    downloadProgress = p,
                                    statusText = "正在下载更新 (${(p * 100).toInt()}%)"
                                )
                            }
                        }
                        out.flush()
                    }
                }
            }
            _state.value = _state.value.copy(
                isDownloading = false,
                downloadProgress = 1f,
                statusText = "下载完成，正在拉起系统原地覆盖安装..."
            )
            triggerSystemInstall(dest)
            dest
        }.onFailure { err ->
            _state.value = _state.value.copy(
                isDownloading = false,
                downloadProgress = 0f,
                statusText = "更新下载失败：${err.message}"
            )
        }
    }

    private fun triggerSystemInstall(apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    companion object {
        fun compareVersions(v1: String, v2: String): Int {
            val p1 = v1.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val p2 = v2.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val len = maxOf(p1.size, p2.size)
            for (i in 0 until len) {
                val a = p1.getOrElse(i) { 0 }
                val b = p2.getOrElse(i) { 0 }
                if (a != b) return a.compareTo(b)
            }
            return 0
        }
    }
}
