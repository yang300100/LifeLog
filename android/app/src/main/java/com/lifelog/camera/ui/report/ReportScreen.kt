package com.lifelog.camera.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifelog.camera.data.local.entity.ActivityLabelEntity
import com.lifelog.camera.ui.components.ActivityBubble
import com.lifelog.camera.ui.components.ActivityBubbleData

@Composable
fun ReportScreen(viewModel: ReportViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val analysisState by viewModel.analysisState.collectAsState()
    val unanalyzed by viewModel.unanalyzedCount.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {

        if (report == null && !isAnalyzing) {
            // 空状态 — 信封插画
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 48.dp)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Column(
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Text(
                            "「时间暂停本是神技",
                            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                        Text(
                            "　但摄影和记录让我们窥探了神的技能」",
                            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.padding(start = 36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { viewModel.runAnalysis() },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (unanalyzed > 0) "开始分析 ($unanalyzed)" else "开始分析")
                    }
                }
            }
        } else if (isAnalyzing && report == null) {
            // 分析中
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        analysisState ?: "分析中...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            report?.let { r ->
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 信笺卡片
                    item {
                        LetterCard(
                            date = r.date,
                            summary = r.summary
                        )
                    }

                    if (r.activities.isNotEmpty()) {
                        // 活动概览标题
                        item {
                            Text(
                                "今日活动概览",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                            )
                        }

                        // 活动圆钮行
                        item {
                            ActivityBubblesRow(activities = r.activities)
                        }

                        // 时间线回顾标题
                        item {
                            Text(
                                "时间线回顾",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                            )
                        }

                        items(r.activities) { activity ->
                            CompactTimelineRow(
                                timestamp = activity.timestamp,
                                category = activity.category,
                                description = activity.description
                            )
                        }
                    }

                    // 重新生成按钮
                    item {
                        OutlinedButton(
                            onClick = { viewModel.generateReport() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("重新生成日报")
                        }
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }
}

// ── 信笺卡片 ──

@Composable
fun LetterCard(date: String, summary: String) {
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .drawBehind {
                    // 信纸格线（左侧淡竖线）
                    drawLine(
                        color = lineColor,
                        start = Offset(size.width * 0.15f, 0f),
                        end = Offset(size.width * 0.15f, size.height),
                        strokeWidth = 2f
                    )
                }
        ) {
            Column {
                // 桃子装饰 + 日期
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🍑", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        date,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "亲爱的我，",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                )

                Spacer(modifier = Modifier.height(16.dp))

                // AI 签名
                Text(
                    "── Yuki",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

// ── 活动圆钮行 ──

@Composable
fun ActivityBubblesRow(activities: List<ActivityLabelEntity>) {
    // 按 category 聚合
    val grouped = activities.groupBy { it.category }
    val bubbles = grouped.map { (category, acts) ->
        val emoji = when (category.lowercase()) {
            "work" -> "🖥️"
            "eat" -> "🍜"
            "outdoor", "walk" -> "🚶"
            "transport" -> "🚗"
            "sport", "exercise" -> "🏃"
            "sleep" -> "😴"
            "home" -> "🏠"
            else -> "📌"
        }
        val name = when (category.lowercase()) {
            "work" -> "工作"
            "eat" -> "用餐"
            "outdoor" -> "户外"
            "walk" -> "散步"
            "transport" -> "通勤"
            "sport" -> "运动"
            "exercise" -> "运动"
            "sleep" -> "睡眠"
            "home" -> "居家"
            else -> category
        }
        val totalMin = (acts.maxOf { it.timestamp } - acts.minOf { it.timestamp }) / 60000
        val duration = if (totalMin >= 60) "${totalMin / 60}h${totalMin % 60}m"
                       else "${totalMin}m"
        ActivityBubbleData(emoji, name, duration)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)
    ) {
        bubbles.take(5).forEach { data ->
            ActivityBubble(
                data = data,
                modifier = Modifier.size(76.dp)
            )
        }
    }
}

// ── 紧凑时间线行 ──

@Composable
fun CompactTimelineRow(timestamp: Long, category: String, description: String) {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val timeStr = "%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 时间圆点
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            timeStr,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(42.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                category.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
