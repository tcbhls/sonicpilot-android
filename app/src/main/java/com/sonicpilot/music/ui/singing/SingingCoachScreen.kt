package com.sonicpilot.music.ui.singing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicpilot.music.SonicPilotApp
import com.sonicpilot.music.data.model.PitchSample
import com.sonicpilot.music.data.model.SingingEvaluation
import com.sonicpilot.music.ui.components.formatDuration
import com.sonicpilot.music.ui.theme.SonicColors
import java.io.File
import kotlin.math.abs

/**
 * 极简高级「声乐棚」：
 * - 单一视觉重心：居中大音名 + 水平音分准星 + 柔金实时音高曲线 + 大留白圆形录音开关
 * - 录完即时呈现极简评分卡，一键交给 AI 导师做深度声乐点评
 */
@Composable
fun SingingCoachScreen(
    app: SonicPilotApp,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onAskAiToCritique: (SingingEvaluation) -> Unit,
    onShowToast: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val live by app.singingCoach.liveState.collectAsState()
    val playback by app.playerController.state.collectAsState()
    val progress by app.playerController.progress.collectAsState()
    val history by app.repository.evaluations.collectAsState()

    var singAlongWithTrack by remember { mutableStateOf(false) }

    val curSong = playback.currentSong
    val curLyricLine = remember(progress.positionMs, playback.lyrics) {
        playback.lyrics.lines.lastOrNull { it.timeMs <= progress.positionMs + 250L }
    }
    val curLyric = curLyricLine?.text.orEmpty()
    val filteredHistory = remember(history, live.latestEvaluation) {
        val latestId = live.latestEvaluation?.id
        if (latestId == null) history else history.filterNot { it.id == latestId }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SonicColors.BgDeep)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 130.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("声乐棚", style = MaterialTheme.typography.headlineMedium, color = SonicColors.TextPrimary)
                    Text("端侧实时音准仪 · AI 唱功导师", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Tune, "设置", tint = SonicColors.TextSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }

        // 核心音准仪与录制控制台
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(SonicColors.SurfaceCard)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (live.isRecording) "录制中 · ${formatDuration(live.elapsedMs)}" else "点击下方麦克风开始试唱",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (live.isRecording) SonicColors.RoseFavorite else SonicColors.TextSecondary
                )

                Spacer(Modifier.height(10.dp))

                // 居中特大音名
                Text(
                    text = live.currentNote,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (live.currentFreqHz > 0f && abs(live.currentCentsOffset) <= 25f) {
                        SonicColors.Accent
                    } else SonicColors.TextPrimary
                )

                Text(
                    text = if (live.currentFreqHz > 0f) {
                        val sign = if (live.currentCentsOffset >= 0) "+" else ""
                        "${live.currentFreqHz.toInt()} Hz · $sign${live.currentCentsOffset.toInt()} cents"
                    } else "实时 YIN 音高检测就绪",
                    style = MaterialTheme.typography.labelMedium,
                    color = SonicColors.TextSecondary
                )

                Spacer(Modifier.height(16.dp))

                // 柔金实时音高曲线 Canvas
                MinimalPitchCurve(
                    samples = live.recentPitchHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SonicColors.BgDeep)
                )

                // 跟唱提词显示
                if (singAlongWithTrack && curLyric.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = curLyric,
                        style = MaterialTheme.typography.titleMedium,
                        color = SonicColors.Accent,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(20.dp))

                // 极简录音大圆按钮
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(if (live.isRecording) SonicColors.RoseFavorite else SonicColors.Accent)
                        .clickable {
                            if (!hasAudioPermission) {
                                onRequestAudioPermission()
                                return@clickable
                            }
                            if (live.isRecording) {
                                if (singAlongWithTrack) app.playerController.pause()
                                scope.launch {
                                    val eval = app.singingCoach.stopAndEvaluateSafe()
                                    if (eval != null) onShowToast("评测完成：${eval.overallScore} 分")
                                }
                            } else {
                                val title = if (singAlongWithTrack && curSong != null) curSong.title else "自由清唱"
                                val artist = if (singAlongWithTrack && curSong != null) curSong.artist else ""
                                if (singAlongWithTrack && curSong != null) app.playerController.play() else app.playerController.pause()
                                app.singingCoach.startRecording(
                                    songTitle = title,
                                    songArtist = artist,
                                    songId = curSong?.id.orEmpty(),
                                    lyrics = if (singAlongWithTrack) playback.lyrics.lines else emptyList()
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (live.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = "录音",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 伴奏跟唱开关
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SonicColors.SurfaceElevated)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (curSong != null) "跟唱《${curSong.title}》" else "清唱模式（播歌后可跟唱）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonicColors.TextPrimary
                        )
                        Text("建议佩戴耳机以获得最准人声提取", style = MaterialTheme.typography.labelSmall, color = SonicColors.TextMuted)
                    }
                    Switch(
                        checked = singAlongWithTrack && curSong != null,
                        enabled = curSong != null && !live.isRecording,
                        onCheckedChange = { singAlongWithTrack = it }
                    )
                }

                live.errorMessage?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(err, color = SonicColors.RoseFavorite, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // 最新评测卡片
        live.latestEvaluation?.let { ev ->
            item {
                MinimalScoreCard(
                    evaluation = ev,
                    onReplay = { app.playerController.playLocalAudioFile(File(ev.audioFilePath), "练唱回放：${ev.songTitle}") },
                    onAskAi = { onAskAiToCritique(ev) },
                    onDelete = null
                )
            }
        }

        // 历史练唱档案（自动排除上方已展示的最新一条，避免两张完全一样的评分卡叠在一起）
        if (filteredHistory.isNotEmpty()) {
            item {
                Text("历史练唱档案 · ${filteredHistory.size}", style = MaterialTheme.typography.labelMedium, color = SonicColors.TextSecondary)
            }
            items(filteredHistory, key = { it.id }) { ev ->
                MinimalScoreCard(
                    evaluation = ev,
                    onReplay = { app.playerController.playLocalAudioFile(File(ev.audioFilePath), "练唱回放：${ev.songTitle}") },
                    onAskAi = { onAskAiToCritique(ev) },
                    onDelete = {
                        app.repository.deleteSingingEvaluation(ev.id)
                        onShowToast("已删除记录")
                    }
                )
            }
        }
    }
}

@Composable
private fun MinimalPitchCurve(
    samples: List<PitchSample>,
    modifier: Modifier = Modifier
) {
    val lineColor = SonicColors.Hairline
    val curveColor = SonicColors.Accent
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 极淡参考中线
        drawLine(
            color = lineColor,
            start = Offset(0f, h / 2f),
            end = Offset(w, h / 2f),
            strokeWidth = 1f
        )
        if (samples.size < 2) return@Canvas
        val path = Path()
        var started = false
        val stepX = w / (samples.size - 1).coerceAtLeast(1)

        samples.forEachIndexed { idx, s ->
            if (s.frequencyHz > 0f && s.midiNote in 38f..86f) {
                val x = idx * stepX
                val norm = ((s.midiNote - 40f) / 40f).coerceIn(0f, 1f)
                val y = h - norm * (h - 20f) - 10f
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            } else {
                started = false
            }
        }
        drawPath(
            path = path,
            color = curveColor,
            style = Stroke(width = 3.5f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun MinimalScoreCard(
    evaluation: SingingEvaluation,
    onReplay: () -> Unit,
    onAskAi: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SonicColors.SurfaceCard)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${evaluation.overallScore}",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = SonicColors.GoldAccent
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(evaluation.songTitle, style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary)
                Text(
                    "音域 ${evaluation.lowestNote}~${evaluation.highestNote} · ${evaluation.estimatedKey}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SonicColors.TextSecondary
                )
            }
            IconButton(onClick = onReplay, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.PlayArrow, "回放", tint = SonicColors.TextPrimary)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, "删除", tint = SonicColors.TextMuted, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MinimalMetric("音准", evaluation.pitchAccuracyScore)
            MinimalMetric("稳定", evaluation.pitchStabilityScore)
            MinimalMetric("节奏", evaluation.rhythmScore)
            MinimalMetric("气息", evaluation.breathControlScore)
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SonicColors.Accent)
                .clickable { onAskAi() }
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = SonicColors.OnAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("让 AI 声乐导师深度点评", style = MaterialTheme.typography.labelMedium, color = SonicColors.OnAccent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MinimalMetric(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.titleMedium, color = SonicColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = SonicColors.TextMuted)
    }
}
