package com.lifelog.camera.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.lifelog.camera.ai.KeyframeExtractor
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 ESP32 录制的自定义 MJPEG 文件 ([4B帧长LE][JPEG]×N) 转码为标准 MP4 (H.264)。
 *
 * 背景：.mjpg 是项目私有格式，任何系统/第三方播放器都不认识，
 * 同步到手机后必须转成 MP4 才能用 Intent 播放。
 * 转码后 .mjpg 原文件保留（AI 关键帧提取仍走 raw MJPEG 解析），MP4 只做播放用途。
 */
@Singleton
class MjpegTranscoder @Inject constructor(
    private val keyframeExtractor: KeyframeExtractor
) {
    companion object {
        private const val TAG = "MjpegTranscoder"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    /**
     * @param mjpgFile 输入的 .mjpg 文件
     * @param outFile  输出 MP4 路径（失败会自动删除）
     * @param durationMs 原始录制时长（用于生成帧时间戳）
     * @return true = 转码成功
     */
    fun transcode(mjpgFile: File, outFile: File, durationMs: Int = 5000): Boolean {
        val frameInfo = keyframeExtractor.parseFrameOffsets(mjpgFile)
        val total = frameInfo.offsets.size
        if (total == 0) {
            Log.w(TAG, "无有效帧: ${mjpgFile.name}（文件可能损坏）")
            return false
        }

        // 首帧确定分辨率（同一文件内所有帧分辨率一致）
        val first = keyframeExtractor.readFrame(mjpgFile, frameInfo.offsets[0], frameInfo.sizes[0])
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(first, 0, first.size, bounds)
        var width = bounds.outWidth
        var height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "首帧解码失败: ${mjpgFile.name}")
            return false
        }
        // H.264 要求偶数尺寸，奇数则编码前缩放掉 1 像素
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--

        val frameDurationUs = (durationMs.toLong() * 1000) / total
        val fps = maxOf(1, total * 1000 / maxOf(1, durationMs))

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            // 低帧率幻灯片式视频，2bit/像素 已经绰绰有余
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 2).coerceIn(1_000_000, 8_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()

        // 从编码器读出数据并写入 muxer；endOfStream=true 时一直等到 EOS
        fun drainEncoder(endOfStream: Boolean) {
            while (true) {
                when (val outIdx = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "编码器 format 变了两次" }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> {
                        if (outIdx < 0) continue  // INFO_OUTPUT_BUFFERS_CHANGED 等，忽略
                        val buf = encoder.getOutputBuffer(outIdx)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0  // SPS/PPS 已由 muxer 从 format 拿，不重复写
                        }
                        if (bufferInfo.size > 0) {
                            check(muxerStarted) { "muxer 未启动就收到数据" }
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, buf, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        var success = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            var pixels = IntArray(0)
            var yBuf = ByteArray(0)
            var uBuf = ByteArray(0)
            var vBuf = ByteArray(0)

            for (i in 0 until total) {
                // 取输入缓冲（编码器忙时先把输出排掉再试）
                var inIdx = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                while (inIdx < 0) {
                    drainEncoder(false)
                    inIdx = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                }

                // 解码 JPEG → ARGB（分辨率不一致时缩放到编码器尺寸）
                val jpeg = keyframeExtractor.readFrame(mjpgFile, frameInfo.offsets[i], frameInfo.sizes[i])
                var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: throw IllegalStateException("帧 $i JPEG 解码失败")
                if (bmp.width != width || bmp.height != height) {
                    val scaled = Bitmap.createScaledBitmap(bmp, width, height, true)
                    bmp.recycle()
                    bmp = scaled
                }

                // ARGB → I420 (BT.601 limited range)
                if (pixels.size != width * height) {
                    pixels = IntArray(width * height)
                    yBuf = ByteArray(width * height)
                    uBuf = ByteArray(width * height / 4)
                    vBuf = ByteArray(width * height / 4)
                }
                bmp.getPixels(pixels, 0, width, 0, 0, width, height)
                bmp.recycle()
                argbToI420(pixels, width, height, yBuf, uBuf, vBuf)

                // 填入编码器输入（getInputImage 模式：填完 close 再 queue）
                val image = encoder.getInputImage(inIdx)
                    ?: throw IllegalStateException("getInputImage 返回 null")
                fillPlane(image.planes[0], yBuf, width, height)
                fillPlane(image.planes[1], uBuf, width / 2, height / 2)
                fillPlane(image.planes[2], vBuf, width / 2, height / 2)
                image.close()

                encoder.queueInputBuffer(inIdx, 0, width * height * 3 / 2, i * frameDurationUs, 0)
                drainEncoder(false)
            }

            // 收尾：送 EOS 并排干编码器
            var inIdx = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            while (inIdx < 0) {
                drainEncoder(false)
                inIdx = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            }
            encoder.queueInputBuffer(
                inIdx, 0, 0, total * frameDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            drainEncoder(true)
            success = true
            Log.i(TAG, "转码完成: ${mjpgFile.name} ($total 帧 ${width}x${height}) → ${outFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "转码失败: ${mjpgFile.name}", e)
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            try { muxer.release() } catch (_: Exception) {}
            if (!success) outFile.delete()  // 半成品不能留
        }
        return success
    }

    // ── 色彩空间转换与平面填充 ──

    /** ARGB → I420，BT.601 limited range（H.264 默认色彩约定） */
    private fun argbToI420(pixels: IntArray, width: Int, height: Int,
                           yOut: ByteArray, uOut: ByteArray, vOut: ByteArray) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val yy = (66 * r + 129 * g + 25 * b + 128) shr 8
                yOut[row + x] = (yy + 16).toByte()  // 16..235
                // 色度 2×2 子采样：取每块左上角像素（速度优先，画质足够）
                if ((y and 1) == 0 && (x and 1) == 0) {
                    val ci = (y / 2) * (width / 2) + (x / 2)
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uOut[ci] = u.coerceIn(0, 255).toByte()
                    vOut[ci] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    /** 把一帧 Y/U/V 平面数据填进编码器的 Image（兼容不同的 rowStride/pixelStride） */
    private fun fillPlane(plane: Image.Plane, data: ByteArray, width: Int, height: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1) {
            // 连续布局：逐行批量拷贝
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.put(data, row * width, width)
            }
        } else {
            // 交错布局：逐像素写
            for (row in 0 until height) {
                var pos = row * rowStride
                val srcRow = row * width
                for (col in 0 until width) {
                    buffer.put(pos, data[srcRow + col])
                    pos += pixelStride
                }
            }
        }
    }
}
