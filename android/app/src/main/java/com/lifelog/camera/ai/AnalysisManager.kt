package com.lifelog.camera.ai

import android.util.Log
import com.lifelog.camera.data.local.entity.ActivityLabelEntity
import com.lifelog.camera.data.repository.VideoRepository
import com.lifelog.camera.util.CrashLogger
import com.lifelog.camera.util.TimeUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalysisManager @Inject constructor(
    private val repository: VideoRepository,
    private val keyframeExtractor: KeyframeExtractor,
    private val aiClient: AIClient,
    private val apiPreferences: ApiPreferences
) {
    companion object {
        private const val TAG = "AnalysisMgr"
        private const val BATCH_SIZE = 10
    }

    sealed class AnalysisState {
        data object Idle : AnalysisState()
        data class Extracting(val current: Int, val total: Int) : AnalysisState()
        data object Analyzing : AnalysisState()
        data class Done(val activities: Int) : AnalysisState()
        data class Error(val msg: String) : AnalysisState()
    }

    // 注：AnalysisManager 为 Singleton，scope 生命周期 = app 进程
    // 分析在后台持续运行，即使 UI 销毁也不中断
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    /** UI 可观察的运行状态（ViewModel 重建后仍可获取） */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    private val _lastResult = MutableStateFlow<AnalysisState?>(null)
    val lastResult: StateFlow<AnalysisState?> = _lastResult.asStateFlow()

    // ── 检查是否需要分析 ──

    suspend fun getUnanalyzedCount(): Int {
        return repository.getUnanalyzedClips(BATCH_SIZE).size
    }

    // ── 运行分析流水线 ──

    fun runAnalysis(
        onState: (AnalysisState) -> Unit = {}
    ) {
        if (job?.isActive == true) {
            CrashLogger.w(TAG, "分析已在运行中，忽略重复请求")
            return
        }

        CrashLogger.i(TAG, "========== 开始分析流水线 ==========")
        _isRunning.value = true
        _lastResult.value = null
        val safeCallback = safeStateCallback(onState)
        job = scope.launch {
            try {
                // Step 0: 检查 API 配置
                val config = apiPreferences.get()
                CrashLogger.i(TAG, "API 配置: baseUrl=${config.baseUrl}, model=${config.model}, hasKey=${config.apiKey.isNotBlank()}")
                if (!config.isValid) {
                    CrashLogger.w(TAG, "API Key 未配置，终止分析")
                    safeCallback(AnalysisState.Error("请先配置 API Key"))
                    return@launch
                }

                // Step 1: 获取未分析视频
                val clips = repository.getUnanalyzedClips(BATCH_SIZE)
                CrashLogger.i(TAG, "未分析视频数: ${clips.size}")
                if (clips.isEmpty()) {
                    CrashLogger.i(TAG, "没有未分析的视频，跳过")
                    safeCallback(AnalysisState.Done(0))
                    return@launch
                }

                // Step 2-4: 共享的分析流水线
                runAnalysisPipeline(clips, config, safeCallback)

            } catch (e: Exception) {
                CrashLogger.e(TAG, "分析流水线异常: ${e.message}", e)
                safeCallback(AnalysisState.Error(e.message ?: "未知错误"))
            }
        }
    }

    // ── 分析单个视频 ──

    fun analyzeSingleClip(clipId: Long, onState: (AnalysisState) -> Unit = {}) {
        if (job?.isActive == true) {
            CrashLogger.w(TAG, "分析已在运行中，忽略单条分析请求")
            onState(AnalysisState.Error("分析正在进行中，请稍后重试"))
            return
        }

        CrashLogger.i(TAG, "========== 开始单条分析: clipId=$clipId ==========")
        _isRunning.value = true
        _lastResult.value = null
        val safeCallback = safeStateCallback(onState)
        job = scope.launch {
            try {
                val config = apiPreferences.get()
                CrashLogger.i(TAG, "API 配置: baseUrl=${config.baseUrl}, hasKey=${config.apiKey.isNotBlank()}")
                if (!config.isValid) {
                    CrashLogger.w(TAG, "API Key 未配置，终止分析")
                    safeCallback(AnalysisState.Error("请先配置 API Key"))
                    return@launch
                }

                val clip = repository.getClipById(clipId)
                if (clip == null) {
                    safeCallback(AnalysisState.Error("视频不存在"))
                    return@launch
                }

                // 如果已分析，先重置
                if (clip.analyzed) {
                    repository.resetAnalyzed(listOf(clipId))
                }

                runAnalysisPipeline(listOf(clip), config, safeCallback)
            } catch (e: Exception) {
                CrashLogger.e(TAG, "单条分析异常: ${e.message}", e)
                safeCallback(AnalysisState.Error(e.message ?: "未知错误"))
            }
        }
    }

    /** RPG 模式分析流水线（分析后保存状态+技能到 DB） */
    fun runRpgAnalysis(
        onState: (AnalysisState) -> Unit = {}
    ) {
        if (job?.isActive == true) {
            CrashLogger.w(TAG, "分析已在运行中，忽略 RPG 分析请求")
            return
        }

        CrashLogger.i(TAG, "========== 开始 RPG 分析流水线 ==========")
        _isRunning.value = true
        _lastResult.value = null
        val safeCallback = safeStateCallback(onState)
        job = scope.launch {
            try {
                val config = apiPreferences.get()
                CrashLogger.i(TAG, "API 配置: baseUrl=${config.baseUrl}, hasKey=${config.apiKey.isNotBlank()}")
                if (!config.isValid) {
                    CrashLogger.w(TAG, "API Key 未配置，终止分析")
                    safeCallback(AnalysisState.Error("请先配置 API Key"))
                    return@launch
                }

                val clips = repository.getUnanalyzedClips(BATCH_SIZE)
                if (clips.isEmpty()) {
                    safeCallback(AnalysisState.Done(0))
                    return@launch
                }

                val inputs = mutableListOf<AIClient.VideoInput>()
                val timestamps = mutableListOf<String>()
                val validClips = mutableListOf<com.lifelog.camera.data.local.entity.VideoClipEntity>()

                for ((idx, clip) in clips.withIndex()) {
                    val file = File(clip.localPath)
                    if (!file.exists()) continue

                    val time = TimeUtils.formatTime(clip.capturedAt)

                    val input = if (file.extension.equals("mp4", ignoreCase = true)) {
                        AIClient.VideoInput.VideoFile(file.absolutePath)
                    } else {
                        val frames = keyframeExtractor.readAllFrames(file)
                        if (frames.isEmpty()) continue
                        AIClient.VideoInput.Frames(frames)
                    }

                    inputs.add(input)
                    timestamps.add(time)
                    validClips.add(clip)
                    safeCallback(AnalysisState.Extracting(idx + 1, clips.size))
                }

                if (inputs.isEmpty()) {
                    safeCallback(AnalysisState.Error("没有有效视频"))
                    return@launch
                }

                safeCallback(AnalysisState.Analyzing)
                val result = aiClient.analyzeRpg(inputs, timestamps, config)

                result.onSuccess { rpgResults ->
                    if (rpgResults.isEmpty()) {
                        CrashLogger.w(TAG, "AI 返回空结果，视频保持未分析状态")
                        safeCallback(AnalysisState.Error("AI 未识别到活动，请重试"))
                        return@onSuccess
                    }
                    // 累加 delta 到现有角色状态
                    val latestStats = repository.getLatestCharacterStats()
                    val prevHp = latestStats?.hp ?: 50
                    val prevMp = latestStats?.mp ?: 50
                    val prevMood = latestStats?.mood ?: 50
                    val prevMastery = latestStats?.mastery ?: 50

                    var curHp = prevHp; var curMp = prevMp
                    var curMood = prevMood; var curMastery = prevMastery

                    // 只处理有对应视频的结果（防止 AI 返回多余数据映射到第一个视频）
                    rpgResults.take(validClips.size).forEachIndexed { idx, r ->
                        val clip = validClips[idx]
                        curHp = (curHp + r.stats.hp).coerceIn(10, 100)
                        curMp = (curMp + r.stats.mp).coerceIn(10, 100)
                        curMood = (curMood + r.stats.mood).coerceIn(10, 100)
                        curMastery = (curMastery + r.stats.mastery).coerceIn(10, 100)

                        val entity = com.lifelog.camera.data.local.entity.CharacterStatsEntity(
                            timestamp = clip.capturedAt,
                            hp = curHp, mp = curMp,
                            mood = curMood, mastery = curMastery,
                            clipId = clip.id
                        )
                        repository.saveCharacterStats(entity)
                    }

                    // 聚合 + 更新技能熟练度（30min 冷却 + 练习时长）
                    val now = System.currentTimeMillis()
                    val mergedSkills = RpgAnalyzer.mergeSkillGains(rpgResults)
                    val COOLDOWN_MS = 30 * 60 * 1000L  // 30 分钟冷却

                    mergedSkills.forEach { (name, skill) ->
                        val existing = repository.getSkillByName(name)
                        if (existing != null) {
                            // 练习时长每次检测都 +2min
                            val newPm = existing.practiceMinutes + 2
                            // 经验仅在冷却结束后累积
                            val canGainExp = now - existing.lastExpGainAt >= COOLDOWN_MS
                            val newExp = if (canGainExp) existing.exp + skill.expGain else existing.exp
                            val newLastExpGainAt = if (canGainExp) now else existing.lastExpGainAt

                            repository.upsertSkill(existing.copy(
                                exp = newExp,
                                displayName = skill.display.ifBlank { existing.displayName },
                                practiceMinutes = newPm,
                                lastExpGainAt = newLastExpGainAt,
                                lastUsedAt = now
                            ))
                        } else {
                            // 新技能：先加初始经验 + 2min 练习时长
                            repository.upsertSkill(
                                com.lifelog.camera.data.local.entity.SkillProficiencyEntity(
                                    skillName = name,
                                    displayName = skill.display.ifBlank { name },
                                    exp = skill.expGain,
                                    practiceMinutes = 2,
                                    lastExpGainAt = now,
                                    lastUsedAt = now
                                )
                            )
                        }
                    }

                    // 保存新发现技能
                    val todayStart = TimeUtils.getDayStart()
                    val todayEnd = TimeUtils.getDayEnd()
                    val existingDiscovered = repository.getTodayDiscoveredSkills(todayStart, todayEnd)
                    rpgResults.flatMap { it.newSkills }.forEach { skillName ->
                        if (existingDiscovered.none { it.skillName == skillName }) {
                            repository.insertDiscoveredSkill(
                                com.lifelog.camera.data.local.entity.DiscoveredSkillEntity(
                                    skillName = skillName,
                                    displayName = skillName,
                                    discoveredAt = System.currentTimeMillis(),
                                    clipId = validClips.firstOrNull()?.id ?: 0
                                )
                            )
                        }
                    }

                    // 保存活动标签（日志 Tab 展示用）
                    val labels = rpgResults.mapIndexed { idx, r ->
                        val clip = validClips.getOrElse(idx) { validClips.first() }
                        com.lifelog.camera.data.local.entity.ActivityLabelEntity(
                            clipId = clip.id,
                            timestamp = clip.capturedAt,
                            category = r.category,
                            behavior = r.activity.lowercase().replace(" ", "_"),
                            description = r.activity,
                            confidence = 0.8f
                        )
                    }
                    repository.saveLabels(labels)

                    // 标记已分析
                    repository.markAnalyzed(validClips.map { it.id })
                    safeCallback(AnalysisState.Done(rpgResults.size))
                }

                result.onFailure { e ->
                    safeCallback(AnalysisState.Error(e.message ?: "RPG 分析失败"))
                }
            } catch (e: Exception) {
                CrashLogger.e(TAG, "RPG 分析流水线异常: ${e.message}", e)
                safeCallback(AnalysisState.Error(e.message ?: "未知错误"))
            }
        }
    }

    // ── RPG 模式重新分析今天全部视频 ──

    fun reanalyzeAllRpg(onState: (AnalysisState) -> Unit = {}) {
        if (job?.isActive == true) {
            CrashLogger.w(TAG, "分析已在运行中，忽略重复请求")
            return
        }

        CrashLogger.i(TAG, "========== RPG 重新分析今日全部视频 ==========")
        _isRunning.value = true
        _lastResult.value = null
        val safeCallback = safeStateCallback(onState)
        job = scope.launch {
            try {
                val (dayStart, dayEnd) = TimeUtils.getTodayRange()
                val clips = repository.getTodayClips(dayStart, dayEnd)
                CrashLogger.i(TAG, "今日视频总数: ${clips.size}")

                if (clips.isEmpty()) {
                    safeCallback(AnalysisState.Done(0))
                    return@launch
                }

                val config = apiPreferences.get()
                CrashLogger.i(TAG, "API 配置: baseUrl=${config.baseUrl}, hasKey=${config.apiKey.isNotBlank()}")
                if (!config.isValid) {
                    CrashLogger.w(TAG, "API Key 未配置，终止分析")
                    safeCallback(AnalysisState.Error("请先配置 API Key"))
                    return@launch
                }

                // 重置所有视频的分析状态
                repository.resetAnalyzed(clips.map { it.id })
                CrashLogger.i(TAG, "已重置 ${clips.size} 个视频的分析状态")

                // 复用 RPG 流水线核心逻辑（提取帧 → AI RPG 分析 → 保存）
                val inputs = mutableListOf<AIClient.VideoInput>()
                val timestamps = mutableListOf<String>()
                val validClips = mutableListOf<com.lifelog.camera.data.local.entity.VideoClipEntity>()

                for ((idx, clip) in clips.withIndex()) {
                    val file = File(clip.localPath)
                    CrashLogger.i(TAG, "  [${idx+1}/${clips.size}] path=${clip.localPath}, exists=${file.exists()}, size=${if(file.exists()) file.length() else -1}")
                    if (!file.exists()) {
                        CrashLogger.w(TAG, "  文件不存在，跳过")
                        continue
                    }

                    val time = TimeUtils.formatTime(clip.capturedAt)

                    val input = if (file.extension.equals("mp4", ignoreCase = true)) {
                        AIClient.VideoInput.VideoFile(file.absolutePath)
                    } else {
                        val frames = keyframeExtractor.readAllFrames(file)
                        if (frames.isEmpty()) continue
                        AIClient.VideoInput.Frames(frames)
                    }
                    inputs.add(input)
                    timestamps.add(time)
                    validClips.add(clip)
                    safeCallback(AnalysisState.Extracting(idx + 1, clips.size))
                }

                if (inputs.isEmpty()) {
                    CrashLogger.w(TAG, "没有有效视频输入，终止分析")
                    // 恢复已重置的分析状态
                    repository.markAnalyzed(validClips.map { it.id })
                    safeCallback(AnalysisState.Error("没有有效视频"))
                    return@launch
                }

                CrashLogger.i(TAG, "输入准备完成: ${inputs.size} 个视频有效，发送 RPG 分析请求...")
                safeCallback(AnalysisState.Analyzing)
                val result = aiClient.analyzeRpg(inputs, timestamps, config)

                result.onSuccess { rpgResults ->
                    if (rpgResults.isEmpty()) {
                        CrashLogger.w(TAG, "AI 返回空结果，视频保持未分析状态")
                        safeCallback(AnalysisState.Error("AI 未识别到活动，请重试"))
                        return@onSuccess
                    }
                    // 累加 delta 到现有角色状态
                    val latestStats2 = repository.getLatestCharacterStats()
                    var curHp2 = latestStats2?.hp ?: 50
                    var curMp2 = latestStats2?.mp ?: 50
                    var curMood2 = latestStats2?.mood ?: 50
                    var curMastery2 = latestStats2?.mastery ?: 50

                    rpgResults.take(validClips.size).forEachIndexed { idx, r ->
                        val clip = validClips[idx]
                        curHp2 = (curHp2 + r.stats.hp).coerceIn(10, 100)
                        curMp2 = (curMp2 + r.stats.mp).coerceIn(10, 100)
                        curMood2 = (curMood2 + r.stats.mood).coerceIn(10, 100)
                        curMastery2 = (curMastery2 + r.stats.mastery).coerceIn(10, 100)

                        repository.saveCharacterStats(
                            com.lifelog.camera.data.local.entity.CharacterStatsEntity(
                                timestamp = clip.capturedAt, hp = curHp2,
                                mp = curMp2, mood = curMood2,
                                mastery = curMastery2, clipId = clip.id
                            )
                        )
                    }

                    // 聚合 + 更新技能
                    val now = System.currentTimeMillis()
                    val mergedSkills = RpgAnalyzer.mergeSkillGains(rpgResults)
                    val COOLDOWN_MS = 30 * 60 * 1000L
                    mergedSkills.forEach { (name, skill) ->
                        val existing = repository.getSkillByName(name)
                        if (existing != null) {
                            val newPm = existing.practiceMinutes + 2
                            val canGainExp = now - existing.lastExpGainAt >= COOLDOWN_MS
                            val newExp = if (canGainExp) existing.exp + skill.expGain else existing.exp
                            val newLastExpGainAt = if (canGainExp) now else existing.lastExpGainAt
                            repository.upsertSkill(existing.copy(
                                exp = newExp, displayName = skill.display.ifBlank { existing.displayName },
                                practiceMinutes = newPm, lastExpGainAt = newLastExpGainAt, lastUsedAt = now
                            ))
                        } else {
                            repository.upsertSkill(
                                com.lifelog.camera.data.local.entity.SkillProficiencyEntity(
                                    skillName = name, displayName = skill.display.ifBlank { name },
                                    exp = skill.expGain, practiceMinutes = 2,
                                    lastExpGainAt = now, lastUsedAt = now
                                )
                            )
                        }
                    }

                    // 保存活动标签
                    val labels = rpgResults.mapIndexed { idx, r ->
                        val clip = validClips.getOrElse(idx) { validClips.first() }
                        com.lifelog.camera.data.local.entity.ActivityLabelEntity(
                            clipId = clip.id, timestamp = clip.capturedAt,
                            category = r.category, behavior = r.activity.lowercase().replace(" ", "_"),
                            description = r.activity, confidence = 0.8f
                        )
                    }
                    repository.saveLabels(labels)

                    // 保存新发现技能
                    rpgResults.flatMap { it.newSkills }.forEach { skillName ->
                        val existing = repository.getTodayDiscoveredSkills(
                            com.lifelog.camera.util.TimeUtils.getDayStart(),
                            com.lifelog.camera.util.TimeUtils.getDayEnd()
                        )
                        if (existing.none { it.skillName == skillName }) {
                            repository.insertDiscoveredSkill(
                                com.lifelog.camera.data.local.entity.DiscoveredSkillEntity(
                                    skillName = skillName, displayName = skillName,
                                    discoveredAt = now, clipId = validClips.firstOrNull()?.id ?: 0
                                )
                            )
                        }
                    }

                    repository.markAnalyzed(validClips.map { it.id })
                    safeCallback(AnalysisState.Done(rpgResults.size))
                }

                result.onFailure { e ->
                    safeCallback(AnalysisState.Error(e.message ?: "RPG 分析失败"))
                }
            } catch (e: Exception) {
                CrashLogger.e(TAG, "RPG 重新分析异常: ${e.message}", e)
                safeCallback(AnalysisState.Error(e.message ?: "未知错误"))
            }
        }
    }

    // ── 重新分析今天全部视频 ──

    fun reanalyzeAll(onState: (AnalysisState) -> Unit = {}) {
        if (job?.isActive == true) {
            CrashLogger.w(TAG, "分析已在运行中，忽略重复请求")
            return
        }

        CrashLogger.i(TAG, "========== 重新分析今日全部视频 ==========")
        val safeCallback = safeStateCallback(onState)
        job = scope.launch {
            try {
                val (dayStart, dayEnd) = TimeUtils.getTodayRange()
                val clips = repository.getTodayClips(dayStart, dayEnd)
                CrashLogger.i(TAG, "今日视频总数: ${clips.size} (含已分析)")

                if (clips.isEmpty()) {
                    safeCallback(AnalysisState.Done(0))
                    return@launch
                }

                // 重置所有视频的分析状态
                val allIds = clips.map { it.id }
                repository.resetAnalyzed(allIds)
                CrashLogger.i(TAG, "已重置 ${allIds.size} 个视频的分析状态")

                // 复用正常分析流程
                val config = apiPreferences.get()
                CrashLogger.i(TAG, "API 配置: baseUrl=${config.baseUrl}, hasKey=${config.apiKey.isNotBlank()}")
                if (!config.isValid) {
                    CrashLogger.w(TAG, "API Key 未配置，终止分析")
                    safeCallback(AnalysisState.Error("请先配置 API Key"))
                    return@launch
                }

                runAnalysisPipeline(clips, config, safeCallback)
            } catch (e: Exception) {
                CrashLogger.e(TAG, "重新分析异常: ${e.message}", e)
                safeCallback(AnalysisState.Error(e.message ?: "未知错误"))
            }
        }
    }

    /** 共享的分析流水线（提取全部帧 → AI 分析 → 存储结果） */
    private suspend fun runAnalysisPipeline(
        clips: List<com.lifelog.camera.data.local.entity.VideoClipEntity>,
        config: AIClient.ApiConfig,
        onState: (AnalysisState) -> Unit
    ) {
        // Step 2: 提取全部帧（不再只取 3 帧关键帧）
        CrashLogger.i(TAG, "开始提取全部帧...")
        onState(AnalysisState.Extracting(0, clips.size))

        val inputs = mutableListOf<AIClient.VideoInput>()
        val timestamps = mutableListOf<String>()
        val validClipIds = mutableListOf<Long>()

        for ((idx, clip) in clips.withIndex()) {
            CrashLogger.i(TAG, "  [${idx+1}/${clips.size}] clip id=${clip.id}, path=${clip.localPath}")
            val file = File(clip.localPath)
            if (!file.exists()) {
                CrashLogger.w(TAG, "  文件不存在: ${clip.localPath}")
                continue
            }
            CrashLogger.i(TAG, "  文件大小: ${file.length()} bytes")

            val cal = Calendar.getInstance().apply { timeInMillis = clip.capturedAt }
            val time = "%02d:%02d".format(
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE)
            )

            val input = if (file.extension.equals("mp4", ignoreCase = true)) {
                CrashLogger.i(TAG, "  检测到 MP4，直接作为视频输入 (${file.length()} bytes)")
                AIClient.VideoInput.VideoFile(file.absolutePath)
            } else {
                val allFrames = keyframeExtractor.readAllFrames(file)
                if (allFrames.isEmpty()) {
                    CrashLogger.w(TAG, "  帧提取失败（文件为空或损坏）")
                    continue
                }
                CrashLogger.i(TAG, "  全部帧提取成功: ${allFrames.size} 帧")
                AIClient.VideoInput.Frames(allFrames)
            }

            inputs.add(input)
            timestamps.add(time)
            validClipIds.add(clip.id)
            onState(AnalysisState.Extracting(idx + 1, clips.size))
        }

        CrashLogger.i(TAG, "输入准备完成: ${inputs.size}/${clips.size} 个视频有效")
        if (inputs.isEmpty()) {
            CrashLogger.w(TAG, "没有有效输入，终止分析")
            onState(AnalysisState.Error("没有有效视频"))
            return
        }

        // Step 3: AI 分析
        CrashLogger.i(TAG, "发送 AI 分析请求 (${inputs.size} 个视频)...")
        onState(AnalysisState.Analyzing)

        val result = aiClient.analyzeActivity(inputs, timestamps, config)

        result.onSuccess { activities ->
            CrashLogger.i(TAG, "AI 分析成功: ${activities.size} 个活动标签")
            if (activities.isEmpty()) {
                CrashLogger.w(TAG, "AI 返回 0 个活动，视频保持未分析状态以便重试")
                onState(AnalysisState.Error("AI 未识别到活动，请重试"))
                return
            }
            // Step 4: 存储结果
            val labels = activities.mapIndexed { idx, act ->
                val clipId = validClipIds.getOrElse(idx) { 0L }
                val clipEntity = clips.find { it.id == clipId }
                val cal = Calendar.getInstance().apply {
                    if (clipEntity != null) {
                        timeInMillis = clipEntity.capturedAt
                    }
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val parts = act.time.split(":")
                if (parts.size == 2) {
                    cal.set(Calendar.HOUR_OF_DAY, parts[0].toIntOrNull() ?: 0)
                    cal.set(Calendar.MINUTE, parts[1].toIntOrNull() ?: 0)
                }
                ActivityLabelEntity(
                    clipId = clipId,
                    timestamp = cal.timeInMillis,
                    category = act.category,
                    behavior = act.behavior,
                    description = act.description,
                    confidence = act.confidence
                )
            }
            repository.saveLabels(labels)
            repository.markAnalyzed(validClipIds)
            CrashLogger.i(TAG, "结果已保存: ${labels.size} 标签, ${validClipIds.size} 视频标记为已分析")
            onState(AnalysisState.Done(activities.size))
        }

        result.onFailure { e ->
            CrashLogger.e(TAG, "AI 分析失败: ${e.message}", e)
            onState(AnalysisState.Error(e.message ?: "AI 分析失败"))
        }
    }

    /** 包装回调：更新持久状态 + 防 UI 销毁异常，确保后台分析继续 */
    private fun safeStateCallback(
        original: (AnalysisState) -> Unit
    ): (AnalysisState) -> Unit = { state ->
        // 更新持久状态（ViewModel 重建后可读取）
        _lastResult.value = state
        _isRunning.value = state !is AnalysisState.Done && state !is AnalysisState.Error
        // 通知 UI（可能已销毁，try-catch 保护）
        try {
            original(state)
        } catch (e: Exception) {
            CrashLogger.w(TAG, "状态回调异常（UI 可能已销毁），分析继续: ${e.message}")
        }
    }

    // ── 生成日报 ──

    suspend fun generateDailyReport(): Result<String> {
        val config = apiPreferences.get()
        if (!config.isValid) return Result.failure(Exception("请先配置 API Key"))

        val (dayStart, dayEnd) = TimeUtils.getTodayRange()
        val labels = repository.getLabelsByDate(dayStart, dayEnd)
        if (labels.isEmpty()) return Result.success("暂无今日活动记录。")

        val activities = labels.map { label ->
            AIClient.ActivityResult(
                time = TimeUtils.formatTime(label.timestamp),
                category = label.category,
                behavior = label.behavior,
                description = label.description,
                confidence = label.confidence
            )
        }

        return aiClient.generateReport(activities, config)
    }

}
