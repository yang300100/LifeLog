package com.lifelog.camera.ui.realtime

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifelog.camera.ui.components.EventLogData

/** 活动日志页 — 含点击放大弹窗（播放+分析+删除） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealtimeActivityLogPage(viewModel: RealtimeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val logItems by viewModel.eventLogItems.collectAsState()
    val dateLabel by viewModel.selectedDateLabel.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val isToday = selectedDate >= com.lifelog.camera.util.TimeUtils.getDayStart()
    var zoomedItem by remember { mutableStateOf<EventLogData?>(null) }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        // 日期导航条
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { viewModel.goToPrevDay() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ChevronLeft, "前一天", tint = MaterialTheme.colorScheme.primary)
            }
            Text(dateLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(
                onClick = { viewModel.goToNextDay() },
                modifier = Modifier.size(32.dp),
                enabled = !isToday
            ) {
                Icon(Icons.Default.ChevronRight, "后一天",
                     tint = if (isToday) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                           else MaterialTheme.colorScheme.primary)
            }
            if (!isToday) {
                TextButton(onClick = { viewModel.goToToday() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("今天", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            ActivityLog(
                logItems = logItems.map { it.copy(onClick = { zoomedItem = it }) },
                captureCount = logItems.size,
                activityCount = logItems.count { it.isAnalyzed },
            )

            // 放大弹窗
            zoomedItem?.let { item ->
                LogZoomOverlay(
                item = item,
                isAnalyzing = state.isAnalyzing,
                onDismiss = { zoomedItem = null },
                onPlay = {
                    val file = java.io.File(item.localPath)
                    if (file.exists()) {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    }
                },
                onAnalyze = { viewModel.startRpgAnalysis() },
                onDelete = {
                    viewModel.deleteClip(item.clipId, item.localPath)
                    zoomedItem = null
                }
            )
        }
    }
}
}

// ── 日志放大弹窗 ──

@Composable
private fun LogZoomOverlay(
    item: EventLogData,
    isAnalyzing: Boolean = false,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onAnalyze: () -> Unit,
    onDelete: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { visible = true }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除视频") },
            text = { Text("确定要删除 ${item.time} 的视频记录吗？") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    Dialog(
        onDismissRequest = { visible = false },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        LaunchedEffect(visible, item.clipId) {
            if (!visible) {
                kotlinx.coroutines.delay(300); onDismiss()
            }
        }

        Box(
            modifier = Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = if (visible) 0.3f else 0f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { visible = false }
        ) {
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.Center),
                enter = scaleIn(
                    initialScale = 0.85f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f)
                ) + fadeIn(),
                exit = scaleOut(targetScale = 0.85f) + fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.75f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {},
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
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
                            IconButton(
                                onClick = { visible = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    "关闭",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (item.isAnalyzed) "已分析" else "待分析 · ${item.skillExpText ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 播放按钮
                        Button(
                            onClick = onPlay,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("播放视频", style = MaterialTheme.typography.bodyLarge)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 内容区
                        if (item.isAnalyzed) {
                            Text(
                                "🤖 分析结果",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(
                                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            "${item.emoji} ${item.description}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (item.statChanges != null) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                item.statChanges,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("⏳", style = MaterialTheme.typography.displaySmall)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "该视频尚未分析",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                // 分析中显示加载状态
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "分析中...",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onAnalyze,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (item.isAnalyzed) "重新分析" else "AI 分析")
                                }
                            }
                            OutlinedButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }
}
