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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private var isPersistent = false

    companion object {
        const val CHANNEL_ID = "ble_sync"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_SYNC = "com.lifelog.camera.START_SYNC"
        const val ACTION_START_PERSISTENT = "com.lifelog.camera.START_PERSISTENT"
        private const val TAG = "BleSyncSvc"
        private const val PERSISTENT_INTERVAL_MS = 300_000L  // 实时模式 5 分钟扫一次（跟固件拍摄周期对齐）
        private const val PERSISTENT_SCAN_TIMEOUT = 15_000L  // 每次扫 15 秒
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        CrashLogger.i(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashLogger.i(TAG, "onStartCommand action=${intent?.action}, flags=$flags")
        if (intent == null) {
            CrashLogger.w(TAG, "Service 被系统重启，intent 为 null，等待主动启动")
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START_SYNC -> startSync(oneShot = true)
            ACTION_START_PERSISTENT -> startSync(oneShot = false)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var notificationText = MutableStateFlow("准备扫描...")
    private val _notificationText: StateFlow<String> get() = notificationText

    private fun startSync(oneShot: Boolean) {
        if (syncJob?.isActive == true) {
            if (isPersistent == !oneShot) {
                CrashLogger.w(TAG, "已在${if(isPersistent)"实时" else "单次"}模式，忽略")
                return
            }
            // 切换模式：先停旧任务
            syncJob?.cancel()
        }

        isPersistent = !oneShot
        val modeLabel = if (isPersistent) "实时模式" else "日志模式"
        CrashLogger.i(TAG, "启动同步: $modeLabel")

        syncJob = scope.launch {
            // 启动前台服务
            try {
                val notification = buildNotification("$modeLabel - 准备扫描...")
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
                            is BleFileTransfer.SyncState.Idle -> "$modeLabel - 等待扫描"
                            is BleFileTransfer.SyncState.Scanning -> "$modeLabel - 扫描中..."
                            is BleFileTransfer.SyncState.Connecting -> "$modeLabel - 连接中..."
                            is BleFileTransfer.SyncState.Syncing -> "$modeLabel - 同步 ${state.current}/${state.total}"
                            is BleFileTransfer.SyncState.Done -> "$modeLabel - 完成 ${state.files} 文件"
                            is BleFileTransfer.SyncState.Error -> "$modeLabel - ${state.msg}"
                        }
                        try {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, buildNotification(text))
                        } catch (_: Exception) {}
                    }
                } catch (_: CancellationException) {}
                catch (e: Exception) { CrashLogger.e(TAG, "collect 异常", e) }
            }

            // 同步循环
            val videoDir = repository.getVideoDir()
            while (isPersistent || syncJob == coroutineContext[Job]) {
                CrashLogger.i(TAG, "开始扫描...")

                // 连接建立后，先把设置页面暂存的配置发到设备
                if (bleTransfer.hasPendingConfig) {
                    bleTransfer.flushPendingConfig()
                }

                val result = try {
                    bleTransfer.startSync(videoDir)
                } catch (e: SecurityException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(e)
                }

                // 同步完成后刷新设备信息缓存（设置页面展示用）
                if (result.isSuccess) {
                    try { bleTransfer.refreshCachedDeviceInfo() } catch (_: Exception) {}
                }

                result.onSuccess { count ->
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
                        // 实时模式：同步完成后立刻自动 RPG 分析
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
                    // 同步成功后：给所有缺 MP4 的 .mjpg 补转码（含历史遗留文件）
                    // .mjpg 是私有格式没法播，MP4 才是播放用的正式产物
                    try {
                        transcodeMissingMp4(videoDir)
                    } catch (e: Exception) {
                        CrashLogger.e(TAG, "批量转码异常", e)
                    }
                }

                result.onFailure { e ->
                    CrashLogger.w(TAG, "同步失败: ${e.message}")
                }

                if (!isPersistent) break
                // 等待 2 分钟后再次扫描
                delay(PERSISTENT_INTERVAL_MS)
            }

            // 实时模式结束才关前台
            if (!isPersistent) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
