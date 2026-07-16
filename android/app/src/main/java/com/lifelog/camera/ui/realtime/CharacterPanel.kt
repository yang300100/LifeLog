package com.lifelog.camera.ui.realtime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelog.camera.data.local.entity.SkillProficiencyEntity
import com.lifelog.camera.ui.components.RpgStatsBar
import com.lifelog.camera.ui.components.SkillRow
import com.lifelog.camera.ui.components.StatInfo
import com.lifelog.camera.ui.components.autoEmoji

// 语义色（柔和版）
private val HpColor = Color(0xFFE8928A)
private val MpColor = Color(0xFF8A9BE8)
private val MoodColor = Color(0xFFE8C98A)
private val MasteryColor = Color(0xFF8AC8A0)
private val NewColor = Color(0xFFE8D48A)

@Composable
fun CharacterPanel(
    latestHp: Int,
    latestMp: Int,
    latestMood: Int,
    latestMastery: Int,
    skills: List<SkillProficiencyEntity>,
    discoveredCount: Int,
    todaySteps: Int = 0,
    screenMinutes: Int = 0,
    locationContext: String = "",
    level: Int,
    title: String,
    onAddSkill: (String, Int) -> Unit = { _, _ -> },
    onDeleteSkill: (String) -> Unit = {},
    onCalibrate: ((Int, Int, Int, Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showAllSkills by remember { mutableStateOf(false) }
    var showAddSkillDialog by remember { mutableStateOf(false) }
    var showCalibrateDialog by remember { mutableStateOf(onCalibrate != null) }

    if (showCalibrateDialog && onCalibrate != null) {
        CalibrateDialog(
            currentHp = latestHp, currentMp = latestMp,
            currentMood = latestMood, currentMastery = latestMastery,
            onDismiss = { showCalibrateDialog = false },
            onConfirm = { hp, mp, mood, m -> showCalibrateDialog = false; onCalibrate(hp, mp, mood, m) }
        )
    }

    if (showAddSkillDialog) {
        AddSkillDialog(
            onDismiss = { showAddSkillDialog = false },
            onConfirm = { name, lv ->
                showAddSkillDialog = false
                onAddSkill(name, lv)
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 等级 + 称号
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🏰", fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Lv.$level", color = MaterialTheme.colorScheme.primary,
                             fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }

        // 核心属性
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("📊 核心属性", color = MaterialTheme.colorScheme.onSurface,
                     fontWeight = FontWeight.Bold, fontSize = 14.sp)
                RpgStatsBar("❤️", "生命", latestHp, color = HpColor, statInfo = StatInfo(
                    fullName = "生命值", description = "反映你的身体疲劳程度和健康状态。持续活动会消耗生命值，休息和用餐可以恢复。",
                    guide = "感觉累了就休息一下，吃顿饭也能回血哦～",
                    ranges = listOf("🟢 70-100" to "精力充沛", "🟡 40-69" to "有些疲惫", "🔴 10-39" to "需要休息")
                ))
                RpgStatsBar("⚡", "精力", latestMp, color = MpColor, statInfo = StatInfo(
                    fullName = "精力值", description = "反映你的精神能量水平。脑力工作（学习、写作）和体力活动都会消耗，睡眠和饮食帮你恢复。",
                    guide = "适当休息、切换任务可以防止精力透支",
                    ranges = listOf("🟢 60-100" to "精力旺盛", "🟡 30-59" to "有些疲倦", "🔴 10-29" to "筋疲力尽")
                ))
                RpgStatsBar("😊", "心情", latestMood, color = MoodColor, statInfo = StatInfo(
                    fullName = "心情值", description = "反映你的情绪状态。受环境、社交、天气、活动类型等多因素影响，波动较大是正常的。",
                    guide = "出去走走、听听音乐、和朋友聊天都能提升心情",
                    ranges = listOf("🟢 70-100" to "心情愉悦", "🟡 40-69" to "心情一般", "🔴 10-39" to "情绪低落")
                ))
                RpgStatsBar("📊", "掌控", latestMastery, color = MasteryColor, statInfo = StatInfo(
                    fullName = "掌控度", description = "反映你对今日计划的执行程度。按计划行事会上升，拖延或分心会下降。是衡量自律和效率的指标。",
                    guide = "制定合理计划并坚持执行，掌控度自然提升",
                    ranges = listOf("🟢 65-100" to "掌控良好", "🟡 35-64" to "有些失控", "🔴 10-34" to "完全失控")
                ))
            }
        }

        // 传感器数据
        val sensorInfo = buildString {
            if (todaySteps > 0) append("👟 $todaySteps 步  ")
            if (screenMinutes > 0) append("📱 ${screenMinutes}分钟  ")
            if (locationContext.isNotBlank()) append("📍 $locationContext")
        }
        if (sensorInfo.isNotBlank()) {
            Text(
                sensorInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        // 技能熟练度
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🎯 技能熟练度", color = MaterialTheme.colorScheme.onSurface,
                     fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                if (skills.isEmpty()) {
                    Text("暂无技能记录", color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    val visibleSkills = if (showAllSkills) skills else skills.take(5)
                    visibleSkills.forEach { skill ->
                        val emoji = autoEmoji(skill.skillName, skill.displayName)
                        val lv = SkillProficiencyEntity.expToLevel(skill.exp)
                        SkillRow(
                            emoji = emoji,
                            name = skill.displayName.ifBlank { skill.skillName },
                            level = lv,
                            exp = skill.exp,
                            practiceMinutes = skill.practiceMinutes,
                            onDelete = { onDeleteSkill(skill.skillName) }
                        )
                    }

                    if (skills.size > 5) {
                        TextButton(
                            onClick = { showAllSkills = !showAllSkills },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (showAllSkills) "收起" else "展开全部 (${skills.size}项)",
                                color = MaterialTheme.colorScheme.primary, fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { showAddSkillDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("＋ 添加已有技能", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
            }
        }

        // 今日新发现
        if (discoveredCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🌟 今日新发现", color = NewColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("$discoveredCount 个新技能",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── 添加技能弹窗 ──

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit
) {
    var skillName by remember { mutableStateOf("") }
    var skillLevel by remember { mutableIntStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加已有技能") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = skillName,
                    onValueChange = { skillName = it },
                    label = { Text("技能名称") },
                    placeholder = { Text("如：钢琴、英语...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("当前等级：", style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { if (skillLevel > 1) skillLevel-- }) {
                        Text("−", fontSize = 20.sp)
                    }
                    Text("Lv.$skillLevel", style = MaterialTheme.typography.titleMedium,
                         fontWeight = FontWeight.Bold)
                    IconButton(onClick = { if (skillLevel < 100) skillLevel++ }) {
                        Text("＋", fontSize = 20.sp)
                    }
                }
                val estimatedExp = SkillProficiencyEntity.levelToExp(skillLevel)
                Text("≈ $estimatedExp EXP", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (skillName.isNotBlank()) onConfirm(skillName.trim(), skillLevel) },
                enabled = skillName.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 每日校准弹窗 ──

@Composable
private fun CalibrateDialog(
    currentHp: Int, currentMp: Int, currentMood: Int, currentMastery: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int, Int) -> Unit
) {
    var hp by remember { mutableIntStateOf(currentHp) }
    var mp by remember { mutableIntStateOf(currentMp) }
    var mood by remember { mutableIntStateOf(currentMood) }
    var mastery by remember { mutableIntStateOf(currentMastery) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🌅 今日状态校准", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI 推断的状态，你可以自由调整：", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)

                StatSlider("❤️ 生命", hp, { hp = it }, HpColor)
                StatSlider("⚡ 精力", mp, { mp = it }, MpColor)
                StatSlider("😊 心情", mood, { mood = it }, MoodColor)
                StatSlider("📊 掌控", mastery, { mastery = it }, MasteryColor)

                HorizontalDivider()
                Text("快速预设：", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { hp=80; mp=80; mood=80; mastery=80 },
                        label = { Text("😄 精力充沛") })
                    AssistChip(onClick = { hp=60; mp=60; mood=60; mastery=60 },
                        label = { Text("😐 一般般") })
                    AssistChip(onClick = { hp=30; mp=30; mood=30; mastery=30 },
                        label = { Text("😫 累死了") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hp, mp, mood, mastery) }) { Text("✅ 确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("跳过") }
        }
    )
}

@Composable
private fun StatSlider(label: String, value: Int, onValueChange: (Int) -> Unit, color: androidx.compose.ui.graphics.Color) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("$value", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
        }
        Slider(
            value = value.toFloat(), onValueChange = { onValueChange(it.toInt()) },
            valueRange = 10f..100f, steps = 17,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color)
        )
    }
}
