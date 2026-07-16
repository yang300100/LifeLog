package com.lifelog.camera.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lifelog.camera.data.model.ReferenceCategory
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifelog.camera.ui.components.PeachSwitch
import com.lifelog.camera.ui.components.PeachTextField
import com.lifelog.camera.ui.theme.PeachSuccess

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToLog: () -> Unit = {}
) {
    val clipCount by viewModel.clipCount.collectAsState()
    val dirSize by viewModel.videoDirSize.collectAsState()
    val apiBaseUrl by viewModel.apiBaseUrl.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val apiModel by viewModel.apiModel.collectAsState()
    val apiTemperature by viewModel.apiTemperature.collectAsState()
    val isSaved by viewModel.isSaved.collectAsState()

    var showKey by remember { mutableStateOf(false) }
    var showSavedAnimation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // ── AI 配置 ──
        SectionHeader("AI 配置")

        SettingsCard {
            PeachTextField(
                value = apiBaseUrl,
                onValueChange = { viewModel.apiBaseUrl.value = it },
                label = "API Base URL",
                placeholder = "https://api.openai.com/v1",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Spacer(modifier = Modifier.height(14.dp))

            PeachTextField(
                value = apiKey,
                onValueChange = { viewModel.apiKey.value = it },
                label = "API Key",
                placeholder = "sk-...",
                visualTransformation = if (showKey) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = { showKey = !showKey },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text(
                            if (showKey) "🙈" else "👁️",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Model + Temperature 并排
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    PeachTextField(
                        value = apiModel,
                        onValueChange = { viewModel.apiModel.value = it },
                        label = "Model",
                        placeholder = "gpt-4o"
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    PeachTextField(
                        value = apiTemperature,
                        onValueChange = { viewModel.apiTemperature.value = it },
                        label = "Temperature",
                        placeholder = "1.0",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))

            // Seedream API Key (图像生成)
            var seedreamKey by remember { mutableStateOf(viewModel.seedreamApiKey.value) }
            PeachTextField(
                value = seedreamKey,
                onValueChange = { seedreamKey = it },
                label = "Seedream API Key (图像生成)",
                placeholder = "ark-...",
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { seedreamKey = "" }) { Icon(Icons.Default.Close, "", modifier = Modifier.size(18.dp)) }
                }
            )
            Spacer(Modifier.height(2.dp))
            Text("火山引擎方舟",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(16.dp))

            // 统一保存按钮
            Button(
                onClick = {
                    // 上方 API Key 同步给 Kimi
                    viewModel.saveKimiKey(apiKey)
                    viewModel.saveApiConfig()
                    viewModel.saveSeedreamKey(seedreamKey)
                    showSavedAnimation = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showSavedAnimation) PeachSuccess
                                     else MaterialTheme.colorScheme.primary
                )
            ) {
                AnimatedContent(
                    targetState = showSavedAnimation && isSaved,
                    transitionSpec = {
                        fadeIn() + scaleIn() togetherWith fadeOut() + scaleOut()
                    },
                    label = "saveBtn"
                ) { saved ->
                    if (saved) {
                        Icon(Icons.Default.Check, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("已保存")
                    } else {
                        Text("保存配置")
                    }
                }
            }
        }

        // ── 存储 ──
        SectionHeader("存储")

        SettingsCard {
            SettingsInfoRow("视频总数", "${clipCount} 个")
            Divider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            SettingsInfoRow("存储大小", dirSize)
        }

        // ── 运行 & 设备 ──
        SectionHeader("运行 & 设备")

        SettingsCard {
            val isRealtime by viewModel.isRealtimeMode.collectAsState()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isRealtime) "实时模式" else "日志模式",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (isRealtime) "每拍一个视频立刻同步"
                        else "打开 App 后手动同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PeachSwitch(
                    checked = isRealtime,
                    onCheckedChange = { viewModel.toggleMode() }
                )
            }

            Divider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            SettingsInfoRow("设备名称", "lifelog-cam")

            Divider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            SettingsInfoRow("传输方式", "BLE 4.2+")

            Divider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            SettingsInfoRow("录制间隔", "2 分钟")

            Divider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            SettingsInfoRow("视频时长", "5 秒 / QVGA")
        }

        // ── 同伴配置 ──
        SectionHeader("同伴配置")

        Spacer(modifier = Modifier.height(8.dp))

        // 角色文字描述 (无参考图时使用)
        SettingsCard {
            var charDesc by remember { mutableStateOf(viewModel.characterDescription.value) }
            Text("角色文字描述", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text("当不上传参考图时，用这段描述生成角色",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = charDesc,
                onValueChange = { charDesc = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 200.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("例如: a young girl with silver short hair and purple eyes, white shirt, blue skirt...",
                    style = MaterialTheme.typography.bodySmall) },
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.saveCharacterDescription(charDesc) },
                modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(10.dp),
                enabled = charDesc.isNotBlank() && charDesc != viewModel.characterDescription.value
            ) { Text("保存描述", style = MaterialTheme.typography.labelSmall) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 角色参考图 (多图)
        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let { viewModel.addCharacterReference(it) }
        }

        SettingsCard {
            Text("角色参考图", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text("可为每张图选择分类，帮助 AI 理解参考用途",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            val refBitmaps by viewModel.characterRefBitmaps.collectAsState()
            val refCats by viewModel.refCategories.collectAsState()
            val refNames by viewModel.characterRefFileNames.collectAsState()

            // 已有参考图 — 带分类标签的网格展示
            if (refBitmaps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    refBitmaps.forEachIndexed { idx, bmp ->
                        val fileName = refNames.getOrNull(idx) ?: ""
                        val category = refCats[fileName] ?: ReferenceCategory.CHARACTER
                        var showCatMenu by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 缩略图 + 删除按钮
                            Box(modifier = Modifier.size(64.dp)) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "参考图${idx + 1}",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                // 删除按钮: 用 Box+clickable 替代 IconButton，消除 48dp 最小触摸区和阴影
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-4).dp)
                                        .size(18.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                        .clickable { viewModel.removeCharacterRef(idx) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Close, "删除参考图${idx + 1}",
                                        modifier = Modifier.size(11.dp),
                                        tint = Color.White)
                                }
                            }

                            // 分类选择
                            Box {
                                Text(
                                    "#${category.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                                        .clickable { showCatMenu = true }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                DropdownMenu(
                                    expanded = showCatMenu,
                                    onDismissRequest = { showCatMenu = false }
                                ) {
                                    ReferenceCategory.values().forEach { cat ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (cat == category) {
                                                        Icon(Icons.Default.Check, null,
                                                            modifier = Modifier.size(14.dp),
                                                            tint = MaterialTheme.colorScheme.primary)
                                                        Spacer(Modifier.width(4.dp))
                                                    }
                                                    Text("#${cat.label}", style = MaterialTheme.typography.labelSmall)
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(cat.promptHint.take(12),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = {
                                                viewModel.setRefCategory(idx, cat)
                                                showCatMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 添加按钮
                    if (refBitmaps.size < 6) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth().height(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { imagePickerLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Text("添加参考图", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (refBitmaps.size >= 6) {
                    Spacer(Modifier.height(4.dp))
                    Text("最多6张参考图", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // 空状态
                Box(
                    modifier = Modifier.fillMaxWidth().height(80.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .clickable { imagePickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AddPhotoAlternate, null, modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("点击选择角色参考图", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── 调试 ──
        SectionHeader("调试")

        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToLog() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("崩溃日志", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "查看应用异常记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 小节标题 ──

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

// ── 分组卡片容器 ──

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

// ── 信息行 ──

@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
