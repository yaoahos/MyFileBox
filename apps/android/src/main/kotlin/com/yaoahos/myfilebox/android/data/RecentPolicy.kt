package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.FileEntry
import java.util.Locale

/**
 * 「最近文件」的展示规则。
 *
 * 目标不是「磁盘上最近变动的字节」，而是「用户可能想打开的最新文件」。
 * 全盘遍历会把大量机器产物一起捞出来（应用缓存、缩略图、临时文件、各类备份副本），
 * 它们的 mtime 往往比用户真正在用的文件还新，于是「最近」第一屏全是噪音。
 *
 * 这里只做纯规则判定，不碰文件系统，方便直接在 JVM 单测里跑矩阵。
 */
object RecentPolicy {
    /** 时间窗：只看最近 7 天改过的文件。 */
    const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** 列表上限：超过这个量级「最近」已经失去意义，也避免整份列表常驻内存。 */
    const val LIMIT = 500

    /** 机器生成、点了也打不开或没有意义的后缀。 */
    private val NOISE_TAILS = listOf(
        ".tmp", ".temp", ".part", ".crdownload", ".download",
        ".log", ".swp", ".swo", ".~lock", ".lock",
        "-wal", "-shm", ".db-journal"
    )

    /** 备份副本：内容完整但属于「另一份」，不该占据时间流。 */
    private val BACKUP_TAILS = listOf(".bak", ".orig", ".asd", ".save", "~")

    /** 应用内部的缓存目录：文件量大、全是碎片，系统相册本身也不展示。 */
    private val CACHE_SEGMENTS = listOf(
        "/android/media/",
        "/tencent/micromsg/",
        "/tencent/qq_",
        "/.thumbnails/",
        "/dcim/.thumbnails/"
    )

    /** 打开过程中产生的锁/缓冲：关掉文档就消失，永远不该出现在「最近」。 */
    fun isNoiseName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.startsWith("~$")) return true
        if (name.length > 2 && name.startsWith("#") && name.endsWith("#")) return true
        val lower = name.lowercase(Locale.ROOT)
        return NOISE_TAILS.any { lower.endsWith(it) }
    }

    /** 备份副本判定：`报告.md.bak`、`报告.orig`、`报告~`、`笔记.asd`。 */
    fun isBackupName(name: String): Boolean {
        if (name.isEmpty()) return false
        val lower = name.lowercase(Locale.ROOT)
        if (lower.startsWith("~$")) return false
        return BACKUP_TAILS.any { lower.endsWith(it) }
    }

    /** 备份副本去掉后缀后的原名；不是备份则返回 null。 */
    fun originalNameOf(name: String): String? {
        if (!isBackupName(name)) return null
        val lower = name.lowercase(Locale.ROOT)
        val tail = BACKUP_TAILS.firstOrNull { lower.endsWith(it) } ?: return null
        return name.dropLast(tail.length)
    }

    fun isCachePath(path: String): Boolean {
        if (path.isEmpty()) return false
        val lower = path.lowercase(Locale.ROOT)
        return CACHE_SEGMENTS.any { lower.contains(it) }
    }

    /**
     * 文件是否位于「备份目录」里：`Documents/Backup/xxx.docx`、`我的备份/...`。
     *
     * 只看目录段，不看文件名——文件名里的 `.bak` 由 [isBackupName] 负责。
     * 这条和遍历层的 `LocalRepository.isBackupDirName` 是同一套口径：遍历层不进这些
     * 目录，但 MediaStore 索引照样会返回它们的内容，所以展示层还要再拦一次。
     */
    fun isBackupPath(path: String): Boolean {
        if (path.isEmpty()) return false
        val dir = path.substringBeforeLast('/', "")
        if (dir.isEmpty()) return false
        return dir.split('/').any { it.isNotEmpty() && LocalRepository.isBackupDirName(it) }
    }

    /**
     * 同目录下有正文时，把它的备份副本收起来。
     *
     * 写作软件每次保存都会刷新 `.bak` 的 mtime，不去重的话正文明明是三天前改的，
     * 却因为副本昨晚刷新而排进「今天」那一组，同一篇文档还会出现两条。
     * 只有正文已经不存在时才保留副本——那种情况下它往往是唯一还能打开的一份。
     */
    fun collapseBackups(files: List<FileEntry>): List<FileEntry> {
        if (files.none { isBackupName(it.name) }) return files
        val originals = files.asSequence()
            .filter { !isBackupName(it.name) }
            .map { it.path }
            .toHashSet()
        return files.filter { file ->
            val original = originalNameOf(file.name)
            if (original == null || original.isEmpty()) return@filter true
            val dir = file.path.substringBeforeLast('/', "")
            val sibling = if (dir.isEmpty()) original else "$dir/$original"
            sibling !in originals
        }
    }

    /**
     * 「最近」最终展示的列表：时间窗 → 噪音 → 缓存目录 → 备份副本 → 去重 → 倒序截断。
     *
     * @param now 当前时间，显式传入以便单测固定时间点。
     */
    fun filter(
        files: List<FileEntry>,
        now: Long,
        hideBackups: Boolean = true,
        hideCache: Boolean = true
    ): List<FileEntry> {
        val since = now - WINDOW_MS
        val kept = files.asSequence()
            .filter { !it.isFolder }
            .filter { it.modified > 0L && it.modified >= since }
            .filter { it.sizeBytes > 0L }
            .filter { !isNoiseName(it.name) }
            .filter { !(hideCache && isCachePath(it.path)) }
            .filter { !(hideBackups && isBackupPath(it.path)) }
            .toList()
        // 备份副本不整类丢弃：正文还在时收起副本，正文没了就把它留下——
        // 那种情况下它往往是唯一还能打开的一份。
        val deduped = if (hideBackups) collapseBackups(kept) else kept
        return deduped.sortedByDescending { it.modified }.take(LIMIT)
    }

    /**
     * 两路合并：MediaStore 快路径结果 + 全盘遍历结果。
     *
     * 同一路径取更大的 mtime（索引里的是入库快照，可能比真实 mtime 旧），
     * 然后按时间倒序、按 path 去重。
     */
    fun merge(fast: List<FileEntry>, slow: List<FileEntry>, now: Long, hideBackups: Boolean, hideCache: Boolean): List<FileEntry> {
        if (fast.isEmpty() && slow.isEmpty()) return emptyList()
        val byPath = LinkedHashMap<String, FileEntry>(fast.size + slow.size)
        for (entry in fast + slow) {
            val existing = byPath[entry.path]
            byPath[entry.path] = if (existing == null || entry.modified > existing.modified) entry else existing
        }
        return filter(byPath.values.toList(), now, hideBackups, hideCache)
    }
}
