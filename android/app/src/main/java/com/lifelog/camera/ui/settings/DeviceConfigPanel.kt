package com.lifelog.camera.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.lifelog.camera.ble.BleFileTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RESOLUTIONS = listOf("QVGA", "VGA", "SVGA", "XGA", "SXGA", "UXGA", "QXGA")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConfigPanel(bleTransfer: BleFileTransfer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<BleFileTransfer.DeviceInfo?>(null) }
    var loading by remember { mutableStateOf(true) }

    var intervalMin by remember { mutableIntStateOf(5) }
    var videoDurationSec by remember { mutableIntStateOf(5) }
    var resolution by remember { mutableStateOf("UXGA") }
    var quality by remember { mutableIntStateOf(12) }
    var fps by remember { mutableIntStateOf(3) }
    var bleTimeoutSec by remember { mutableIntStateOf(300) }
    var bleName by remember { mutableStateOf("lifelog-cam") }
    var flashThresh by remember { mutableIntStateOf(150) }
    var sharpness by remember { mutableIntStateOf(1) }
    var contrast by remember { mutableIntStateOf(0) }
    var saturation by remember { mutableIntStateOf(0) }
    var initialized by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    /** 从 DeviceInfo 填充所有滑块值 */
    fun loadFrom(d: BleFileTransfer.DeviceInfo) {
        intervalMin = d.interval / 60000
        videoDurationSec = d.videoDuration / 1000
        resolution = d.videoResolution; quality = d.videoQuality; fps = d.videoFps
        bleTimeoutSec = d.bleAdvertiseTimeout / 1000; bleName = d.bleDeviceName
        flashThresh = d.flashThreshold
        sharpness = d.sharpness; contrast = d.contrast; saturation = d.saturation
    }

    // 首次加载
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { info = bleTransfer.readDeviceInfo() }
        info?.let { loadFrom(it) }
        initialized = true; loading = false
    }

    // 监听同步完成事件：硬件配置已更新 → 自动回读，滑块对齐硬件实际值
    LaunchedEffect(Unit) {
        bleTransfer.syncCompleted.collectLatest {
            if (it > 0L) {
                withContext(Dispatchers.IO) { info = bleTransfer.readDeviceInfo() }
                info?.let { d -> loadFrom(d); info = d }
                dirty = false
            }
        }
    }

    // 不在此处 verticalScroll —— 父级 SettingsScreen 的 Column 已经是滚动容器，
    // 嵌套 verticalScroll 会导致测量时拿到无限高度约束 → Crash
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 状态卡片 ──
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        )) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("📡 设备状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    IconButton(modifier = Modifier.size(32.dp), onClick = {
                        scope.launch {
                            loading = true
                            withContext(Dispatchers.IO) { info = bleTransfer.readDeviceInfo() }
                            info?.let { d ->
                                intervalMin = d.interval / 60000; videoDurationSec = d.videoDuration / 1000
                                resolution = d.videoResolution; quality = d.videoQuality; fps = d.videoFps
                                bleTimeoutSec = d.bleAdvertiseTimeout / 1000; bleName = d.bleDeviceName
                                flashThresh = d.flashThreshold
                                sharpness = d.sharpness; contrast = d.contrast; saturation = d.saturation
                            }
                            loading = false
                        }
                    }) { Icon(Icons.Default.Refresh, "刷新", Modifier.size(18.dp)) }
                }
                if (loading && !initialized) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else info?.let { d ->
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("固件"); Text(d.fwVersion, fontWeight = FontWeight.Medium) }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("片段"); Text("${d.totalClips} 段 / 已同步 ${d.syncedClips}", fontWeight = FontWeight.Medium) }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("SD"); Text("${d.sdFreeMb} MB", fontWeight = FontWeight.Medium) }
                    Spacer(Modifier.height(8.dp))
                    if (d.battery >= 0) {
                        Text("🔋 ${d.battery}%", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { d.battery / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = when { d.battery > 50 -> MaterialTheme.colorScheme.primary; d.battery > 20 -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.error },
                            trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    } else Text("🔋 未监测", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } ?: Text("未能读取设备（请确认已连接）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── 配置卡片 ──
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("⚙️ 拍摄配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("保存后，下次同步时自动发送到设备，下个周期生效",
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))

                // ── 间隔 ──
                Text("拍摄间隔: ${intervalMin} 分钟", fontWeight = FontWeight.Medium)
                Text("每段视频之间的等待时间。越短越耗电、文件越多。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = intervalMin.toFloat(), onValueChange = { intervalMin = it.toInt().coerceIn(1, 60); dirty = true }, valueRange = 1f..60f)
                Spacer(Modifier.height(12.dp))

                // ── 时长 ──
                Text("录制时长: ${videoDurationSec} 秒", fontWeight = FontWeight.Medium)
                Text("每段视频的录制长度。更长 = 文件更大、传输更慢。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = videoDurationSec.toFloat(), onValueChange = { videoDurationSec = it.toInt().coerceIn(1, 30); dirty = true }, valueRange = 1f..30f)
                Spacer(Modifier.height(12.dp))

                // ── 分辨率 ──
                Text("分辨率: $resolution", fontWeight = FontWeight.Medium)
                Text("越高越清晰，但文件也越大（UXGA 约 2~5 MB / 段）。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var resExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = resExpanded,
                    onExpandedChange = { resExpanded = it }
                ) {
                    OutlinedTextField(
                        value = resolution,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = resExpanded,
                        onDismissRequest = { resExpanded = false }
                    ) {
                        RESOLUTIONS.forEach { r ->
                            DropdownMenuItem(
                                text = { Text(r) },
                                onClick = { resolution = r; resExpanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ── 质量 ──
                Text("JPEG 质量: $quality", fontWeight = FontWeight.Medium)
                Text("数字越小画质越高、文件越大。10=极精细，20=均衡，30=省空间。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = quality.toFloat(), onValueChange = { quality = it.toInt().coerceIn(10, 30); dirty = true }, valueRange = 10f..30f)
                Spacer(Modifier.height(12.dp))

                // ── 帧率 ──
                Text("帧率: $fps fps", fontWeight = FontWeight.Medium)
                Text("每秒录多少帧。3 fps 够日常记录，更高会显著增大文件。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = fps.toFloat(), onValueChange = { fps = it.toInt().coerceIn(1, 10); dirty = true }, valueRange = 1f..10f)
                Spacer(Modifier.height(12.dp))

                // ── 锐度 ──
                Text("锐度: ${if (sharpness > 0) "+$sharpness" else "$sharpness"}", fontWeight = FontWeight.Medium)
                Text("+ = 更锐利细节 / - = 柔焦。生活记录建议 +1~+2。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = sharpness.toFloat(), onValueChange = { sharpness = it.toInt().coerceIn(-2, 2); dirty = true }, valueRange = -2f..2f, steps = 3)
                Spacer(Modifier.height(12.dp))

                // ── 对比度 ──
                Text("对比度: ${if (contrast > 0) "+$contrast" else "$contrast"}", fontWeight = FontWeight.Medium)
                Text("+ = 更通透明暗分明 / - = 更柔和保留细节。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = contrast.toFloat(), onValueChange = { contrast = it.toInt().coerceIn(-2, 2); dirty = true }, valueRange = -2f..2f, steps = 3)
                Spacer(Modifier.height(12.dp))

                // ── 饱和度 ──
                Text("饱和度: ${if (saturation > 0) "+$saturation" else "$saturation"}", fontWeight = FontWeight.Medium)
                Text("+ = 色彩更鲜艳 / - = 更素雅。日常 0~+1，风景可 +2。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = saturation.toFloat(), onValueChange = { saturation = it.toInt().coerceIn(-2, 2); dirty = true }, valueRange = -2f..2f, steps = 3)
                Spacer(Modifier.height(12.dp))

                // ── 广播窗口 ──
                Text("广播窗口: $bleTimeoutSec 秒", fontWeight = FontWeight.Medium)
                Text("设备录完视频后保持可连接的时长。建议 ≥ 60 秒，确保 App 能扫到。越长越耗电。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = bleTimeoutSec.toFloat(), onValueChange = { bleTimeoutSec = it.toInt().coerceIn(30, 600); dirty = true }, valueRange = 30f..600f)
                Spacer(Modifier.height(12.dp))

                // ── 闪光灯灵敏度 ──
                Text("闪光灯暗光阈值: ${flashThresh} 行", fontWeight = FontWeight.Medium)
                Text("AEC 曝光行数超过此值开启闪光灯。越小越暗才触发，越大越灵敏。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = flashThresh.toFloat(), onValueChange = { flashThresh = it.toInt().coerceIn(50, 300); dirty = true }, valueRange = 50f..300f)
                Spacer(Modifier.height(12.dp))

                // ── 设备名 ──
                OutlinedTextField(
                    value = bleName, onValueChange = { if (it.length <= 30) bleName = it },
                    label = { Text("设备名（蓝牙广播名称）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── 保存 ──
        val hasPending by bleTransfer.hasPendingConfig.collectAsState()
        Button(
            onClick = {
                bleTransfer.saveConfig(intervalMin * 60000, videoDurationSec * 1000, resolution, quality, fps, bleTimeoutSec * 1000, bleName, flashThreshold = flashThresh, sharpness = sharpness, contrast = contrast, saturation = saturation)
                dirty = false
                Toast.makeText(context, "已保存，将在下次同步时发送到设备", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            enabled = !hasPending
        ) {
            Text(if (hasPending) "已保存，等待同步..." else "保存（下次同步发送）")
        }
    }
}
