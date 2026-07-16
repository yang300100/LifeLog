package com.lifelog.camera.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lifelog.camera.data.model.CompanionGenerationSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Companion 陪伴图库页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionGalleryScreen(
    generations: List<CompanionGenerationSummary>,
    onDeleteGeneration: (String) -> Unit,
    onBack: () -> Unit
) {
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }
    var previewPath by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("陪伴图库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (generations.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Collections,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("还没有生成的陪伴图",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("在时间轴中点击视频 → 同伴 → 生成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(generations) { gen ->
                    GalleryItem(
                        summary = gen,
                        onClick = { previewPath = gen.thumbnailPath },
                        onLongPress = { deleteConfirmId = gen.generationId }
                    )
                }
            }
        }
    }

    // 删除确认对话框
    deleteConfirmId?.let { genId ->
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("删除这张陪伴图?") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteGeneration(genId)
                    deleteConfirmId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) { Text("取消") }
            }
        )
    }

    // 全屏预览 (原图分辨率)
    previewPath?.let { path ->
        if (path.isNotBlank() && File(path).exists()) {
            Dialog(
                onDismissRequest = { previewPath = null },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    usePlatformDefaultWidth = false  // 全屏
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { previewPath = null },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = "陪伴图预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryItem(
    summary: CompanionGenerationSummary,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress
                )
        ) {
            if (summary.thumbnailPath.isNotBlank() && File(summary.thumbnailPath).exists()) {
                AsyncImage(
                    model = File(summary.thumbnailPath),
                    contentDescription = "陪伴图",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                    contentScale = ContentScale.FillWidth
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.BrokenImage, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
                Text(
                    "片段 #${summary.clipId}",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    dateFormat.format(Date(summary.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
