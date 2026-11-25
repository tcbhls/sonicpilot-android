package com.sonicpilot.music.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.sonicpilot.music.data.model.AppAccentStyle
import com.sonicpilot.music.data.model.AppThemeMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sonicpilot.music.BuildConfig
import com.sonicpilot.music.SonicPilotApp
import com.sonicpilot.music.ai.AppAgentToolRegistry
import com.sonicpilot.music.data.model.AiEngineMode
import com.sonicpilot.music.data.model.AudioQuality
import com.sonicpilot.music.data.model.CantonesePhoneticMode
import com.sonicpilot.music.data.model.ChannelType
import com.sonicpilot.music.data.model.LyricDisplayMode
import com.sonicpilot.music.data.model.MusicChannel
import com.sonicpilot.music.data.settings.SettingsStore
import com.sonicpilot.music.ui.components.QualityBadge
import com.sonicpilot.music.ui.theme.SonicColors
import kotlinx.coroutines.launch

/**
 * 收纳式极简「枢纽设置」二级页面（由各页右上角齿轮进入，不再挤占底部 Tab）
 * 清晰分段：AI 引擎 · 音乐渠道 · 音质与权限，并支持随时重玩游戏式新手教程
 */
@Composable
fun HubSettingsScreen(
    app: SonicPilotApp,
    onBack: () -> Unit,
    onReplayTutorial: () -> Unit,
    onPickLocalGgufFile: () -> Unit,
    onShowToast: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                app.localLlm.refreshStorageStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val channels by app.channelManager.channels.collectAsState()
    val aiConfig by app.settings.aiConfig.collectAsState()
    val localStatus by app.localLlm.status.collectAsState()
    val preferredQuality by app.settings.preferredQuality.collectAsState()
    val themeMode by app.settings.themeMode.collectAsState()
    val accentStyle by app.settings.accentStyle.collectAsState()
    val lyricDisplayMode by app.settings.lyricDisplayMode.collectAsState()
    val autoCantoneseJyutping by app.settings.autoCantoneseJyutping.collectAsState()
    val cantonesePhoneticMode by app.settings.cantonesePhoneticMode.collectAsState()
    val savedGithubToken by app.settings.githubUpdateToken.collectAsState()
    val updateState by app.appUpdater.state.collectAsState()
    val favoritesCount = app.repository.favorites.collectAsState().value.size
    var isSyncingSeed by remember { mutableStateOf(false) }
    val hasBundledSeed = remember {
        runCatching { app.assets.list("")?.contains("seed_favorites.json") == true }.getOrDefault(false)
    }

    var sectionTab by remember { mutableIntStateOf(0) }
    var githubTokenInput by remember(savedGithubToken) { mutableStateOf(savedGithubToken) }
    var directApkUrlInput by remember { mutableStateOf("") }
    var showTokenPlain by remember { mutableStateOf(false) }
    var showAddChannelDialog by remember { mutableStateOf(false) }
    var showImportSubDialog by remember { mutableStateOf(false) }
    var subUrlText by remember { mutableStateOf("") }

    var cloudKeyInput by remember(aiConfig.cloudApiKey) { mutableStateOf(aiConfig.cloudApiKey) }
    var cloudUrlInput by remember(aiConfig.cloudBaseUrl) { mutableStateOf(aiConfig.cloudBaseUrl) }
    var cloudModelInput by remember(aiConfig.cloudModel) { mutableStateOf(aiConfig.cloudModel) }
    var showKeyPlain by remember { mutableStateOf(false) }
    var cloudTestResult by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SonicColors.BgDeep)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = SonicColors.TextPrimary)
            }
            Spacer(Modifier.width(6.dp))
            Text("设置与枢纽", style = MaterialTheme.typography.headlineSmall, color = SonicColors.TextPrimary, modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(SonicColors.SurfaceCard)
                    .clickable {
                        onBack()
                        onReplayTutorial()
                    }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.School, null, tint = SonicColors.GoldAccent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("新手教程", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextPrimary)
            }
        }

        Spacer(Modifier.height(14.dp))

        // 极简三段暗石切换条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SonicColors.SurfaceCard)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("外观与音质", "AI 引擎", "音乐渠道").forEachIndexed { idx, label ->
                val active = sectionTab == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (active) SonicColors.SurfaceElevated else Color.Transparent)
                        .clickable { sectionTab = idx }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) SonicColors.TextPrimary else SonicColors.TextSecondary,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        when (sectionTab) {
            // ==================== Tab 0: 外观主题（浅色/深色/系统 + 强调色）、音质与 AI 权限 ====================
            0 -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 36.dp)
                ) {
                    item {
                        GroupCard("外观模式（浅色 / 深色 / 跟随系统）") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AppThemeMode.entries.forEach { m ->
                                    val active = themeMode == m
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (active) SonicColors.Accent else SonicColors.SurfaceElevated)
                                            .clickable {
                                                app.settings.setThemeMode(m)
                                                onShowToast("外观已设为：${m.label}")
                                            }
                                            .padding(vertical = 11.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            m.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (active) SonicColors.OnAccent else SonicColors.TextPrimary,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("主题强调色", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AppAccentStyle.entries.forEach { st ->
                                    val active = accentStyle == st
                                    val swatch = Color(st.hexColor)
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (active) swatch.copy(alpha = 0.14f) else SonicColors.SurfaceElevated)
                                            .clickable {
                                                app.settings.setAccentStyle(st)
                                                onShowToast("已切换主题色：${st.label}")
                                            }
                                            .padding(vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(swatch),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (active) {
                                                Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        Text(st.label.take(4), style = MaterialTheme.typography.labelSmall, color = if (active) swatch else SonicColors.TextSecondary)
                                    }
                                }
                            }
                        }
                    }

                    if (hasBundledSeed) {
                        item {
                            GroupCard("预置收藏曲库同步") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("当前「我喜欢」共 $favoritesCount 首", style = MaterialTheme.typography.bodyLarge, color = SonicColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                                        Text("已内置预置原唱/同版本收藏曲库，首次启动自动写入", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    enabled = !isSyncingSeed,
                                    onClick = {
                                        isSyncingSeed = true
                                        scope.launch {
                                            val count = app.repository.ensureSeededQqFavorites(app, app.settings, force = true)
                                            isSyncingSeed = false
                                            onShowToast("已重新同步并补全 $count 首预置收藏")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isSyncingSeed) "正在同步收藏..." else "重新同步 / 补全预置收藏")
                                }
                            }
                        }
                    }

                    item {
                        GroupCard("应用更新（原地覆盖升级）") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("音枢 AI 音乐 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge, color = SonicColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                                    Text(updateState.statusText, style = MaterialTheme.typography.labelSmall, color = SonicColors.Accent)
                                }
                                QualityBadge("覆盖安装")
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "包名固定为 com.sonicpilot.music，可直接从 GitHub Releases 免费检更新并原地覆盖升级，无须卸载重装。",
                                style = MaterialTheme.typography.labelSmall,
                                color = SonicColors.TextMuted
                            )
                            if (updateState.isDownloading) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { updateState.downloadProgress },
                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                    color = SonicColors.Accent
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = githubTokenInput,
                                onValueChange = { githubTokenInput = it },
                                label = { Text("GitHub Token（公开库免填，仅提速/私有 Fork 可选）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (showTokenPlain) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { showTokenPlain = !showTokenPlain }) {
                                        Text(if (showTokenPlain) "隐藏" else "显示")
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !updateState.isChecking && !updateState.isDownloading,
                                    onClick = {
                                        app.settings.setGithubUpdateToken(githubTokenInput)
                                        scope.launch {
                                            val res = app.appUpdater.checkGitHubRelease(githubToken = githubTokenInput)
                                            res.onSuccess { info ->
                                                if (info.hasNewerVersion) {
                                                    onShowToast("发现新版本 ${info.latestTag}")
                                                } else {
                                                    onShowToast("当前已是最新版本")
                                                }
                                            }.onFailure { err ->
                                                onShowToast(err.message ?: "检查更新失败")
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (updateState.isChecking) "检测中..." else "检查 GitHub 新版") }

                                val avail = updateState.availableUpdate
                                if (avail != null && avail.hasNewerVersion && (avail.apkAssetApiUrl.isNotBlank() || avail.apkDownloadUrl.isNotBlank())) {
                                    Button(
                                        enabled = !updateState.isDownloading,
                                        onClick = {
                                            scope.launch {
                                                val useApi = githubTokenInput.isNotBlank() && avail.apkAssetApiUrl.isNotBlank()
                                                val url = if (useApi) avail.apkAssetApiUrl else avail.apkDownloadUrl
                                                app.appUpdater.downloadAndInstallApk(
                                                    url = url,
                                                    githubToken = githubTokenInput,
                                                    useGitHubAssetOctetStream = useApi
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("下载并覆盖装") }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = directApkUrlInput,
                                onValueChange = { directApkUrlInput = it },
                                label = { Text("或粘贴任意新版 APK 直链原地安装") },
                                placeholder = { Text("https://.../SonicPilot-release.apk") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    TextButton(
                                        enabled = directApkUrlInput.startsWith("http") && !updateState.isDownloading,
                                        onClick = {
                                            scope.launch {
                                                app.appUpdater.downloadAndInstallApk(directApkUrlInput)
                                            }
                                        }
                                    ) { Text("安装") }
                                }
                            )
                        }
                    }

                    item {
                        GroupCard("歌词与语言学习偏好") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("粤语歌自动逐字注音（粤拼）", color = SonicColors.TextPrimary)
                                    Text("内置 21,574 字离线粤拼词库，注音紧贴每字下方", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary)
                                }
                                Switch(
                                    checked = autoCantoneseJyutping,
                                    onCheckedChange = { app.settings.setAutoCantoneseJyutping(it) }
                                )
                            }
                            Text("粤语注音风格（QQ 音乐双模式）", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CantonesePhoneticMode.entries.forEach { pm ->
                                    val active = cantonesePhoneticMode == pm
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (active) SonicColors.Accent else SonicColors.SurfaceElevated)
                                            .clickable {
                                                app.settings.setCantonesePhoneticMode(pm)
                                                onShowToast("默认粤语注音：${pm.label}")
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            pm.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (active) SonicColors.OnAccent else SonicColors.TextPrimary,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                            Text("外语 / 英文歌默认歌词展示", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LyricDisplayMode.entries.forEach { m ->
                                    val active = lyricDisplayMode == m
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (active) SonicColors.Accent else SonicColors.SurfaceElevated)
                                            .clickable {
                                                app.settings.setLyricDisplayMode(m)
                                                onShowToast("默认歌词显示：${m.label}")
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            m.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (active) SonicColors.OnAccent else SonicColors.TextPrimary,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        GroupCard("默认音质（播放 & 下载）") {
                            AudioQuality.entries.forEach { q ->
                                val active = preferredQuality == q
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (active) SonicColors.SurfaceElevated else Color.Transparent)
                                        .clickable {
                                            app.settings.setPreferredQuality(q)
                                            onShowToast("已设为 ${q.displayName}")
                                        }
                                        .padding(horizontal = 10.dp, vertical = 11.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(q.displayName, color = if (active) SonicColors.Accent else SonicColors.TextPrimary)
                                    QualityBadge(q.badge)
                                }
                            }
                        }
                    }

                    item {
                        GroupCard("AI App 内权限沙箱清单") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("允许 AI 联网搜歌与歌词寻曲", color = SonicColors.TextPrimary)
                                Switch(
                                    checked = aiConfig.enableWebSearchForAi,
                                    onCheckedChange = { enable -> app.settings.updateAiConfig { it.copy(enableWebSearchForAi = enable) } }
                                )
                            }
                            AppAgentToolRegistry.APP_PERMISSION_MANIFEST.forEach { (title, desc) ->
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text("✓ $title", color = SonicColors.Accent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(desc, color = SonicColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            // ==================== Tab 1: AI 引擎（本地离线 + 免费/付费云端） ====================
            1 -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 36.dp)
                ) {
                    item {
                        GroupCard("运行模式") {
                            AiEngineMode.entries.forEach { mode ->
                                val active = aiConfig.engineMode == mode
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (active) SonicColors.SurfaceElevated else Color.Transparent)
                                        .clickable {
                                            app.settings.updateAiConfig { it.copy(engineMode = mode) }
                                            onShowToast("已切换：${mode.label}")
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(mode.label, color = if (active) SonicColors.GoldAccent else SonicColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(mode.description, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextMuted)
                                    }
                                    if (active) Icon(Icons.Rounded.CheckCircle, null, tint = SonicColors.GoldAccent, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    item {
                        GroupCard("云端免费 & 付费 API（OpenAI 兼容）") {
                            SettingsStore.CLOUD_AI_PRESETS.forEach { preset ->
                                val active = aiConfig.cloudPresetId == preset.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (active) SonicColors.SurfaceElevated else Color.Transparent)
                                        .clickable {
                                            cloudUrlInput = preset.baseUrl
                                            cloudModelInput = preset.defaultModel
                                            app.settings.updateAiConfig {
                                                it.copy(
                                                    cloudPresetId = preset.id,
                                                    cloudBaseUrl = preset.baseUrl,
                                                    cloudModel = preset.defaultModel
                                                )
                                            }
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(preset.name, style = MaterialTheme.typography.bodyMedium, color = if (active) SonicColors.GoldAccent else SonicColors.TextPrimary)
                                        Text(preset.description, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextMuted)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    QualityBadge(preset.tag.substringAfter("· "))
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(value = cloudUrlInput, onValueChange = { cloudUrlInput = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = cloudModelInput, onValueChange = { cloudModelInput = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            val activePreset = SettingsStore.CLOUD_AI_PRESETS.firstOrNull { it.id == aiConfig.cloudPresetId }
                            OutlinedTextField(
                                value = cloudKeyInput,
                                onValueChange = { cloudKeyInput = it },
                                label = { Text("API Key（本地安全保存，不上传任何第三方）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (showKeyPlain) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { showKeyPlain = !showKeyPlain }) {
                                        Text(if (showKeyPlain) "隐藏" else "显示")
                                    }
                                }
                            )
                            if (activePreset != null && activePreset.keyHelpUrl.startsWith("http")) {
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        text = "前往官网获取免费 API Key ↗",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SonicColors.Accent,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(activePreset.keyHelpUrl)))
                                                }.onFailure {
                                                    onShowToast("请用浏览器访问：${activePreset.keyHelpUrl}")
                                                }
                                            }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        app.settings.updateAiConfig {
                                            it.copy(
                                                cloudBaseUrl = cloudUrlInput.trim(),
                                                cloudModel = cloudModelInput.trim(),
                                                cloudApiKey = cloudKeyInput.trim()
                                            )
                                        }
                                        onShowToast("API 配置已保存")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("保存") }
                                OutlinedButton(
                                    onClick = {
                                        val testCfg = aiConfig.copy(
                                            cloudBaseUrl = cloudUrlInput.trim(),
                                            cloudModel = cloudModelInput.trim(),
                                            cloudApiKey = cloudKeyInput.trim()
                                        )
                                        cloudTestResult = "正在测试..."
                                        scope.launch {
                                            val res = app.cloudLlm.testConnection(testCfg)
                                            cloudTestResult = res.getOrElse { "失败：${it.message}" }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("测试连接") }
                            }
                            if (cloudTestResult.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(cloudTestResult, style = MaterialTheme.typography.labelSmall, color = SonicColors.GoldAccent)
                            }
                        }
                    }

                    item {
                        GroupCard("本地离线大模型（共享目录 Download/LocalAI · llama.cpp）") {
                            val refreshTick = localStatus.storageRefreshTick
                            Text(localStatus.statusMessage, style = MaterialTheme.typography.labelMedium, color = SonicColors.GoldAccent)
                            Spacer(Modifier.height(8.dp))

                            // 公共共享目录权限状态条（开启后与手机内其它本地 AI 应用零拷贝共用 GGUF 模型）
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (localStatus.sharedStorageGranted) SonicColors.Accent.copy(alpha = 0.10f)
                                        else SonicColors.SurfaceElevated
                                    )
                                    .padding(11.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (localStatus.sharedStorageGranted) "✓ 已连通公共模型目录（Download/LocalAI）"
                                            else "未开启公共模型目录权限",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (localStatus.sharedStorageGranted) SonicColors.Accent else SonicColors.TextPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            if (localStatus.sharedStorageGranted) "下载或放入 Download/LocalAI 的 GGUF 模型可直接识别，零拷贝复用"
                                            else "授权「所有文件访问」后，可将大模型存放在公共目录 Download/LocalAI，卸载重装不丢模型",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SonicColors.TextSecondary
                                        )
                                    }
                                    if (!localStatus.sharedStorageGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        Spacer(Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                runCatching {
                                                    val intent = Intent(
                                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                        Uri.parse("package:${context.packageName}")
                                                    )
                                                    context.startActivity(intent)
                                                }.onFailure {
                                                    runCatching {
                                                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                                    }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                        ) { Text("去开启", style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                            }

                            if (localStatus.downloadingModelId != null) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { localStatus.downloadProgress },
                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                    color = SonicColors.GoldAccent
                                )
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    val speedStr = if (localStatus.downloadSpeedMbps > 0.05f) " · ${String.format("%.1f", localStatus.downloadSpeedMbps)} MB/s" else ""
                                    Text("${localStatus.downloadedMb.toInt()} / ${localStatus.totalMb.toInt()} MB$speedStr", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary)
                                    TextButton(onClick = { app.localLlm.cancelDownload() }) { Text("暂停", color = SonicColors.RoseFavorite) }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            SettingsStore.LOCAL_MODEL_CATALOG.forEach { spec ->
                                val modelFile = remember(spec.fileName, refreshTick, localStatus.downloadingModelId) {
                                    app.localLlm.findModelFile(spec.fileName)
                                }
                                val downloaded = modelFile != null
                                val isSharedFile = modelFile != null && app.localLlm.isFileInSharedDir(modelFile)
                                val isLoaded = localStatus.isModelLoaded && modelFile != null && localStatus.loadedModelPath == modelFile.absolutePath
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SonicColors.SurfaceElevated)
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(spec.name, style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary, modifier = Modifier.weight(1f))
                                        if (isSharedFile) QualityBadge("共享目录")
                                        else if (spec.recommended) QualityBadge("推荐")
                                    }
                                    val sizeLabel = if (spec.sizeMb >= 1024) "${String.format("%.2f", spec.sizeMb / 1024f)}GB" else "${spec.sizeMb}MB"
                                    Text("$sizeLabel · ${spec.description}", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        when {
                                            isLoaded -> OutlinedButton(onClick = { app.localLlm.unloadModel() }) { Text("释放内存") }
                                            downloaded && modelFile != null -> {
                                                Button(onClick = {
                                                    scope.launch { app.localLlm.loadModel(modelFile.absolutePath, spec.name) }
                                                }) { Text("加载运行") }
                                                TextButton(onClick = {
                                                    app.localLlm.deleteModelFile(modelFile)
                                                    onShowToast("已删除模型")
                                                }) { Text("删除", color = SonicColors.RoseFavorite) }
                                            }
                                            else -> Button(enabled = localStatus.downloadingModelId == null, onClick = { app.localLlm.downloadModel(spec) }) {
                                                Text("魔搭高速下载 ($sizeLabel)")
                                            }
                                        }
                                    }
                                }
                            }
                            // 展示共享/本地目录中其他用户自行放入的 GGUF 模型
                            val extraFiles = remember(refreshTick, localStatus.downloadingModelId) {
                                val catalogNames = SettingsStore.LOCAL_MODEL_CATALOG.map { it.fileName }.toSet()
                                app.localLlm.listDownloadedGgufFiles().filter { it.name !in catalogNames }
                            }
                            extraFiles.forEach { extra ->
                                val isLoaded = localStatus.isModelLoaded && localStatus.loadedModelPath == extra.absolutePath
                                val mb = (extra.length() / (1024L * 1024L)).toInt()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SonicColors.SurfaceElevated)
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(extra.name, style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary, modifier = Modifier.weight(1f))
                                        QualityBadge(if (app.localLlm.isFileInSharedDir(extra)) "共享目录" else "已导入")
                                    }
                                    Text("${mb}MB · 自定义 GGUF 模型", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextSecondary)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (isLoaded) {
                                            OutlinedButton(onClick = { app.localLlm.unloadModel() }) { Text("释放内存") }
                                        } else {
                                            Button(onClick = {
                                                scope.launch { app.localLlm.loadModel(extra.absolutePath, extra.name) }
                                            }) { Text("加载运行") }
                                            TextButton(onClick = {
                                                app.localLlm.deleteModelFile(extra)
                                                onShowToast("已删除 ${extra.name}")
                                            }) { Text("删除", color = SonicColors.RoseFavorite) }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = onPickLocalGgufFile, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("导入手机本地 .gguf 文件（支持同步到共享目录）")
                            }
                        }
                    }
                }
            }

            // ==================== Tab 2: 音乐渠道插件 ====================
            2 -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 36.dp)
                ) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { showAddChannelDialog = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("新增渠道")
                            }
                            OutlinedButton(onClick = { showImportSubDialog = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.CloudDownload, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("订阅导入")
                            }
                        }
                    }

                    items(channels, key = { it.id }) { ch ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SonicColors.SurfaceCard)
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(ch.name, style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary)
                                        if (ch.isPrimary) QualityBadge("主渠道")
                                    }
                                    Text(
                                        "${ch.type.label} · ${ch.sourceKey}" + if (ch.lastLatencyMs > 0) " · ${ch.lastLatencyMs}ms" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SonicColors.TextSecondary
                                    )
                                }
                                Switch(checked = ch.enabled, onCheckedChange = { app.channelManager.toggleChannelEnabled(ch.id, it) })
                            }
                            if (ch.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(ch.description, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextMuted)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!ch.isPrimary) {
                                    TextButton(onClick = {
                                        app.channelManager.setPrimaryChannel(ch.id)
                                        onShowToast("已设为主渠道")
                                    }) {
                                        Icon(Icons.Rounded.Star, null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("设为主源")
                                    }
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        val ms = app.channelManager.testChannelLatency(ch.id)
                                        onShowToast(if (ms >= 0) "延迟 ${ms}ms" else "当前不可达")
                                    }
                                }) {
                                    Icon(Icons.Rounded.Speed, null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("测速")
                                }
                                if (!ch.isBuiltIn) {
                                    IconButton(onClick = { app.channelManager.deleteChannel(ch.id) }) {
                                        Icon(Icons.Rounded.DeleteOutline, "删除", tint = SonicColors.RoseFavorite, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    if (showAddChannelDialog) {
        AddChannelDialog(
            onDismiss = { showAddChannelDialog = false },
            onSave = { ch ->
                app.channelManager.addOrUpdateChannel(ch)
                showAddChannelDialog = false
                onShowToast("已添加渠道：${ch.name}")
            }
        )
    }

    if (showImportSubDialog) {
        AlertDialog(
            onDismissRequest = { showImportSubDialog = false },
            containerColor = SonicColors.SurfaceElevated,
            title = { Text("JSON 订阅导入渠道") },
            text = {
                OutlinedTextField(
                    value = subUrlText,
                    onValueChange = { subUrlText = it },
                    placeholder = { Text("https://... 或粘贴 JSON") },
                    maxLines = 4
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val raw = subUrlText.trim()
                    if (raw.isBlank()) return@TextButton
                    scope.launch {
                        val res = if (raw.startsWith("http")) app.channelManager.importChannelsFromJsonUrl(raw)
                        else app.channelManager.importChannelsFromJsonText(raw)
                        res.onSuccess { list ->
                            showImportSubDialog = false
                            subUrlText = ""
                            onShowToast("成功导入 ${list.size} 个渠道")
                        }.onFailure { err -> onShowToast("导入失败：${err.message}") }
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImportSubDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun GroupCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SonicColors.SurfaceCard)
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun AddChannelDialog(
    onDismiss: () -> Unit,
    onSave: (MusicChannel) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ChannelType.GDSTUDIO) }
    var baseUrl by remember { mutableStateOf("https://") }
    var sourceKey by remember { mutableStateOf("netease") }
    var apiKey by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SonicColors.SurfaceElevated,
        title = { Text("新增音乐渠道") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("渠道名称") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChannelType.entries.forEach { t ->
                        val active = selectedType == t
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) SonicColors.GoldAccent else SonicColors.SurfaceCard)
                                .clickable { selectedType = t }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(t.name, color = if (active) SonicColors.BgDeep else SonicColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API URL") }, singleLine = true)
                OutlinedTextField(value = sourceKey, onValueChange = { sourceKey = it }, label = { Text("平台标识 (netease/joox/kuwo)") }, singleLine = true)
                if (selectedType == ChannelType.SUBSONIC) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, singleLine = true)
                    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("密码") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && baseUrl.startsWith("http")) {
                        onSave(
                            MusicChannel(
                                id = "",
                                name = name.trim(),
                                type = selectedType,
                                baseUrl = baseUrl.trim(),
                                sourceKey = sourceKey.trim().ifBlank { "netease" },
                                enabled = true,
                                isPrimary = false,
                                isBuiltIn = false,
                                apiKey = apiKey.trim(),
                                username = username.trim(),
                                description = "用户自定义扩展渠道"
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SonicColors.PearlWhite)
            ) { Text("添加", color = SonicColors.BgDeep) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
