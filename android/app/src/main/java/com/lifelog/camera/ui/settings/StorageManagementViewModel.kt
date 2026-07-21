package com.lifelog.camera.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifelog.camera.data.local.StorageDayStats
import com.lifelog.camera.data.local.entity.VideoClipEntity
import com.lifelog.camera.data.repository.VideoRepository
import com.lifelog.camera.util.CrashLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StorageManagementViewModel @Inject constructor(
    private val repository: VideoRepository
) : ViewModel() {

    private val _dayStats = MutableStateFlow<List<StorageDayStats>>(emptyList())
    val dayStats: StateFlow<List<StorageDayStats>> = _dayStats.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // 展开的日期（显示该日期下的全部片段）
    private val _expandedDate = MutableStateFlow<String?>(null)
    val expandedDate: StateFlow<String?> = _expandedDate.asStateFlow()

    // 展开日期下的 clips
    private val _expandedClips = MutableStateFlow<List<VideoClipEntity>>(emptyList())
    val expandedClips: StateFlow<List<VideoClipEntity>> = _expandedClips.asStateFlow()

    // 总占用字节
    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    private val _totalClips = MutableStateFlow(0)
    val totalClips: StateFlow<Int> = _totalClips.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val stats = repository.getStorageStatsByDay()
                _dayStats.value = stats
                _totalBytes.value = stats.sumOf { it.totalBytes }
                _totalClips.value = stats.sumOf { it.clipCount }
            } catch (e: Exception) {
                CrashLogger.e("StorageVM", "加载存储统计失败", e)
                _toastMessage.emit("加载失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** 展开/折叠某一天的片段列表 */
    fun toggleDate(dateStr: String) {
        viewModelScope.launch {
            if (_expandedDate.value == dateStr) {
                _expandedDate.value = null
                _expandedClips.value = emptyList()
            } else {
                _expandedDate.value = dateStr
                try {
                    // 解析日期范围为当天 0:00 到 23:59:59
                    val parts = dateStr.split("-")
                    if (parts.size == 3) {
                        val cal = java.util.Calendar.getInstance().apply {
                            set(java.util.Calendar.YEAR, parts[0].toInt())
                            set(java.util.Calendar.MONTH, parts[1].toInt() - 1)
                            set(java.util.Calendar.DAY_OF_MONTH, parts[2].toInt())
                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }
                        val start = cal.timeInMillis
                        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                        val end = cal.timeInMillis - 1
                        _expandedClips.value = repository.getClipsInRange(start, end)
                    }
                } catch (e: Exception) {
                    CrashLogger.e("StorageVM", "加载日期片段失败: $dateStr", e)
                }
            }
        }
    }

    /** 刷新当前展开日期的片段列表（新增/删除后调用） */
    fun refreshExpandedClips() {
        val dateStr = _expandedDate.value ?: return
        viewModelScope.launch {
            try {
                val parts = dateStr.split("-")
                if (parts.size == 3) {
                    val cal = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.YEAR, parts[0].toInt())
                        set(java.util.Calendar.MONTH, parts[1].toInt() - 1)
                        set(java.util.Calendar.DAY_OF_MONTH, parts[2].toInt())
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val start = cal.timeInMillis
                    cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                    val end = cal.timeInMillis - 1
                    _expandedClips.value = repository.getClipsInRange(start, end)
                }
            } catch (e: Exception) {
                CrashLogger.e("StorageVM", "刷新展开列表失败", e)
            }
        }
    }

    /** 删除单个片段的源文件 */
    fun deleteSingleSourceFile(clip: VideoClipEntity) {
        viewModelScope.launch {
            try {
                repository.deleteSourceFilesOnly(listOf(clip.id))
                _toastMessage.emit("已删除 ${clip.fileName} 的源文件")
                refresh()
                refreshExpandedClips()
            } catch (e: Exception) {
                CrashLogger.e("StorageVM", "删除源文件失败", e)
                _toastMessage.emit("删除失败: ${e.message}")
            }
        }
    }

    /** 删除某一天所有片段的源文件 */
    fun deleteDaySourceFiles(dateStr: String) {
        viewModelScope.launch {
            try {
                val parts = dateStr.split("-")
                if (parts.size != 3) return@launch
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.YEAR, parts[0].toInt())
                    set(java.util.Calendar.MONTH, parts[1].toInt() - 1)
                    set(java.util.Calendar.DAY_OF_MONTH, parts[2].toInt())
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val start = cal.timeInMillis
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                val end = cal.timeInMillis - 1

                val clips = repository.getClipsInRange(start, end)
                val deleted = repository.deleteSourceFilesOnly(clips.map { it.id })
                _toastMessage.emit("已删除 $dateStr 的 ${deleted} 个源文件")
                _expandedDate.value = null
                _expandedClips.value = emptyList()
                refresh()
            } catch (e: Exception) {
                CrashLogger.e("StorageVM", "删除当天源文件失败", e)
                _toastMessage.emit("删除失败: ${e.message}")
            }
        }
    }

    /** 删除全部源文件 */
    fun deleteAllSourceFiles() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                var totalDeleted = 0
                val stats = repository.getStorageStatsByDay()
                for (stat in stats) {
                    val parts = stat.dateStr.split("-")
                    if (parts.size != 3) continue
                    val cal = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.YEAR, parts[0].toInt())
                        set(java.util.Calendar.MONTH, parts[1].toInt() - 1)
                        set(java.util.Calendar.DAY_OF_MONTH, parts[2].toInt())
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val start = cal.timeInMillis
                    cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                    val end = cal.timeInMillis - 1
                    val clips = repository.getClipsInRange(start, end)
                    totalDeleted += repository.deleteSourceFilesOnly(clips.map { it.id })
                }
                _toastMessage.emit("已删除全部 ${totalDeleted} 个源文件")
                refresh()
            } catch (e: Exception) {
                CrashLogger.e("StorageVM", "删除全部源文件失败", e)
                _toastMessage.emit("删除失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }
}
