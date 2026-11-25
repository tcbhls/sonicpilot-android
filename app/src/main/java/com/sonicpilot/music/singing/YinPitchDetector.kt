package com.sonicpilot.music.singing

import com.sonicpilot.music.data.model.LyricLine
import com.sonicpilot.music.data.model.PitchSample
import com.sonicpilot.music.data.model.SingingEvaluation
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 端侧声乐 DSP 引擎（纯 Kotlin 零外库依赖，可单元测试验证精度）
 * 1. YIN 基频检测（含 CMNDF + 抛物线插值，覆盖男低音 75Hz 到女高音 1150Hz）
 * 2. 十二平均律音名 & 音分偏移（Cents）
 * 3. Krumhansl-Schmuckler 调性估计（24 大小调匹配）
 * 4. 颤音（Vibrato）与气息稳定性分析
 * 5. 多维声乐评测报告生成（供 UI 展示与 AI 声乐导师结合数据点评）
 */
object YinPitchDetector {

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    // Krumhansl-Schmuckler 大小调音级权重模板
    private val MAJOR_PROFILE = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
    private val MINOR_PROFILE = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)

    data class DetectionResult(
        val frequencyHz: Float,
        val confidence: Float,
        val rmsDb: Float
    )

    /**
     * 对一帧 PCM 浮点音频（[-1f, 1f]）执行 YIN 基频检测
     */
    fun detectPitch(
        buffer: FloatArray,
        sampleRate: Int = 44100,
        threshold: Float = 0.16f,
        minFreqHz: Float = 75f,
        maxFreqHz: Float = 1150f
    ): DetectionResult {
        val n = buffer.size
        if (n < 512) return DetectionResult(0f, 0f, -90f)

        // 1. 计算 RMS 响度（dBFS），静音帧直接过滤
        var sumSq = 0.0
        for (v in buffer) sumSq += v * v
        val rms = sqrt(sumSq / n).toFloat()
        val rmsDb = if (rms > 1e-6f) (20.0 * log10(rms.toDouble())).toFloat() else -90f
        if (rmsDb < -46f) {
            return DetectionResult(0f, 0f, rmsDb)
        }

        val halfSize = n / 2
        val minTau = (sampleRate / maxFreqHz).toInt().coerceAtLeast(2)
        val maxTau = (sampleRate / minFreqHz).toInt().coerceAtMost(halfSize - 1)
        if (minTau >= maxTau) return DetectionResult(0f, 0f, rmsDb)

        val diff = FloatArray(maxTau + 1)
        val cmndf = FloatArray(maxTau + 1)

        // 2. 差分函数 d(tau)
        for (tau in 1..maxTau) {
            var sum = 0f
            for (i in 0 until halfSize) {
                val delta = buffer[i] - buffer[i + tau]
                sum += delta * delta
            }
            diff[tau] = sum
        }

        // 3. 累积均值归一化差分函数 d'(tau)
        cmndf[0] = 1f
        var runningSum = 0f
        for (tau in 1..maxTau) {
            runningSum += diff[tau]
            cmndf[tau] = if (runningSum > 0f) diff[tau] * tau / runningSum else 1f
        }

        // 4. 寻找首个低于阈值的局部极小值
        var tauEstimate = -1
        var tau = minTau
        while (tau < maxTau) {
            if (cmndf[tau] < threshold) {
                while (tau + 1 <= maxTau && cmndf[tau + 1] < cmndf[tau]) {
                    tau++
                }
                tauEstimate = tau
                break
            }
            tau++
        }

        // 若无低于阈值，取全局最小（低置信度）
        if (tauEstimate == -1) {
            var bestVal = Float.MAX_VALUE
            for (t in minTau..maxTau) {
                if (cmndf[t] < bestVal) {
                    bestVal = cmndf[t]
                    tauEstimate = t
                }
            }
            if (bestVal > 0.38f) {
                return DetectionResult(0f, (1f - bestVal).coerceIn(0f, 1f), rmsDb)
            }
        }

        // 5. 抛物线插值提高频率精度
        val betterTau = if (tauEstimate in (minTau + 1) until maxTau) {
            val s0 = cmndf[tauEstimate - 1]
            val s1 = cmndf[tauEstimate]
            val s2 = cmndf[tauEstimate + 1]
            val denom = 2f * (2f * s1 - s2 - s0)
            if (abs(denom) > 1e-6f) tauEstimate + (s2 - s0) / denom else tauEstimate.toFloat()
        } else {
            tauEstimate.toFloat()
        }

        val freq = sampleRate / betterTau
        val confidence = (1f - cmndf[tauEstimate]).coerceIn(0f, 1f)
        return if (freq in minFreqHz..maxFreqHz) {
            DetectionResult(freq, confidence, rmsDb)
        } else {
            DetectionResult(0f, 0f, rmsDb)
        }
    }

    fun frequencyToMidi(freqHz: Float): Float {
        if (freqHz <= 0f) return 0f
        return (69.0 + 12.0 * ln(freqHz / 440.0) / ln(2.0)).toFloat()
    }

    fun midiToNoteName(midiRounded: Int): String {
        if (midiRounded <= 0) return "--"
        val note = NOTE_NAMES[((midiRounded % 12) + 12) % 12]
        val octave = (midiRounded / 12) - 1
        return "$note$octave"
    }

    fun toPitchSample(timeMs: Long, det: DetectionResult): PitchSample {
        if (det.frequencyHz <= 0f || det.confidence < 0.62f) {
            return PitchSample(
                timeMs = timeMs,
                frequencyHz = 0f,
                confidence = det.confidence,
                rmsDb = det.rmsDb,
                midiNote = 0f,
                noteName = "--",
                centsOffset = 0f
            )
        }
        val midi = frequencyToMidi(det.frequencyHz)
        val nearest = midi.roundToInt()
        val cents = (midi - nearest) * 100f
        return PitchSample(
            timeMs = timeMs,
            frequencyHz = det.frequencyHz,
            confidence = det.confidence,
            rmsDb = det.rmsDb,
            midiNote = midi,
            noteName = midiToNoteName(nearest),
            centsOffset = cents
        )
    }

    /**
     * Krumhansl-Schmuckler 调性估算（基于全部有声帧的音级直方图相关性）
     */
    fun estimateKey(voicedSamples: List<PitchSample>): String {
        if (voicedSamples.size < 8) return "样本较短，暂未判定调性"
        val hist = FloatArray(12)
        for (s in voicedSamples) {
            val pc = ((s.midiNote.roundToInt() % 12) + 12) % 12
            hist[pc] += 1f
        }
        var bestKey = "C 大调"
        var bestCorr = Float.NEGATIVE_INFINITY
        for (root in 0 until 12) {
            val majCorr = pearsonShift(hist, MAJOR_PROFILE, root)
            if (majCorr > bestCorr) {
                bestCorr = majCorr
                bestKey = "${NOTE_NAMES[root]} 大调"
            }
            val minCorr = pearsonShift(hist, MINOR_PROFILE, root)
            if (minCorr > bestCorr) {
                bestCorr = minCorr
                bestKey = "${NOTE_NAMES[root]} 小调"
            }
        }
        return bestKey
    }

    private fun pearsonShift(hist: FloatArray, profile: FloatArray, shift: Int): Float {
        var meanH = 0f
        var meanP = 0f
        for (i in 0 until 12) {
            meanH += hist[(i + shift) % 12]
            meanP += profile[i]
        }
        meanH /= 12f
        meanP /= 12f
        var num = 0f
        var denH = 0f
        var denP = 0f
        for (i in 0 until 12) {
            val dh = hist[(i + shift) % 12] - meanH
            val dp = profile[i] - meanP
            num += dh * dp
            denH += dh * dh
            denP += dp * dp
        }
        val denom = sqrt((denH * denP).toDouble()).toFloat()
        return if (denom > 1e-6f) num / denom else 0f
    }

    /**
     * 综合评测一整段演唱采样，生成结构化声学指标报告
     */
    fun evaluateSession(
        songTitle: String,
        songArtist: String,
        songId: String,
        audioFilePath: String,
        durationMs: Long,
        allSamples: List<PitchSample>,
        referenceLyrics: List<LyricLine> = emptyList()
    ): SingingEvaluation {
        val voiced = allSamples.filter { it.frequencyHz > 0f && it.confidence >= 0.68f }
        if (voiced.size < 6) {
            return SingingEvaluation(
                songTitle = songTitle,
                songArtist = songArtist,
                songId = songId,
                audioFilePath = audioFilePath,
                durationMs = durationMs,
                overallScore = 40,
                pitchAccuracyScore = 40,
                pitchStabilityScore = 40,
                rhythmScore = 40,
                breathControlScore = 45,
                vocalRangeSemitones = 0,
                lowestNote = "--",
                highestNote = "--",
                estimatedKey = "人声有效帧过少（请靠近麦克风演唱）",
                vibratoDetected = false,
                inTuneRatioPercent = 0,
                medianAbsCentsError = 50f,
                offPitchMoments = listOf("未检测到足够清晰的持续人声，请确保环境安静并靠近麦克风")
            )
        }

        // 1. 音准分析：统计有效帧相对十二平均律的绝对音分偏移
        val absCents = voiced.map { abs(it.centsOffset) }.sorted()
        val medianCents = absCents[absCents.size / 2]
        val inTuneCount = voiced.count { abs(it.centsOffset) <= 32f }
        val inTuneRatio = (inTuneCount * 100f / voiced.size).roundToInt().coerceIn(0, 100)
        // 中位误差越小、命中率越高，音准分越高
        val pitchAccuracyScore = (100f - medianCents * 1.45f)
            .times(0.55f)
            .plus(inTuneRatio * 0.45f)
            .roundToInt()
            .coerceIn(35, 99)

        // 2. 音高稳定性：连续音符内的微抖动（剔除正常换音跳动 > 1.2 半音）
        val intraNoteDiffs = mutableListOf<Float>()
        for (i in 1 until voiced.size) {
            val dt = voiced[i].timeMs - voiced[i - 1].timeMs
            val dMidi = abs(voiced[i].midiNote - voiced[i - 1].midiNote)
            if (dt in 1..150 && dMidi < 1.2f) {
                intraNoteDiffs += dMidi * 100f // 转为 cents 帧间抖动
            }
        }
        val avgJitterCents = if (intraNoteDiffs.isNotEmpty()) intraNoteDiffs.average().toFloat() else 25f
        val pitchStabilityScore = (100f - (avgJitterCents - 8f).coerceAtLeast(0f) * 1.6f)
            .roundToInt()
            .coerceIn(40, 98)

        // 3. 颤音检测（持续稳态音符中是否存在周期性正负过零且摆幅 15~65 cents）
        var vibratoDetected = false
        if (voiced.size >= 16) {
            var signChanges = 0
            var prevSlope = 0f
            var ampSum = 0f
            var count = 0
            for (i in 1 until voiced.size) {
                val dt = voiced[i].timeMs - voiced[i - 1].timeMs
                val diffCents = (voiced[i].midiNote - voiced[i - 1].midiNote) * 100f
                if (dt in 20..90 && abs(diffCents) < 75f) {
                    if (prevSlope * diffCents < 0f && abs(diffCents) > 6f) {
                        signChanges++
                    }
                    prevSlope = diffCents
                    ampSum += abs(diffCents)
                    count++
                }
            }
            val durationSec = (voiced.last().timeMs - voiced.first().timeMs).coerceAtLeast(1000L) / 1000f
            val oscRate = signChanges / (2f * durationSec)
            val meanAmp = if (count > 0) ampSum / count else 0f
            vibratoDetected = oscRate in 3.2f..8.5f && meanAmp in 8f..45f
        }

        // 4. 气息与响度动态控制
        val rmsValues = voiced.map { it.rmsDb }
        val meanRms = rmsValues.average().toFloat()
        val rmsStd = sqrt(rmsValues.map { (it - meanRms) * (it - meanRms) }.average()).toFloat()
        // 理想流行人声响度标准差在 3.5 ~ 8.5 dB（既有强弱表情又不会突然断气爆麦）
        val breathPenalty = when {
            rmsStd < 2.0f -> (2.0f - rmsStd) * 6f
            rmsStd > 9.0f -> (rmsStd - 9.0f) * 4.5f
            else -> 0f
        }
        val voicedCoverage = voiced.size.toFloat() / allSamples.size.coerceAtLeast(1)
        val coverageBonus = if (voicedCoverage in 0.35f..0.88f) 6f else -5f
        val breathControlScore = (86f - breathPenalty + coverageBonus).roundToInt().coerceIn(42, 97)

        // 5. 节奏与乐句切分度（结合歌词时间轴或发声段落规律性）
        val phraseOnsets = mutableListOf<Long>()
        var inVoice = false
        for (s in allSamples) {
            val active = s.frequencyHz > 0f && s.confidence >= 0.65f
            if (active && !inVoice) {
                phraseOnsets += s.timeMs
                inVoice = true
            } else if (!active && inVoice) {
                inVoice = false
            }
        }
        val rhythmScore = if (referenceLyrics.isNotEmpty() && phraseOnsets.isNotEmpty()) {
            val lrcTimes = referenceLyrics.map { it.timeMs }.filter { it <= durationMs + 1500L }
            if (lrcTimes.isNotEmpty()) {
                val aligned = phraseOnsets.count { onset ->
                    lrcTimes.any { abs(it - onset) <= 950L }
                }
                val ratio = aligned.toFloat() / phraseOnsets.size
                (62f + ratio * 34f).roundToInt().coerceIn(50, 96)
            } else 78
        } else {
            val gapRegularity = if (phraseOnsets.size >= 3) {
                val gaps = (1 until phraseOnsets.size).map { (phraseOnsets[it] - phraseOnsets[it - 1]).toFloat() }
                val avgGap = gaps.average().toFloat().coerceAtLeast(1f)
                val cv = sqrt(gaps.map { (it - avgGap) * (it - avgGap) }.average()).toFloat() / avgGap
                (90f - cv * 28f).roundToInt().coerceIn(55, 94)
            } else 76
            gapRegularity
        }

        // 6. 音域统计（去除 3% 极端离群伪峰）
        val sortedMidi = voiced.map { it.midiNote }.sorted()
        val lowMidi = sortedMidi[(sortedMidi.size * 0.03f).toInt().coerceIn(0, sortedMidi.lastIndex)].roundToInt()
        val highMidi = sortedMidi[(sortedMidi.size * 0.97f).toInt().coerceIn(0, sortedMidi.lastIndex)].roundToInt()
        val rangeSemi = (highMidi - lowMidi).coerceAtLeast(0)

        // 7. 定位最明显的跑调时间点（连续偏离 > 38 cents）
        val offPitchMoments = mutableListOf<String>()
        var lastMomentMs = -3000L
        for (s in voiced) {
            if (abs(s.centsOffset) > 38f && s.timeMs - lastMomentMs >= 2500L) {
                val dir = if (s.centsOffset > 0f) "偏高" else "偏低"
                val sec = s.timeMs / 1000
                val timeStr = String.format("%02d:%02d", sec / 60, sec % 60)
                offPitchMoments += "$timeStr 处（${s.noteName} ${dir} ${abs(s.centsOffset).roundToInt()} 音分）"
                lastMomentMs = s.timeMs
                if (offPitchMoments.size >= 5) break
            }
        }

        val overall = (
            pitchAccuracyScore * 0.40f +
                pitchStabilityScore * 0.22f +
                rhythmScore * 0.20f +
                breathControlScore * 0.18f
            ).roundToInt().coerceIn(35, 99)

        return SingingEvaluation(
            songTitle = songTitle.ifBlank { "自由清唱练习" },
            songArtist = songArtist,
            songId = songId,
            audioFilePath = audioFilePath,
            durationMs = durationMs,
            overallScore = overall,
            pitchAccuracyScore = pitchAccuracyScore,
            pitchStabilityScore = pitchStabilityScore,
            rhythmScore = rhythmScore,
            breathControlScore = breathControlScore,
            vocalRangeSemitones = rangeSemi,
            lowestNote = midiToNoteName(lowMidi),
            highestNote = midiToNoteName(highMidi),
            estimatedKey = estimateKey(voiced),
            vibratoDetected = vibratoDetected,
            inTuneRatioPercent = inTuneRatio,
            medianAbsCentsError = (medianCents * 10f).roundToInt() / 10f,
            offPitchMoments = offPitchMoments
        )
    }
}
