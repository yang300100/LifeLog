package com.lifelog.camera.ui.realtime

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifelog.camera.ble.BleFileTransfer
import com.lifelog.camera.ble.BleSyncService
import java.io.File

@Composable
fun RealtimeRpgHost(
    onNavigateToSettings: () -> Unit,
    viewModel: RealtimeViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val state by viewModel.state.collectAsState()
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

    // 称号生成
    val titleSuffix = state.skills.maxByOrNull { it.exp }?.displayName ?: "冒险者"
    val titlePrefix = when {
        level >= 12 -> "传说"
        level >= 8 -> "专业"
        level >= 4 -> "熟练"
        else -> "新手"
    }
    val title = "$titlePrefix$titleSuffix"

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── 顶部栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⚡ 实时 RPG",
                     style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.Bold,
                     color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    com.lifelog.camera.util.TimeUtils.formatDate(
                        java.util.Calendar.getInstance()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Settings, "设置",
                         tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Tab 栏 ──
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("◉ 角色状态", fontSize = 13.sp) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("📜 活动日志", fontSize = 13.sp) }
                )
            }

            // ── Tab 内容 ──
            when (selectedTab) {
                0 -> CharacterPanel(
                    latestHp = state.latestStats?.hp ?: 50,
                    latestMp = state.latestStats?.mp ?: 50,
                    latestMood = state.latestStats?.mood ?: 50,
                    latestMastery = state.latestStats?.mastery ?: 50,
                    skills = state.skills,
                    discoveredCount = state.todayDiscovered.size,
                    level = level,
                    title = title
                )
                1 -> ActivityLog(
                    logItems = emptyList(),
                    captureCount = 0,
                    activityCount = state.todayDiscovered.size + state.skills.size,
                )
            }

            // ── 底部摄像头状态条 ──
            CameraStatusBar(syncState)
        }

        // 分析中指示器
        if (state.isAnalyzing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // FAB 拍照按钮
        FloatingActionButton(
            onClick = { launchCapture() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 24.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "拍摄")
        }
    }
}

@Composable
private fun CameraStatusBar(syncState: BleFileTransfer.SyncState) {
    val (text, color) = when (syncState) {
        is BleFileTransfer.SyncState.Idle -> "🔗 就绪" to MaterialTheme.colorScheme.onSurfaceVariant
        is BleFileTransfer.SyncState.Scanning -> "🔗 扫描中..." to MaterialTheme.colorScheme.primary
        is BleFileTransfer.SyncState.Connecting -> "🔗 连接中..." to MaterialTheme.colorScheme.primary
        is BleFileTransfer.SyncState.Syncing -> "🔗 同步中 ${syncState.current}/${syncState.total}" to MaterialTheme.colorScheme.primary
        is BleFileTransfer.SyncState.Done -> "✅ 已同步 ${syncState.files} 个视频" to MaterialTheme.colorScheme.primary
        is BleFileTransfer.SyncState.Error -> "⚠️ ${syncState.msg}" to MaterialTheme.colorScheme.error
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = color,
            fontSize = 12.sp
        )
    }
}
