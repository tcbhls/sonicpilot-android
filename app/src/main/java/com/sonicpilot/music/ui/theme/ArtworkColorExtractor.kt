package com.sonicpilot.music.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 专辑封面动态氛围流光色板（对标 Apple Music / QQ 音乐流光舞台）：
 * 零第三方依赖，在 IO 线程将封面降采样至 36x36 提取主视觉高饱和暗调色与辅助氛围光色，
 * 并带有内存 LRU 缓存，切歌瞬间流光丝滑过渡、不掉一帧！
 */
data class AmbientStagePalette(
    val primaryGlow: Color,
    val secondaryGlow: Color,
    val deepBase: Color
) {
    companion object {
        val Default = AmbientStagePalette(
            primaryGlow = Color(0xFF281D38),
            secondaryGlow = Color(0xFF142436),
            deepBase = Color(0xFF0A0B0F)
        )
    }
}

object ArtworkColorExtractor {
    private val cache = LruCache<String, AmbientStagePalette>(96)

    suspend fun extract(context: Context, imageUrl: String): AmbientStagePalette {
        if (!imageUrl.startsWith("http")) return AmbientStagePalette.Default
        cache.get(imageUrl)?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(40)
                    .allowHardware(false)
                    .build()
                val res = context.imageLoader.execute(request) as? SuccessResult
                    ?: return@runCatching AmbientStagePalette.Default
                val bmp = res.drawable.toBitmap(36, 36, Bitmap.Config.ARGB_8888)
                val palette = computePaletteFromBitmap(bmp)
                cache.put(imageUrl, palette)
                palette
            }.getOrDefault(AmbientStagePalette.Default)
        }
    }

    /**
     * 在 12 个色相桶中加权聚类，筛选最具氛围美感的主光色 + 次光色，自动钳制明度确保白色歌词始终清晰通透
     */
    internal fun computePaletteFromBitmap(bitmap: Bitmap): AmbientStagePalette {
        val w = bitmap.width
        val h = bitmap.height
        val bucketWeight = FloatArray(12)
        val bucketR = FloatArray(12)
        val bucketG = FloatArray(12)
        val bucketB = FloatArray(12)
        val hsv = FloatArray(3)

        var fallbackR = 0f
        var fallbackG = 0f
        var fallbackB = 0f
        var fallbackCount = 0

        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val px = bitmap.getPixel(x, y)
                val a = AndroidColor.alpha(px)
                if (a < 180) continue
                val r = AndroidColor.red(px)
                val g = AndroidColor.green(px)
                val b = AndroidColor.blue(px)
                fallbackR += r; fallbackG += g; fallbackB += b; fallbackCount++

                AndroidColor.RGBToHSV(r, g, b, hsv)
                val sat = hsv[1]
                val value = hsv[2]
                // 跳过纯黑、死白或极灰噪点，优先提取有情绪张力的色彩
                if (value < 0.12f || value > 0.96f || sat < 0.14f) continue
                val bucket = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                val weight = sat * sat * (0.4f + value * 0.6f)
                bucketWeight[bucket] += weight
                bucketR[bucket] += r * weight
                bucketG[bucket] += g * weight
                bucketB[bucket] += b * weight
            }
        }

        val ranked = (0 until 12).filter { bucketWeight[it] > 0.01f }
            .sortedByDescending { bucketWeight[it] }

        if (ranked.isEmpty()) {
            if (fallbackCount > 0) {
                val avg = toStageGlowColor(
                    (fallbackR / fallbackCount).toInt(),
                    (fallbackG / fallbackCount).toInt(),
                    (fallbackB / fallbackCount).toInt(),
                    targetValue = 0.28f
                )
                return AmbientStagePalette(
                    primaryGlow = avg,
                    secondaryGlow = Color(0xFF151926),
                    deepBase = Color(0xFF0A0B0F)
                )
            }
            return AmbientStagePalette.Default
        }

        val top1 = ranked[0]
        val primary = toStageGlowColor(
            (bucketR[top1] / bucketWeight[top1]).toInt(),
            (bucketG[top1] / bucketWeight[top1]).toInt(),
            (bucketB[top1] / bucketWeight[top1]).toInt(),
            targetValue = 0.42f
        )

        // 优先挑一个色相距离 >= 2 格的互补/邻近氛围辅色，形成双色极光层次
        val top2 = ranked.drop(1).firstOrNull { idx ->
            val diff = kotlin.math.abs(idx - top1)
            minOf(diff, 12 - diff) >= 2
        } ?: ranked.getOrNull(1) ?: top1

        val secondary = if (top2 != top1) {
            toStageGlowColor(
                (bucketR[top2] / bucketWeight[top2]).toInt(),
                (bucketG[top2] / bucketWeight[top2]).toInt(),
                (bucketB[top2] / bucketWeight[top2]).toInt(),
                targetValue = 0.30f
            )
        } else {
            toStageGlowColor(
                (bucketR[top1] / bucketWeight[top1]).toInt(),
                (bucketG[top1] / bucketWeight[top1]).toInt(),
                (bucketB[top1] / bucketWeight[top1]).toInt(),
                targetValue = 0.22f,
                hueShift = 28f
            )
        }

        val deepBase = toStageGlowColor(
            (bucketR[top1] / bucketWeight[top1]).toInt(),
            (bucketG[top1] / bucketWeight[top1]).toInt(),
            (bucketB[top1] / bucketWeight[top1]).toInt(),
            targetValue = 0.08f
        )

        return AmbientStagePalette(primaryGlow = primary, secondaryGlow = secondary, deepBase = deepBase)
    }

    private fun toStageGlowColor(
        r: Int,
        g: Int,
        b: Int,
        targetValue: Float,
        hueShift: Float = 0f
    ): Color {
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), hsv)
        hsv[0] = (hsv[0] + hueShift + 360f) % 360f
        hsv[1] = hsv[1].coerceIn(0.32f, 0.72f)
        hsv[2] = targetValue.coerceIn(0.06f, 0.48f)
        return Color(AndroidColor.HSVToColor(hsv))
    }
}
