package com.sonicpilot.music.singing

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.sonicpilot.music.data.model.LyricLine
import com.sonicpilot.music.data.model.PitchSample
import com.sonicpilot.music.data.model.SingingEvaluation
import com.sonicpilot.music.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 声乐棚实时 HUD 状态
 */
data class SingingLiveState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val currentNote: String = "--",
    val currentFreqHz: Float = 0f,
    val currentCentsOffset: Float = 0f,
    val currentRmsDb: Float = -90f,
    val recentPitchHistory: List<PitchSample> = emptyList(),
    val latestEvaluation: SingingEvaluation? = null,
    val errorMessage: String? = null
)

/**
 * 端侧实时录音与声乐评测引擎
 * - 使用 44.1kHz PCM 16-bit AudioRecord 实时采集人声
 * - 每帧实时运行 YIN 基频检测并刷新实时音准曲线 HUD
 * - 同步保存标准 WAV 录音文件（支持随时回放）
 * - 结束录制自动计算五维唱功得分并写入 SQLite，随时可交给 AI 管家深度点评！
 */
class SingingCoachEngine(
    private val context: Context,
    private val repository: MusicRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordingJob: Job? = null

    private val _liveState = MutableStateFlow(SingingLiveState())
    val liveState: StateFlow<SingingLiveState> = _liveState.asStateFlow()

    private val collectedSamples = CopyOnWriteArrayList<PitchSample>()
    private var currentWavFile: File? = null
    private var currentTitle: String = "自由清唱"
    private var currentArtist: String = ""
    private var currentSongId: String = ""
    private var currentLyrics: List<LyricLine> = emptyList()
    private var startTimeMs: Long = 0L

    private val recordingDir: File by lazy {
        File(context.filesDir, "singing_recordings").apply { if (!exists()) mkdirs() }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        songTitle: String = "自由清唱练习",
        songArtist: String = "",
        songId: String = "",
        lyrics: List<LyricLine> = emptyList()
    ) {
        if (_liveState.value.isRecording) return

        val sampleRate = 44100
        val frameSize = 2048
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            _liveState.value = _liveState.value.copy(errorMessage = "当前设备不支持 44.1kHz 麦克风录音")
            return
        }

        val audioRecord = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, frameSize * 4)
            )
        }.getOrElse {
            _liveState.value = _liveState.value.copy(errorMessage = "麦克风初始化失败，请授予录音权限：${it.message}")
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            _liveState.value = _liveState.value.copy(errorMessage = "录音设备未就绪，请检查录音权限")
            return
        }

        currentTitle = songTitle
        currentArtist = songArtist
        currentSongId = songId
        currentLyrics = lyrics
        collectedSamples.clear()
        startTimeMs = System.currentTimeMillis()
        val wavFile = File(recordingDir, "vocal_${startTimeMs}.wav")
        currentWavFile = wavFile

        _liveState.value = SingingLiveState(
            isRecording = true,
            latestEvaluation = _liveState.value.latestEvaluation
        )

        recordingJob = scope.launch {
            val wavWriter = WavWriter(wavFile, sampleRate = sampleRate)
            val shortBuf = ShortArray(frameSize)
            val floatBuf = FloatArray(frameSize)
            val displayWindow = ArrayDeque<PitchSample>(90)

            try {
                audioRecord.startRecording()
                while (isActive && _liveState.value.isRecording) {
                    val read = audioRecord.read(shortBuf, 0, frameSize)
                    if (read > 0) {
                        wavWriter.writePcmShorts(shortBuf, read)
                        for (i in 0 until read) {
                            floatBuf[i] = shortBuf[i] / 32768f
                        }
                        val elapsed = System.currentTimeMillis() - startTimeMs
                        val slice = if (read == frameSize) floatBuf else floatBuf.copyOf(read)
                        val det = YinPitchDetector.detectPitch(slice, sampleRate)
                        val sample = YinPitchDetector.toPitchSample(elapsed, det)
                        collectedSamples.add(sample)

                        if (displayWindow.size >= 85) displayWindow.removeFirst()
                        displayWindow.addLast(sample)

                        _liveState.value = _liveState.value.copy(
                            elapsedMs = elapsed,
                            currentNote = sample.noteName,
                            currentFreqHz = sample.frequencyHz,
                            currentCentsOffset = sample.centsOffset,
                            currentRmsDb = sample.rmsDb,
                            recentPitchHistory = displayWindow.toList()
                        )
                    }
                }
            } catch (e: Exception) {
                _liveState.value = _liveState.value.copy(errorMessage = "录音采集异常：${e.message}")
            } finally {
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
                runCatching { wavWriter.close() }
            }
        }
    }

    /**
     * 安全挂起版停止录音：等待 AudioRecord 释放并确保 WAV 头完整落盘后再生成评测，彻底杜绝立刻回放无声或文件头损坏！
     */
    suspend fun stopAndEvaluateSafe(): SingingEvaluation? {
        if (!_liveState.value.isRecording) return _liveState.value.latestEvaluation
        _liveState.value = _liveState.value.copy(isRecording = false)
        val job = recordingJob
        recordingJob = null
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(1200L) { job?.cancelAndJoin() }
        }
        return finalizeEvaluation()
    }

    /**
     * 停止录制并生成声学多维评测报告（同时持久化到数据库）
     */
    fun stopAndEvaluate(): SingingEvaluation? {
        if (!_liveState.value.isRecording) return _liveState.value.latestEvaluation
        _liveState.value = _liveState.value.copy(isRecording = false)
        recordingJob?.cancel()
        recordingJob = null
        return finalizeEvaluation()
    }

    private fun finalizeEvaluation(): SingingEvaluation {
        val duration = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1000L)
        val wavPath = currentWavFile?.absolutePath.orEmpty()
        val evaluation = YinPitchDetector.evaluateSession(
            songTitle = currentTitle,
            songArtist = currentArtist,
            songId = currentSongId,
            audioFilePath = wavPath,
            durationMs = duration,
            allSamples = collectedSamples.toList(),
            referenceLyrics = currentLyrics
        )
        repository.saveSingingEvaluation(evaluation)
        _liveState.value = _liveState.value.copy(
            isRecording = false,
            latestEvaluation = evaluation
        )
        return evaluation
    }

    fun updateEvaluationAiFeedback(evaluationId: String, feedback: String) {
        val existing = repository.evaluations.value.firstOrNull { it.id == evaluationId } ?: return
        val updated = existing.copy(aiFeedback = feedback)
        repository.saveSingingEvaluation(updated)
        if (_liveState.value.latestEvaluation?.id == evaluationId) {
            _liveState.value = _liveState.value.copy(latestEvaluation = updated)
        }
    }
}
