package com.lifelog.camera.ui.realtime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelog.camera.ui.components.EventLogData
import com.lifelog.camera.ui.components.EventLogItem

@Composable
fun ActivityLog(
    logItems: List<EventLogData>,
    captureCount: Int,
    activityCount: Int,
    modifier: Modifier = Modifier
) {
    if (logItems.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📜", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("还没有战斗记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp)
                Text("同步摄像头数据后自动更新",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 13.sp)
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem("📷", "$captureCount 次")
                    StatItem("🎯", "$activityCount 活动")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        items(logItems) { item ->
            EventLogItem(data = item)
        }
    }
}

@Composable
private fun StatItem(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface,
             fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
