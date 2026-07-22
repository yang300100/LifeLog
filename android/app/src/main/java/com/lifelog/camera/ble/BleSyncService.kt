package com.lifelog.camera.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lifelog.camera.MainActivity
import com.lifelog.camera.R
import com.lifelog.camera.ai.AnalysisManager
import com.lifelog.camera.ai.ApiPreferences
import com.lifelog.camera.data.repository.VideoRepository
import com.lifelog.camera.data.local.entity.VideoClipEntity
import com.lifelog.camera.media.MjpegTranscoder
import com.lifelog.camera.util.CrashLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class BleSyncService : Service() {

    @Inject lateinit var bleTransfer: BleFileTransfer
    @Inject lateinit var repository: VideoRepository
    @Inject lateinit var analysisManager: AnalysisManager
    @Inject lateinit var apiPreferences: ApiPreferences
    @Inject lateinit var mjpegTranscoder: MjpegTranscoder

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    companion object {
        const val CHANNEL_ID = "ble_sync"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_PERSISTENT = "com.lifelog.camera.START_PERSISTENT"
        private const val TAG = "BleSyncSvc"
        private const val PERSISTENT_INTERVAL_MS = 60_000L  // 后台 60 秒扫一次（固件 BLE 窗口仅 30s，频繁扫才能命中）
        private const val NOTIFY_LABEL = "LifeLog 同步"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        CrashLogger.i(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashLogger.i(TAG, "onStartCommand action=${intent?.action}")
        if (intent == null) {
            // 系统重启 Service（START_STICKY），自动恢复后台同步
            CrashLogger.i(TAG, "系统重启 Service，自动恢复同步")
            startSync()
        } else if (intent.action == ACTION_START_PERSISTENT) {
            startSync()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSync() {
        // 如果已有同步在跑，中断当前等待立刻开始新一轮
        syncJob?.cancel()
        CrashLogger.i(TAG, "启动后台同步")

        syncJob = scope.launch {
            // 启动前台服务
            try {
                val notification = buildNotification("$NOTIFY_LABEL - 准备扫描...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                CrashLogger.e(TAG, "startForeground 失败", e)
                stopSelf()
                return@launch
            }

            // 通知栏状态更新协程
            launch {
                try {
                    bleTransfer.state.collect { state ->
                        val text = when (state) {
                            is BleFileTransfer.SyncState.Idle -> "$NOTIFY_LABEL - 等待扫描"
                            is BleFileTransfer.SyncState.Scanning -> "$NOTIFY_LABEL - 扫描中..."
                            is BleFileTransfer.SyncState.Connecting -> "$NOTIFY_LABEL - 连接中..."
                            is BleFileTransfer.SyncState.Syncing -> "$NOTIFY_LABEL - 同步 ${state.current}/${state.total}"
                            is BleFileTransfer.SyncState.Done -> "$NOTIFY_LABEL - 完成 ${state.files} 文件"
                            is BleFileTransfer.SyncState.Error -> "$NOTIFY_LABEL - ${state.msg}"
                        }
                        try {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, buildNotification(text))
                        } catch (_: Exception) {}
                    }
                } catch (_: CancellationException) {}
                catch (e: Exception) { CrashLogger.e(TAG, "collect 异常", e) }
            }

            // 同步循环（syncJob 被替换时旧循环自动退出，防止双循环）
            val videoDir = repository.getVideoDir()
            val myJob = coroutineContext[Job]
            while (myJob?.isActive == true) {
                CrashLogger.i(TAG, "开始扫描...")

                val result = try {
                    bleTransfer.startSync(videoDir)
                } catch (e: SecurityException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(e)
                }

                if (result.isSuccess) {
                    try { bleTransfer.refreshCachedDeviceInfo() } catch (_: Exception) {}
                }

                result.onSuccess { count ->
                    notifySyncDone(count)
                    if (count > 0) {
                        CrashLogger.i(TAG, "同步 $count 个文件")
                        try {
                            val entities = buildEntitiesFromFiles(videoDir)
                            if (entities.isNotEmpty()) {
                                repository.saveClips(entities)
                            }
                        } catch (e: Exception) {
                            CrashLogger.e(TAG, "注册文件失败", e)
                        }
                        try {
                            repository.fixTimestamps(bleTransfer.lastCapturedAt)
                        } catch (e: Exception) {
                            CrashLogger.e(TAG, "时间戳回填失败", e)
                        }
                        try {
                            transcodeMissingMp4(videoDir)
                        } catch (e: Exception) {
                            CrashLogger.e(TAG, "批量转码异常", e)
                        }
                        val isRealtime = apiPreferences.getMode() == "realtime"
                        if (isRealtime && apiPreferences.get().isValid) {
                            CrashLogger.i(TAG, "自动触发 RPG 分析...")
                            analysisManager.reanalyzeAllRpg { state ->
                                val label = when (state) {
                                    is AnalysisManager.AnalysisState.Done -> "RPG分析: ${state.activities} 活动"
                                    is AnalysisManager.AnalysisState.Error -> "RPG分析: ${state.msg}"
                                    is AnalysisManager.AnalysisState.Analyzing -> "AI 分析中..."
                                    else -> null
                                }
                                if (label != null) {
                                    try {
                                        getSystemService(NotificationManager::class.java)
                                            .notify(NOTIFICATION_ID, buildNotification(label))
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }

                result.onFailure { e ->
                    CrashLogger.w(TAG, "同步失败: ${e.message}")
                }

                delay(PERSISTENT_INTERVAL_MS)
            }
        }
    }

    /** 为 videoDir 中缺少同名 .mp4 的 .mjpg 逐个转码（播放用；mjpg 保留给 AI 分析） */
    private fun transcodeMissingMp4(videoDir: File) {
        val mjpgFiles = videoDir.listFiles()?.filter { it.extension == "mjpg" } ?: return
        for (mjpg in mjpgFiles) {
            val mp4 = File(videoDir, mjpg.nameWithoutExtension + ".mp4")
            // mjpg 比 mp4 新 = 重传补齐过，旧 mp4（可能是残片转的）要重转
            if (mp4.exists() && mp4.length() > 0 && mp4.lastModified() >= mjpg.lastModified()) continue
            try {
                val ok = mjpegTranscoder.transcode(mjpg, mp4, durationMs = 5000)
                CrashLogger.i(TAG, "转码 ${mjpg.name} → ${if (ok) "OK" else "失败(源文件可能损坏)"}")
            } catch (e: Exception) {
                CrashLogger.e(TAG, "转码异常: ${mjpg.name}", e)
            }
        }
    }

    /** 同步完成后发一条系统通知 */
    private fun notifySyncDone(count: Int) {
        val text = if (count > 0) "同步完成: $count 个视频已传输" else "同步完成，无新视频"
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID + 1, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LifeLog 同步")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /** 从 videoDir 中的 .mjpg 文件构建 VideoClipEntity 列表（仅新增） */
    private suspend fun buildEntitiesFromFiles(videoDir: File): List<VideoClipEntity> {
        val files = videoDir.listFiles()
            ?.filter { it.extension == "mjpg" }
            ?: emptyList()
        if (files.isEmpty()) return emptyList()

        val maxId = repository.getMaxId()
        return files
            .filter { f ->
                val id = f.nameWithoutExtension
                    .removePrefix("clip_")
                    .toLongOrNull() ?: 0
                id > maxId
            }
            .map { f ->
                val id = f.nameWithoutExtension
                    .removePrefix("clip_")
                    .toLongOrNull() ?: 0L
                VideoClipEntity(
                    id = id,
                    fileName = f.name,
                    // 真实录制时间：固件给的相对年龄换算，取不到则从已知文件估算
                    capturedAt = bleTransfer.estimatedCapturedAt(id),
                    fileSize = f.length(),
                    durationMs = 5000,
                    localPath = f.absolutePath,
                    syncedAt = System.currentTimeMillis()
                )
            }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "BLE 同步",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "摄像头文件同步状态"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        CrashLogger.i(TAG, "Service onDestroy")
        scope.cancel()
        bleTransfer.disconnect()
        super.onDestroy()
    }
}
