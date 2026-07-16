package com.lifelog.camera.util

import android.app.Application
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
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    fun init(app: Application) {
        logDir = File(app.filesDir, "crash_logs").apply { mkdirs() }

        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(throwable, thread)
            originalHandler?.uncaughtException(thread, throwable)
            // 如果没有原始 handler，强制退出
            android.os.Process.killProcess(android.os.Process.myPid())
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

            val crashFile = File(logDir, "crash_${fileDateFormat.format(Date())}_${System.currentTimeMillis()}.txt")
            FileWriter(crashFile).use { it.write(sw.toString()) }
            Log.e(TAG, "崩溃日志已写入: ${crashFile.absolutePath}")
        } catch (ex: Exception) {
            Log.e(TAG, "写入崩溃日志失败", ex)
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
