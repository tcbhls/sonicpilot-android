package com.sonicpilot.music.singing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 标准 16-bit Mono PCM WAV 写入器（用于录制并回放声乐棚人声）
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int = 44100,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {
    private val raf: RandomAccessFile = RandomAccessFile(file, "rw")
    private var totalAudioBytes: Int = 0

    init {
        raf.setLength(0)
        // 预留 44 字节 WAV 文件头
        raf.write(ByteArray(44))
    }

    fun writePcmShorts(samples: ShortArray, count: Int) {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            bb.putShort(samples[i])
        }
        val bytes = bb.array()
        raf.write(bytes)
        totalAudioBytes += bytes.size
    }

    fun close() {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + totalAudioBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // PCM chunk size
            putShort(1) // AudioFormat = 1 (PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(totalAudioBytes)
        }
        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }
}
