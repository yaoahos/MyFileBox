package com.yaoahos.myfilebox.android.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKinds
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FolderCard
import com.yaoahos.myfilebox.core.RecycleItem
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class LocalSnapshot(
    val files: List<FileEntry>,
    val photos: List<FileEntry>,
    val folders: List<FolderCard>,
    val counts: Map<FileKind, Int>,
    val usedGb: Double,
    val freeGb: Double,
    val totalGb: Double,
    val hasAccess: Boolean,
    val rootPath: String
)

/**
 * Real local storage access. On Android 11+ this needs MANAGE_EXTERNAL_STORAGE;
 * until it is granted we fall back to the app-specific external directory so
 * the UI still shows real files from the app's own storage.
 */
class LocalRepository(private val context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("myfilebox_prefs", Context.MODE_PRIVATE)

    fun hasAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun root(): File {
        return if (hasAccess()) {
            Environment.getExternalStorageDirectory()
        } else {
            app.getExternalFilesDir(null) ?: app.filesDir
        }
    }

    fun downloadsDir(): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return if (hasAccess() && (public.exists() || public.mkdirs())) public else File(root(), "Download").apply { mkdirs() }
    }

    fun currentDownloadFolder(): String {
        val stored = prefs.getString("real_download_folder", null)
        if (!stored.isNullOrBlank() && File(stored).exists()) return stored
        return downloadsDir().absolutePath
    }

    fun setDownloadFolder(path: String) {
        prefs.edit().putString("real_download_folder", path).apply()
    }

    private fun storage(): Triple<Double, Double, Double> {
        return runCatching {
            val stat = StatFs(root().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val used = (total - free).coerceAtLeast(0)
            Triple(used / GB, free / GB, total / GB)
        }.getOrElse { Triple(0.0, 0.0, 0.0) }
    }

    /**
     * 只读取根目录一层的快照。
     *
     * 全盘遍历（[snapshot]）在文件多的机器上要几秒，冷启动这段时间首页是空白的，
     * 用户会以为「没读到文件，得手动下拉刷新」。先用这一层数据把首页铺满，
     * 深扫描完成后再整体替换。
     */
    fun quickSnapshot(): LocalSnapshot {
        val root = root()
        val entries = list(root.absolutePath).filterNot { it.isFolder }
        val (used, free, total) = storage()
        return LocalSnapshot(
            files = entries,
            photos = emptyList(),
            folders = folderCards(root),
            counts = emptyMap(),
            usedGb = used,
            freeGb = free,
            totalGb = total,
            hasAccess = hasAccess(),
            rootPath = root.absolutePath
        )
    }

    fun snapshot(): LocalSnapshot {
        val root = root()
        val files = java.util.concurrent.ConcurrentHashMap<String, FileEntry>()
        // 两阶段并行扫描：
        // 阶段一单线程只走目录骨架（readdir 很便宜），收集每个目录的子项清单；
        // 阶段二把每个目录的「子项 → FileEntry」（stat 为主要开销）扔进固定线程池并行处理。
        // 相比「每个一级目录一个线程」，大子树不再独占线程，也避免一级目录很多时的线程风暴。
        val batches = mutableListOf<List<File>>()
        collectBatches(root, 0, batches)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            for (batch in batches) {
                pool.execute {
                    for (child in batch) {
                        if (child.length() >= 0) files[child.absolutePath] = child.toEntry()
                    }
                }
            }
        } finally {
            pool.shutdown()
            runCatching { pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) }
        }
        val all = files.values.toList()
        val photos = all.filter { it.kind == FileKind.IMAGE }.sortedByDescending { it.modified }
        val counts = FileKind.entries.associateWith { kind -> all.count { it.kind == kind } }
        val folders = folderCards(root)
        val (used, free, total) = storage()
        return LocalSnapshot(
            files = all,
            photos = photos,
            folders = folders,
            counts = counts,
            usedGb = used,
            freeGb = free,
            totalGb = total,
            hasAccess = hasAccess(),
            rootPath = root.absolutePath
        )
    }

    /** 阶段一：递归收集目录骨架与各目录的文件清单（只 readdir，不做 stat 聚合）。 */
    private fun collectBatches(dir: File, depth: Int, into: MutableList<List<File>>) {
        if (depth > 8) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        val filesHere = mutableListOf<File>()
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                // Android/data 与 Android/obb 既大又受系统限制，用户也看不到，扫了纯浪费。
                val p = child.absolutePath
                if (p.endsWith("/Android/data") || p.endsWith("/Android/obb") ||
                    child.name == "LOST.DIR" || child.name == "\$RECYCLE.BIN"
                ) continue
                collectBatches(child, depth + 1, into)
            } else if (child.isFile) {
                filesHere += child
            }
        }
        if (filesHere.isNotEmpty()) into += filesHere
    }

    private fun walk(dir: File, depth: Int, into: MutableMap<String, FileEntry>) {
        // Index the whole shared storage rather than a shallow sample. Hidden
        // folders (including our own recycle bin) are intentionally skipped.
        if (depth > 8) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                // Android/data 与 Android/obb 既大又受系统限制，用户也看不到，扫了纯浪费。
                val p = child.absolutePath
                if (p.endsWith("/Android/data") || p.endsWith("/Android/obb") ||
                    child.name == "LOST.DIR" || child.name == "\$RECYCLE.BIN"
                ) continue
                walk(child, depth + 1, into)
            } else if (child.isFile && child.length() >= 0) {
                into[child.absolutePath] = child.toEntry()
            }
        }
    }

    fun list(path: String): List<FileEntry> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return runCatching { dir.listFiles() }.getOrNull()
            ?.filter { !it.name.startsWith(".") }
            ?.map { it.toEntry() }
            ?.sortedWith(compareBy<FileEntry> { !it.isFolder }.thenByDescending { it.modified })
            .orEmpty()
    }

    fun category(kind: FileKind): List<FileEntry> {
        val roots = listOf(
            File(root(), Environment.DIRECTORY_DOWNLOADS),
            File(root(), Environment.DIRECTORY_DCIM),
            File(root(), Environment.DIRECTORY_PICTURES),
            File(root(), Environment.DIRECTORY_DOCUMENTS),
            File(root(), Environment.DIRECTORY_MUSIC),
            File(root(), Environment.DIRECTORY_MOVIES),
            File(root(), "WeiXin"),
            root()
        )
        val result = LinkedHashMap<String, FileEntry>()
        for (dir in roots.distinctBy { it.absolutePath }) {
            if (dir.exists()) walk(dir, 0, result)
        }
        return result.values.filter { it.kind == kind }.sortedByDescending { it.modified }
    }

    fun folderCards(root: File): List<FolderCard> {
        // Show what is actually on this device. Never invent preferred folders.
        return runCatching { root.listFiles() }.getOrNull()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.canRead() }
            ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
            ?.map { dir ->
                val count = runCatching {
                    dir.listFiles()?.count { !it.name.startsWith(".") }
                }.getOrNull() ?: 0
                FolderCard(
                    name = dir.name,
                    meta = "$count 项",
                    kind = FileKind.FOLDER,
                    path = dir.absolutePath,
                    date = formatStamp(dir.lastModified()),
                    count = count
                )
            }
            ?.toList()
            .orEmpty()
    }

    fun createFolder(parent: String, name: String): Boolean =
        runCatching { File(parent, name).mkdirs() }.getOrDefault(false)

    /** 在 parent 下新建空文件；重名时自动加序号，返回实际创建的文件。 */
    fun createFile(parent: String, name: String): File? {
        val clean = name.trim()
        if (clean.isBlank() || clean.contains('/') || clean.contains('\\')) return null
        val dir = File(parent)
        if (!dir.exists() || !dir.isDirectory) return null
        val target = uniqueTarget(dir, clean)
        return runCatching {
            if (target.createNewFile()) target else null
        }.getOrNull()
    }

    fun rename(entry: FileEntry, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isBlank() || clean.contains('/') || clean.contains('\\')) return false
        val source = File(entry.path)
        if (!source.exists()) return false
        val target = File(source.parentFile, clean)
        if (target.absolutePath == source.absolutePath) return true
        if (target.exists()) return false
        return runCatching { source.renameTo(target) }.getOrDefault(false)
    }

    /** 生成压缩包。format 只支持 "zip" / "7z"；baseName 不带扩展名。 */
    fun createArchive(
        entries: List<FileEntry>,
        destination: String,
        baseName: String,
        format: String = "zip"
    ): File? {
        if (entries.isEmpty()) return null
        val dir = File(destination)
        if (!dir.exists() || !dir.isDirectory) return null
        val ext = if (format.equals("7z", true)) "7z" else "zip"
        val plain = baseName.trim().removeSuffix(".zip").removeSuffix(".7z").ifBlank { "压缩包" }
        val target = uniqueTarget(dir, "$plain.$ext")
        return runCatching {
            if (ext == "7z") writeSevenZ(entries, target) else writeZip(entries, target)
            target
        }.getOrNull()
    }

    fun createZip(entries: List<FileEntry>, destination: String, baseName: String = "压缩包"): File? =
        createArchive(entries, destination, baseName, "zip")

    private fun writeZip(entries: List<FileEntry>, target: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            entries.forEach { entry ->
                val source = File(entry.path)
                if (source.exists()) addToZip(source, entry.name, zip)
            }
        }
    }

    private fun writeSevenZ(entries: List<FileEntry>, target: File) {
        val out = org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(target)
        try {
            entries.forEach { entry ->
                val source = File(entry.path)
                if (source.exists()) addToSevenZ(source, entry.name, out)
            }
        } finally {
            out.close()
        }
    }

    private fun addToSevenZ(file: File, path: String, out: org.apache.commons.compress.archivers.sevenz.SevenZOutputFile) {
        if (file.isDirectory) {
            val children = file.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
            children.forEach { addToSevenZ(it, "$path/${it.name}", out) }
        } else {
            val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
            entry.name = path
            out.putArchiveEntry(entry)
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
            out.closeArchiveEntry()
        }
    }

    private fun addToZip(file: File, path: String, zip: ZipOutputStream) {
        if (file.isDirectory) {
            val children = file.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
            if (children.isEmpty()) {
                zip.putNextEntry(ZipEntry("$path/"))
                zip.closeEntry()
            } else {
                children.forEach { addToZip(it, "$path/${it.name}", zip) }
            }
        } else {
            zip.putNextEntry(ZipEntry(path))
            FileInputStream(file).use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }

    fun delete(entry: FileEntry): Boolean =
        runCatching { File(entry.path).deleteRecursively() }.getOrDefault(false)

    fun copy(entry: FileEntry, destDir: String, move: Boolean): Boolean {
        val source = File(entry.path)
        if (!source.exists()) return false
        val target = uniqueTarget(File(destDir), source.name)
        return runCatching {
            if (source.isDirectory) source.copyRecursively(target, overwrite = false)
            else {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = false)
            }
            if (move) source.deleteRecursively()
            true
        }.getOrDefault(false)
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (target.exists()) {
            target = File(dir, if (ext.isBlank()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return target
    }

    private fun displayName(raw: String): String = when (raw) {
        Environment.DIRECTORY_DOWNLOADS -> "下载"
        Environment.DIRECTORY_DCIM -> "相机"
        Environment.DIRECTORY_PICTURES -> "图片"
        Environment.DIRECTORY_DOCUMENTS -> "文档"
        Environment.DIRECTORY_MUSIC -> "音乐"
        Environment.DIRECTORY_MOVIES -> "视频"
        else -> raw
    }

    private fun recycleDir(): File = File(root(), ".myfilebox_recycle").apply { mkdirs() }

    fun recycle(entries: List<FileEntry>): Int {
        val dir = recycleDir()
        var moved = 0
        entries.forEachIndexed { index, entry ->
            val source = File(entry.path)
            if (!source.exists()) return@forEachIndexed
            val isDir = source.isDirectory
            val size = if (isDir) 0L else source.length()
            val originalPath = source.absolutePath
            val id = "${System.currentTimeMillis()}_${index}_${source.name.take(40)}"
            val target = File(dir, id)
            val ok = runCatching {
                if (!source.renameTo(target)) {
                    if (isDir) source.copyRecursively(target, overwrite = true)
                    else source.copyTo(target, overwrite = true)
                    if (isDir) source.deleteRecursively() else source.delete()
                }
                true
            }.getOrDefault(false)
            if (ok) {
                runCatching {
                    File(dir, "$id.meta").writeText(
                        listOf(
                            originalPath,
                            entry.name,
                            System.currentTimeMillis().toString(),
                            isDir.toString(),
                            size.toString()
                        ).joinToString("\n"),
                        Charsets.UTF_8
                    )
                }
                moved++
            }
        }
        return moved
    }

    fun listRecycle(): List<RecycleItem> {
        val dir = recycleDir()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".meta") }
            ?.mapNotNull { meta ->
                runCatching {
                    val id = meta.name.removeSuffix(".meta")
                    val lines = meta.readLines(Charsets.UTF_8)
                    if (lines.size < 5) return@runCatching null
                    val trash = File(dir, id)
                    if (!trash.exists()) return@runCatching null
                    RecycleItem(
                        id = id,
                        name = lines[1],
                        originalPath = lines[0],
                        trashPath = trash.absolutePath,
                        deletedAt = lines[2].toLong(),
                        isFolder = lines[3].toBoolean(),
                        sizeBytes = lines[4].toLong(),
                        // 清单 14a：本地回收站文件夹显示子项数量。
                        childCount = if (lines[3].toBoolean() && trash.isDirectory) trash.listFiles()?.size ?: 0 else 0
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.deletedAt }
            .orEmpty()
    }

    fun restoreRecycle(item: RecycleItem): Boolean {
        val source = File(item.trashPath)
        if (!source.exists()) return false
        val target = File(item.originalPath)
        val ok = runCatching {
            target.parentFile?.mkdirs()
            if (target.exists()) {
                val renamed = File(target.parentFile, "${target.name} (恢复 1)")
                if (source.isDirectory) source.copyRecursively(renamed, overwrite = true)
                else source.copyTo(renamed, overwrite = true)
            } else {
                if (!source.renameTo(target)) {
                    if (source.isDirectory) source.copyRecursively(target, overwrite = true)
                    else source.copyTo(target, overwrite = true)
                }
            }
            true
        }.getOrDefault(false)
        if (ok) {
            runCatching {
                if (source.isDirectory) source.deleteRecursively() else source.delete()
                File(recycleDir(), "${item.id}.meta").delete()
            }
        }
        return ok
    }

    fun purgeRecycle(days: Int): Int {
        // days <= 0 means 永不: never delete anything.
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days.toLong() * 24L * 60L * 60L * 1000L
        var purged = 0
        listRecycle().filter { it.deletedAt < cutoff }.forEach { item ->
            runCatching {
                val file = File(item.trashPath)
                if (file.isDirectory) file.deleteRecursively() else file.delete()
                File(recycleDir(), "${item.id}.meta").delete()
                purged++
            }
        }
        return purged
    }

    fun emptyRecycle(): Int {
        val dir = recycleDir()
        // Count real recycle entries only; .meta sidecars must not be counted.
        val count = listRecycle().size
        dir.listFiles().orEmpty().forEach {
            runCatching { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        }
        return count
    }

    fun extractZip(sourcePath: String, destination: File): File {
        val root = destination.canonicalFile
        root.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(File(sourcePath)))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = safeExtractTarget(root, entry.name)
                    ?: throw SecurityException("压缩包包含非法路径")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return root
    }

    private fun extractSevenZ(sourcePath: String, destination: File): File {
        val root = destination.canonicalFile
        root.mkdirs()
        try {
            val seven = org.apache.commons.compress.archivers.sevenz.SevenZFile(File(sourcePath))
            try {
                var entry = seven.nextEntry
                while (entry != null) {
                    val target = safeExtractTarget(root, entry.name)
                        ?: throw SecurityException("压缩包包含非法路径")
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            seven.getInputStream(entry).use { it.copyTo(output) }
                        }
                    }
                    entry = seven.nextEntry
                }
            } finally {
                seven.close()
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw IllegalStateException("该 7z 文件已加密，暂不支持密码解压；请先解除密码或改用 zip 格式")
        } catch (e: java.io.IOException) {
            // 未支持编码（如 PPMd 等老变体）底层只抛英文 IOException，这里翻成可执行的中文指引。
            if (e.message?.contains("Unsupported compression method", ignoreCase = true) == true) {
                throw IllegalStateException("该 7z 文件使用了暂不支持的压缩编码；可先用 7-Zip 另存为 zip 后重试")
            }
            throw e
        }
        return root
    }

    fun extractArchive(sourcePath: String, destination: File): File {
        val name = File(sourcePath).name.lowercase(Locale.ROOT)
        if (name.endsWith(".zip")) return extractZip(sourcePath, destination)
        if (name.endsWith(".7z")) return extractSevenZ(sourcePath, destination)
        if (!(name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz"))) {
            throw IllegalArgumentException("仅支持 zip / 7z / tar / tar.gz")
        }
        val root = destination.canonicalFile
        root.mkdirs()
        val fileInput = BufferedInputStream(FileInputStream(File(sourcePath)))
        val input = if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            GzipCompressorInputStream(fileInput)
        } else {
            fileInput
        }
        TarArchiveInputStream(input).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val target = safeExtractTarget(root, entry.name)
                    ?: throw SecurityException("压缩包包含非法路径")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> tar.copyTo(output) }
                }
                entry = tar.nextEntry
            }
        }
        return root
    }

    private fun File.toEntry(): FileEntry {
        val isDir = isDirectory
        val kind = if (isDir) FileKind.FOLDER else FileKinds.of(name)
        val count = if (isDir) (runCatching { listFiles()?.count { !it.name.startsWith(".") } }.getOrNull() ?: 0) else 0
        return FileEntry(
            id = absolutePath,
            name = name,
            kind = kind,
            sizeLabel = if (isDir) "$count 项" else formatSize(length()),
            timeLabel = formatStamp(lastModified()),
            folder = parentFile?.name.orEmpty(),
            childCount = count,
            path = absolutePath,
            sizeBytes = if (isDir) 0 else length(),
            modified = lastModified(),
            mime = mimeOf(name)
        )
    }

    companion object {
        const val GB = 1024.0 * 1024.0 * 1024.0
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            if (kb < 1) return "$bytes B"
            val mb = kb / 1024.0
            if (mb < 1) return String.format(Locale.US, "%.1f KB", kb)
            val gb = mb / 1024.0
            if (gb < 1) return String.format(Locale.US, "%.1f MB", mb)
            return String.format(Locale.US, "%.2f GB", gb)
        }

        fun formatTime(ms: Long): String {
            if (ms <= 0) return ""
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = ms }
            val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            val yesterday = now.clone() as Calendar
            yesterday.add(Calendar.DAY_OF_YEAR, -1)
            val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
            return when {
                sameDay -> "今天 $hm"
                isYesterday -> "昨天 $hm"
                now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
                    SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()).format(Date(ms))
                else -> SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault()).format(Date(ms))
            }
        }

        /** 列表里显示的紧凑日期，固定 yyyy/MM/dd。 */
        fun formatDate(ms: Long): String {
            if (ms <= 0) return ""
            return SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(ms))
        }

        /**
         * 列表里的时间列：日期与时间一起给全（`yyyy/MM/dd HH:mm:ss`）。
         * 早期版本「当天只给 HH:mm:ss、更早只给日期」，同一天与跨天两种口径来回切，
         * 反而看不出「哪天几点」。列表统一成完整时间，详情页口径与之一致。
         */
        fun formatStamp(ms: Long): String {
            if (ms <= 0) return ""
            return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
        }

        /**
         * 宫格里的时间列：当天只给 `HH:mm:ss`，更早只给 `yyyy/MM/dd`。
         *
         * 宫格一行排 4 格，宽度放不下 `yyyy/MM/dd HH:mm:ss`；同一天的文件靠时间区分、
         * 跨天的靠日期区分，才既省地方又能一眼看懂。列表不受此限，一律给全。
         */
        fun formatGridStamp(ms: Long): String {
            if (ms <= 0) return ""
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = ms }
            val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            return if (sameDay) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
            else SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(ms))
        }

        /**
         * 已格式化成字符串的时间（`yyyy/MM/dd HH:mm:ss`）→ 宫格口径。
         *
         * FolderCard 只带显示字符串、没有时间戳，只能从字符串反推：日期是今天且带时间，
         * 就给时间部分；否则只留日期部分。不是日期形状的文本（如群晖共享卡的副标题）
         * 原样返回，别把「2 天前更新」按空格切成「2」。
         */
        fun formatGridText(text: String): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return ""
            val datePart = trimmed.substringBefore(" ").trim()
            if (!dateShape.matches(datePart)) return trimmed
            val timePart = trimmed.substringAfter(" ", "").trim()
            val today = formatDate(System.currentTimeMillis())
            return if (timePart.isNotEmpty() && datePart == today) timePart else datePart
        }

        private val dateShape = Regex("""\d{4}/\d{2}/\d{2}""")

        /** 详情弹窗用：日期与时间都给全（`yyyy/MM/dd HH:mm:ss`）。 */
        fun formatFull(ms: Long): String {
            if (ms <= 0) return ""
            return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
        }

        /** 分区标题用：本年度可省略年份（MM/dd），跨年才带年份。 */
        fun formatDateShort(ms: Long): String {
            if (ms <= 0) return ""
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = ms }
            val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
            return if (sameYear) SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(ms))
            else SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(ms))
        }

        /**
         * 解压目标路径校验（zip-slip 防护，纯规则可单测）：
         * 先 canonical 化消掉 ../ 与符号链接，再要求目标严格落在 root 目录内部。
         * 越界条目（如 ../evil、a/../../evil）返回 null，由调用方拒绝。
         */
        fun safeExtractTarget(root: File, entryName: String): File? {
            val rootPath = root.canonicalFile.path
            val target = File(root, entryName).canonicalFile
            return if (target.path.startsWith(rootPath + File.separator)) target else null
        }

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            val known = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "flac" -> "audio/flac"
                "pdf" -> "application/pdf"
                "apk" -> "application/vnd.android.package-archive"
                "zip" -> "application/zip"
                else -> null
            }
            if (known != null) return known
            // 以前这里直接回落 application/octet-stream，于是 .txt / .docx 在「打开方式」
            // 里全被归成一个「未知类型文件」，记住的默认应用也跟着串。改用系统扩展名表，
            // 认不出来的才真的算未知（JVM 单测里 MimeTypeMap 是桩，会安全地返回 null）。
            return runCatching {
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            }.getOrNull() ?: "application/octet-stream"
        }
    }
}

fun formatTb(value: Double): String = String.format(Locale.US, "%.2f TB", value)
fun formatGb(value: Double): String = String.format(Locale.US, "%.1f GB", value)
