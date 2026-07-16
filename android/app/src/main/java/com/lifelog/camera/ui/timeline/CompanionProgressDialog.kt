package com.lifelog.camera.ui.timeline

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lifelog.camera.data.model.PipelineState

/**
 * Companion 新管线进度弹窗 (B方案)
 *
 * 步骤: 分割 → 候选生成 → 标注图 → Kimi筛选 → Seedream生成 → 完成
 */
@Composable
fun CompanionProgressDialog(
    state: PipelineState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onViewGallery: () -> Unit
) {
    // Idle / CandidatesReady / KimiDone — 不显示进度弹窗 (由 PromptEditorSheet 处理)
    if (state is PipelineState.Idle ||
        state is PipelineState.CandidatesReady ||
        state is PipelineState.KimiDone) return

    val isTerminal = state is PipelineState.Done || state is PipelineState.Error

    Dialog(
        onDismissRequest = { if (isTerminal) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = isTerminal,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state) {
                    is PipelineState.Segmenting -> {
                        ProgressStep("图像分割中", "正在分析场景物体...", 0)
                    }
                    is PipelineState.GeneratingCandidates -> {
                        ProgressStep("候选位置计算", "已生成 ${state.count} 个候选位置", 1)
                    }
                    is PipelineState.BuildingAnnotated -> {
                        ProgressStep("生成标注图", "正在标注分割结果与候选位置...", 2)
                    }
                    is PipelineState.KimiSelecting -> {
                        ProgressStep("筛选中", "AI 正在评估最佳位置与互动方式...", 3)
                    }
                    is PipelineState.SeedreamGenerating -> {
                        ProgressStep("图像生成中", "多图融合, 将角色自然融入场景...", 4)
                    }
                    is PipelineState.PostProcessing -> {
                        ProgressStep("后处理中", "色彩增强 + 柔焦 + 胶片颗粒...", 5)
                    }
                    is PipelineState.Done -> {
                        // 成功!
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("生成完成!",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("关闭") }
                            Button(
                                onClick = onViewGallery,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("查看画廊") }
                        }
                    }
                    is PipelineState.Error -> {
                        Icon(
                            Icons.Default.Error,
                            null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("生成失败",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error)
                        Text(state.msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("关闭") }
                    }
                    else -> {}
                }

                // 取消按钮（非终态）
                if (!isTerminal) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun ProgressStep(title: String, subtitle: String, stepIndex: Int) {
    CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        strokeWidth = 4.dp
    )
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    // 步骤指示器
    Spacer(Modifier.height(12.dp))
    val stepLabels = listOf("分割", "候选", "标注图", "筛选", "生成", "后处理")
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        for (i in stepLabels.indices) {
            Text(
                when {
                    i < stepIndex -> "○"
                    i == stepIndex -> "●"
                    else -> "·"
                },
                color = if (i <= stepIndex) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 3.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${stepIndex + 1}/6 ${stepLabels.getOrElse(stepIndex) { "" }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
