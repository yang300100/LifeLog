package com.lifelog.camera.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KEYWORD_EMOJI_MAP = mapOf(
        "code" to "💻", "program" to "💻", "dev" to "💻",
        "cook" to "🍳", "bake" to "🍳", "kitchen" to "🍳",
        "piano" to "🎹", "music" to "🎵", "guitar" to "🎸", "drum" to "🥁",
        "english" to "🇬🇧", "japanese" to "🇯🇵", "language" to "🗣️",
        "read" to "📖", "book" to "📖",
        "write" to "✍️", "writing" to "✍️",
        "game" to "🎮", "gaming" to "🎮",
        "photo" to "📷", "camera" to "📷", "photography" to "📷",
        "draw" to "🎨", "art" to "🎨", "paint" to "🎨", "sketch" to "🎨",
        "swim" to "🏊", "swimming" to "🏊",
        "bike" to "🚴", "cycle" to "🚴", "cycling" to "🚴",
        "yoga" to "🧘", "meditate" to "🧘",
        "dance" to "💃", "dancing" to "💃",
        "sing" to "🎤", "singing" to "🎤",
        "run" to "🏃", "running" to "🏃", "jog" to "🏃",
        "exercise" to "🏃", "fitness" to "🏃", "gym" to "🏃",
        "drive" to "🚗", "driving" to "🚗",
        "garden" to "🌱", "plant" to "🌱",
        "carpentry" to "🔨", "wood" to "🔨",
        "chess" to "♟️", "board" to "♟️",
        "video" to "🎬", "edit" to "🎬",
    )

    /** 根据技能名自动推断 emoji */
    fun autoEmoji(skillName: String, displayName: String): String {
        for ((key, emoji) in KEYWORD_EMOJI_MAP) {
            if (skillName.contains(key, ignoreCase = true)) return emoji
        }
        for ((key, emoji) in KEYWORD_EMOJI_MAP) {
            if (displayName.contains(key, ignoreCase = true)) return emoji
        }
        return "⭐"
    }

/** 练习时长格式化 */
fun formatPracticeTime(minutes: Int): String = when {
    minutes < 60 -> "${minutes}分钟"
    minutes < 600 -> "%.1f小时".format(minutes / 60.0)
    else -> "${minutes / 60}小时"
}

/** 技能行 — 点击展开显示详情和删除 */
@Composable
fun SkillRow(
    emoji: String,
    name: String,
    level: Int,
    exp: Int,
    practiceMinutes: Int = 0,
    maxExp: Int = 100,
    color: Color = Color(0xFFC4A8E8),
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = (exp % maxExp).toFloat() / maxExp,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "skillExp"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        // ── 收起态：名称 + 等级 + 进度条 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(name, color = MaterialTheme.colorScheme.onSurface,
                 fontSize = 13.sp, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.2f)) {
                Text("Lv.$level", color = color, fontSize = 12.sp,
                     fontWeight = FontWeight.Bold,
                     modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            // 迷你进度条
            Box(
                modifier = Modifier.width(48.dp).height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(progress).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp)).background(color)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── 展开态：练习时长 + 删除 ──
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "练习 ${formatPracticeTime(practiceMinutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp),
                         tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                }
            }
        }
    }
}
