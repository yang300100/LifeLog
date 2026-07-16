package com.lifelog.camera.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class StatInfo(
    val fullName: String,
    val description: String,
    val guide: String,
    val ranges: List<Pair<String, String>>  // "🟢 70-100" to "精力充沛"
)

@Composable
fun RpgStatsBar(
    emoji: String,
    label: String,
    value: Int,
    maxValue: Int = 100,
    color: Color,
    statInfo: StatInfo? = null,
    modifier: Modifier = Modifier
) {
    var showInfo by remember { mutableStateOf(false) }
    val animatedProgress by animateFloatAsState(
        targetValue = value.toFloat() / maxValue,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 200f),
        label = "statsProgress"
    )

    if (showInfo && statInfo != null) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("${statInfo.fullName} (${statInfo.fullName.first()})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(statInfo.description, style = MaterialTheme.typography.bodyMedium)
                    Column {
                        Text("📊 参考范围:", fontWeight = FontWeight.Bold,
                             style = MaterialTheme.typography.bodySmall)
                        statInfo.ranges.forEach { (range, meaning) ->
                            Text("  $range  $meaning",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("💡 ${statInfo.guide}",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("知道了") }
            }
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (statInfo != null) Modifier.clickable { showInfo = true } else Modifier
            ) {
                Text(emoji, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(label, color = MaterialTheme.colorScheme.onSurface,
                     fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (statInfo != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ℹ️", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("$value", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}
