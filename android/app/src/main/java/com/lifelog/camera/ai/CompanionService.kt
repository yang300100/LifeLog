package com.lifelog.camera.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lifelog.camera.MainActivity
import com.lifelog.camera.data.model.PipelineState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * 前台保活服务 — 管线运行时防止进程被杀
 *
 * 由 ViewModel 在 runPipeline/step1 时启动,
 * 在 PipelineState.Done/Error 时停止.
 */
@AndroidEntryPoint
class CompanionService : Service() {

    @Inject lateinit var pipeline: CompanionPipeline

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "companion_pipeline"
        const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, CompanionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CompanionService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    private var collectJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("准备中..."))

        // 取消旧的 collector, 避免重复订阅
        collectJob?.cancel()
        collectJob = scope.launch {
            pipeline.state.collect { state ->
                when (state) {
                    is PipelineState.Idle -> {}
                    is PipelineState.Done -> {
                        updateNotification("生成完成!")
                        delay(1500)
                        // 确认状态未变 (用户可能已触发新管线)
                        if (pipeline.state.value is PipelineState.Done) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                    is PipelineState.Error -> {
                        updateNotification("生成失败: ${state.msg}")
                        delay(3000)
                        if (pipeline.state.value is PipelineState.Error) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                    else -> {
                        val text = when (state) {
                            is PipelineState.Segmenting -> "分析场景中..."
                            is PipelineState.GeneratingCandidates -> "生成候选位置..."
                            is PipelineState.KimiSelecting -> "筛选中..."
                            is PipelineState.SeedreamGenerating -> "生成图像中..."
                            else -> "处理中..."
                        }
                        updateNotification(text)
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "AI 生成", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "LifeLog AI 图像生成"; setShowBadge(false) })
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val appIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LifeLog 同伴")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)  // 系统图标, 后续可换 app icon
            .setOngoing(true)
            .setContentIntent(appIntent)
            .build()
    }
}
