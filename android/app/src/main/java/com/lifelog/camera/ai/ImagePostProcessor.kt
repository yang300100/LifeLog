package com.lifelog.camera.ai

import android.graphics.Bitmap
import com.lifelog.camera.util.CrashLogger
import kotlin.random.Random

/**
 * 图像后处理器 — 三步走: 高斯柔焦 → 色彩调色 → 胶片颗粒
 *
 * 全部在 android.graphics + kotlin 内完成，零外部依赖。
 */
object ImagePostProcessor {

    private const val TAG = "ImagePostProcessor"

    // ── 可调参数 (@Volatile 保证多线程可见性) ──

    /** 高斯柔焦半径 (像素, 默认 1.8 — 模拟镜头柔焦，帮助软化人物边缘) */
    @Volatile var blurRadius: Float = 1.8f

    /** 颗粒强度 0~1 (默认 0.10 — 明显胶片质感) */
    @Volatile var grainIntensity: Float = 0.10f

    /** 饱和度系数 (1.0=不变, 默认 1.15) */
    @Volatile var saturationFactor: Float = 1.15f

    /** 对比度系数 (默认 1.04) */
    @Volatile var contrastFactor: Float = 1.04f

    // ═══════════════════════════════════════════════════════

    /** 一键后处理: 柔焦 → 色彩+颗粒 (单次遍历) */
    fun process(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height

        // 快照参数 — 避免并发修改
        val blurR = blurRadius
        val sat = saturationFactor
        val cont = contrastFactor
        val grain = grainIntensity

        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(w * h)
        result.getPixels(pixels, 0, w, 0, 0, w, h)

        // ① 高斯柔焦 — 消除 AI 图锐利边缘 + 黑边残留
        if (blurR > 0f) {
            // 多次迭代增强柔焦效果：半径>1.5时两次pass，半径<1.0时单次
            val passes = if (blurR >= 1.5f) 2 else 1
            repeat(passes) { gaussianBlur(pixels, w, h) }
        }

        // ②+③ 色彩调色 + 胶片颗粒 — 单次遍历
        applyColorAndGrain(pixels, sat, cont, grain)

        result.setPixels(pixels, 0, w, 0, 0, w, h)
        CrashLogger.i(TAG, "后处理完成: ${w}x${h}, blur=$blurR, sat=$sat, grain=$grain")
        return result
    }

    // ── ① 高斯柔焦 (分离式 3×3 高斯核 [1,2,1]/4, sigma≈0.85) ──

    private fun gaussianBlur(pixels: IntArray, w: Int, h: Int) {
        // 水平 pass: 1D 高斯 [1, 2, 1] / 4
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rSum = 0; var gSum = 0; var bSum = 0
                for (dx in -1..1) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val p = pixels[y * w + nx]
                    val wgt = if (dx == 0) 2 else 1
                    rSum += ((p shr 16) and 0xFF) * wgt
                    gSum += ((p shr 8) and 0xFF) * wgt
                    bSum += (p and 0xFF) * wgt
                }
                val a = (pixels[y * w + x] shr 24) and 0xFF
                tmp[y * w + x] = (a shl 24) or
                    (((rSum / 4).coerceIn(0, 255)) shl 16) or
                    (((gSum / 4).coerceIn(0, 255)) shl 8) or
                    ((bSum / 4).coerceIn(0, 255))
            }
        }

        // 垂直 pass: 同样的 1D 高斯核
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rSum = 0; var gSum = 0; var bSum = 0
                for (dy in -1..1) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val p = tmp[ny * w + x]
                    val wgt = if (dy == 0) 2 else 1
                    rSum += ((p shr 16) and 0xFF) * wgt
                    gSum += ((p shr 8) and 0xFF) * wgt
                    bSum += (p and 0xFF) * wgt
                }
                val a = (tmp[y * w + x] shr 24) and 0xFF
                pixels[y * w + x] = (a shl 24) or
                    (((rSum / 4).coerceIn(0, 255)) shl 16) or
                    (((gSum / 4).coerceIn(0, 255)) shl 8) or
                    ((bSum / 4).coerceIn(0, 255))
            }
        }
    }

    // ── ②+③ 色彩调色 + 胶片颗粒 (单次遍历) ──

    private fun applyColorAndGrain(
        pixels: IntArray,
        saturation: Float,
        contrast: Float,
        grain: Float
    ) {
        val lumR = 0.299f; val lumG = 0.587f; val lumB = 0.114f
        val doSat = saturation != 1f
        val doCont = contrast != 1f
        val doGrain = grain > 0f
        val rng = if (doGrain) Random(42) else null

        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            var rf = ((pixels[i] shr 16) and 0xFF) / 255f
            var gf = ((pixels[i] shr 8) and 0xFF) / 255f
            var bf = (pixels[i] and 0xFF) / 255f

            if (doSat) {
                val lum = lumR * rf + lumG * gf + lumB * bf
                rf = (lum + saturation * (rf - lum)).coerceIn(0f, 1f)
                gf = (lum + saturation * (gf - lum)).coerceIn(0f, 1f)
                bf = (lum + saturation * (bf - lum)).coerceIn(0f, 1f)
            }

            if (doCont) {
                rf = ((rf - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                gf = ((gf - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                bf = ((bf - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
            }

            var ri = (rf * 255f).toInt()
            var gi = (gf * 255f).toInt()
            var bi = (bf * 255f).toInt()

            // 颗粒: 每 2px (更密集), 亮度噪点
            if (doGrain && i % 2 == 0) {
                val noise = ((rng!!.nextFloat() - 0.5f) * 2f * grain * 255f).toInt()
                if (noise != 0) {
                    ri = (ri + noise).coerceIn(0, 255)
                    gi = (gi + noise).coerceIn(0, 255)
                    bi = (bi + noise).coerceIn(0, 255)
                }
            }

            pixels[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
    }
}
