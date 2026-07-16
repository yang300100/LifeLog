package com.lifelog.camera.ui.realtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifelog.camera.ai.AnalysisManager
import com.lifelog.camera.ble.BleFileTransfer
import com.lifelog.camera.data.local.entity.CharacterStatsEntity
import com.lifelog.camera.data.local.entity.SkillProficiencyEntity
import com.lifelog.camera.data.local.entity.DiscoveredSkillEntity
import com.lifelog.camera.data.repository.VideoRepository
import com.lifelog.camera.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class RealtimeViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val analysisManager: AnalysisManager,
    private val bleTransfer: BleFileTransfer
) : ViewModel() {

    data class RpgState(
        val latestStats: CharacterStatsEntity? = null,
        val skills: List<SkillProficiencyEntity> = emptyList(),
        val todayDiscovered: List<DiscoveredSkillEntity> = emptyList(),
        val todaySteps: Int = 0,
        val screenMinutes: Int = 0,
        val locationContext: String = "",
        val isAnalyzing: Boolean = false,
        val analysisMsg: String? = null
    )

    private val _state = MutableStateFlow(RpgState())
    val state: StateFlow<RpgState> = _state.asStateFlow()

    // 日志查看日期（默认今天）
    private val _selectedDate = MutableStateFlow(TimeUtils.getDayStart())
    val selectedDate: StateFlow<Long> = _selectedDate.asStateFlow()
    val selectedDateLabel: StateFlow<String> = _selectedDate.map { dayStart ->
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = dayStart }
        "%d月%d日".format(cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun goToPrevDay() { _selectedDate.update { it - 86400000L } }
    fun goToNextDay() {
        val today = TimeUtils.getDayStart()
        _selectedDate.update { minOf(it + 86400000L, today) }  // 不能超过今天
    }
    fun goToToday() { _selectedDate.update { TimeUtils.getDayStart() } }

    val syncState = bleTransfer.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
                 BleFileTransfer.SyncState.Idle)

    // 计算等级（基于总经验）
    val totalLevel: StateFlow<Int> = repository.getAllSkillsFlow()
        .map { skills -> skills.sumOf { it.exp } / 100 + 1 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    // 时间衰减（含传感器）：30min 无新数据后向基线 50 回归
    val effectiveStats: StateFlow<CharacterStatsEntity?> = combine(
        repository.getLatestStatsFlow(),
        _state.map { it.todaySteps to it.screenMinutes }
    ) { stats, (steps, screen) -> applyTimeDecay(stats, steps, screen) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val categoryEmoji = mapOf(
        "work" to "🖥️", "eat" to "🍜", "sport" to "🏃", "exercise" to "🏃",
        "transport" to "🚗", "sleep" to "😴", "home" to "🏠",
        "outdoor" to "🚶", "walk" to "🚶", "other" to "📌", "uncertain" to "❓"
    )

    // 事件日志（按选定日期过滤，旧日期标签可能为空）
    val eventLogItems: StateFlow<List<com.lifelog.camera.ui.components.EventLogData>> =
        combine(
            repository.getAllClipsFlow(),
            repository.getLabelsByDateFlow(TimeUtils.getDayStart(), TimeUtils.getDayEnd()),
            repository.getTodayStatsFlow(TimeUtils.getDayStart(), TimeUtils.getDayEnd()),
            _selectedDate
        ) { allClips, labels, statsList, dayStart ->
            // 预建 Map：O(1) 查找替代 O(n) find
            val todayStart = dayStart
            val todayEnd = dayStart + 86400000L - 1
            val labelByClipId = labels.associateBy { it.clipId }
            val statsByClipId = statsList.associateBy { it.clipId }

            // 只取今天的视频，按时间升序
            val todayClips = allClips
                .filter { it.capturedAt in todayStart..todayEnd }
                .sortedBy { it.capturedAt }

            // 为每个已分析的 stats 计算与前一条的 delta（基线 50）
            var lastHp = 50
            var lastMp = 50
            var lastMood = 50

            todayClips.map { clip ->
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = clip.capturedAt }
                val time = "%02d:%02d".format(
                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE)
                )

                val label = labelByClipId[clip.id]
                val stats = statsByClipId[clip.id]

                if (label != null && stats != null) {
                    // 已分析：显示活动描述 + 属性变化量
                    val emoji = categoryEmoji[label.category.lowercase()] ?: "📌"
                    // 计算与上一次的 delta
                    val deltaText = buildString {
                        val dhp = stats.hp - lastHp
                        val dmp = stats.mp - lastMp
                        val dmood = stats.mood - lastMood
                        if (dhp != 0) append("❤️${if(dhp>0)"+" else ""}$dhp ")
                        if (dmp != 0) append("⚡${if(dmp>0)"+" else ""}$dmp ")
                        if (dmood != 0) append("😊${if(dmood>0)"+" else ""}$dmood")
                    }.ifEmpty { null }
                    lastHp = stats.hp; lastMp = stats.mp; lastMood = stats.mood

                    com.lifelog.camera.ui.components.EventLogData(
                        time = time, emoji = emoji,
                        description = label.description,
                        skillExpText = null,
                        statChanges = deltaText,
                        clipId = clip.id,
                        localPath = clip.localPath,
                        isAnalyzed = true,
                        onClick = {}
                    )
                } else if (label != null) {
                    // 有标签无状态：显示活动描述
                    val emoji = categoryEmoji[label.category.lowercase()] ?: "📌"
                    com.lifelog.camera.ui.components.EventLogData(
                        time = time, emoji = emoji,
                        description = label.description,
                        skillExpText = null,
                        statChanges = null,
                        clipId = clip.id,
                        localPath = clip.localPath,
                        isAnalyzed = true,
                        onClick = {}
                    )
                } else {
                    // 未分析：显示待分析状态
                    com.lifelog.camera.ui.components.EventLogData(
                        time = time, emoji = "⏳",
                        description = "待分析",
                        skillExpText = "%.1f KB".format(clip.fileSize / 1024.0),
                        statChanges = null,
                        clipId = clip.id,
                        localPath = clip.localPath,
                        isAnalyzed = false,
                        onClick = {}
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 监听最新角色状态
        viewModelScope.launch {
            repository.getLatestStatsFlow().collect { stats ->
                _state.update { it.copy(latestStats = stats) }
            }
        }
        // 监听技能列表
        viewModelScope.launch {
            repository.getAllSkillsFlow().collect { skills ->
                _state.update { it.copy(skills = skills) }
            }
        }
        // 监听新发现
        viewModelScope.launch {
            repository.getTodayDiscoveredSkillsFlow(
                TimeUtils.getDayStart(), TimeUtils.getDayEnd()
            ).collect { discovered ->
                _state.update { it.copy(todayDiscovered = discovered) }
            }
        }
        // 监听分析状态
        viewModelScope.launch {
            analysisManager.isRunning.collect { running ->
                if (!running && _state.value.isAnalyzing) {
                    _state.update { it.copy(isAnalyzing = false, analysisMsg = "分析完成") }
                }
            }
        }
    }

    fun startRpgAnalysis() {
        // 原子 start 检查（update 内判断避免竞态）
        var started = false
        _state.update { current ->
            if (current.isAnalyzing) current
            else { started = true; current.copy(isAnalyzing = true, analysisMsg = "开始分析...") }
        }
        if (!started) {
            android.util.Log.w("RealtimeVM", "分析已在运行中，忽略重复请求")
            return
        }

        analysisManager.reanalyzeAllRpg { analysisState ->
            _state.update { current ->
                val msg = when (analysisState) {
                    is AnalysisManager.AnalysisState.Extracting ->
                        "提取关键帧 ${analysisState.current}/${analysisState.total}"
                    is AnalysisManager.AnalysisState.Analyzing -> "AI 分析中..."
                    is AnalysisManager.AnalysisState.Done ->
                        "分析完成: ${analysisState.activities} 个活动"
                    is AnalysisManager.AnalysisState.Error ->
                        "错误: ${analysisState.msg}"
                    else -> current.analysisMsg
                }
                val done = analysisState is AnalysisManager.AnalysisState.Done
                        || analysisState is AnalysisManager.AnalysisState.Error
                current.copy(analysisMsg = msg, isAnalyzing = !done)
            }
        }
    }

    /** 分析单个视频（复用日志模式的分析） */
    fun analyzeSingleClip(clipId: Long) {
        analysisManager.analyzeSingleClip(clipId)
    }

    /** 删除视频 */
    fun deleteClip(clipId: Long, localPath: String) {
        viewModelScope.launch { repository.deleteClip(clipId, localPath) }
    }

    /** 处理手机拍摄的视频：保存并注册到数据库 */
    fun onPhoneVideoCaptured(mp4File: File) {
        viewModelScope.launch {
            try {
                val videoDir = repository.getVideoDir()
                val nextId = repository.getMaxId() + 1
                val savedName = "clip_%04d.mp4".format(nextId)
                val savedFile = java.io.File(videoDir, savedName)
                mp4File.copyTo(savedFile, overwrite = true)

                val entity = com.lifelog.camera.data.local.entity.VideoClipEntity(
                    id = nextId,
                    fileName = savedName,
                    capturedAt = System.currentTimeMillis(),
                    fileSize = savedFile.length(),
                    durationMs = 5000,
                    localPath = savedFile.absolutePath,
                    syncedAt = System.currentTimeMillis()
                )
                repository.saveClip(entity)
                mp4File.delete()
                // 实时模式自动触发 RPG 分析
                startRpgAnalysis()
            } catch (_: Exception) {}
        }
    }

    /** 删除技能 */
    fun deleteSkill(skillName: String) {
        viewModelScope.launch { repository.deleteSkill(skillName) }
    }

    /** 刷新传感器数据（步数+屏幕+位置） */
    fun refreshSensors(context: android.content.Context) {
        viewModelScope.launch {
            val steps = com.lifelog.camera.util.StepCounter.getTodaySteps(context)
            val screen = com.lifelog.camera.util.ScreenTimeTracker.getTodayScreenMinutes(context)
            val loc = com.lifelog.camera.util.LocationHelper.getLocationContext(context)
            _state.update { it.copy(
                todaySteps = steps,
                screenMinutes = screen,
                locationContext = loc.detail
            ) }
        }
    }

    /** 每日校准：用户手动设定属性值 */
    fun calibrateStats(hp: Int, mp: Int, mood: Int, mastery: Int) {
        viewModelScope.launch {
            repository.saveCharacterStats(
                com.lifelog.camera.data.local.entity.CharacterStatsEntity(
                    timestamp = System.currentTimeMillis(),
                    hp = hp, mp = mp, mood = mood, mastery = mastery,
                    clipId = 0  // 0 = 手动校准
                )
            )
        }
    }

    /** 手动添加已有技能（用户自己会的但未被 AI 识别过的） */
    fun addManualSkill(name: String, level: Int) {
        val skillName = name.lowercase().replace(" ", "_")
        val exp = SkillProficiencyEntity.levelToExp(level)
        viewModelScope.launch {
            val existing = repository.getSkillByName(skillName)
            if (existing == null) {
                repository.upsertSkill(
                    com.lifelog.camera.data.local.entity.SkillProficiencyEntity(
                        skillName = skillName,
                        displayName = name,
                        exp = exp,
                        practiceMinutes = 0,
                        lastExpGainAt = 0,
                        lastUsedAt = 0  // 手动添加标记
                    )
                )
            }
        }
    }

    companion object {
        /** 时间衰减：30min 无新数据后，每 30min 向基线 50 回归 1 点。
         *  @param todaySteps 步数加速 MP 衰减
         *  @param screenMinutes 屏幕时间延缓 Mastery 衰减（工作=掌控感） */
        fun applyTimeDecay(
            latest: com.lifelog.camera.data.local.entity.CharacterStatsEntity?,
            todaySteps: Int = 0,
            screenMinutes: Int = 0
        ): com.lifelog.camera.data.local.entity.CharacterStatsEntity? {
            if (latest == null) return null
            val now = System.currentTimeMillis()
            val elapsed = now - latest.timestamp
            val decayInterval = 30 * 60 * 1000L
            if (elapsed < decayInterval) return latest

            val decaySteps = (elapsed / decayInterval).toInt().coerceAtMost(50)
            val stepPenalty = (todaySteps / 2000).coerceAtMost(5)
            // 屏幕时间 > 2h → Mastery 不衰减（在认真工作）
            val screenBonus = if (screenMinutes > 120) decaySteps else 0

            fun decay(value: Int): Int {
                return if (value > 50) (value - decaySteps).coerceAtLeast(50)
                       else if (value < 50) (value + decaySteps).coerceAtMost(50)
                       else value
            }
            return latest.copy(
                hp = decay(latest.hp),
                mp = (decay(latest.mp) - stepPenalty).coerceAtLeast(10),
                mood = decay(latest.mood),
                mastery = (decay(latest.mastery) + screenBonus).coerceAtMost(100)
            )
        }
    }
}
