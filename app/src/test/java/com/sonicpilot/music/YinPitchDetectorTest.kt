package com.sonicpilot.music

import com.sonicpilot.music.singing.YinPitchDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class YinPitchDetectorTest {

    private fun synthSine(freqHz: Double, sampleRate: Int = 44100, size: Int = 2048, amp: Double = 0.5): FloatArray {
        return FloatArray(size) { i ->
            (amp * sin(2.0 * PI * freqHz * i / sampleRate)).toFloat()
        }
    }

    @Test
    fun detectA440AndC4Accurately() {
        val a4 = YinPitchDetector.detectPitch(synthSine(440.0), 44100)
        assertTrue("A4 频率应接近 440Hz，实际=${a4.frequencyHz}", abs(a4.frequencyHz - 440f) < 1.5f)
        assertTrue("置信度应高于 0.9", a4.confidence > 0.9f)
        val a4Sample = YinPitchDetector.toPitchSample(0L, a4)
        assertEquals("A4", a4Sample.noteName)
        assertTrue("音分偏移应小于 5 cents", abs(a4Sample.centsOffset) < 5f)

        val c4 = YinPitchDetector.detectPitch(synthSine(261.6256), 44100)
        assertTrue("C4 频率应接近 261.63Hz，实际=${c4.frequencyHz}", abs(c4.frequencyHz - 261.63f) < 1.5f)
        assertEquals("C4", YinPitchDetector.toPitchSample(50L, c4).noteName)
    }

    @Test
    fun evaluateCleanMajorScaleScoresHigherThanOffPitch() {
        // 构造一段准音 C 大调演唱序列
        val cMajorFreqs = listOf(261.63, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88, 523.25)
        val goodSamples = mutableListOf<com.sonicpilot.music.data.model.PitchSample>()
        var t = 0L
        for (f in cMajorFreqs) {
            repeat(5) {
                val det = YinPitchDetector.detectPitch(synthSine(f), 44100)
                goodSamples += YinPitchDetector.toPitchSample(t, det)
                t += 50L
            }
        }
        val goodEval = YinPitchDetector.evaluateSession(
            songTitle = "音准测试",
            songArtist = "测试",
            songId = "1",
            audioFilePath = "/tmp/a.wav",
            durationMs = t,
            allSamples = goodSamples
        )
        assertTrue("标准音阶综合分应 >= 85，实际=${goodEval.overallScore}", goodEval.overallScore >= 85)
        assertEquals("C4", goodEval.lowestNote)
        assertEquals("C5", goodEval.highestNote)
        assertEquals(12, goodEval.vocalRangeSemitones)

        // 构造一段严重跑调（整体偏高约 45 音分）演唱序列
        val badSamples = mutableListOf<com.sonicpilot.music.data.model.PitchSample>()
        var tb = 0L
        for (f in cMajorFreqs) {
            val detuned = f * 1.0265 // ~ +45 cents
            repeat(5) {
                val det = YinPitchDetector.detectPitch(synthSine(detuned), 44100)
                badSamples += YinPitchDetector.toPitchSample(tb, det)
                tb += 50L
            }
        }
        val badEval = YinPitchDetector.evaluateSession(
            songTitle = "跑调测试",
            songArtist = "测试",
            songId = "2",
            audioFilePath = "/tmp/b.wav",
            durationMs = tb,
            allSamples = badSamples
        )
        assertTrue(
            "准音得分(${goodEval.pitchAccuracyScore})应显著高于跑调得分(${badEval.pitchAccuracyScore})",
            goodEval.pitchAccuracyScore > badEval.pitchAccuracyScore + 20
        )
    }
}
