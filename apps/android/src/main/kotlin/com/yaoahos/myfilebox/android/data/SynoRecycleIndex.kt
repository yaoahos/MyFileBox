package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.RecycleItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 群晖回收站「删除单元」索引。
 *
 * DSM 的 #recycle 保留原路径结构（删 /Backup/Temp/Ces 会落到 #recycle/Temp/Ces，
 * 删 /Backup/Temp/Ces/0.log 会落到 #recycle/Temp/Ces/0.log），光看目录树无法区分
 * 「被整只删掉的文件夹」和「为保存原路径自动补出来的中间层目录」——两者都是目录。
 * 结果就是：删整个文件夹后回收站只列出文件夹内的文件、还原时父目录链缺失而失败。
 *
 * 这里在「本应用删除」时把真正的删除单元记下来（与本地回收站 .meta 同语义）：
 * 列表按记录整只展示文件夹（不展开内容），还原整只移回。外部（File Station 等）
 * 删除的条目没有记录，退回「叶子」规则兜底。记录的单元若已不在 #recycle
 * （被外部还原/清空）会在列表合并时被剔除。
 */
class SynoRecycleIndex(private val file: File) {

    @Synchronized
    fun all(): List<RecycleItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                RecycleItem(
                    id = o.optString("id").ifBlank { o.optString("trashPath") },
                    name = o.optString("name"),
                    originalPath = o.optString("originalPath"),
                    trashPath = o.optString("trashPath"),
                    deletedAt = o.optLong("deletedAt"),
                    sizeBytes = o.optLong("sizeBytes"),
                    isFolder = o.optBoolean("isFolder")
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 追加删除单元（同 trashPath 覆盖旧记录）。 */
    @Synchronized
    fun add(items: List<RecycleItem>) {
        if (items.isEmpty()) return
        val map = LinkedHashMap<String, RecycleItem>()
        all().forEach { map[normalize(it.trashPath)] = it }
        items.forEach { map[normalize(it.trashPath)] = it }
        write(map.values)
    }

    /** 移除记录（含其内部的子记录）：单元被还原/彻底删除时调用。 */
    @Synchronized
    fun remove(trashPaths: Collection<String>) {
        if (trashPaths.isEmpty()) return
        val targets = trashPaths.map { normalize(it) }
        val kept = all().filter { item ->
            val p = normalize(item.trashPath)
            targets.none { t -> p == t || p.startsWith("$t/") }
        }
        write(kept)
    }

    /** 只保留 trashPath 在 [keep] 里的记录：列表合并后清掉已消失的单元。 */
    @Synchronized
    fun retain(keep: Collection<String>) {
        val keepSet = keep.map { normalize(it) }.toSet()
        write(all().filter { normalize(it.trashPath) in keepSet })
    }

    @Synchronized
    fun clear() = write(emptyList())

    private fun normalize(path: String) = path.trimEnd('/')

    private fun write(items: Collection<RecycleItem>) {
        runCatching {
            val arr = JSONArray()
            items.forEach { item ->
                arr.put(JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("originalPath", item.originalPath)
                    put("trashPath", item.trashPath)
                    put("deletedAt", item.deletedAt)
                    put("sizeBytes", item.sizeBytes)
                    put("isFolder", item.isFolder)
                })
            }
            file.parentFile?.mkdirs()
            file.writeText(arr.toString(), Charsets.UTF_8)
        }
    }
}
