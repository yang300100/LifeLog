package com.lifelog.camera.ui.timeline

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.lifelog.camera.data.model.PipelineState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifelog.camera.ble.BleFileTransfer
import com.lifelog.camera.ble.BleSyncService
import com.lifelog.camera.ui.theme.PeachSuccess
import com.lifelog.camera.ui.theme.PeachSuccessContainer
import java.io.File

/**
 * 播放片段：ESP32 片段的 localPath 是 .mjpg（私有格式，播放器不认识），
 * 实际播放同步后转码出的同名 .mp4；手机拍摄的片段 localPath 本身就是 .mp4。
 */
private fun playClipFile(context: android.content.Context, localPath: String) {
    val playFile = if (localPath.endsWith(".mjpg")) {
        File(localPath.removeSuffix(".mjpg") + ".mp4")
    } else {
        File(localPath)
    }
    if (!playFile.exists() || playFile.length() < 100) {
        Toast.makeText(context, if (playFile.exists()) "视频正在转码，稍后再试~"
                                 else "视频还在转码中，稍后再试~",
                       Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", playFile
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "没有找到可以播放视频的应用", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {}
}

/** 启动摄像头同步（尊重实时/日志模式），多处按钮共用 */
private fun startCameraSync(context: android.content.Context, realtime: Boolean) {
    val action = if (realtime) BleSyncService.ACTION_START_PERSISTENT
                 else BleSyncService.ACTION_START_SYNC
    val intent = Intent(context, BleSyncService::class.java).apply { this.action = action }
    try { context.startForegroundService(intent) } catch (_: Exception) {}
}

@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel = hiltViewModel(),
    onNavigateToLog: () -> Unit = {}
) {
    val items by viewModel.todayItems.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val unanalyzed by viewModel.unanalyzedCount.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val analysisMsg by viewModel.analysisMsg.collectAsState()

    // Companion
    val companionVM: CompanionViewModel = hiltViewModel()
    val companionFrames by companionVM.extractedFrames.collectAsState()
    val companionSelectedFrame by companionVM.selectedFrameIndex.collectAsState()
    val companionIsExtracting by companionVM.isExtracting.collectAsState()
    val companionState by companionVM.pipelineState.collectAsState()

    var showFrameSheet by remember { mutableStateOf(false) }
    var showPromptSheet by remember { mutableStateOf(false) }
    var showCompanionProgress by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    val analysisError by viewModel.analysisError.collectAsState()
    val analyzingClipIds by viewModel.analyzingClipIds.collectAsState()
    val dateLabel by viewModel.selectedDateLabel.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val isToday = selectedDate >= com.lifelog.camera.util.TimeUtils.getDayStart()
    val context = LocalContext.current

    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    var showSyncCapsule by remember { mutableStateOf(false) }
    var zoomedClipId by remember { mutableStateOf<Long?>(null) }

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

    // 页面首次加载时自动启动后台定时同步（每 5 分钟扫一次，与固件拍摄周期对齐）
    // 如果已在跑，Service 内部会检测并忽略重复启动
    LaunchedEffect(Unit) {
        startCameraSync(context, realtime = true)
    }

    // 同步状态变化时显示胶囊
    LaunchedEffect(syncState, isAnalyzing) {
        val isActive = syncState !is BleFileTransfer.SyncState.Idle &&
                       syncState !is BleFileTransfer.SyncState.Done
        if (isActive || isAnalyzing) {
            showSyncCapsule = true
        } else if (syncState is BleFileTransfer.SyncState.Done || syncState is BleFileTransfer.SyncState.Error) {
            showSyncCapsule = true
            kotlinx.coroutines.delay(3000)
            showSyncCapsule = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 同步状态胶囊（动画显示/隐藏）
            AnimatedVisibility(
                visible = showSyncCapsule,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SyncCapsule(
                    state = syncState,
                    isAnalyzing = isAnalyzing,
                    analysisMsg = analysisMsg,
                    analysisError = analysisError,
                    unanalyzed = unanalyzed,
                    onSync = { startCameraSync(context, viewModel.isRealtimeMode()) },
                    onAnalyze = { viewModel.runAnalysis() },
                    onReanalyze = { viewModel.reanalyzeAll() }
                )
            }

            // 日期导航条（右侧常驻同步按钮 —— 有视频了也要能随时手动同步）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(40.dp))  // 左侧配重，让日期保持视觉居中
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = { viewModel.goToPrevDay() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ChevronLeft, "前一天", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(dateLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    IconButton(
                        onClick = { viewModel.goToNextDay() }, modifier = Modifier.size(28.dp),
                        enabled = !isToday
                    ) {
                        Icon(Icons.Default.ChevronRight, "后一天",
                             tint = if (isToday) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                   else MaterialTheme.colorScheme.primary)
                    }
                    if (!isToday) {
                        TextButton(onClick = { viewModel.goToToday() }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("今天", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                val syncActive = syncState is BleFileTransfer.SyncState.Scanning ||
                                 syncState is BleFileTransfer.SyncState.Connecting ||
                                 syncState is BleFileTransfer.SyncState.Syncing
                IconButton(
                    onClick = { startCameraSync(context, viewModel.isRealtimeMode()) },
                    enabled = !syncActive,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (syncActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(Icons.Default.Sync, "同步摄像头",
                             tint = MaterialTheme.colorScheme.primary,
                             modifier = Modifier.size(20.dp))
                    }
                }
            }

            if (items.isEmpty()) {
                // 空状态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(80.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shadowElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            "今天的故事还没开始写呢~",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { startCameraSync(context, false) },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("同步摄像头")
                            }
                            OutlinedButton(
                                onClick = { launchCapture() },
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.Videocam, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("拍摄")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 底部留足空间，最新的视频不会被右下角的悬浮按钮（拍摄/图库）挡住
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 170.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(items) { item ->
                        TimelineStickyNote(
                            item = item,
                            isAnalyzing = item.clip.id in analyzingClipIds,
                            onCardClick = { zoomedClipId = item.clip.id },
                            onPlay = { playClipFile(context, item.clip.localPath) },
                            onDelete = {
                                viewModel.deleteClip(item.clip.id, item.clip.localPath)
                            },
                            onAnalyze = {
                                viewModel.analyzeSingleClip(item.clip.id)
                            }
                        )
                    }
                }
            }
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

        // ── 放大卡片弹窗（从 items 实时查找 + key 强制刷新） ──
        zoomedClipId?.let { clipId ->
            val currentItem = items.find { it.clip.id == clipId }
            if (currentItem != null) {
                // key 包含 labels 数量，分析完成后 Dialog 自动重建显示新标签
                key(clipId, currentItem.labels.size) {
                    ZoomedCardOverlay(
                        item = currentItem,
                        isAnalyzing = clipId in analyzingClipIds,
                        onDismiss = { zoomedClipId = null },
                        onPlay = { playClipFile(context, currentItem.clip.localPath) },
                    onDelete = {
                        viewModel.deleteClip(currentItem.clip.id, currentItem.clip.localPath)
                        zoomedClipId = null
                    },
                    onAnalyze = { viewModel.analyzeSingleClip(clipId) },
                    onCompanion = {
                        companionVM.startFrameExtraction(currentItem.clip.localPath, clipId)
                        showFrameSheet = true
                    }
                )
                }  // key
            }
        }

        // ── 同伴: 帧选择 Sheet ──
        if (showFrameSheet) {
            FrameSelectionSheet(
                frames = companionFrames,
                selectedIndex = companionSelectedFrame,
                isExtracting = companionIsExtracting,
                onSelectFrame = { companionVM.selectFrame(it) },
                onConfirm = {
                    showFrameSheet = false
                    // Step1: 生成候选 + 标注图
                    companionVM.step1_generateCandidates()
                    showCompanionProgress = true
                },
                onDismiss = { showFrameSheet = false }
            )
        }

        // ── 管线进度/状态监听 ──
        val pipelineState by companionVM.pipelineState.collectAsState()
        LaunchedEffect(pipelineState) {
            when (pipelineState) {
                is PipelineState.CandidatesReady -> {
                    // Step1 完成 → 展示候选
                    showCompanionProgress = false
                    showPromptSheet = true
                }
                is PipelineState.KimiDone -> {
                    // Step2 完成 → 展示 Kimi 结果 + Seedream Prompt
                    showCompanionProgress = false
                    showPromptSheet = true
                }
                is PipelineState.Done -> {
                    showCompanionProgress = false
                    showPromptSheet = false
                    companionVM.refreshGallery()
                }
                is PipelineState.Error -> {
                    showCompanionProgress = true  // 进度弹窗会显示错误
                }
                else -> {}
            }
        }

        // ── 同伴: 提示词编辑 Sheet (分步) ──
        if (showPromptSheet) {
            val kimiPrompt by companionVM.kimiPrompt.collectAsState()
            val seedreamPrompt by companionVM.seedreamPrompt.collectAsState()
            val candidates by companionVM.candidates.collectAsState()
            val kimiSelection by companionVM.kimiSelection.collectAsState()
            val annotatedBmp by companionVM.annotatedBitmap.collectAsState()

            PromptEditorSheet(
                pipelineState = pipelineState,
                candidates = candidates,
                annotatedBitmap = annotatedBmp,
                kimiSelection = kimiSelection,
                kimiPrompt = kimiPrompt,
                seedreamPrompt = seedreamPrompt,
                onConfirmStep1 = {
                    showPromptSheet = false
                    showCompanionProgress = true
                    companionVM.step2_kimiSelect()
                },
                onConfirmStep2 = {
                    showPromptSheet = false
                    showCompanionProgress = true
                    companionVM.step3_seedreamGenerate()
                },
                onDeleteCandidate = { id -> companionVM.deleteCandidate(id) },
                onAddCandidate = { x, y, desc -> companionVM.addCandidate(x, y, desc) },
                onKimiPromptChange = companionVM::updateKimiPrompt,
                onSeedreamPromptChange = companionVM::updateSeedreamPrompt,
                onResetKimiPrompt = companionVM::resetKimiPrompt,
                onBuildSeedreamPrompt = companionVM::buildDefaultSeedreamPrompt,
                onDismiss = { showPromptSheet = false }
            )
        }

        // ── 同伴: 进度弹窗 ──
        if (showCompanionProgress) {
            CompanionProgressDialog(
                state = pipelineState,
                onDismiss = { showCompanionProgress = false },
                onCancel = {
                    companionVM.cancel()
                    showCompanionProgress = false
                },
                onViewGallery = {
                    showCompanionProgress = false
                    showGallery = true
                }
            )
        }

        // ── 同伴: 画廊页面 ──
        if (showGallery) {
            val generations by companionVM.generations.collectAsState()
            CompanionGalleryScreen(
                generations = generations,
                onDeleteGeneration = companionVM::deleteGeneration,
                onBack = {
                    showGallery = false
                    companionVM.refreshGallery()
                }
            )
        }

        // ── 画廊入口按钮 (右下, 拍摄FAB正上方, 垂直叠放) ──
        FloatingActionButton(
            onClick = {
                companionVM.refreshGallery()
                showGallery = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 152.dp),
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ) {
            Icon(Icons.Default.Person, "陪伴图库")
        }
    }

}

// ── 同步状态胶囊 ──

@Composable
fun SyncCapsule(
    state: BleFileTransfer.SyncState,
    isAnalyzing: Boolean,
    analysisMsg: String?,
    analysisError: String?,
    unanalyzed: Int,
    onSync: () -> Unit,
    onAnalyze: () -> Unit,
    onReanalyze: () -> Unit
) {
    val (text, isActive) = when (state) {
        is BleFileTransfer.SyncState.Idle -> "就绪" to false
        is BleFileTransfer.SyncState.Scanning -> "扫描设备中..." to true
        is BleFileTransfer.SyncState.Connecting -> "连接中..." to true
        is BleFileTransfer.SyncState.Syncing -> "同步 ${state.current}/${state.total}" to true
        is BleFileTransfer.SyncState.Done -> "已同步 ${state.files} 个视频" to false
        is BleFileTransfer.SyncState.Error -> "同步失败: ${state.msg}" to false
    }

    val bgColor = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer
        state is BleFileTransfer.SyncState.Error || analysisError != null ->
            MaterialTheme.colorScheme.errorContainer
        state is BleFileTransfer.SyncState.Done -> PeachSuccessContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isActive || isAnalyzing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
            } else if (state is BleFileTransfer.SyncState.Done) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = PeachSuccess
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                if (isAnalyzing) analysisMsg ?: "分析中..."
                else text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!isActive && !isAnalyzing) {
                if (analysisError != null) {
                    TextButton(
                        onClick = onAnalyze,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("重试", style = MaterialTheme.typography.labelMedium) }
                } else {
                    if (unanalyzed > 0) {
                        TextButton(
                            onClick = onAnalyze,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("分析($unanalyzed)", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

// ── 时间轴便签卡片 ──

@Composable
fun TimelineStickyNote(
    item: TimelineViewModel.TimelineItem,
    isAnalyzing: Boolean = false,
    onCardClick: () -> Unit = {},
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onAnalyze: () -> Unit = {}
) {
    val hasLabels = item.labels.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // 左侧时间轴（时间圆点 + 竖线，竖线自动填充卡片高度）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight()
        ) {
            Text(
                item.time,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (hasLabels) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasLabels) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
            )
            // 竖线自动填充剩余高度，确保连接到下一条目的圆点
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 右侧便签卡片 — 点击放大到 3/4 屏幕
        Card(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 6.dp)
                .clickable { onCardClick() },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (hasLabels) {
                        val firstLabel = item.labels.first()
                        Text(
                            firstLabel.behavior.replace("_", " "),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            firstLabel.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            "待分析",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "%.1f KB".format(item.clip.fileSize / 1024.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "查看详情",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 放大卡片弹窗 (3/4 屏幕，Dialog 级别覆盖日期栏) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoomedCardOverlay(
    item: TimelineViewModel.TimelineItem,
    isAnalyzing: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onAnalyze: () -> Unit,
    onCompanion: () -> Unit = {}
) {
    val hasLabels = item.labels.isNotEmpty()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }

    // 入场动画
    LaunchedEffect(Unit) { visible = true }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除视频") },
            text = { Text("确定要删除 ${item.time} 的视频记录吗？\n关联的活动标签也会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    Dialog(
        onDismissRequest = { visible = false },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        // 入场/退场动画
        LaunchedEffect(visible) {
            if (!visible) {
                kotlinx.coroutines.delay(300)  // 等动画播完
                onDismiss()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (visible) 0.1f else 0f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { visible = false }
        ) {
            // 卡片带缩放动画
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.Center),
                enter = scaleIn(
                    initialScale = 0.85f,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = 0.6f, stiffness = 400f
                    )
                ) + fadeIn(),
                exit = scaleOut(targetScale = 0.85f) + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.75f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* 阻止穿透 */ },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                // 顶部：时间 + 关闭
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🕘 ${item.time}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "%.1f KB · ${item.clip.durationMs / 1000.0}s".format(
                        item.clip.fileSize / 1024.0
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 播放按钮
                Button(
                    onClick = onPlay,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("播放视频", style = MaterialTheme.typography.bodyLarge)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 开始分析结果
                if (hasLabels) {
                    Text(
                        "🤖 开始分析",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item.labels.forEach { label ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            label.behavior.replace("_", " "),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                "${(label.confidence * 100).toInt()}%",
                                                modifier = Modifier.padding(
                                                    horizontal = 8.dp, vertical = 2.dp
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        label.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 未分析
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🔍", style = MaterialTheme.typography.displaySmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "该视频尚未分析",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "点击下方「分析」按钮获取 AI 行为标签",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isAnalyzing) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("分析中...", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = onAnalyze,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (hasLabels) "重新分析" else "开始分析")
                        }
                    }
                    // 同伴按钮
                    OutlinedButton(
                        onClick = onCompanion,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Icon(Icons.Default.Person, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("同伴")
                    }
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("删除")
                    }
                }
            }
                }  // Card
            }  // AnimatedVisibility
        }  // Box
    }  // Dialog
}
