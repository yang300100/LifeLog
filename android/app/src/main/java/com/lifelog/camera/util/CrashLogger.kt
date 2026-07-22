package com.lifelog.camera.util

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object CrashLogger {
    private const val TAG = "CrashLogger"
    private lateinit var logDir: File
    private lateinit var appContext: Application
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    fun init(app: Application) {
        appContext = app
        logDir = File(app.filesDir, "crash_logs").apply { mkdirs() }

        // 同时确保 Download/LifeLog 目录存在
        getDownloadDir()

        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(throwable, thread)
            originalHandler?.uncaughtException(thread, throwable)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** 获取 Download/LifeLog 目录，用于导出日志到手机存储 */
    private fun getDownloadDir(): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: 使用 MediaStore 不需要权限
                null  // 延迟到写入时创建
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "LifeLog")
                dir.mkdirs()
                dir
            }
        } catch (e: Exception) { null }
    }

    /** 将崩溃日志导出到 Download/LifeLog/ 目录（手机文件管理器可直接查看） */
    private fun exportToDownload(content: String, filename: String) {
        if (!::appContext.isInitialized) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/LifeLog")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = appContext.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    appContext.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(content.toByteArray())
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    appContext.contentResolver.update(uri, values, null, null)
                }
            } else {
                getDownloadDir()?.let { dir ->
                    File(dir, filename).writeText(content)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "导出日志到 Download 失败", e)
        }
    }

    @Synchronized
    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        try {
            val now = Date()
            val sb = StringBuilder()
            sb.append(dateFormat.format(now))
            sb.append(" ")
            sb.append(level)
            sb.append("/")
            sb.append(tag)
            sb.append(": ")
            sb.append(message)
            sb.append("\n")

            if (throwable != null) {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                sb.append(sw.toString())
                sb.append("\n")
            }

            val logFile = File(logDir, "log_${fileDateFormat.format(now)}.txt")
            FileWriter(logFile, true).use { it.write(sb.toString()) }
        } catch (ex: Exception) {
            Log.e(TAG, "写入日志文件失败", ex)
        }
    }

    fun i(tag: String, msg: String) = log("INFO", tag, msg)
    fun w(tag: String, msg: String) = log("WARN", tag, msg)
    fun e(tag: String, msg: String, th: Throwable? = null) = log("ERROR", tag, msg, th)

    private fun writeCrash(throwable: Throwable, thread: Thread) {
        try {
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("=== CRASH ===")
                pw.println("Time: ${dateFormat.format(Date())}")
                pw.println("Thread: ${thread.name}")
                pw.println()
                pw.println("Stack trace:")
                throwable.printStackTrace(pw)
                pw.println()
                pw.println("=== DEVICE INFO ===")
                pw.println("Manufacturer: ${android.os.Build.MANUFACTURER}")
                pw.println("Model: ${android.os.Build.MODEL}")
                pw.println("SDK: ${android.os.Build.VERSION.SDK_INT}")
                pw.println("Release: ${android.os.Build.VERSION.RELEASE}")
            }

            val filename = "LifeLog_crash_${fileDateFormat.format(Date())}_${System.currentTimeMillis()}.txt"
            val crashFile = File(logDir, filename)
            FileWriter(crashFile).use { it.write(sw.toString()) }
            Log.e(TAG, "崩溃日志已写入: ${crashFile.absolutePath}")

            // 同时导出到手机 Download/LifeLog/ 目录
            exportToDownload(sw.toString(), filename)
        } catch (ex: Exception) {
            Log.e(TAG, "写入崩溃日志失败", ex)
        }
    }

    /** 导出当前所有日志到 Download/LifeLog/ */
    fun exportAllLogsToDownload(): String? {
        return try {
            val allLogs = StringBuilder()
            getLogFiles().forEach { file ->
                allLogs.append("=== ${file.name} ===\n")
                allLogs.append(file.readText())
                allLogs.append("\n\n")
            }
            val filename = "LifeLog_all_${fileDateFormat.format(Date())}_${System.currentTimeMillis()}.txt"
            exportToDownload(allLogs.toString(), filename)
            filename
        } catch (e: Exception) {
            Log.e(TAG, "导出全部日志失败", e)
            null
        }
    }

    fun getLogFiles(): List<File> {
        if (!::logDir.isInitialized) return emptyList()
        return logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun readLogFile(file: File): String =
        try { file.readText() } catch (_: Exception) { "无法读取日志文件" }

    fun clearLogs() {
        if (!::logDir.isInitialized) return
        logDir.listFiles()?.forEach { it.delete() }
    }
}
