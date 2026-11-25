package com.sonicpilot.music.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicpilot.music.ui.theme.SonicColors

data class QuestStep(
    val stageCode: String,
    val title: String,
    val subtitle: String,
    val tips: List<String>,
    val icon: ImageVector,
    val targetTabRoute: String,
    val actionLabel: String? = null
)

val SONIC_QUEST_STEPS = listOf(
    QuestStep(
        stageCode = "QUEST 01 / 05",
        title = "免费无损，一搜即听",
        subtitle = "这是「发现」页——内置大陆直连 Hi-Res 无损主源，涵盖华语、欧美 Billboard 与日韩热榜。",
        tips = listOf(
            "顶部搜索框搜全网歌曲，搜索瞬间后台并行预热，点歌秒开",
            "每首歌右侧直接有「MV」一键看横屏高清视频，点「···」可收藏/下无损"
        ),
        icon = Icons.Rounded.Explore,
        targetTabRoute = "discover",
        actionLabel = "试播榜首无损热歌"
    ),
    QuestStep(
        stageCode = "QUEST 02 / 05",
        title = "全屏沉浸歌词 · 专属学歌室",
        subtitle = "点底部播放条即可在「黑胶 / 沉浸词 / 学歌室 / MV」四种视图间丝滑切换。",
        tips = listOf(
            "粤语歌自动每字下注音，一键切换「标准粤拼 / QQ 谐音」；英文歌支持双语/仅英文切换",
            "学歌室：0.5x~1.25x 原调慢放 + 单句循环复读 + AI 拆解唱法"
        ),
        icon = Icons.Rounded.Lyrics,
        targetTabRoute = "discover",
        actionLabel = "打开学歌室看一眼"
    ),
    QuestStep(
        stageCode = "QUEST 03 / 05",
        title = "全权 AI 音乐管家",
        subtitle = "「AI 管家」已获本 App 内全部 25 项音乐控制权限（品味策展、原唱搜歌、插队、定时、看 MV、批量下载与评唱功），零配置即可直接响应日常指令。",
        tips = listOf(
            "支持配置免费云端 API（智谱 GLM / 硅基流动 / Gemini）或下载离线 Qwen3 模型解锁完整乐评大脑",
            "严格权限沙箱，只管音乐 App，不碰手机任何外部隐私"
        ),
        icon = Icons.Rounded.AutoAwesome,
        targetTabRoute = "ai",
        actionLabel = "体验一句 AI 点歌"
    ),
    QuestStep(
        stageCode = "QUEST 04 / 05",
        title = "声乐棚：让 AI 评判你的唱功",
        subtitle = "切到「声乐棚」录一段清唱或戴耳机跟唱，端侧 YIN 引擎会实时画出你的音准曲线。",
        tips = listOf(
            "自动测出音准、稳定度、节奏、气息、音域与跑调时间点",
            "点击「让 AI 声乐导师深度点评」获取针对性练声建议"
        ),
        icon = Icons.Rounded.Mic,
        targetTabRoute = "singing"
    ),
    QuestStep(
        stageCode = "QUEST 05 / 05",
        title = "自由加渠道 · 离线本地 AI",
        subtitle = "所有进阶配置都安静收纳在各页右上角的「设置与枢纽」图标中，支持 QQ / 网易云歌单链接一键迁移。",
        tips = listOf(
            "可随时新增外部免费音乐渠道、Subsonic 私人库或 JSON 订阅",
            "支持一键魔搭下载 Qwen3-8B / 4B / 1.7B 本地离线模型，或切换任意 OpenAI 兼容 API"
        ),
        icon = Icons.Rounded.Tune,
        targetTabRoute = "discover"
    )
)

/**
 * 游戏关卡式分步交互新手教程浮层
 * - 实时联动切换背景真实页面，让用户边看真界面边一步步通关上手
 * - 支持每关真实交互体验、上一步/下一步、随时跳过与通关激励
 */
@Composable
fun OnboardingQuestOverlay(
    stepIndex: Int,
    onPrev: () -> Unit,
    onNextOrFinish: () -> Unit,
    onSkip: () -> Unit,
    onTriggerQuestAction: (Int) -> Unit
) {
    val step = SONIC_QUEST_STEPS.getOrNull(stepIndex) ?: return
    val isFirst = stepIndex == 0
    val isLast = stepIndex == SONIC_QUEST_STEPS.lastIndex
    val progress = (stepIndex + 1).toFloat() / SONIC_QUEST_STEPS.size.toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3050507))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        // 顶部跳过与进度条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SonicColors.GoldAccent)
                )
                Text(
                    text = step.stageCode,
                    style = MaterialTheme.typography.labelMedium,
                    color = SonicColors.GoldAccent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Text(
                text = "跳过教程",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFDCE0EA),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSkip() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // 底部优雅任务引导卡（透出上方真实 App 页面结构，边看边学）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SonicColors.SurfaceCard)
                .padding(22.dp)
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = SonicColors.GoldAccent,
                trackColor = SonicColors.Hairline
            )

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(SonicColors.GoldAccent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(step.icon, null, tint = SonicColors.GoldAccent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = SonicColors.TextPrimary
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = step.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = SonicColors.TextSecondary
            )

            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SonicColors.BgDeep)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                step.tips.forEach { tip ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("·", color = SonicColors.GoldAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                        Text(tip, style = MaterialTheme.typography.bodyMedium, color = SonicColors.TextPrimary)
                    }
                }
            }

            // 本关可选实战体验按钮
            if (!step.actionLabel.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SonicColors.GoldAccent.copy(alpha = 0.16f))
                        .clickable { onTriggerQuestAction(stepIndex) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✦ ${step.actionLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = SonicColors.GoldAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // 关卡步进控制栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isFirst) {
                    Text(
                        text = "← 上一关",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonicColors.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPrev() }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(SonicColors.Accent)
                        .clickable { onNextOrFinish() }
                        .padding(horizontal = 24.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = if (isLast) "通关 · 开始听歌" else "下一关 →",
                        style = MaterialTheme.typography.labelMedium,
                        color = SonicColors.OnAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
