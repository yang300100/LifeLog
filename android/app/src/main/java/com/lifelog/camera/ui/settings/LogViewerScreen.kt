package com.lifelog.camera.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelog.camera.util.CrashLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var logFiles by remember { mutableStateOf(CrashLogger.getLogFiles()) }
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空日志") },
            text = { Text("确定要删除所有日志文件吗？") },
            confirmButton = {
                TextButton(onClick = {
                    CrashLogger.clearLogs()
                    logFiles = emptyList()
                    selectedFile = null
                    showClearDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    if (selectedFile != null) {
        // 日志内容详情
        val content = remember(selectedFile) { CrashLogger.readLogFile(selectedFile!!) }
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(selectedFile!!.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { selectedFile = null }) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showClearDialog = true }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = {
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, content)
                        }
                        context.startActivity(android.content.Intent.createChooser(shareIntent, "分享日志"))
                    }) {
                        Icon(Icons.Default.Share, "分享")
                    }
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    content,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    } else {
        // 日志文件列表
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("崩溃日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (logFiles.isNotEmpty()) {
                        TextButton(onClick = {
                            val filename = CrashLogger.exportAllLogsToDownload()
                            Toast.makeText(context,
                                if (filename != null) "已导出: Download/LifeLog/$filename"
                                else "导出失败", Toast.LENGTH_LONG).show()
                        }) { Text("导出", color = MaterialTheme.colorScheme.primary) }
                        TextButton(onClick = { showClearDialog = true }) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )

            if (logFiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("没有崩溃日志", style = MaterialTheme.typography.bodyLarge)
                        Text("应用运行正常", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logFiles) { file ->
                        LogFileRow(file, onClick = { selectedFile = file })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFileRow(file: File, onClick: () -> Unit) {
    val dateStr = remember(file) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.format(Date(file.lastModified()))
        } catch (_: Exception) {
            "未知时间"
        }
    }
    val isCrash = file.name.startsWith("crash_")
    val sizeKB = file.length() / 1024.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isCrash) Icons.Default.Error else Icons.Default.Description,
                contentDescription = null,
                tint = if (isCrash) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCrash) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "$dateStr · %.1f KB".format(sizeKB),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
