package com.yaoahos.myfilebox.android.ui

import java.io.File

/**
 * 删除下载任务时要清理的路径。
 *
 * 文件夹下载的任务 target 是「下载目录」，仅按来源名拼根目录会漏掉嵌套子文件；
 * 同名冲突还会被 uniqueTarget 改成「xxx (1)」，只有子项记录的 target 才准确。
 */
internal data class DownloadCleanupPaths(
    /** 任务真正下载下来的文件；删除前要确认是文件，不能删掉目标目录本身。 */
    val files: List<File>,
    /** 各文件对应的 .part 临时文件，无论主文件是否存在都要清理。 */
    val parts: List<File>,
    /** 本次下载新建的目录，按由深到浅排列；删除任务时只回收其中的空目录。 */
    val dirs: List<File> = emptyList()
)

/**
 * 从「名字 (1).ext」还原成「名字.ext」；不是这个形式返回 null。
 *
 * 恢复下载时旧版本会把已存在的半截文件改名成「(1)」，原名那份就此失联——
 * 删任务只清得到「(1)」，用户看到的却是原名文件一直躺在原地。
 */
private fun baseNameOf(file: File): File? {
    val match = Regex("""^(.*) \(\d+\)$""").find(file.nameWithoutExtension) ?: return null
    val base = match.groupValues[1]
    if (base.isBlank()) return null
    val ext = file.extension
    return File(file.parentFile, if (ext.isEmpty()) base else "$base.$ext")
}

/** 旧版本把「端」的名字写进 to 的位置，这些串是标签不是路径。 */
private val REMOTE_END_LABELS = setOf("本地", "群晖", "网络", "群晖 NAS", "网络存储")

/**
 * 删除上传任务时可能空出来的远端目录，按由深到浅排列。
 *
 * 上传会顺带把缺失的目录建出来（FTP / SFTP 逐级 mkdir，SMB / WebDAV 建目录），
 * 删掉文件后这些目录就成了远端空壳——用户看到的是「记录删了、文件夹还在」。
 * 这里只按已登记的上传目标反推层级，由调用方逐个确认确实是空的再删：
 * 非空目录一律不碰（SMB 的 rmdir 是递归删，判空这一层绝不能省）。
 *
 * 根目录本身是用户自己选的目标位置，除非整条记录的目标就是本次上传的文件夹
 * （旧记录把标签写进了 to），否则绝不删。
 */
internal fun uploadDirCleanupPaths(task: TransferTask): List<String> {
    if (task.mode != TransferMode.UPLOAD) return emptyList()
    // 与删除文件用同一套目标：uploadedTargets 为空的老记录靠子项 target 还原，
    // 否则会出现「文件删掉了、它所在的空文件夹一直留着」。
    val targets = uploadCleanupTargets(task).filter { it.isNotBlank() }.map { it.trimEnd('/') }
    if (targets.isEmpty()) return emptyList()

    /**
     * 统一成绝对形式。
     *
     * 目录选择器回来的远端路径可能不带前导 `/`，老记录里 `to` / `target` 就存成了
     * 「Backup/Temp」这种相对形式。下面所有判断都靠 `startsWith` / 长度比较，
     * 不先补正的话 boundary 判空、`inside()` 恒不成立，整段目录回收直接失效——
     * 表现就是「文件删掉了，空文件夹一直留着」。
     */
    fun abs(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return trimmed.trimEnd('/')
        }
        // 老记录把端名当路径写进了 to（「群晖」「网络」这类标签），不能补成 /群晖。
        if (trimmed in REMOTE_END_LABELS) return null
        return normalizeRemotePath(trimmed)
    }

    val declared = abs(task.to)?.takeIf { it.isNotBlank() }
    // 记录里的产物目录：`to` 是标签 / 空串时，它才是本次上传落地的那个文件夹。
    val targetTop = abs(task.target)?.takeIf { it.isNotBlank() && it != "/" }
    // 目标路径的最深公共目录：前两者都推不出时用它当边界。
    val ancestor = commonParentOf(targets.map { it.substringBeforeLast('/') })
        ?.takeIf { it.isNotBlank() && it != "/" }

    fun under(top: String, path: String) = top == "/" || path == top || path.startsWith("$top/")

    val top: String
    val includeTop: Boolean
    when {
        // 用户选的落地目录（含远端根）：本次上传的目录都在它下面，它是边界、本身不删。
        declared != null && targets.all { under(declared, it) } -> {
            top = declared
            includeTop = false
        }
        // to 是端名 / 空串：退回记录里的产物目录，它也是本次上传建出来的。
        targetTop != null && targets.all { under(targetTop, it) } -> {
            top = targetTop
            includeTop = true
        }
        // 兜底用目标的公共父目录，同样算本次产物。
        // 必须落在 to 之下，否则「/photo 的记录混进 /photography」会把别人的目录当空壳。
        ancestor != null && (declared == null || ancestor.startsWith("$declared/")) -> {
            top = ancestor
            includeTop = true
        }
        declared != null -> {
            top = declared
            includeTop = false
        }
        else -> return emptyList()
    }

    val dirs = LinkedHashSet<String>()
    // 前缀必须带分隔符：/photo 不能把 /photography 也当成自己的子目录。
    fun inside(path: String) = if (top == "/") {
        path.length > 1 && path.startsWith("/")
    } else {
        path.length > top.length && path.startsWith("$top/")
    }
    fun climb(path: String) {
        var cur = path
        while (inside(cur)) {
            dirs += cur
            val parent = cur.substringBeforeLast('/')
            if (parent.length >= cur.length) break
            cur = parent
        }
    }
    targets.forEach { target -> climb(target.substringBeforeLast('/')) }
    // 边界是标签时 top 自身也是本次建出来的文件夹，一并纳入（由深到浅的自然顺序里最靠后）。
    if (includeTop) dirs += top
    // 空文件夹上传：没有任何子项，target 本身就是那个目录，也要能回收。
    // 单文件上传同样可能落到这里，但目录不存在时会判空失败而跳过（见 removeEmptyRemoteDirs）。
    val loneTargets = task.uploadedTargets.isEmpty() && task.children.isEmpty()
    if (loneTargets) dirs += targets

    return dirs
        .filter {
            (it.isNotBlank() && it != "/" && inside(it)) ||
                (includeTop && it == top) ||
                (loneTargets && it in targets)
        }
        .sortedByDescending { it.count { ch -> ch == '/' } }
}

/**
 * 详情里这次传输「用户实际选中的条目」（顶层条目）。
 *
 * **一个文件一条、一个文件夹一条**：文件夹只给文件夹自己，不展开夹内文件。
 * 新记录直接读 `sourceItems`；老记录（没有这个字段）用两条可靠线索兼容：
 * - 上传一个文件夹时 `sources` 记的是夹内每个文件 → 若它们同属一个目录、且目录名
 *   等于记录标题，就折回那个目录（否则详情会把文件夹里的文件一条条列出来）；
 * - 其余情况 `sources` 本来就是当时选中的条目。
 */
internal fun transferDisplayItemsOf(task: TransferTask): List<String> {
    val declared = task.sourceItems.filter { it.isNotBlank() }.map { normalizeRemotePath(it) }
    if (declared.isNotEmpty()) return declared.distinct()
    val sources = task.sources.filter { it.isNotBlank() }.map { normalizeRemotePath(it) }
    if (sources.size > 1) {
        // 老记录（含没写 folderTask 的）：「同属一个目录、且目录名就是记录标题」说明
        // 这次传的是那个文件夹本身，sources 只是夹内文件。多选几个文件时标题是第一个
        // 文件的名字，不会等于它们的父目录名，所以不会被误折。
        val parents = sources.map { it.trimEnd('/').substringBeforeLast('/').trimEnd('/') }.distinct()
        val only = parents.singleOrNull()
        if (only != null && only.substringAfterLast('/') == task.title.trim()) return listOf(only)
    }
    return sources.distinct()
}

/** 目录 + 名字拼成路径：不改写 http(s) URL，也不动本地绝对路径。 */
private fun joinDisplayPath(dir: String, name: String): String {
    val base = dir.trim().trimEnd('/')
    val leaf = name.trim().trimEnd('/')
    if (leaf.isBlank()) return base.ifBlank { "/" }
    return if (base.isBlank() || base == "/") "/$leaf" else "$base/$leaf"
}

/** 多条路径的最深公共目录；按分隔符比较，/photo 不会吞掉 /photography。 */
private fun commonParentOf(paths: List<String>): String? {
    val clean = paths.map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }.distinct()
    if (clean.isEmpty()) return null
    // WebDAV 的路径是绝对 URL，上溯不能越过 host 根。
    fun floorOf(path: String): Int {
        val start = when {
            path.startsWith("https://", true) -> 8
            path.startsWith("http://", true) -> 7
            else -> 1
        }
        val slash = path.indexOf('/', start)
        return if (slash < 0) path.length else slash
    }
    var prefix = clean.first()
    clean.drop(1).forEach { path ->
        while (prefix.isNotBlank() && prefix != "/" && path != prefix && !path.startsWith("$prefix/")) {
            val cut = prefix.lastIndexOf('/')
            if (cut <= 0 || cut < floorOf(prefix)) {
                prefix = "/"
                break
            }
            prefix = prefix.substring(0, cut)
        }
    }
    return prefix.ifBlank { "/" }
}

/**
 * 详情里「来源」显示的路径。
 *
 * 每条记录只对应一个条目（一次传输按顶层条目拆成多条记录），所以正常只有一条：
 * 选中的是文件夹就给文件夹本身，是文件就给文件。
 * 老记录里一次传输可能带多个条目，仍逐条列出，不做折叠。
 */
internal fun transferSourcePathsOf(task: TransferTask): List<String> = transferDisplayItemsOf(task)

/** 单条来源路径。 */
internal fun transferSourcePathOf(task: TransferTask): String =
    transferSourcePathsOf(task).firstOrNull().orEmpty()

/**
 * 详情里「目标」显示的路径。
 *
 * 单条目 = 产物本身（[transferTargetPathOf]）。老记录的多条目仍逐条给出落地路径。
 */
internal fun transferTargetPathsOf(task: TransferTask): List<String> {
    val items = transferSourcePathsOf(task)
    if (items.size <= 1) return listOfNotNull(transferTargetPathOf(task).takeIf { it.isNotBlank() })
    val declared = task.to.trim()
    if (declared.isBlank()) return emptyList()
    return items.map { item -> joinDisplayPath(declared, item.trimEnd('/').substringAfterLast('/')) }
        .distinct()
}

/**
 * 这条记录是文件夹（点开进目录）还是文件（点开打开文件）。
 *
 * 新记录直接看 `folderTask`（传输发起时就写好了，最准）。老记录按线索回退：
 * 有子项明细 / 新建目录，以及「上传记录里 target 已是夹内文件」（基名和标题不同）。
 */
internal fun transferIsFolderTask(task: TransferTask): Boolean {
    if (task.folderTask || task.children.isNotEmpty() || task.createdDirs.isNotEmpty()) return true
    if (transferSourcePathsOf(task).size > 1) return true
    if (task.mode == TransferMode.UPLOAD) {
        val name = task.target.trim().trimEnd('/').substringAfterLast('/')
        if (name.isNotBlank() && name != task.title.trim()) return true
    }
    return false
}

/**
 * 产物路径：既是详情里的「目标」，也是「点击记录要打开的位置」，只用这一处规则。
 *
 * - 文件：记录里的 `target`（真实落盘 / 落远端路径，含同名冲突改名后的名字）；
 * - 文件夹：目标目录（`to`）+ 文件夹名（用户选中的那个条目名）。
 *
 * 不做「子项公共父目录」之类的反推：同名文件夹、夹内文件都在某个子目录时，
 * 反推会落到更深一层甚至另一条记录的目录上。
 */
internal fun transferTargetPathOf(task: TransferTask): String {
    val declared = task.to.trim()
    val target = task.target.trim()
    val fallback = target.ifBlank { declared }
    if (!transferIsFolderTask(task)) return fallback
    val name = transferSourcePathsOf(task).firstOrNull()
        ?.trimEnd('/')?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: task.title.trim().trimEnd('/').substringAfterLast('/')
    if (declared.isBlank() || name.isBlank()) return fallback
    val product = joinDisplayPath(declared, name)
    return if (task.targetCtx == FileContext.SYNO.name || task.targetCtx == FileContext.NET.name) {
        normalizeRemotePath(product)
    } else {
        product
    }
}

/**
 * 收集一次下载任务产生的全部本地文件与 .part。
 *
 * - 优先用子项 target（精确到嵌套路径与「(1)」重命名）；
 * - 再按子项相对路径从下载目录推导，覆盖传到一半、还没登记 target 的文件；
 * - 老记录没有 children 时退回来源文件名；
 * - 同名同大小被跳过（preExisting）的文件不是本次下载产物，保留；它的残留 .part 仍要清。
 */
internal fun downloadCleanupPaths(task: TransferTask): DownloadCleanupPaths {
    if (task.mode != TransferMode.DOWNLOAD) return DownloadCleanupPaths(emptyList(), emptyList())
    val root = task.target.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }
    val files = LinkedHashSet<File>()
    val parts = LinkedHashSet<File>()

    fun add(path: File, keepFile: Boolean = false) {
        val abs = path.absoluteFile
        if (abs.name.isBlank()) return
        if (!keepFile) files += abs
        parts += File(abs.parentFile, "${abs.name}.part").absoluteFile
    }

    /**
     * 「(1)」这类改名的原名若存在且**大小与本条记录不符**，说明它才是本任务留下的
     * 半截，一并清掉。大小相符时不碰：那可能是用户自己的同名文件（下载时正是为了
     * 不覆盖它才另存「(1)」）。
     */
    fun addStaleBase(path: File, expectedBytes: Long) {
        if (expectedBytes <= 0L) return
        val base = baseNameOf(path) ?: return
        val abs = base.absoluteFile
        if (abs.isFile && abs.length() != expectedBytes) add(abs)
    }

    task.children.forEach { child ->
        if (child.target.isNotBlank()) {
            // 有 target 时它是权威路径：同名冲突改名为「(1)」时，相对路径指向的是
            // 触发改名的那个原有文件，绝不能再按相对路径删一遍。
            add(File(child.target), child.preExisting)
            // conflictOriginal 非空 = 原名是下载前就存在的用户文件，另存的 (n) 才是本次产物；
            // 老记录没有这个字段时才按大小猜旧半截。
            if (!child.preExisting && child.conflictOriginal == null) addStaleBase(File(child.target), child.sizeBytes)
        } else if (root != null && child.name.isNotBlank()) {
            // 传到一半、还没登记 target 的文件：按相对路径推导也要清掉。
            add(File(root, child.name), child.preExisting)
        }
    }
    if (task.children.isEmpty() && root != null) {
        if (task.targetPreExisting) {
            // 同名同大小被跳过：原文件一直属于用户，只清可能存在的旧 .part。
            parts += File(root.parentFile, "${root.name}.part").absoluteFile
        } else {
            add(root)
            // 单文件另存为「(n)」时 task.target 是 (n) 那份，原名是用户自己的文件，
            // 绝不能按名字反查回去删；老记录没有标记时才走下面的旧半截推断。
            if (task.conflictOriginal == null) {
                val dir = root.parentFile ?: root
                task.sources.forEach { source ->
                    val name = source.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
                    if (name.isBlank()) return@forEach
                    val direct = File(dir, name)
                    if (direct.isFile && task.totalBytes > 0L && direct.length() < task.totalBytes) {
                        add(direct)
                    } else if (root.isDirectory) {
                        add(File(root, name))
                    }
                }
            }
        }
    }
    // 空目录：只取本次任务新建、且确实在目标目录之下的层级，由深到浅排列。
    // createdDirs 记录的是相对「下载目录」(task.to) 的路径，不是 task.target：
    // 文件夹里只有 1 个文件时，target 会被改成那个文件本身的路径，拿它当根会拼出错位
    // 的目录，空文件夹就一直清不掉。to 为空的老记录退回用 target（当年它就是下载目录）。
    // createdDirs 来自持久化 JSON，必须挡住绝对路径与 .. 转义，再做规范化包含检查。
    val dirsRoot = task.to.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile } ?: root
    val created = if (dirsRoot != null) {
        val rootPrefix = dirsRoot.canonicalPath + File.separator
        task.createdDirs
            .filter { rel ->
                rel.isNotBlank() && !rel.startsWith('/') && !rel.startsWith('\\') &&
                    rel.split('/', '\\').none { it == ".." }
            }
            .map { File(dirsRoot, it).canonicalFile }
            .filter { it.path.startsWith(rootPrefix) }
            .distinct()
    } else emptyList()
    // createdDirs 缺失时（老记录、恢复过的任务）空文件夹会一直留着：用产物的顶层文件夹兜底。
    // 老记录的标题可能是「夹内第一个文件的名字」，按规则算出的目录并不存在，
    // 这时退回子项的父目录。只加进「候选」，调用方仍会确认是空目录再删。
    val topProduct = if (transferIsFolderTask(task)) {
        val byRule = transferTargetPathOf(task).takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }
        val fromChildren = task.children.mapNotNull { child ->
            child.target.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile.parentFile }
        }.distinct().singleOrNull()
        byRule?.takeIf { it.isDirectory } ?: fromChildren
    } else null
    val rootPath = dirsRoot?.path?.trimEnd('/')
    val extraTop = if (topProduct != null && topProduct.isDirectory &&
        topProduct.path.trimEnd('/') != rootPath
    ) listOf(topProduct) else emptyList()
    val dirs = (created + extraTop)
        .distinctBy { it.path }
        .sortedByDescending { it.path.length }
    return DownloadCleanupPaths(files.toList(), parts.toList(), dirs)
}
