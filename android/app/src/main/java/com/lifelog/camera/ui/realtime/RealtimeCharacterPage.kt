package com.lifelog.camera.ui.realtime

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifelog.camera.ble.BleFileTransfer
import com.lifelog.camera.ble.BleSyncService
import java.io.File

/** 角色状态页 — 含同步条 + 角色面板 + FAB */
@Composable
fun RealtimeCharacterPage(viewModel: RealtimeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val effectiveStats by viewModel.effectiveStats.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val level by viewModel.totalLevel.collectAsState()
    val context = LocalContext.current

    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }

    val captureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val file = pendingCaptureFile
        if (result.resultCode == android.app.Activity.RESULT_OK && file != null && file.exists() && file.length() > 0) {
            viewModel.onPhoneVideoCaptured(file)
        }
        pendingCaptureFile = null
    }

    fun launchCapture() {
        val file = File(context.cacheDir, "phone_capture_${System.currentTimeMillis()}.mp4")
        file.parentFile?.mkdirs()
        pendingCaptureFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            putExtra(MediaStore.EXTRA_SIZE_LIMIT, 10L * 1024 * 1024)
            putExtra(MediaStore.EXTRA_DURATION_LIMIT, 5)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        captureLauncher.launch(intent)
    }

    fun startSync() {
        val intent = Intent(context, BleSyncService::class.java).apply {
            action = BleSyncService.ACTION_START_SYNC
        }
        try { context.startForegroundService(intent) } catch (_: Exception) {}
    }

    // 每日校准检查
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
    val calibratedToday = com.lifelog.camera.ai.ApiPreferences(context).getLastCalibrateDate() == today
    val feedbackDoneToday = com.lifelog.camera.ai.ApiPreferences(context).getLastFeedbackDate() == today

    // 21:00 后弹每日反馈
    val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    var showFeedbackDialog by remember { mutableStateOf(currentHour >= 21 && !feedbackDoneToday) }

    if (showFeedbackDialog) {
        AlertDialog(
            onDismissRequest = { showFeedbackDialog = false },
            title = { Text("🌙 今日状态回顾") },
            text = { Text("今天 AI 对你的状态判断准确吗？\n\n你的反馈会帮助明天更准确～") },
            confirmButton = {
                TextButton(onClick = {
                    showFeedbackDialog = false
                    com.lifelog.camera.ai.ApiPreferences(context).setLastFeedbackDate(today)
                }) { Text("👍 挺准的") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFeedbackDialog = false
                    com.lifelog.camera.ai.ApiPreferences(context).setLastFeedbackDate(today)
                }) { Text("👎 不太准") }
            }
        )
    }

    // 加载传感器数据
    LaunchedEffect(Unit) { viewModel.refreshSensors(context) }

    val titleSuffix = state.skills.maxByOrNull { it.exp }?.displayName ?: "冒险者"
    val titlePrefix = when {
        level >= 12 -> "传说"
        level >= 8 -> "专业"
        level >= 4 -> "熟练"
        else -> "新手"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 同步状态条 ──
            val (syncText, isActive) = when (syncState) {
                is BleFileTransfer.SyncState.Idle -> "🔗 就绪" to false
                is BleFileTransfer.SyncState.Scanning -> "扫描设备中..." to true
                is BleFileTransfer.SyncState.Connecting -> "连接中..." to true
                is BleFileTransfer.SyncState.Syncing -> "同步 ${(syncState as BleFileTransfer.SyncState.Syncing).current}/${(syncState as BleFileTransfer.SyncState.Syncing).total}" to true
                is BleFileTransfer.SyncState.Done -> "✅ 已同步 ${(syncState as BleFileTransfer.SyncState.Done).files} 个视频" to false
                is BleFileTransfer.SyncState.Error -> "⚠️ ${(syncState as BleFileTransfer.SyncState.Error).msg}" to false
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 分析中 或 同步中 显示转圈
                    if (isActive || state.isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    // 分析状态优先显示
                    val displayMsg = state.analysisMsg ?: syncText
                    val isError = state.analysisMsg?.contains("错误") == true
                    Text(
                        displayMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isActive && !state.isAnalyzing) {
                        if (isError) {
                            TextButton(
                                onClick = { viewModel.startRpgAnalysis() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("重试", style = MaterialTheme.typography.labelMedium,
                                     color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(
                            onClick = { startSync() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Sync, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("同步", style = MaterialTheme.typography.labelMedium)
                        }
                        if (!isError) {
                            TextButton(
                                onClick = { viewModel.startRpgAnalysis() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("分析", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 角色面板 ──
            CharacterPanel(
                latestHp = effectiveStats?.hp ?: state.latestStats?.hp ?: 50,
                latestMp = effectiveStats?.mp ?: state.latestStats?.mp ?: 50,
                latestMood = effectiveStats?.mood ?: state.latestStats?.mood ?: 50,
                latestMastery = effectiveStats?.mastery ?: state.latestStats?.mastery ?: 50,
                skills = state.skills,
                discoveredCount = state.todayDiscovered.size,
                todaySteps = state.todaySteps,
                screenMinutes = state.screenMinutes,
                locationContext = state.locationContext,
                level = level,
                title = "$titlePrefix$titleSuffix",
                onAddSkill = { name, lv -> viewModel.addManualSkill(name, lv) },
                onDeleteSkill = { skillName -> viewModel.deleteSkill(skillName) },
                onCalibrate = if (!calibratedToday) { { hp, mp, mood, m ->
                    viewModel.calibrateStats(hp, mp, mood, m)
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                    com.lifelog.camera.ai.ApiPreferences(context).setLastCalibrateDate(today)
                } } else null
            )
        }

        // FAB
        FloatingActionButton(
            onClick = { launchCapture() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 80.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "拍摄")
        }
    }
}
