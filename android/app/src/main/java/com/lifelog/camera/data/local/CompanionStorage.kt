package com.lifelog.camera.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.GsonBuilder
import com.lifelog.camera.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Companion JSON 文件存储管理
 *
 * 目录结构:
 *   filesDir/companion/
 *     ├── character_ref.png
 *     ├── companion_profile.json
 *     └── generated/<generationId>/
 *           ├── meta.json
 *           ├── 01_character_raw.png
 *           ├── 02_character_nobg.png
 *           └── 03_final_composite.png
 */
@Singleton
class CompanionStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CompanionStorage"
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val companionDir = File(context.filesDir, "companion")
    private val generatedDir = File(companionDir, "generated")

    init {
        if (!companionDir.exists()) companionDir.mkdirs()
        if (!generatedDir.exists()) generatedDir.mkdirs()
    }

    // ── 目录访问 ──

    fun getCompanionDir(): File = companionDir
    fun getGeneratedDir(): File = generatedDir
    fun getCharacterRefFile(): File = File(companionDir, "character_ref.png")

    /** 多参考图支持 */
    fun getCharacterRefFiles(): List<File> {
        val refs = companionDir.listFiles { f ->
            f.isFile && f.name.matches(Regex("character_ref(_\\d+)?\\.(png|jpg|jpeg)"))
        }?.sortedBy { it.name } ?: emptyList()
        return if (refs.isEmpty() && getCharacterRefFile().exists())
            listOf(getCharacterRefFile()) else refs
    }

    fun saveCharacterReference(index: Int, bitmap: Bitmap) {
        val file = if (index == 0) getCharacterRefFile()
                   else File(companionDir, "character_ref_$index.png")
        try {
            // 原子写入：先写 .tmp，成功后再 rename，防止崩溃导致文件损坏
            val tmp = File(companionDir, "character_ref_$index.png.tmp")
            tmp.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (!tmp.renameTo(file)) {
                file.outputStream().use { out -> tmp.inputStream().use { it.copyTo(out) } }
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存参考图 $index 失败", e)
        }
    }

    /** 删除参考图 (按文件列表中的位置) */
    fun deleteCharacterRefAt(displayIndex: Int) {
        val files = getCharacterRefFiles()
        if (displayIndex >= files.size) return
        val file = files[displayIndex]
        file.delete()
        // 同步清理元数据
        val metas = loadRefMetas().toMutableMap()
        metas.remove(file.name)
        saveRefMetas(metas)
    }

    /** 删除参考图 (按存储索引, 兼容旧逻辑) */
    fun deleteCharacterReference(index: Int) {
        val file = if (index == 0) getCharacterRefFile()
                   else File(companionDir, "character_ref_$index.png")
        file.delete()
        val metas = loadRefMetas().toMutableMap()
        metas.remove(file.name)
        saveRefMetas(metas)
    }

    fun hasAnyCharacterReference(): Boolean = getCharacterRefFiles().isNotEmpty()

    // ── 参考图分类元数据 ──

    private val refMetaFile = File(companionDir, "character_refs_meta.json")

    fun loadRefMetas(): Map<String, ReferenceCategory> {
        if (!refMetaFile.exists()) return emptyMap()
        return try {
            val json = refMetaFile.readText()
            val arr = org.json.JSONObject(json)
            val map = mutableMapOf<String, ReferenceCategory>()
            for (key in arr.keys()) {
                map[key] = try {
                    ReferenceCategory.valueOf(arr.getString(key))
                } catch (_: Exception) { ReferenceCategory.CHARACTER }
            }
            map
        } catch (e: Exception) { emptyMap() }
    }

    fun saveRefMetas(metas: Map<String, ReferenceCategory>) {
        try {
            val obj = org.json.JSONObject()
            for ((k, v) in metas) obj.put(k, v.name)
            refMetaFile.writeText(obj.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "保存参考图元数据失败", e)
        }
    }

    fun setRefCategory(fileName: String, category: ReferenceCategory) {
        val metas = loadRefMetas().toMutableMap()
        metas[fileName] = category
        saveRefMetas(metas)
    }

    fun getGenerationDir(generationId: String): File {
        val dir = File(generatedDir, generationId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── 角色档案 ──

    fun loadProfile(): CompanionProfile {
        val file = File(companionDir, "companion_profile.json")
        if (!file.exists()) return CompanionProfile()
        return try {
            gson.fromJson(file.readText(), CompanionProfile::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "加载 profile 失败", e)
            CompanionProfile()
        }
    }

    fun saveProfile(profile: CompanionProfile) {
        val file = File(companionDir, "companion_profile.json")
        try {
            file.writeText(gson.toJson(profile))
        } catch (e: Exception) {
            Log.e(TAG, "保存 profile 失败", e)
        }
    }

    // ── 角色参考图 ──

    fun saveCharacterReference(bitmap: Bitmap) {
        val file = getCharacterRefFile()
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存参考图失败", e)
        }
    }

    fun loadCharacterReference(): Bitmap? {
        val file = getCharacterRefFile()
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "加载参考图失败", e)
            null
        }
    }

    fun hasCharacterReference(): Boolean = getCharacterRefFile().exists()

    // ── 单次生成管理 ──

    fun saveMeta(generationId: String, meta: CompanionMeta) {
        val file = File(getGenerationDir(generationId), "meta.json")
        try {
            file.writeText(gson.toJson(meta))
        } catch (e: Exception) {
            Log.e(TAG, "保存 meta 失败", e)
        }
    }

    fun loadMeta(generationId: String): CompanionMeta? {
        val file = File(getGenerationDir(generationId), "meta.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), CompanionMeta::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "加载 meta 失败", e)
            null
        }
    }

    fun getGenerationOutputPath(generationId: String, filename: String): File {
        return File(getGenerationDir(generationId), filename)
    }

    fun listGenerations(): List<CompanionGenerationSummary> {
        if (!generatedDir.exists()) return emptyList()
        val dirs = generatedDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val metaFile = File(dir, "meta.json")
            if (!metaFile.exists()) return@mapNotNull null
            try {
                val meta = gson.fromJson(metaFile.readText(), CompanionMeta::class.java)
                // 过滤掉错误/未完成状态
                if (meta.status != "done") return@mapNotNull null
                // 新管线用 final_result.png, 旧管线用 03_final_composite.png
                var thumbFile = File(dir, "final_result.png")
                if (!thumbFile.exists()) thumbFile = File(dir, "03_final_composite.png")
                // 跳过没有缩略图的条目
                if (!thumbFile.exists()) return@mapNotNull null
                CompanionGenerationSummary(
                    generationId = meta.generationId,
                    clipId = meta.clipId,
                    createdAt = meta.createdAt,
                    thumbnailPath = thumbFile.absolutePath,
                    status = meta.status
                )
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.createdAt }
    }

    fun deleteGeneration(generationId: String) {
        val dir = getGenerationDir(generationId)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    // ── 新管线结果 ──

    fun savePipelineResult(generationId: String, result: PipelineResult) {
        val file = File(getGenerationDir(generationId), "pipeline_result.json")
        try {
            file.writeText(gson.toJson(result))
        } catch (e: Exception) {
            Log.e(TAG, "保存管线结果失败", e)
        }
    }

    fun loadPipelineResult(generationId: String): PipelineResult? {
        val file = File(getGenerationDir(generationId), "pipeline_result.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), PipelineResult::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "加载管线结果失败", e)
            null
        }
    }

    // ── 流水线状态 (断点续传) ──

    fun saveProcessingState(state: ProcessingState) {
        val file = File(companionDir, "processing_state.json")
        try {
            file.writeText(gson.toJson(state))
        } catch (e: Exception) {
            Log.e(TAG, "保存处理状态失败", e)
        }
    }

    fun loadProcessingState(): ProcessingState? {
        val file = File(companionDir, "processing_state.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), ProcessingState::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clearProcessingState() {
        File(companionDir, "processing_state.json").delete()
    }
}
