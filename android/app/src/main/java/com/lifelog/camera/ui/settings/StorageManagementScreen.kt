package com.lifelog.camera.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifelog.camera.data.local.StorageDayStats
import com.lifelog.camera.data.local.entity.VideoClipEntity
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagementScreen(
    onBack: () -> Unit,
    viewModel: StorageManagementViewModel = hiltViewModel()
) {
    val dayStats by viewModel.dayStats.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val expandedDate by viewModel.expandedDate.collectAsState()
    val expandedClips by viewModel.expandedClips.collectAsState()
    val totalBytes by viewModel.totalBytes.collectAsState()
    val totalClips by viewModel.totalClips.collectAsState()
    val context = LocalContext.current

    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteDayTarget by remember { mutableStateOf<String?>(null) }
    var deleteSingleTarget by remember { mutableStateOf<VideoClipEntity?>(null) }

    // 每次进入页面自动刷新数据
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Toast 消息
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 删除确认对话框
    deleteDayTarget?.let { date ->
        AlertDialog(
            onDismissRequest = { deleteDayTarget = null },
            title = { Text("删除 $date 的全部源文件") },
            text = { Text("将删除 ${date} 当天所有视频的 .mjpg 和 .mp4 文件。\n\n数据库记录和分析结果会保留。\n\n删除后视频无法播放。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDaySourceFiles(date)
                    deleteDayTarget = null
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteDayTarget = null }) { Text("取消") } }
        )
    }

    deleteSingleTarget?.let { clip ->
        AlertDialog(
            onDismissRequest = { deleteSingleTarget = null },
            title = { Text("删除源文件") },
            text = { Text("删除 ${clip.fileName} 的源文件（${formatBytes(clip.fileSize)}）？\n\n分析记录会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSingleSourceFile(clip)
                    deleteSingleTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteSingleTarget = null }) { Text("取消") } }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("删除全部源文件") },
            text = { Text("将删除所有 ${totalClips} 个视频的源文件（共 ${formatBytes(totalBytes)}）。\n\n数据库记录和分析结果会保留。\n\n此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllSourceFiles()
                    showDeleteAllDialog = false
                }) { Text("全部删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteAllDialog = false }) { Text("取消") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        TopAppBar(
            title = { Text("存储管理") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回")
                }
            },
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, "刷新")
                }
            }
        )

        if (isLoading && dayStats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (dayStats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无视频文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 概览卡片
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("💾 存储概览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(formatBytes(totalBytes), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text("总占用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$totalClips", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text("片段数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { showDeleteAllDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.DeleteForever, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("一键清理全部源文件")
                            }
                        }
                    }
                }

                // 按日期列表
                items(dayStats, key = { it.dateStr }) { stat ->
                    DayStorageRow(
                        stat = stat,
                        isExpanded = expandedDate == stat.dateStr,
                        clips = if (expandedDate == stat.dateStr) expandedClips else emptyList(),
                        onToggle = { viewModel.toggleDate(stat.dateStr) },
                        onDeleteDay = { deleteDayTarget = stat.dateStr },
                        onDeleteSingle = { deleteSingleTarget = it }
                    )
                }

                // 底部留白
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun DayStorageRow(
    stat: StorageDayStats,
    isExpanded: Boolean,
    clips: List<VideoClipEntity>,
    onToggle: () -> Unit,
    onDeleteDay: () -> Unit,
    onDeleteSingle: (VideoClipEntity) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable { onToggle() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("📅 ${stat.dateStr}", fontWeight = FontWeight.Medium)
                }
                Text(
                    "${stat.clipCount} 片段 · ${formatBytes(stat.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 操作按钮（始终显示，不展开也能操作）
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDeleteDay) {
                    Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除当天源文件", style = MaterialTheme.typography.labelSmall)
                }
            }

            // 展开的片段列表
            AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    clips.forEach { clip ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Videocam,
                                    contentDescription = null,
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    clip.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatBytes(clip.fileSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { onDeleteSingle(clip) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "删除源文件",
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 格式化字节数为可读字符串 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
