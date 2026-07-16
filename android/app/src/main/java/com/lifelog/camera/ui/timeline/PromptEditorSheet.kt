package com.lifelog.camera.ui.timeline

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.lifelog.camera.data.model.CandidatePosition
import com.lifelog.camera.data.model.KimiSelection
import com.lifelog.camera.data.model.PipelineState

/**
 * 分步交互 Sheet
 *
 * Phase 1 (CandidatesReady): 候选标注图 + 可增删候选列表 → 确认 → Kimi
 * Phase 2 (KimiDone): Kimi 评分 + 生成 Prompt 编辑 → 确认 → 生成
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptEditorSheet(
    pipelineState: PipelineState,
    candidates: List<CandidatePosition>,
    annotatedBitmap: Bitmap?,
    kimiSelection: KimiSelection?,
    kimiPrompt: String,
    seedreamPrompt: String,
    onConfirmStep1: () -> Unit,
    onConfirmStep2: () -> Unit,
    onDeleteCandidate: (Int) -> Unit,
    onAddCandidate: (Float, Float, String) -> Unit,
    onKimiPromptChange: (String) -> Unit,
    onSeedreamPromptChange: (String) -> Unit,
    onResetKimiPrompt: () -> Unit,
    onBuildSeedreamPrompt: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val isStep1 = pipelineState is PipelineState.CandidatesReady
    val isStep2 = pipelineState is PipelineState.KimiDone

    // 添加候选对话框状态
    var showAddDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ═══ Phase 1: 候选预览 ═══
            if (isStep1) {
                Text("· 候选位置预览", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text("共 ${candidates.size} 个候选，可删除或添加，确认后开始筛选",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                // 标注图
                annotatedBitmap?.let { bmp ->
                    Spacer(Modifier.height(12.dp))
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "候选标注图",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                // 候选列表 (可删除)
                Spacer(Modifier.height(12.dp))
                candidates.forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("#${c.id} ", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.regionDescription, style = MaterialTheme.typography.bodySmall)
                            if (c.nearbyObjects.isNotEmpty()) {
                                Text("附近: ${c.nearbyObjects.take(3).joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(
                            onClick = { onDeleteCandidate(c.id) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, "删除候选${c.id}",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // 添加候选按钮
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加候选位置")
                }

                Spacer(Modifier.height(8.dp))

                // Prompt 编辑 (可选)
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起 Prompt ^" else "编辑 Prompt v")
                }
                if (expanded) {
                    OutlinedTextField(
                        value = kimiPrompt,
                        onValueChange = onKimiPromptChange,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("筛选 Prompt") }
                    )
                    TextButton(onClick = onResetKimiPrompt) { Text("恢复默认") }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onConfirmStep1,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("确认候选, 开始筛选") }
            }

            // ═══ Phase 2: Kimi 结果 + 生成 Prompt ═══
            if (isStep2) {
                Text("筛选完成", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))

                kimiSelection?.let { sel ->
                    // 选中的候选 — 高亮展示
                    val bestEval = sel.evaluations.find { it.id == sel.bestCandidateId }
                    if (bestEval != null || sel.bestCandidateId > 0) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("[选中] 候选 #${sel.bestCandidateId}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(2.dp))
                                bestEval?.let { ev ->
                                    Text("评分: ${ev.score}/5 分", style = MaterialTheme.typography.bodySmall)
                                    Text(ev.reason, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                sel.selectedInteraction?.let { inter ->
                                    Spacer(Modifier.height(6.dp))
                                    Text("✦ ${inter.obj}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(inter.detailedDescription,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // 未选中的候选 — 折叠显示
                    val rejected = sel.evaluations.filter { it.id != sel.bestCandidateId }
                    if (rejected.isNotEmpty()) {
                        var showRejected by remember { mutableStateOf(false) }
                        TextButton(onClick = { showRejected = !showRejected }) {
                            Text(if (showRejected) "收起未选中候选 ^" else ">  查看未选中的候选 (${rejected.size}个) v",
                                style = MaterialTheme.typography.labelMedium)
                        }
                        if (showRejected) {
                            rejected.forEach { ev ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("候选 #${ev.id} — ${ev.score}/5 分",
                                            style = MaterialTheme.typography.labelMedium)
                                        Text(ev.reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    // 元信息
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(" 光源: ${sel.lightDirection.ifBlank { "自动" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" 面向: ${sel.personFacing}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" 置信度: ${(sel.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // 警告
                    if (sel.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        sel.warnings.forEach { w ->
                            Text("!  $w",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(12.dp))

                Text("图像生成 Prompt", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = seedreamPrompt,
                    onValueChange = onSeedreamPromptChange,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("融合 Prompt") }
                )
                TextButton(onClick = onBuildSeedreamPrompt) { Text("自动构建") }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onConfirmStep2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("确认 Prompt, 开始生成") }
            }
        }
    }

    // ── 添加候选对话框 ──
    if (showAddDialog) {
        var desc by remember { mutableStateOf("") }
        var xPct by remember { mutableStateOf("50") }
        var yPct by remember { mutableStateOf("70") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加候选位置") },
            text = {
                Column {
                    Text("输入位置描述和画面中的大致坐标 (百分比)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("位置描述") },
                        placeholder = { Text("如: 画面左下角桌子旁") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = xPct,
                            onValueChange = { xPct = it },
                            label = { Text("水平位置 %") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = yPct,
                            onValueChange = { yPct = it },
                            label = { Text("垂直位置 %") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Text("提示: 左上角=(0,0), 右下角=(100,100)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val x = (xPct.toFloatOrNull() ?: 50f) / 100f
                    val y = (yPct.toFloatOrNull() ?: 70f) / 100f
                    onAddCandidate(x, y, desc)
                    showAddDialog = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }
}
