package com.lifelog.camera.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyframeExtractor @Inject constructor() {

    companion object {
        private const val TAG = "KeyframeEx"
        private const val THUMB_SIZE = 48 // 缩略图尺寸（直方图比较用）
    }

    data class KeyFrames(
        val frameEarly: ByteArray,   // ~0.5s 处 (index 3)
        val frameMiddle: ByteArray,  // 中点 (index 12)
        val frameDiff: ByteArray,    // 差异最大帧
        val totalFrames: Int
    )

    data class FrameOffsets(
        val offsets: List<Long>,
        val sizes: List<Int>
    )

    // ── 解析 MJPEG 文件 → 帧偏移列表 ──

    fun parseFrameOffsets(file: File): FrameOffsets {
        val offsets = mutableListOf<Long>()
        val sizes = mutableListOf<Int>()

        FileInputStream(file).use { fis ->
            val header = ByteArray(4)
            while (true) {
                val n = fis.read(header)
                if (n < 4) break

                val frameSize = ((header[0].toInt() and 0xFF)) or
                        ((header[1].toInt() and 0xFF) shl 8) or
                        ((header[2].toInt() and 0xFF) shl 16) or
                        ((header[3].toInt() and 0xFF) shl 24)

                if (frameSize <= 0 || frameSize > 2_000_000) break

                offsets.add(fis.channel.position())
                sizes.add(frameSize)

                val skipped = fis.skip(frameSize.toLong())
                if (skipped < frameSize) break
            }
        }
        return FrameOffsets(offsets, sizes)
    }

    // ── 读取指定帧的 JPEG 数据 ──

    fun readFrame(file: File, offset: Long, size: Int): ByteArray {
        FileInputStream(file).use { fis ->
            fis.skip(offset)
            return fis.readNBytes(size)
        }
    }

    // ── 提取关键帧 ──

    fun extract(file: File): KeyFrames? {
        val frameInfo = parseFrameOffsets(file)
        val total = frameInfo.offsets.size
        if (total < 5) {
            Log.w(TAG, "帧数太少: $total")
            return null
        }

        // Frame A: ~25% 处
        val idxA = (total * 0.25).toInt().coerceIn(0, total - 1)
        val dataA = readFrame(file, frameInfo.offsets[idxA], frameInfo.sizes[idxA])

        // Frame B: ~50% 处
        val idxB = (total * 0.5).toInt().coerceIn(0, total - 1)
        val dataB = readFrame(file, frameInfo.offsets[idxB], frameInfo.sizes[idxB])

        // Frame C: 与 A+B 差异最大的帧
        val thumbA = decodeThumb(dataA)
        val thumbB = decodeThumb(dataB)
        val histA = computeHistogram(thumbA)
        val histB = computeHistogram(thumbB)

        var maxDiff = 0.0
        var idxC = (total * 0.75).toInt().coerceIn(0, total - 1) // 默认75%

        val step = maxOf(1, total / 8) // 采样以提升性能
        var i = 0
        while (i < total) {
            if (i != idxA && i != idxB) {
                val frameData = readFrame(file, frameInfo.offsets[i], frameInfo.sizes[i])
                val thumb = decodeThumb(frameData)
                val hist = computeHistogram(thumb)
                val diff = histogramDiff(hist, histA) + histogramDiff(hist, histB)
                if (diff > maxDiff) {
                    maxDiff = diff
                    idxC = i
                }
                thumb?.recycle()
            }
            i += step
        }

        val dataC = readFrame(file, frameInfo.offsets[idxC], frameInfo.sizes[idxC])

        thumbA?.recycle()
        thumbB?.recycle()

        return KeyFrames(dataA, dataB, dataC, total)
    }

    // ── 读取 MJPEG 全部帧 ──

    fun readAllFrames(file: File): List<ByteArray> {
        val frameInfo = parseFrameOffsets(file)
        return frameInfo.offsets.mapIndexed { idx, offset ->
            readFrame(file, offset, frameInfo.sizes[idx])
        }
    }

    // ── 从 MP4 提取指定数量帧（避免全文件 base64 OOM）──

    fun extractMp4Frames(mp4Path: String, frameCount: Int = 15): List<ByteArray> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mp4Path)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 5000L
            val intervalUs = (durationMs * 1000) / frameCount

            val frames = mutableListOf<ByteArray>()
            for (i in 0 until frameCount) {
                val timeUs = i * intervalUs
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    // 保持原始分辨率，不做缩放
                    val jpeg = ByteArrayOutputStream().use { bos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                        bos.toByteArray()
                    }
                    frames.add(jpeg)
                    bitmap.recycle()
                }
            }
            Log.i(TAG, "MP4 提取 $mp4Path: ${frames.size}/$frameCount 帧")
            return frames
        } catch (e: Exception) {
            Log.e(TAG, "MP4 帧提取失败", e)
            return emptyList()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // ── MP4 → MJPEG 转换（手机拍摄的视频转为与 ESP32 兼容的格式）──

    /**
     * 用 MediaMetadataRetriever 从 MP4 中提取帧，写入 MJPEG 格式文件。
     * @param mp4File 手机拍摄的 MP4 文件
     * @param outputFile 输出的 .mjpg 文件路径
     * @param fps 目标帧率
     * @param maxDurationMs 最长提取时长
     * @return 实际写入的帧数，失败返回 -1
     */
    fun convertMp4ToMjpeg(
        mp4File: File,
        outputFile: File,
        fps: Int = 5,
        maxDurationMs: Int = 5000
    ): Int {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mp4File.absolutePath)

            val durationStr = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            val videoDurationMs = durationStr?.toLongOrNull() ?: maxDurationMs.toLong()
            val extractDurationMs = minOf(videoDurationMs, maxDurationMs.toLong())

            val frameIntervalUs = (1_000_000L / fps)
            val totalFrames = ((extractDurationMs * fps) / 1000).toInt()
            if (totalFrames < 1) {
                Log.w(TAG, "MP4 太短，无法提取帧")
                return -1
            }

            FileOutputStream(outputFile).use { fos ->
                var frameCount = 0
                var timeUs = 0L

                while (timeUs < extractDurationMs * 1000 && frameCount < totalFrames) {
                    val bitmap = retriever.getFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    if (bitmap != null) {
                        // 缩放到 QVGA (320×240) 保持与 ESP32 一致
                        val scaled = Bitmap.createScaledBitmap(bitmap, 320, 240, true)
                        bitmap.recycle()

                        val jpegBytes = ByteArrayOutputStream().use { bos ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 75, bos)
                            scaled.recycle()
                            bos.toByteArray()
                        }

                        // MJPEG 格式：[4B frame_size LE] [JPEG data]
                        val sizeLE = byteArrayOf(
                            (jpegBytes.size and 0xFF).toByte(),
                            ((jpegBytes.size shr 8) and 0xFF).toByte(),
                            ((jpegBytes.size shr 16) and 0xFF).toByte(),
                            ((jpegBytes.size shr 24) and 0xFF).toByte()
                        )
                        fos.write(sizeLE)
                        fos.write(jpegBytes)
                        frameCount++
                    }
                    timeUs += frameIntervalUs
                }
                Log.i(TAG, "MP4→MJPEG: 提取 $frameCount 帧 → ${outputFile.name}")
                return frameCount
            }
        } catch (e: Exception) {
            Log.e(TAG, "MP4→MJPEG 转换失败", e)
            outputFile.delete()
            return -1
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // ── 将 JPEG 转 Base64 data URI ──

    fun toBase64Uri(jpeg: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    // ── 私有辅助 ──

    private fun decodeThumb(jpeg: ByteArray): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 8 // 320/8 = 40px, 足够直方图
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            if (bmp != null) {
                Bitmap.createScaledBitmap(bmp, THUMB_SIZE, THUMB_SIZE, true)
                    .also { bmp.recycle() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun computeHistogram(bmp: Bitmap?): IntArray {
        val hist = IntArray(64 * 3) // 64 bins × RGB
        if (bmp == null) return hist

        val pixels = IntArray(THUMB_SIZE * THUMB_SIZE)
        bmp.getPixels(pixels, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)

        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 4  // 0-63
            val g = ((p shr 8) and 0xFF) / 4
            val b = (p and 0xFF) / 4
            hist[r]++
            hist[64 + g]++
            hist[128 + b]++
        }
        return hist
    }

    private fun histogramDiff(h1: IntArray, h2: IntArray): Double {
        var diff = 0.0
        for (i in h1.indices) {
            val sum = h1[i] + h2[i]
            if (sum > 0) {
                val d = h1[i] - h2[i]
                diff += (d.toDouble() * d) / sum
            }
        }
        return diff
    }
}
