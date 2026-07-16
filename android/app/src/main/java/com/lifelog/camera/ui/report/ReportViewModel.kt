package com.lifelog.camera.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifelog.camera.ai.AnalysisManager
import com.lifelog.camera.data.local.entity.ActivityLabelEntity
import com.lifelog.camera.data.repository.VideoRepository
import com.lifelog.camera.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val analysisManager: AnalysisManager
) : ViewModel() {

    data class DailyReport(
        val date: String,
        val summary: String,
        val activities: List<ActivityLabelEntity>
    )

    val report = MutableStateFlow<DailyReport?>(null)
    val isAnalyzing = MutableStateFlow(false)
    val analysisState = MutableStateFlow<String?>(null)
    val unanalyzedCount = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            unanalyzedCount.value = analysisManager.getUnanalyzedCount()
            loadTodayReport()
        }
    }

    fun runAnalysis() {
        if (isAnalyzing.value) return
        isAnalyzing.value = true

        analysisManager.runAnalysis { state ->
            analysisState.value = when (state) {
                is AnalysisManager.AnalysisState.Idle -> null
                is AnalysisManager.AnalysisState.Extracting ->
                    "提取关键帧... ${state.current}/${state.total}"
                is AnalysisManager.AnalysisState.Analyzing ->
                    "AI 分析中..."
                is AnalysisManager.AnalysisState.Done -> {
                    isAnalyzing.value = false
                    loadTodayReport()
                    "分析完成: ${state.activities} 个活动"
                }
                is AnalysisManager.AnalysisState.Error -> {
                    isAnalyzing.value = false
                    "错误: ${state.msg}"
                }
            }
        }
    }

    fun generateReport() {
        viewModelScope.launch {
            isAnalyzing.value = true
            analysisState.value = "生成日报中..."

            val result = analysisManager.generateDailyReport()
            result.onSuccess { summary ->
                val (start, end) = TimeUtils.getTodayRange()
                val labels = repository.getLabelsByDate(start, end)
                report.value = DailyReport(
                    date = TimeUtils.formatDate(Calendar.getInstance()),
                    summary = summary,
                    activities = labels
                )
                analysisState.value = null
            }
            result.onFailure { e ->
                analysisState.value = "日报生成失败: ${e.message}"
            }
            isAnalyzing.value = false
        }
    }

    private fun loadTodayReport() {
        viewModelScope.launch {
            val (start, end) = TimeUtils.getTodayRange()
            val labels = repository.getLabelsByDate(start, end)
            if (labels.isNotEmpty()) {
                report.value = DailyReport(
                    date = TimeUtils.formatDate(Calendar.getInstance()),
                    summary = buildLocalSummary(labels),
                    activities = labels
                )
            }
            unanalyzedCount.value = analysisManager.getUnanalyzedCount()
        }
    }

    private fun buildLocalSummary(labels: List<ActivityLabelEntity>): String {
        if (labels.isEmpty()) return "暂无今日活动记录。"
        val sb = StringBuilder("我今天")
        val seen = mutableSetOf<String>()
        labels.forEach { label ->
            val time = TimeUtils.formatTime(label.timestamp)
            val desc = label.description
            if (seen.add(desc)) {
                sb.append(" ${time}${desc}，")
            }
        }
        sb.append("以上就是今天的活动摘要。")
        return sb.toString()
    }
}
