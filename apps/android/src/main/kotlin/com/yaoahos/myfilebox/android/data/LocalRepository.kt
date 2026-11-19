package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.displayLeaf

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
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
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 解压时遇到同名文件的处理方式。
 *
 * 早期是直接 `outputStream()` 盖掉：包里的 `报告.docx` 会无声替换掉目标目录里的同名
 * 文件，用户既没被告知也没有机会换一种做法。现在由界面先问一次再按选择执行。
 */
enum class ExtractConflict {
    /** 用压缩包里的版本覆盖已有文件。 */
    OVERWRITE,

    /** 保留已有文件，跳过这一条。 */
    SKIP,

    /** 两份都留：`报告.docx` → `报告 (1).docx`。 */
    KEEP_BOTH
}

/**
 * 加密压缩包需要密码：解压层抛出后由界面弹密码框重试。
 *
 * 不复用底层的 PasswordRequiredException：那个类型在不同压缩格式里含义不一，
 * 界面只需要知道「问一次密码再重试」，而且密码错误时也要走同一个弹窗。
 */
class ArchivePasswordRequired(message: String = "该压缩包已加密，请输入密码") :
    IllegalStateException(message)

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
        // 单次分组统计：原来对同一列表按 17 个 kind 各 count 一遍（O(17×N)）。
        val grouped = all.groupingBy { it.kind }.eachCount()
        val counts = FileKind.entries.associateWith { grouped[it] ?: 0 }
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
        if (depth > MAX_SCAN_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        val filesHere = mutableListOf<File>()
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                if (skippedDir(child)) continue
                collectBatches(child, depth + 1, into)
            } else if (child.isFile) {
                filesHere += child
            }
        }
        if (filesHere.isNotEmpty()) into += filesHere
    }

    /**
     * 扫描时不进入的目录。
     *
     * 隐藏目录已在外层按 `.` 前缀跳过；这里只补两类：
     * ① Android/data 与 Android/obb 既大又受系统限制，用户也看不到，扫了纯浪费；
     * ② 系统残留目录。
     *
     * **备份目录不再在这里跳过**：早先按目录名（backup / 备份 / xxx 备份）整棵跳过，
     * 副作用是「最近」确实干净了，但搜索和文件分类也一起搜不到 Documents/Backup
     * 里的文件——用户在那儿放了自己的文档，却怎么都找不到。现在遍历层照常收录，
     * 「最近」的排除交给 [RecentPolicy.isBackupPath] 在展示层做，两边各管一件事。
     */
    private fun skippedDir(child: File): Boolean {
        val name = child.name
        if (name == "LOST.DIR" || name == "\$RECYCLE.BIN") return true
        val path = child.absolutePath
        return path.endsWith("/Android/data") || path.endsWith("/Android/obb")
    }

    private fun walk(dir: File, depth: Int, into: MutableMap<String, FileEntry>) {
        // Index the whole shared storage rather than a shallow sample. Hidden
        // folders (including our own recycle bin) are intentionally skipped.
        if (depth > MAX_SCAN_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                if (skippedDir(child)) continue
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

    /**
     * 「最近」快路径：直接查系统媒体库索引，不再等全盘遍历。
     *
     * 全盘遍历在文件多的机器上要几秒，而这个查询是索引命中，与文件总量基本无关，
     * 且天然跨卷（含 SD 卡）。索引会漏掉 `.nomedia` 目录与部分 ROM 未收录的文件，
     * 所以调用方要用遍历结果做一次合并兜底，见 RecentPolicy.merge。
     */
    fun recentFromMediaStore(sinceMs: Long, limit: Int = RecentPolicy.LIMIT): List<FileEntry> {
        if (!hasAccess()) return emptyList()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val selection = "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
        val args = arrayOf((sinceMs / 1000L).toString())
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val out = ArrayList<FileEntry>(minOf(limit, 256))
        runCatching {
            app.contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (pathIndex < 0) return@use
                while (cursor.moveToNext() && out.size < limit) {
                    val path = cursor.getString(pathIndex) ?: continue
                    // 索引行不代表磁盘上还有：刚删掉、换了存储卷都会留下陈旧条目。
                    val file = File(path)
                    if (!file.isFile) continue
                    out += file.toEntry()
                }
            }
        }
        return out
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

    /**
     * 生成压缩包。format 只支持 "zip" / "7z"；baseName 不带扩展名。
     * password 为空 = 不加密；非空时 7z 用原生加密，zip 用 zip4j 的 AES-256。
     */
    fun createArchive(
        entries: List<FileEntry>,
        destination: String,
        baseName: String,
        format: String = "zip",
        abort: (() -> Boolean)? = null,
        password: CharArray? = null
    ): File? {
        if (entries.isEmpty()) return null
        val dir = File(destination)
        if (!dir.exists() || !dir.isDirectory) return null
        val ext = if (format.equals("7z", true)) "7z" else "zip"
        val plain = baseName.trim().removeSuffix(".zip").removeSuffix(".7z").ifBlank { "压缩包" }
        val target = uniqueTarget(dir, "$plain.$ext")
        return runCatching {
            if (ext == "7z") writeSevenZ(entries, target, abort, password)
            else writeZip(entries, target, abort, password)
            target
        }.onFailure {
            // 取消或失败都把半成品删掉：留一个能打开但不完整的压缩包，
            // 比没有更糟——用户会以为压缩成功了。
            runCatching { if (target.exists()) target.delete() }
        }.getOrNull()
    }

    fun createZip(entries: List<FileEntry>, destination: String, baseName: String = "压缩包"): File? =
        createArchive(entries, destination, baseName, "zip")

    private fun writeZip(
        entries: List<FileEntry>,
        target: File,
        abort: (() -> Boolean)?,
        password: CharArray?
    ) {
        if (password == null) {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
                entries.forEach { entry ->
                    val source = File(entry.path)
                    if (source.exists()) addToZip(source, entry.name, zip, abort)
                }
            }
            return
        }
        // 加密 zip：JDK 的 ZipOutputStream 没有加密 API，走 zip4j 的 AES-256。
        // 文件名保留原有的相对路径与隐藏文件过滤，取消仍靠逐项查旗标。
        val zip = net.lingala.zip4j.ZipFile(target, password)
        // 与无密码分支一致：写入统一用 UTF-8，读取侧仍按 zipCharsetOf 探测 GBK。
        zip.charset = Charsets.UTF_8
        val parameters = net.lingala.zip4j.model.ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
            aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
            compressionMethod = net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE
        }
        entries.forEach { entry ->
            val collected = mutableListOf<Pair<File, String>>()
            collectZipSources(File(entry.path), entry.name, abort, collected)
            collected.forEach { (file, path) ->
                if (abort?.invoke() == true) throw TransferAbortedException()
                parameters.fileNameInZip = path
                if (file.isDirectory) zip.addFolder(file, parameters) else zip.addFile(file, parameters)
            }
        }
    }

    /** 递归收集待写入 zip 的文件，规则与 [addToZip] 完全一致。 */
    private fun collectZipSources(
        file: File,
        path: String,
        abort: (() -> Boolean)?,
        out: MutableList<Pair<File, String>>
    ) {
        if (abort?.invoke() == true) throw TransferAbortedException()
        if (file.isDirectory) {
            val children = file.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
            if (children.isEmpty()) out += file to "$path/"
            else children.forEach { collectZipSources(it, "$path/${it.name}", abort, out) }
        } else {
            out += file to path
        }
    }

    private fun writeSevenZ(
        entries: List<FileEntry>,
        target: File,
        abort: (() -> Boolean)?,
        password: CharArray?
    ) {
        val out = if (password == null) {
            org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(target)
        } else {
            org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(target, password)
        }
        try {
            entries.forEach { entry ->
                val source = File(entry.path)
                if (source.exists()) addToSevenZ(source, entry.name, out, abort)
            }
        } finally {
            out.close()
        }
    }

    private fun addToSevenZ(
        file: File,
        path: String,
        out: org.apache.commons.compress.archivers.sevenz.SevenZOutputFile,
        abort: (() -> Boolean)?
    ) {
        if (abort?.invoke() == true) throw TransferAbortedException()
        if (file.isDirectory) {
            val children = file.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
            children.forEach { addToSevenZ(it, "$path/${it.name}", out, abort) }
        } else {
            val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
            entry.name = path
            out.putArchiveEntry(entry)
            FileInputStream(file).use { input ->
                // SevenZOutputFile 不是 OutputStream，用不了 copyWithAbort，同样的循环抄一遍。
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (abort?.invoke() == true) throw TransferAbortedException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
            out.closeArchiveEntry()
        }
    }

    private fun addToZip(file: File, path: String, zip: ZipOutputStream, abort: (() -> Boolean)?) {
        if (abort?.invoke() == true) throw TransferAbortedException()
        if (file.isDirectory) {
            val children = file.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
            if (children.isEmpty()) {
                zip.putNextEntry(ZipEntry("$path/"))
                zip.closeEntry()
            } else {
                children.forEach { addToZip(it, "$path/${it.name}", zip, abort) }
            }
        } else {
            zip.putNextEntry(ZipEntry(path))
            FileInputStream(file).use { input -> copyWithAbort(input, zip, abort) }
            zip.closeEntry()
        }
    }

    fun delete(entry: FileEntry): Boolean =
        runCatching { File(entry.path).deleteRecursively() }.getOrDefault(false)

    /**
     * 复制 / 移动单个条目。
     *
     * [onProgress] 逐文件回调（copied 从 1 开始，total 为该目录下的文件数）。
     * 目录复制不能再用 `copyRecursively`：它中途不回调，粘贴一个几千文件的目录时
     * 界面在整段时间里是死的，用户以为没点动（只有结束才冒一条结果）。
     */
    fun copy(
        entry: FileEntry,
        destDir: String,
        move: Boolean,
        onProgress: ((copied: Int, total: Int) -> Unit)? = null
    ): Boolean {
        val source = File(entry.path)
        if (!source.exists()) return false
        val target = uniqueTarget(File(destDir), source.name)
        return runCatching {
            if (source.isDirectory) {
                if (!copyDirectory(source, target, onProgress)) return false
            } else {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = false)
                onProgress?.invoke(1, 1)
            }
            if (move) source.deleteRecursively()
            true
        }.getOrDefault(false)
    }

    /**
     * 目录复制：先建目录骨架，再逐文件复制并回报进度。
     *
     * 语义与 `copyRecursively(overwrite = false)` 保持一致：目标已存在同名文件时
     * 不覆盖、直接判失败（否则会把用户已有文件冲掉）。
     */
    private fun copyDirectory(
        source: File,
        target: File,
        onProgress: ((copied: Int, total: Int) -> Unit)?
    ): Boolean = copyDirectoryTree(source, target, onProgress)

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

    /**
     * 把条目移进 App 自带的回收站，返回成功数量。
     *
     * [onProgress] 每处理完一项回调一次（done 从 1 开始）。批量删除几十上百个文件时
     * 界面要能显示「正在删除 12/50 项」——否则用户只看到界面卡住，不知道还要等多久。
     */
    fun recycle(
        entries: List<FileEntry>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Int {
        val dir = recycleDir()
        var moved = 0
        val total = entries.size
        entries.forEachIndexed { index, entry ->
            val source = File(entry.path)
            if (!source.exists()) {
                onProgress?.invoke(index + 1, total)
                return@forEachIndexed
            }
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
            onProgress?.invoke(index + 1, total)
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

    /**
     * 解压 zip。
     *
     * 用 [ZipFile] 而不是 [ZipInputStream]：只有前者能指定条目名的字符集。
     * 字符集由 [zipCharsetOf] 探测——Windows 资源管理器、好压这类工具打的包，
     * 条目名是 GBK 且不带 UTF-8 标志位，硬按 UTF-8 解出来是一串替换字符。
     */
    private fun extractZip(
        sourcePath: String,
        destination: File,
        abort: (() -> Boolean)?,
        conflict: ExtractConflict,
        created: MutableList<File>,
        password: CharArray? = null
    ): File {
        val root = destination.canonicalFile
        root.mkdirs()
        if (password != null) {
            return extractEncryptedZip(sourcePath, root, abort, conflict, created, password)
        }
        val src = File(sourcePath)
        val zip = try {
            ZipFile(src, ZipFile.OPEN_READ, zipCharsetOf(src))
        } catch (e: java.util.zip.ZipException) {
            throw IllegalStateException("压缩包已损坏，或使用了不支持的加密方式", e)
        }
        zip.use {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (abort?.invoke() == true) throw TransferAbortedException()
                val entry = entries.nextElement()
                // 先判路径合法性（null = 越界，必须报错），再判冲突策略
                // （null = 按用户选择跳过，属于正常流程），两者不能混成一个 null。
                val base = safeExtractTarget(root, entry.name)
                    ?: throw SecurityException("压缩包包含非法路径")
                if (entry.isDirectory) {
                    base.mkdirs()
                    continue
                }
                // 目录不参与冲突策略：给它另起名字只会让子文件散到两个目录里。
                val target = conflictTarget(base, conflict) { f -> f.exists() } ?: continue
                if (!target.exists()) created += target
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> copyWithAbort(input, output, abort) }
                }
            }
        }
        return root
    }

    /**
     * 带密码的 zip 解压。用 zip4j 只为「取条目流」，路径校验、冲突策略、取消与
     * 新建文件记录都和 [extractZip] 共用同一套规则；字符集仍走 [zipCharsetOf]，
     * 保住国产压缩工具打出的 GBK 中文名。
     */
    private fun extractEncryptedZip(
        sourcePath: String,
        root: File,
        abort: (() -> Boolean)?,
        conflict: ExtractConflict,
        created: MutableList<File>,
        password: CharArray
    ): File {
        val src = File(sourcePath)
        val zip = net.lingala.zip4j.ZipFile(src, password)
        zip.charset = zipCharsetOf(src)
        try {
            zip.fileHeaders.forEach { entry ->
                if (abort?.invoke() == true) throw TransferAbortedException()
                val base = safeExtractTarget(root, entry.fileName)
                    ?: throw SecurityException("压缩包包含非法路径")
                if (entry.isDirectory) {
                    base.mkdirs()
                    return@forEach
                }
                val target = conflictTarget(base, conflict) { it.exists() } ?: return@forEach
                if (!target.exists()) created += target
                target.parentFile?.mkdirs()
                val input = try {
                    zip.getInputStream(entry)
                } catch (e: net.lingala.zip4j.exception.ZipException) {
                    throw if (passwordLooksWrong(e)) {
                        ArchivePasswordRequired("密码错误，请重新输入")
                    } else e
                }
                input.use { stream ->
                    target.outputStream().use { output -> copyWithAbort(stream, output, abort) }
                }
            }
        } catch (e: net.lingala.zip4j.exception.ZipException) {
            if (passwordLooksWrong(e)) throw ArchivePasswordRequired("密码错误，请重新输入")
            throw e
        }
        return root
    }

    /** zip4j 的密码错误文案按版本有出入，统一按关键字判定，避免误报成包损坏。 */
    private fun passwordLooksWrong(e: Throwable): Boolean =
        e.message?.contains("password", ignoreCase = true) == true

    private fun extractSevenZ(
        sourcePath: String,
        destination: File,
        abort: (() -> Boolean)?,
        conflict: ExtractConflict,
        created: MutableList<File>,
        password: CharArray? = null
    ): File {
        val root = destination.canonicalFile
        root.mkdirs()
        try {
            val seven = if (password == null) {
                org.apache.commons.compress.archivers.sevenz.SevenZFile(File(sourcePath))
            } else {
                org.apache.commons.compress.archivers.sevenz.SevenZFile.Builder()
                    .setFile(File(sourcePath))
                    .setPassword(password)
                    .get()
            }
            try {
                var entry = seven.nextEntry
                while (entry != null) {
                    if (abort?.invoke() == true) throw TransferAbortedException()
                    val base = safeExtractTarget(root, entry.name)
                        ?: throw SecurityException("压缩包包含非法路径")
                    if (entry.isDirectory) {
                        base.mkdirs()
                    } else {
                        val target = conflictTarget(base, conflict) { it.exists() }
                        if (target != null) {
                            if (!target.exists()) created += target
                            target.parentFile?.mkdirs()
                            target.outputStream().use { output ->
                                seven.getInputStream(entry).use { copyWithAbort(it, output, abort) }
                            }
                        }
                    }
                    entry = seven.nextEntry
                }
            } finally {
                seven.close()
            }
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw ArchivePasswordRequired()
        } catch (e: java.io.IOException) {
            val msg = e.message.orEmpty()
            // 密码错误时底层可能是带 password 的 IOException，也可能是 xz 解密失败抛的
            // CorruptedInputException（文案只有 "Corrupted input"），两种都要让用户重输。
            if (password != null && (
                msg.contains("password", ignoreCase = true) ||
                e is org.tukaani.xz.CorruptedInputException
            )) {
                throw ArchivePasswordRequired("密码错误，请重新输入")
            }
            // 未支持编码（如 PPMd 等老变体）底层只抛英文 IOException，这里翻成可执行的中文指引。
            if (msg.contains("Unsupported compression method", ignoreCase = true)) {
                throw IllegalStateException("该 7z 文件使用了暂不支持的压缩编码；可先用 7-Zip 另存为 zip 后重试")
            }
            throw e
        }
        return root
    }

    private fun extractTar(
        sourcePath: String,
        destination: File,
        gzip: Boolean,
        abort: (() -> Boolean)?,
        conflict: ExtractConflict,
        created: MutableList<File>
    ): File {
        val root = destination.canonicalFile
        root.mkdirs()
        // tar 头里没有编码标记，只能解一遍看名字坏没坏；坏了再按 GBK 重来。
        val encoding = if (tarLooksNonUtf8(sourcePath, gzip)) GBK.name() else Charsets.UTF_8.name()
        TarArchiveInputStream(openTarStream(sourcePath, gzip), encoding).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (abort?.invoke() == true) throw TransferAbortedException()
                val base = safeExtractTarget(root, entry.name)
                    ?: throw SecurityException("压缩包包含非法路径")
                if (entry.isDirectory) {
                    base.mkdirs()
                } else {
                    val target = conflictTarget(base, conflict) { it.exists() }
                    if (target != null) {
                        if (!target.exists()) created += target
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> copyWithAbort(tar, output, abort) }
                    }
                }
                entry = tar.nextEntry
            }
        }
        return root
    }

    private fun openTarStream(sourcePath: String, gzip: Boolean) =
        BufferedInputStream(FileInputStream(File(sourcePath))).let {
            if (gzip) GzipCompressorInputStream(it) else it
        }

    /** tar 的条目名是否不是 UTF-8：读一遍头，出现替换字符即判定。 */
    private fun tarLooksNonUtf8(sourcePath: String, gzip: Boolean): Boolean = runCatching {
        TarArchiveInputStream(openTarStream(sourcePath, gzip)).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (entry.name.contains('\uFFFD')) return@use true
                entry = tar.nextEntry
            }
            false
        }
    }.getOrDefault(false)

    /**
     * 解压到 [destination]。
     *
     * @param abort 每读一块前问一次「是否已取消」：解压是纯阻塞 IO，`job.cancel()`
     *   只能在下个挂起点生效，不传这个回调的话点了取消后台仍在写盘。
     * @param conflict 目标已存在同名文件时的处理方式。
     */
    fun extractArchive(
        sourcePath: String,
        destination: File,
        abort: (() -> Boolean)? = null,
        conflict: ExtractConflict = ExtractConflict.OVERWRITE,
        password: CharArray? = null,
        passwordVerified: Boolean = false
    ): File {
        val created = mutableListOf<File>()
        val name = File(sourcePath).name.lowercase(Locale.ROOT)
        // 跳过同名文件或 0 字节条目时，解压循环可能一次密文都不读；正式解压前
        // 先独立校验一次，保证错密码在任何冲突策略下都会触发重输提示。
        // 调用方已经校验过（[passwordVerified]）就不再重验——以前同一个密码
        // 要连开两遍全量条目，几万个小文件的包光这一步就要几秒。
        if (!passwordVerified && password != null && (name.endsWith(".zip") || name.endsWith(".7z")) &&
            !verifyArchivePassword(sourcePath, password)
        ) {
            throw ArchivePasswordRequired("密码错误，请重新输入")
        }
        try {
            if (name.endsWith(".zip")) return extractZip(sourcePath, destination, abort, conflict, created, password)
            if (name.endsWith(".7z")) return extractSevenZ(sourcePath, destination, abort, conflict, created, password)
            if (!(name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz"))) {
                throw IllegalArgumentException("仅支持 zip / 7z / tar / tar.gz")
            }
            val gzip = name.endsWith(".tar.gz") || name.endsWith(".tgz")
            return extractTar(sourcePath, destination, gzip, abort, conflict, created)
        } catch (e: Throwable) {
            // 取消或失败时把本次新建的文件删掉：否则解到一半点取消，目录里会留下
            // 几个大小正确、内容截断的"好文件"，用户下次根本分不出来。
            // 只删新建的；覆盖过的（原本就存在）保留——原内容已经没了，删掉连半份都不剩。
            created.forEach { runCatching { it.delete() } }
            throw e
        }
    }

    /**
     * 解压前扫一遍：目标目录里已经存在的同名条目（只报文件，目录会合并）。
     *
     * 单独做一次、而不是在解压循环里回调 UI：解压跑在 IO 线程，弹窗在主线程，
     * 中途来回切线程既容易卡也容易在用户没回应时把已写的半个文件留下。
     * 先把冲突一次问清，再按选定策略一把解完。
     */
    fun archiveConflictNames(
        sourcePath: String,
        destination: File,
        limit: Int = 20,
        password: CharArray? = null
    ): List<String> {
        val root = destination.canonicalFile
        if (!root.exists()) return emptyList()
        val hits = LinkedHashSet<String>()
        runCatching {
            when {
                File(sourcePath).name.lowercase(Locale.ROOT).endsWith(".zip") -> {
                    val src = File(sourcePath)
                    if (password == null) {
                        ZipFile(src, ZipFile.OPEN_READ, zipCharsetOf(src)).use { zip ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements() && hits.size < limit) {
                                val entry = entries.nextElement()
                                if (entry.isDirectory) continue
                                val target = safeExtractTarget(root, entry.name) ?: continue
                                if (target.exists()) hits += entry.name
                            }
                        }
                    } else {
                        // 加密包的名字在中央目录里不加密，但要按探测出的字符集读，
                        // 否则 GBK 中文名会变成替换字符，冲突一行也扫不出来。
                        val zip = net.lingala.zip4j.ZipFile(src, password)
                        zip.charset = zipCharsetOf(src)
                        zip.fileHeaders.forEach { entry ->
                            if (hits.size >= limit) return@forEach
                            if (entry.isDirectory) return@forEach
                            val target = safeExtractTarget(root, entry.fileName) ?: return@forEach
                            if (target.exists()) hits += entry.fileName
                        }
                    }
                }
                File(sourcePath).name.lowercase(Locale.ROOT).endsWith(".7z") -> {
                    val seven = if (password == null) {
                        org.apache.commons.compress.archivers.sevenz.SevenZFile(File(sourcePath))
                    } else {
                        org.apache.commons.compress.archivers.sevenz.SevenZFile.Builder()
                            .setFile(File(sourcePath))
                            .setPassword(password)
                            .get()
                    }
                    seven.use { seven ->
                        var entry = seven.nextEntry
                        while (entry != null && hits.size < limit) {
                            if (!entry.isDirectory) {
                                val target = safeExtractTarget(root, entry.name)
                                if (target != null && target.exists()) hits += entry.name
                            }
                            entry = seven.nextEntry
                        }
                    }
                }
                else -> {
                    val n = File(sourcePath).name.lowercase(Locale.ROOT)
                    val gzip = n.endsWith(".tar.gz") || n.endsWith(".tgz")
                    val encoding = if (tarLooksNonUtf8(sourcePath, gzip)) GBK.name() else Charsets.UTF_8.name()
                    TarArchiveInputStream(openTarStream(sourcePath, gzip), encoding).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null && hits.size < limit) {
                            if (!entry.isDirectory) {
                                val target = safeExtractTarget(root, entry.name)
                                if (target != null && target.exists()) hits += entry.name
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
        }
        return hits.toList()
    }

    /**
     * 真正校验口令能否解开条目内容。冲突扫描和正式解压前都要调用：
     * zip4j / 7z 在「列出条目名」阶段不读密文，若目标已存在且用户选了跳过，
     * 解压循环可能一次流都不开，错密码就不会暴露。只读一字节，不解码整项。
     */
    fun verifyArchivePassword(sourcePath: String, password: CharArray): Boolean =
        verifyArchivePasswordOf(sourcePath, password)

    /**
     * 压缩包是否带密码。只读文件头 / 中央目录，不读条目内容。
     *
     * 放在冲突扫描之前：加密包的头都不解密就没法列条目，先问密码再扫同名冲突，
     * 用户才不会在输完密码后又冒出第二个弹窗。
     */
    fun archiveNeedsPassword(sourcePath: String): Boolean = try {
        val file = File(sourcePath)
        when {
            file.name.endsWith(".zip", ignoreCase = true) ->
                net.lingala.zip4j.ZipFile(file).isEncrypted()
            file.name.endsWith(".7z", ignoreCase = true) -> {
                // 7z 的密码需求不在构造或读条目头时暴露：要试着读一个字节才会触发
                // PasswordRequiredException / xz 解密失败。探测另开一个流，不影响正式解压。
                org.apache.commons.compress.archivers.sevenz.SevenZFile(file).use { seven ->
                    var entry = seven.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.hasStream()) {
                            seven.read(ByteArray(1))
                            break
                        }
                        entry = seven.nextEntry
                    }
                }
                false
            }
            else -> false
        }
    } catch (e: org.apache.commons.compress.PasswordRequiredException) {
        true
    } catch (e: org.tukaani.xz.CorruptedInputException) {
        // 无密码读 7z 加密流时，xz 解密失败只会抛这个异常。
        true
    } catch (e: Throwable) {
        // 损坏或不支持的包由正式解压路径报错，不要误弹密码框。
        false
    }

    /**
     * 压缩包条目名的字符集。
     *
     * 先按 UTF-8 打开扫一遍：只要有条目名解出替换字符，就说明这个包不是 UTF-8，
     * 换 GBK 再来。不看通用标志位 bit 11——实测大量国产压缩工具压根不写这个位，
     * 而「能解出干净名字」这个判据对所有真实文件名都成立。
     *
     * 反过来的情况不需要担心：UTF-8 的包按 UTF-8 解不会有替换字符，不会误走 GBK。
     */
    private fun zipCharsetOf(src: File): java.nio.charset.Charset {
        val broken = runCatching {
            ZipFile(src, ZipFile.OPEN_READ, Charsets.UTF_8).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    if (entries.nextElement().name.contains('\uFFFD')) return@use true
                }
                false
            }
        }.getOrDefault(false)
        return if (broken) GBK else Charsets.UTF_8
    }

    /**
     * 文件所在位置的显示名。
     *
     * /storage/emulated/0 的末段就是「0」，直接取 parentFile.name 会让根目录下的文件
     * 在详情里显示「所在位置 0」。根目录按用户能读懂的叫法显示成「根目录」。
     */
    private fun folderLabelOf(file: File): String {
        val parent = file.parentFile ?: return ""
        return displayLeaf(parent.absolutePath, root().absolutePath)
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
            folder = folderLabelOf(this),
            childCount = count,
            path = absolutePath,
            sizeBytes = if (isDir) 0 else length(),
            modified = lastModified(),
            mime = mimeOf(name)
        )
    }

    companion object {
        const val GB = 1024.0 * 1024.0 * 1024.0

        /**
         * 目录树复制（纯文件操作，不依赖 Context，单测可直接调用）。
         *
         * 不能用 `copyRecursively`：它中途不回调，粘贴一个几千文件的目录时界面在
         * 整段时间里没有任何反馈。这里逐文件复制并回报进度，语义与
         * `copyRecursively(overwrite = false)` 一致——遇到已存在的同名文件不覆盖，
         * 直接返回 false（否则会把用户已有文件冲掉）。
         *
         * [onProgress] 的 total 是文件总数；空目录也算一次 (1, 1)，
         * 让调用方不会停在「0 项」上。
         */
        fun copyDirectoryTree(
            source: File,
            target: File,
            onProgress: ((copied: Int, total: Int) -> Unit)? = null
        ): Boolean {
            if (!source.isDirectory) return false
            val all = source.walkTopDown().toList()
            val files = all.filter { it.isFile }
            if (!target.isDirectory && !target.mkdirs()) return false
            all.filter { it.isDirectory && it != source }.forEach { dir ->
                val out = File(target, dir.relativeTo(source).path)
                if (!out.isDirectory) out.mkdirs()
            }
            if (files.isEmpty()) {
                onProgress?.invoke(1, 1)
                return true
            }
            var copied = 0
            for (file in files) {
                val out = File(target, file.relativeTo(source).path)
                out.parentFile?.mkdirs()
                if (out.exists()) return false
                file.copyTo(out, overwrite = false)
                copied++
                onProgress?.invoke(copied, files.size)
            }
            return true
        }

        /**
         * 遍历深度上限：6 层。
         *
         * 早期是 8 层，比业界常见（4–6）更深，多出来的两层基本落在第三方 App
         * 的目录树里（缓存、资源包、离线内容），对「找文件」没有帮助，只是把
         * 扫描时间和 stat 次数放大。
         */
        const val MAX_SCAN_DEPTH = 6

        /**
         * 纯校验入口：不依赖 Context，单测可直接调用。
         *
         * 只读**第一个有内容的条目**的一个字节即可判定密码对错：错密码在第一次解流时
         * 就会抛异常。以前要遍历全部条目、每个都开一次流——几百个小文件的包就是几百次
         * AES 初始化和 CRC 校验，这正是「输入密码后要等一下」的来源。
         * 全是空文件 / 空目录的极端包读不出内容，退回逐个开流，保证错密码仍能被发现。
         */
        fun verifyArchivePasswordOf(sourcePath: String, password: CharArray): Boolean {
            if (password.isEmpty()) return false
            val file = File(sourcePath)
            return try {
                when {
                    file.name.endsWith(".zip", ignoreCase = true) -> {
                        net.lingala.zip4j.ZipFile(file, password).use { zip ->
                            val entries = zip.fileHeaders.filter { !it.isDirectory }
                            val probe = entries.firstOrNull { it.uncompressedSize > 0 } ?: entries.firstOrNull()
                            if (probe != null) zip.getInputStream(probe).use { it.read() }
                        }
                        true
                    }
                    file.name.endsWith(".7z", ignoreCase = true) -> {
                        org.apache.commons.compress.archivers.sevenz.SevenZFile.Builder()
                            .setFile(file)
                            .setPassword(password)
                            .get().use { seven ->
                                var entry = seven.nextEntry
                                while (entry != null &&
                                    (entry.isDirectory || !entry.hasStream() || entry.size == 0L)
                                ) {
                                    entry = seven.nextEntry
                                }
                                if (entry != null) {
                                    seven.getInputStream(entry).use { it.read() }
                                } else {
                                    // 没有任何带内容的条目：退回逐个开流，别让错密码蒙过去。
                                    var probe = seven.nextEntry
                                    while (probe != null) {
                                        if (!probe.isDirectory) seven.getInputStream(probe).use { it.read() }
                                        probe = seven.nextEntry
                                    }
                                }
                            }
                        true
                    }
                    else -> true
                }
            } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                false
            } catch (e: net.lingala.zip4j.exception.ZipException) {
                if (passwordLooksWrongForVerify(e)) false else throw e
            } catch (e: org.tukaani.xz.CorruptedInputException) {
                false
            } catch (e: java.io.IOException) {
                if (passwordLooksWrongForVerify(e)) false else throw e
            }
        }

        /**
         * 加密包用错口令时，底层可能抛「password」，也可能只说校验失败：
         * AES 解密出的是乱码，zip4j / commons-compress 会在 CRC / HMAC / checksum
         * 处才报错。这些都算「口令不对」，交回上层提示重新输入。
         */
        private fun passwordLooksWrongForVerify(e: Throwable): Boolean {
            val m = e.message.orEmpty()
            return m.contains("password", ignoreCase = true) ||
                m.contains("checksum", ignoreCase = true) ||
                m.contains("crc", ignoreCase = true) ||
                m.contains("hmac", ignoreCase = true) ||
                m.contains("bad key", ignoreCase = true)
        }

        /** `bak` 只有作为独立词（bak / old.bak / bak_2026）才算备份标记，`bake`、`Bank` 不算。 */
        private val BAK_WORD = Regex("(^|[._\\-\\s])bak([._\\-\\s]|$)")

        /**
         * 目录名是否属于「备份」：`Backup`、`Backups`、`auto-backup`、`我的备份`、`old.bak`…
         * 命中后整棵子树不参与扫描（分类计数、首页、「最近」都不再收录），
         * 但目录本身仍能进入浏览——`list()` 不走这条判定。
         */
        fun isBackupDirName(name: String): Boolean {
            val n = name.lowercase(Locale.ROOT)
            if (n.isEmpty()) return false
            if (n.contains("backup")) return true
            if (n.contains("备份") || n.contains("備份")) return true
            return BAK_WORD.containsMatchIn(n)
        }

        /** 委托 core 的唯一口径；GB 不再单独用两位小数，与其他页面保持一致。 */
        fun formatSize(bytes: Long): String = com.yaoahos.myfilebox.core.FileSizeFormat.format(bytes)

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
            return formatted("yyyy/MM/dd", ms)
        }

        /**
         * 列表里的时间列：日期与时间一起给全（`yyyy/MM/dd HH:mm:ss`）。
         * 早期版本「当天只给 HH:mm:ss、更早只给日期」，同一天与跨天两种口径来回切，
         * 反而看不出「哪天几点」。列表统一成完整时间，详情页口径与之一致。
         */
        fun formatStamp(ms: Long): String {
            if (ms <= 0) return ""
            return formatted("yyyy/MM/dd HH:mm:ss", ms)
        }

        /**
         * 宫格里的时间列：当天只给 `HH:mm:ss`，更早只给 `yyyy/MM/dd`。
         *
         * 宫格一行排 4 格，宽度放不下 `yyyy/MM/dd HH:mm:ss`；同一天的文件靠时间区分、
         * 跨天的靠日期区分，才既省地方又能一眼看懂。列表不受此限，一律给全。
         */
        fun formatGridStamp(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
            if (ms <= 0) return ""
            val now = Calendar.getInstance().apply { timeInMillis = nowMs }
            val then = Calendar.getInstance().apply { timeInMillis = ms }
            val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            return if (sameDay) formatted("HH:mm:ss", ms) else formatted("yyyy/MM/dd", ms)
        }

        /**
         * 按线程缓存格式化器。
         *
         * SimpleDateFormat 每次构造都要读本地化数据，并不便宜；列表行的显示时间是在
         * 组合里现算的（回收站每行一次），滚动 / 反复重组时每行都新建一个纯属浪费。
         * 它本身不是线程安全的，所以用 ThreadLocal 各存一份，既不共享也不重复构造。
         */
        private val formatters = object : ThreadLocal<MutableMap<String, SimpleDateFormat>>() {
            override fun initialValue() = HashMap<String, SimpleDateFormat>(4)
        }

        private fun formatted(pattern: String, ms: Long): String {
            // SimpleDateFormat 构造时会把默认时区/语言固化下来，用户改了系统时区或语言后，
            // 旧实例仍按老口径格式化（跨零点时直接差一天）。缓存键带上这两项，
            // 设置一变就换新实例，旧的自然被淘汰。
            val zone = TimeZone.getDefault()
            val locale = Locale.getDefault()
            val key = pattern + '|' + zone.id + '|' + locale.toLanguageTag()
            val cache = formatters.get() ?: HashMap<String, SimpleDateFormat>(4).also { formatters.set(it) }
            val formatter = cache.getOrPut(key) {
                SimpleDateFormat(pattern, locale).apply { timeZone = zone }
            }
            return formatter.format(Date(ms))
        }

        /**
         * 已格式化成字符串的时间（`yyyy/MM/dd HH:mm:ss`）→ 宫格口径。
         *
         * FolderCard 只带显示字符串、没有时间戳，只能从字符串反推：日期是今天且带时间，
         * 就给时间部分；否则只留日期部分。不是日期形状的文本（如群晖共享卡的副标题）
         * 原样返回，别把「2 天前更新」按空格切成「2」。
         */
        fun formatGridText(text: String, nowMs: Long = System.currentTimeMillis()): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return ""
            val datePart = trimmed.substringBefore(" ").trim()
            if (!dateShape.matches(datePart)) return trimmed
            val timePart = trimmed.substringAfter(" ", "").trim()
            val today = formatDate(nowMs)
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

        /**
         * 按冲突策略算出真正要写的路径；返回 null 表示这一条跳过。
         *
         * [exists] 由调用方注入（生产环境是 `File.exists`），这样单测不用造真实文件。
         * KEEP_BOTH 的命名规格是 `报告.docx` → `报告 (1).docx`：
         * 点号在最前面时（`.gitignore`）不算扩展名，避免拼出 `(1).gitignore` 这种怪名。
         */
        fun conflictTarget(
            target: File,
            conflict: ExtractConflict,
            exists: (File) -> Boolean
        ): File? {
            if (conflict == ExtractConflict.OVERWRITE || !exists(target)) return target
            if (conflict == ExtractConflict.SKIP) return null
            val dir = target.parentFile
            val name = target.name
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var index = 1
            while (true) {
                val candidate = File(dir, "$base ($index)$ext")
                if (!exists(candidate)) return candidate
                index++
                // 一万个重名还没撞到空位基本只可能是病态目录，直接放弃而不是死循环。
                if (index > 9_999) return null
            }
        }

        /** 中文 Windows 的默认代码页，读 GBK 压缩包条目名用。 */
        internal val GBK: Charset = Charset.forName("GBK")

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
