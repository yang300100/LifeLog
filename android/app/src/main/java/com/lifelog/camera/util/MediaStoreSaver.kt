package com.lifelog.camera.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 保存图片/文件到系统公共存储（相册/下载目录）
 *
 * Android 10+: MediaStore API（无需存储权限）
 * Android 9-:  直接写入公共目录
 */
object MediaStoreSaver {

    private const val TAG = "MediaStoreSaver"

    /**
     * 保存图片到 Pictures/LifeLog/ 目录（系统相册可见）
     *
     * @param context 上下文
     * @param sourceFile 源文件
     * @param displayName 显示文件名（不含扩展名，自动加 .png）
     * @return 成功返回 true
     */
    fun saveImageToGallery(context: Context, sourceFile: File, displayName: String): Boolean {
        if (!sourceFile.exists()) {
            CrashLogger.w(TAG, "源文件不存在: ${sourceFile.absolutePath}")
            return false
        }

        val filename = "$displayName.png"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, sourceFile, filename)
            } else {
                saveViaPublicDir(sourceFile, filename)
            }
        } catch (e: Exception) {
            CrashLogger.e(TAG, "保存图片失败: $filename", e)
            false
        }
    }

    /** 使用 MediaStore API（Android 10+，无需 WRITE_EXTERNAL_STORAGE 权限） */
    private fun saveViaMediaStore(context: Context, sourceFile: File, filename: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LifeLog")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return false

        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: return false

            // 写入完成后取消 PENDING 标记，相册 App 才能看到
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)

            CrashLogger.i(TAG, "图片已保存到相册: $filename")
            return true
        } catch (e: Exception) {
            // 写入失败，删除已创建的 MediaStore 条目
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    /** 直接写入公共目录（Android 9-，需要 WRITE_EXTERNAL_STORAGE 权限） */
    private fun saveViaPublicDir(sourceFile: File, filename: String): Boolean {
        val dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        ).resolve("LifeLog")
        if (!dir.exists()) dir.mkdirs()

        val dest = File(dir, filename)
        sourceFile.copyTo(dest, overwrite = true)
        CrashLogger.i(TAG, "图片已保存: ${dest.absolutePath}")
        return true
    }
}
