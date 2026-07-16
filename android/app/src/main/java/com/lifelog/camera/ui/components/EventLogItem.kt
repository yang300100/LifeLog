package com.lifelog.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class EventLogData(
    val time: String,
    val emoji: String,
    val description: String,
    val skillExpText: String?,
    val statChanges: String?,
    val clipId: Long = 0,
    val localPath: String = "",
    val isAnalyzed: Boolean = false,
    val onClick: () -> Unit
)

@Composable
fun EventLogItem(
    data: EventLogData,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { data.onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(data.time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier.size(6.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(data.emoji, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(data.description, color = MaterialTheme.colorScheme.onSurface,
                     fontSize = 13.sp, maxLines = 2)
            }

            if (data.skillExpText != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(data.skillExpText, color = Color(0xFFC4A8E8), fontSize = 12.sp)
            }

            if (data.statChanges != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(data.statChanges, color = MaterialTheme.colorScheme.onSurfaceVariant,
                     fontSize = 11.sp)
            }
        }
    }
}
