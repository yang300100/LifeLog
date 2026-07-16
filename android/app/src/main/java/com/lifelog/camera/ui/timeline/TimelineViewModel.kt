package com.lifelog.camera.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifelog.camera.ai.AnalysisManager
import com.lifelog.camera.ai.ApiPreferences
import com.lifelog.camera.ai.KeyframeExtractor
import com.lifelog.camera.ble.BleFileTransfer
import com.lifelog.camera.data.local.entity.VideoClipEntity
import com.lifelog.camera.data.local.entity.ActivityLabelEntity
import com.lifelog.camera.data.repository.VideoRepository
import com.lifelog.camera.util.CrashLogger
import com.lifelog.camera.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val bleTransfer: BleFileTransfer,
    private val analysisManager: AnalysisManager,
    private val keyframeExtractor: KeyframeExtractor,
    private val apiPreferences: ApiPreferences
) : ViewModel() {

    fun isRealtimeMode(): Boolean = apiPreferences.getMode() == "realtime"

    data class TimelineItem(
        val time: String,
        val clip: VideoClipEntity,
        val labels: List<ActivityLabelEntity>
    )

    private val dataVersion = MutableStateFlow(0)

    // 日期选择
    private val _selectedDate = MutableStateFlow(TimeUtils.getDayStart())
    val selectedDate: StateFlow<Long> = _selectedDate.asStateFlow()
    val selectedDateLabel: StateFlow<String> = _selectedDate.map { dayStart ->
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = dayStart }
        "%d月%d日".format(cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun goToPrevDay() { _selectedDate.update { it - 86400000L } }
    fun goToNextDay() {
        val today = TimeUtils.getDayStart()
        _selectedDate.update { minOf(it + 86400000L, today) }
    }
    fun goToToday() { _selectedDate.update { TimeUtils.getDayStart() } }

    val todayItems: StateFlow<List<TimelineItem>> = combine(
        repository.getAllClipsFlow(),
        repository.getLabelsByDateFlow(TimeUtils.getDayStart(), TimeUtils.getDayEnd()),
        dataVersion,
        _selectedDate
    ) { clips, labels, _, dayStart ->
        val dayEnd = dayStart + 86400000L - 1
        clips.filter { it.capturedAt in dayStart..dayEnd }.sortedBy { it.capturedAt }.map { clip ->
            TimelineItem(
                time = TimeUtils.formatTime(clip.capturedAt),
                clip = clip,
                labels = labels.filter { it.clipId == clip.id }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncState = bleTransfer.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
                 BleFileTransfer.SyncState.Idle)

    val unanalyzedCount = MutableStateFlow(0)
    val isAnalyzing = MutableStateFlow(false)
    val analysisMsg = MutableStateFlow<String?>(null)
    val analysisError = MutableStateFlow<String?>(null)  // 错误消息独立保留，不随 isAnalyzing 消失
    val analyzingClipIds = MutableStateFlow<Set<Long>>(emptySet())  // 正在分析的单条视频 ID

    init {
        viewModelScope.launch {
            unanalyzedCount.value = analysisManager.getUnanalyzedCount()
            // 恢复后台分析状态（用户切走后 ViewModel 重建时）
            if (analysisManager.isRunning.value) {
                CrashLogger.i("TimelineVM", "检测到后台分析正在运行，恢复状态")
                isAnalyzing.value = true
                val last = analysisManager.lastResult.value
                if (last != null) {
                    analysisMsg.value = when (last) {
                        is AnalysisManager.AnalysisState.Extracting -> "提取关键帧..."
                        is AnalysisManager.AnalysisState.Analyzing -> "AI 分析中..."
                        else -> "处理中..."
                    }
                }
            }
        }
        // 持续监听后台分析状态变化
        viewModelScope.launch {
            analysisManager.isRunning.collect { running ->
                if (!running && isAnalyzing.value) {
                    // 后台分析完成
                    isAnalyzing.value = false
                    analysisError.value = null
                    unanalyzedCount.value = analysisManager.getUnanalyzedCount()
                    val result = analysisManager.lastResult.value
                    if (result is AnalysisManager.AnalysisState.Done) {
                        analysisMsg.value = "分析完成: ${result.activities} 个活动"
                    } else if (result is AnalysisManager.AnalysisState.Error) {
                        analysisError.value = result.msg
                    }
                }
            }
        }
    }

    fun startSync() {
        viewModelScope.launch {
            val result = bleTransfer.startSync(repository.getVideoDir())
            result.onSuccess {
                unanalyzedCount.value = analysisManager.getUnanalyzedCount()
            }
        }
    }

    // ── 手机拍摄 ──

    /** 处理手机拍摄的 MP4：保存到视频目录 + 转 MJPEG 预览 + 注册到数据库 */
    fun onPhoneVideoCaptured(mp4File: File) {
        viewModelScope.launch {
            try {
                CrashLogger.i("TimelineVM", "处理手机拍摄视频: ${mp4File.name} (${mp4File.length()} bytes)")
                val videoDir = repository.getVideoDir()

                val nextId = withContext(Dispatchers.IO) {
                    repository.getMaxId() + 1
                }
                // 保存 MP4（AI 分析直接传视频）
                val savedMp4Name = "clip_%04d.mp4".format(nextId)
                val savedMp4File = File(videoDir, savedMp4Name)
                withContext(Dispatchers.IO) {
                    mp4File.copyTo(savedMp4File, overwrite = true)
                }
                CrashLogger.i("TimelineVM", "MP4 已保存: ${savedMp4File.length()} bytes")

                // 同时生成 MJPEG 预览（时间轴回放用）
                val mjpgName = "clip_%04d.mjpg".format(nextId)
                val mjpgFile = File(videoDir, mjpgName)
                withContext(Dispatchers.IO) {
                    keyframeExtractor.convertMp4ToMjpeg(savedMp4File, mjpgFile)
                }

                val entity = VideoClipEntity(
                    id = nextId,
                    fileName = savedMp4Name,
                    capturedAt = System.currentTimeMillis(),
                    fileSize = savedMp4File.length(),
                    durationMs = 5000,
                    localPath = savedMp4File.absolutePath,
                    syncedAt = System.currentTimeMillis()
                )
                repository.saveClip(entity)
                CrashLogger.i("TimelineVM", "手机视频已注册: $savedMp4Name")

                mp4File.delete()
            } catch (e: Exception) {
                CrashLogger.e("TimelineVM", "手机视频处理失败", e)
                try { mp4File.delete() } catch (_: Exception) {}
            }
        }
    }

    /** 重新分析今日全部视频（含已分析） */
    fun reanalyzeAll() {
        if (isAnalyzing.value) return
        CrashLogger.i("TimelineVM", "用户触发重新分析")
        isAnalyzing.value = true
        analysisError.value = null
        analysisManager.reanalyzeAll { state ->
            CrashLogger.i("TimelineVM", "重新分析状态回调: $state")
            analysisMsg.value = when (state) {
                is AnalysisManager.AnalysisState.Extracting ->
                    "提取关键帧 ${state.current}/${state.total}"
                is AnalysisManager.AnalysisState.Analyzing -> "AI 分析中..."
                is AnalysisManager.AnalysisState.Done -> {
                    CrashLogger.i("TimelineVM", "重新分析完成: ${state.activities} 个活动")
                    isAnalyzing.value = false
                    unanalyzedCount.value = 0
                    analysisError.value = null
                    dataVersion.value++
                    "分析完成: ${state.activities} 个活动"
                }
                is AnalysisManager.AnalysisState.Error -> {
                    CrashLogger.w("TimelineVM", "重新分析错误: ${state.msg}")
                    isAnalyzing.value = false
                    analysisError.value = state.msg
                    "错误: ${state.msg}"
                }
                else -> null
            }
        }
    }

    fun deleteClip(clipId: Long, localPath: String) {
        viewModelScope.launch {
            try {
                repository.deleteClip(clipId, localPath)
                CrashLogger.i("TimelineVM", "已删除视频: id=$clipId")
            } catch (e: Exception) {
                CrashLogger.e("TimelineVM", "删除视频失败", e)
            }
        }
    }

    fun playClip(localPath: String, onPlay: (String) -> Unit) {
        onPlay(localPath)
    }

    /** 分析单个视频 */
    fun analyzeSingleClip(clipId: Long) {
        if (isAnalyzing.value) {
            CrashLogger.w("TimelineVM", "分析已在运行中，忽略单条分析")
            return
        }
        CrashLogger.i("TimelineVM", "单条分析请求: clipId=$clipId")
        isAnalyzing.value = true
        analysisError.value = null
        analyzingClipIds.value = analyzingClipIds.value + clipId

        analysisManager.analyzeSingleClip(clipId) { state ->
            CrashLogger.i("TimelineVM", "单条分析状态: $state")
            analysisMsg.value = when (state) {
                is AnalysisManager.AnalysisState.Extracting ->
                    "提取关键帧 ${state.current}/${state.total}"
                is AnalysisManager.AnalysisState.Analyzing -> "AI 分析中..."
                is AnalysisManager.AnalysisState.Done -> {
                    isAnalyzing.value = false
                    analyzingClipIds.value = analyzingClipIds.value - clipId
                    analysisError.value = null
                    dataVersion.value++
                    viewModelScope.launch {
                        unanalyzedCount.value = analysisManager.getUnanalyzedCount()
                    }
                    "分析完成"
                }
                is AnalysisManager.AnalysisState.Error -> {
                    isAnalyzing.value = false
                    analyzingClipIds.value = analyzingClipIds.value - clipId
                    analysisError.value = state.msg
                    viewModelScope.launch {
                        unanalyzedCount.value = analysisManager.getUnanalyzedCount()
                    }
                    "错误: ${state.msg}"
                }
                else -> null
            }
        }
    }

    fun runAnalysis() {
        if (isAnalyzing.value) {
            CrashLogger.w("TimelineVM", "分析已在运行中，忽略")
            return
        }
        CrashLogger.i("TimelineVM", "用户触发分析")
        isAnalyzing.value = true
        analysisError.value = null  // 清除上次错误
        analysisManager.runAnalysis { state ->
            CrashLogger.i("TimelineVM", "分析状态回调: $state")
            analysisMsg.value = when (state) {
                is AnalysisManager.AnalysisState.Extracting ->
                    "提取关键帧 ${state.current}/${state.total}"
                is AnalysisManager.AnalysisState.Analyzing -> "AI 分析中..."
                is AnalysisManager.AnalysisState.Done -> {
                    CrashLogger.i("TimelineVM", "分析完成: ${state.activities} 个活动")
                    isAnalyzing.value = false
                    unanalyzedCount.value = 0
                    analysisError.value = null
                    dataVersion.value++
                    "分析完成: ${state.activities} 个活动"
                }
                is AnalysisManager.AnalysisState.Error -> {
                    CrashLogger.w("TimelineVM", "分析错误: ${state.msg}")
                    isAnalyzing.value = false
                    analysisError.value = state.msg  // 保留错误信息不被 isAnalyzing 切换隐藏
                    "错误: ${state.msg}"
                }
                else -> null
            }
        }
    }

}
