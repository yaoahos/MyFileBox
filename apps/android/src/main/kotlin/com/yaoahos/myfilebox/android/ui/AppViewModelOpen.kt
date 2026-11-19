package com.yaoahos.myfilebox.android.ui
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yaoahos.myfilebox.android.BuildConfig
import com.yaoahos.myfilebox.android.TransferCancellation
import com.yaoahos.myfilebox.android.TransferNotifications
import com.yaoahos.myfilebox.android.TransferService
import com.yaoahos.myfilebox.android.data.BackupCrypto
import com.yaoahos.myfilebox.android.data.BackupCryptoException
import com.yaoahos.myfilebox.android.data.BackupError
import com.yaoahos.myfilebox.android.data.CloudConfigFile
import com.yaoahos.myfilebox.android.data.ConfigBackup
import com.yaoahos.myfilebox.android.data.ConfigCloudSync
import com.yaoahos.myfilebox.android.data.ConfigSyncScheduler
import com.yaoahos.myfilebox.android.data.ConfigSyncStore
import com.yaoahos.myfilebox.android.data.DeviceProfile
import com.yaoahos.myfilebox.android.data.PREFS_NAME
import com.yaoahos.myfilebox.android.data.SyncInterval
import com.yaoahos.myfilebox.android.data.SyncSettings
import com.yaoahos.myfilebox.android.data.DsmRepository
import com.yaoahos.myfilebox.android.data.AppIconSwitcher
import com.yaoahos.myfilebox.android.data.nameMatchesFilter
import com.yaoahos.myfilebox.android.data.itemMatchesFilter
import com.yaoahos.myfilebox.android.data.filterVisible
import com.yaoahos.myfilebox.android.data.hostLooksPrivate
import com.yaoahos.myfilebox.android.data.needsChannelRepair
import com.yaoahos.myfilebox.android.data.LocalRepository
import com.yaoahos.myfilebox.android.data.ExtractConflict
import com.yaoahos.myfilebox.android.data.ArchivePasswordRequired
import com.yaoahos.myfilebox.android.data.TransferAbortedException
import com.yaoahos.myfilebox.android.data.RecentPolicy
import com.yaoahos.myfilebox.android.data.NetworkRepository
import com.yaoahos.myfilebox.android.data.SecretVault
import com.yaoahos.myfilebox.android.data.SftpRepository
import com.yaoahos.myfilebox.android.data.StreamProxy
import com.yaoahos.myfilebox.android.data.SynoRecycleIndex
import com.yaoahos.myfilebox.android.data.ParsedServer
import com.yaoahos.myfilebox.android.data.QuickConnectEndpoint
import com.yaoahos.myfilebox.android.data.friendlyNetError
import com.yaoahos.myfilebox.android.data.DeviceNet
import com.yaoahos.myfilebox.android.data.NetStatus
import com.yaoahos.myfilebox.android.data.offlineNetworkMessage
import com.yaoahos.myfilebox.android.data.networkStatusNotice
import com.yaoahos.myfilebox.core.AppPrefs
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FileKinds
import com.yaoahos.myfilebox.core.FolderCard
import com.yaoahos.myfilebox.core.NetworkConnection
import com.yaoahos.myfilebox.core.RecycleItem
import com.yaoahos.myfilebox.core.Session
import com.yaoahos.myfilebox.core.ShareFolder
import com.yaoahos.myfilebox.core.TabKey
import com.yaoahos.myfilebox.core.ThemeMode
import com.yaoahos.myfilebox.core.ThemePrefs
import com.yaoahos.myfilebox.core.Volume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID


    // ---------------------------------------------------------------- file sheet / open

internal fun AppViewModel.openFile(file: FileEntry) { state = state.copy(fileSheet = file) }


    // ---- 查看详情 / 安装权限 ----

internal fun AppViewModel.showDetail(file: FileEntry) {
        state = state.copy(detailFile = file, fileSheet = null)
    }


internal fun AppViewModel.closeDetail() { state = state.copy(detailFile = null) }


internal fun AppViewModel.dismissInstallPrompt() { state = state.copy(installFile = null) }


internal fun AppViewModel.canInstallPackages(): Boolean {
        // 恢复 1.0.0 的真实判断。1.0.1 曾删掉 REQUEST_INSTALL_PACKAGES 声明，
        // 导致这里调用 canRequestPackageInstalls() 抛 SecurityException（点 APK 闪退），
        // 于是被改成恒 true——结果系统安装器因缺声明直接拒装，提示「没有安装权限」
        // 并把用户踢去设置页自己找入口。权限声明已恢复，正常判断即可：
        // 已授权 → 直接装；未授权 → requestOpen 走应用内引导，一键直达设置页并自动续装。
        val app = getApplication<Application>()
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            app.packageManager.canRequestPackageInstalls()
    }


    /** 系统设置里为本应用打开「安装未知应用」；装完回来点 APK 就能直接安装。 */
internal fun AppViewModel.requestInstallPermission() {
        val app = getApplication<Application>()
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { app.startActivity(intent) }.isSuccess
        if (!ok) {
            // 部分 ROM 没有直达页，退回应用详情页。
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        // 保留 installFile：用户从系统页返回后由 consumePendingInstall() 接着装。
    }


    /** 用户在系统页授权后回到应用，直接把他们刚点的那个 APK 装上。 */
internal fun AppViewModel.consumePendingInstall() {
        val file = state.installFile ?: return
        state = state.copy(installFile = null)
        if (canInstallPackages()) requestOpen(file)
    }


internal fun AppViewModel.closeFileSheet() { state = state.copy(fileSheet = null) }


    /**
     * 预览 / 打开远程文件时的来源推断。搜索结果、首页文件没有 browser，
     * 旧实现只认 state.browser?.ctx，导致这些入口的远程文件一律「无法预览」。
     */
internal fun AppViewModel.sourceContext(file: FileEntry): Pair<FileContext, String?> {
        state.browser?.let { return it.ctx to it.netId }
        val ctx = state.search?.ctx ?: contextOf(state.tab)
        val netId = state.connections.firstOrNull { conn ->
            val root = network.rootPath(conn)
            file.path.startsWith(root) || (conn.host.isNotBlank() && file.path.contains(conn.host))
        }?.id
        return ctx to netId
    }


internal fun AppViewModel.openPreview(file: FileEntry) {
        if (file.isFolder) {
            state = state.copy(fileSheet = null, message = "文件夹不支持预览")
            return
        }
        val textLike = file.kind != FileKind.VIDEO && file.kind != FileKind.AUDIO &&
            file.name.substringAfterLast('.', "").lowercase() in
            setOf("txt", "md", "json", "xml", "csv", "log")
        if (!textLike && file.kind != FileKind.AUDIO && file.kind != FileKind.VIDEO) {
            requestOpen(file)
            return
        }
        // 视频交给外部播放器在线播放：不落地、不受体积限制（见 openRemoteFile）。
        if (file.kind == FileKind.VIDEO) {
            requestOpen(file)
            return
        }
        val (ctx, netId) = sourceContext(file)
        val onDisk = file.path.startsWith("/") && File(file.path).exists()
        if (!onDisk) {
            val limit = if (file.kind == FileKind.AUDIO) PREVIEW_AUDIO_LIMIT else PREVIEW_TEXT_LIMIT
            if (file.sizeBytes > limit) {
                state = state.copy(
                    fileSheet = null,
                    preview = null,
                    message = "「${file.name}」约 ${LocalRepository.formatSize(file.sizeBytes)}，" +
                        "超过在线预览上限 ${LocalRepository.formatSize(limit)}，请下载后用其它应用查看"
                )
                return
            }
        }
        state = state.copy(fileSheet = null, preview = file)
        if (!onDisk) preparePreview(file, ctx, netId)
    }


internal fun AppViewModel.renameFile(file: FileEntry, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        state = state.copy(loading = true)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { renameEntry(file, trimmed) }
            state = state.copy(
                loading = false,
                fileSheet = null,
                // 改名完成后必须彻底退出选择态：列表、首页（长按多选）与回收站各有一套
                // 选择状态，只清一套会留下「已选择 1 项」的假选择页。
                selected = if (ok) emptySet() else state.selected,
                selectionActive = if (ok) false else state.selectionActive,
                homeSelected = if (ok) emptySet() else state.homeSelected,
                homeSelecting = if (ok) false else state.homeSelecting,
                recycleSelected = if (ok) emptySet() else state.recycleSelected,
                recycleSelecting = if (ok) false else state.recycleSelecting,
                message = if (ok) "已重命名为「$trimmed」" else "重命名失败，名称可能已存在或无权限"
            )
            if (ok) {
                // 本机文件先就地改掉缓存里的名字：首页 / 列表立刻显示新名字，
                // 不用等全盘扫描（文件多的设备要十几秒）。
                if (file.path.startsWith("/")) applyLocalRename(file, trimmed)
                val ctx = state.browser?.ctx ?: FileContext.LOCAL
                if (ctx == FileContext.SYNO) {
                    refreshSyno()
                } else {
                    refreshLocal()
                }
                reloadBrowserItems()
            }
        }
    }


    /**
     * 本机改名后的就地修正：只改缓存里的 name / path / id，不触发任何扫描。
     * 首页文件夹卡片、根目录文件、当前浏览页、分类列表一起更新。
     */
internal fun AppViewModel.applyLocalRename(file: FileEntry, newName: String) {
        val oldPath = file.path
        val newPath = File(oldPath).parentFile?.let { File(it, newName).absolutePath } ?: newName
        fun FileEntry.renamed(): FileEntry =
            if (path == oldPath) copy(id = newPath, name = newName, path = newPath) else this
        val folders = state.localFolders.map { card ->
            if (card.path.trimEnd('/') == oldPath.trimEnd('/')) card.copy(name = newName, path = newPath) else card
        }
        state = state.copy(
            localFolders = folders,
            localFiles = state.localFiles.map { it.renamed() },
            photos = state.photos.map { it.renamed() },
            browser = state.browser?.let { b ->
                b.copy(items = b.items.map { it.renamed() })
            },
            browserStack = state.browserStack.map { b -> b.copy(items = b.items.map { it.renamed() }) }
        )
    }


internal suspend fun AppViewModel.renameEntry(file: FileEntry, newName: String): Boolean {
        if (file.path.startsWith("/") && File(file.path).exists()) {
            return local.rename(file, newName)
        }
        val (ctx, netId) = sourceContext(file)
        return when (ctx) {
            FileContext.SYNO -> state.session?.let { dsm.rename(it, file.path, newName) } ?: false
            FileContext.NET -> {
                val connection = state.connections.firstOrNull { it.id == netId }
                connection?.let { network.rename(it, file.path, newName) } ?: false
            }
            FileContext.LOCAL -> local.rename(file, newName)
        }
    }


internal fun AppViewModel.compressFiles(
    files: List<FileEntry>,
    baseName: String,
    format: String = "zip",
    password: String = ""
) {
        if (files.isEmpty()) return
        if (files.any { !it.path.startsWith("/") }) {
            state = state.copy(fileSheet = null, message = "仅支持本机文件压缩")
            return
        }
        val targetDir = File(files.first().path).parentFile
        state = state.copy(fileSheet = null, localTaskLabel = "正在压缩 ${files.size} 项…", localTaskRunning = true)
        localTaskJob = viewModelScope.launch {
            val archive = withContext(Dispatchers.IO) {
                val ctx = currentCoroutineContext()
                if (targetDir == null) null else local.createArchive(
                    files,
                    targetDir.absolutePath,
                    baseName,
                    format,
                    // 压缩同样是整段阻塞 IO，取消要靠读一块查一次旗标才停得下来。
                    { !ctx.isActive },
                    password.ifEmpty { null }?.toCharArray()
                )
            }
            if (archive != null) {
                applyLocalDelta(
                    addedFiles = listOf(
                        FileEntry(
                            id = archive.absolutePath,
                            name = archive.name,
                            kind = FileKinds.of(archive.name),
                            sizeLabel = LocalRepository.formatSize(archive.length()),
                            timeLabel = LocalRepository.formatStamp(archive.lastModified()),
                            folder = localFolderLabel(archive.absolutePath),
                            path = archive.absolutePath,
                            sizeBytes = archive.length(),
                            modified = archive.lastModified()
                        )
                    )
                )
                reloadBrowserItems()
            }
            state = state.copy(
                localTaskLabel = "", localTaskRunning = false,
                message = if (archive != null) "已创建「${archive.name}」" else "创建压缩包失败"
            )
        }
    }


internal fun AppViewModel.downloadSubfolders(path: String): List<Pair<String, String>> {
        return runCatching {
            local.list(path)
                .filter { it.isFolder }
                .map { it.path to it.name }
        }.getOrDefault(emptyList())
    }


internal fun AppViewModel.createDownloadSubfolder(parent: String, name: String): Boolean {
        if (name.isBlank() || name.contains('/')) return false
        return runCatching { local.createFolder(parent, name) }.getOrDefault(false)
    }


internal fun AppViewModel.compressSelected(name: String, format: String, password: String = "") {
        val files = selectedItems()
        if (files.isEmpty()) return
        state = state.copy(selected = emptySet(), selectionActive = false)
        compressFiles(files, name, format, password)
    }


internal fun AppViewModel.requestOpen(
        file: FileEntry,
        /** 由调用方明确指定「这个文件在哪一端」（传输记录 / 通知）。不传就按当前页推断。 */
        forceCtx: FileContext? = null,
        forceNetId: String? = null
    ) {
        state = state.copy(
            fileSheet = null, preview = null, openWith = null,
            openForceCtx = forceCtx?.name ?: "",
            openForceNetId = forceNetId ?: ""
        )
        // APK：没有「安装未知应用」权限时先去申请，否则系统只会静默失败，
        // 用户看到的是「点了没反应」。
        if (!file.isFolder && file.name.endsWith(".apk", true) && !canInstallPackages()) {
            state = state.copy(installFile = file)
            return
        }
        // 所有文件类型统一弹应用内「打开方式」：点应用 = 仅本次，点 ☆ = 始终。
        // （此前只有音视频走这里，图片/文档直接丢给系统选择器，风格不统一。）
        requestOpenWith(file)
    }


    /**
     * 远端文件的「在线打开」：
     * - 视频/音频：不下载，经本机流式代理把地址交给外部播放器（可拖动进度，不受体积限制）；
     * - 图片：下载到缓存 → 系统相册，与本机图片一致；
     * - 其它（apk / 文档 / 压缩包）：下载到缓存 → 交给系统对应应用，apk 直接进安装器。
     */
    /**
     * 在线打开一个远端文件。
     *
     * [forceCtx] / [forceNetId] 由调用方明确指定「这个文件在哪一端」时使用（传输记录、
     * 通知、搜索结果）。两者一旦给出，就**绝不允许**再回落到当前浏览页的连接：
     * `sourceContext()` 第一行取的就是 `state.browser`，从「网络文件」栏点开一条
     * 群晖上传记录时，它会返回 SMB 连接，于是去 SMB 拉同名文件——表现就是
     * 「明明上传到群晖，打开的却是 SMB 里的那个」。
     */
    internal fun AppViewModel.openRemoteFile(
        file: FileEntry,
        gallery: Boolean,
        forceCtx: FileContext? = null,
        forceNetId: String? = null
    ) {
        // 防抖：800ms 内重复点击同一个文件不重复触发，避免弹出多个选择器。
        val now = System.currentTimeMillis()
        if (now - lastRemoteOpenAt < 800) return
        lastRemoteOpenAt = now

        val (autoCtx, autoNetId) = sourceContext(file)
        val ctx = forceCtx ?: autoCtx
        val netId = when {
            forceCtx == FileContext.NET -> forceNetId?.takeIf { it.isNotBlank() }
                // 记录没带连接 id（老记录）：先按路径前缀认，认不出再用当前页的连接。
                ?: state.connections.firstOrNull { file.path.startsWith(network.rootPath(it)) }?.id
                ?: autoNetId
            // 已明确是群晖 / 本地：当前页的网络连接 id 只会把下载指向错误的一端。
            forceCtx != null -> null
            else -> autoNetId
        }
        val connection = state.connections.firstOrNull { it.id == netId }
        // 视频和音频都走流式：不下载整文件，直接把代理地址交给系统播放器。
        if (file.kind == FileKind.VIDEO || file.kind == FileKind.AUDIO) {
            val source = when (ctx) {
                FileContext.SYNO -> state.session?.let { dsm.streamSource(it, file.path) }
                FileContext.NET -> connection?.let { network.streamSource(it, file.path) }
                FileContext.LOCAL -> null
            }
            if (source != null) {
                val url = StreamProxy.publish(source.stampedWith(file))
                if (playRemoteStream(file, url)) return
            }
            // 流式失败（协议不支持 / 无播放器）：小文件退回缓存打开，
            // 大文件绝不去下载——22GB 的片子缓存一份既放不下也没意义，只会更让人迷惑。
            if (file.sizeBytes > STREAM_FALLBACK_LIMIT) {
                state = state.copy(
                    message = "「${file.name}」无法在线播放（约 ${LocalRepository.formatSize(file.sizeBytes)}），" +
                        "请先下载到本机再用播放器打开，或换一个播放器重试"
                )
                return
            }
            cacheThenOpen(file, ctx, netId, gallery = false)
            return
        }
        val limit = if (gallery) PREVIEW_IMAGE_LIMIT else OPEN_CACHE_LIMIT
        if (file.sizeBytes > limit) {
            state = state.copy(
                message = "「${file.name}」约 ${LocalRepository.formatSize(file.sizeBytes)}，" +
                    "超过在线打开上限 ${LocalRepository.formatSize(limit)}，请先下载到本机再打开"
            )
            return
        }
        cacheThenOpen(file, ctx, netId, gallery)
    }


    /**
     * 把本机代理地址交给外部播放器。
     * [pkg] 非空时只交给这个应用（用户在「打开方式」里指定的），失败返回 false 让上层退回缓存打开。
     */
internal fun AppViewModel.playRemoteStream(file: FileEntry, url: String, pkg: String = ""): Boolean = runCatching {
        val context = getApplication<Application>()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            // MIME 没信息量时按后缀重判：发 application/octet-stream 给播放器，
            // 它认不出是音频，表现就是「进了播放器却没自动播」。
            setDataAndType(android.net.Uri.parse(url), effectiveMime(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg.isNotBlank()) setPackage(pkg)
        }
        if (pkg.isNotBlank()) {
            // 指定了应用就直接起，不再套 chooser（套了会退化成「只有一个选项的选择器」）。
            // 显式指定 Activity 组件：MIUI「启动应用」拦截放行后按原 Intent 送达目标页；
            // 只写 setPackage 时偶发被退化成「拉起应用首页、丢掉数据」。
            resolveActivityComponent(pkg, intent)?.let { intent.component = it }
            context.startActivity(intent)
        } else {
            // 用 createChooser 弹出播放器选择，避免没有默认播放器时静默失败。
            val chooser = Intent.createChooser(intent, "选择播放器播放 ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
        true
    }.getOrElse {
        if (pkg.isBlank()) {
            state = state.copy(message = "没有找到可播放「${file.name}」的应用，请先安装视频播放器")
        }
        false
    }


    /** 目标包里真正处理该 Intent 的 Activity 组件（找不到就返回 null，退回隐式启动）。 */
internal fun AppViewModel.resolveActivityComponent(pkg: String, intent: Intent): android.content.ComponentName? = runCatching {
        val info = getApplication<Application>().packageManager
            .queryIntentActivities(intent, 0)
            .firstOrNull { it.activityInfo?.packageName == pkg }
            ?: return@runCatching null
        android.content.ComponentName(info.activityInfo.packageName, info.activityInfo.name)
    }.getOrNull()


    /** 下载到缓存后打开（图片走相册，其它走系统关联应用）。 */
internal fun AppViewModel.cacheThenOpen(
        file: FileEntry,
        ctx: FileContext,
        netId: String?,
        gallery: Boolean,
        app: String = ""
    ) {
        // 重复点击防护：同一个文件正在打开时直接忽略。打开走的是静默传输，
        // 用户看不到反馈就会反复点，起出一堆并行下载写同一个缓存文件，
        // 最后打开的往往是先写完的那个半截文件。换文件也不打断当前这一路。
        val ongoing = state.openingFile
        if (ongoing != null) {
            if (ongoing.name == file.name) return
            state = state.copy(message = "正在打开「${ongoing.name}」，请稍候再试")
            return
        }
        val session = state.session
        val connection = state.connections.firstOrNull { it.id == netId }
        val target = File(
            getApplication<Application>().cacheDir,
            "open-${file.id.hashCode()}-${file.name}"
        )
        val taskId = java.util.UUID.randomUUID().toString()
        // 先登记可见状态，再启动传输：进度上报和取消按钮都靠这个 id 关联。
        state = state.copy(
            openingFile = OpeningFile(
                taskId = taskId,
                name = file.name,
                targetPath = target.absolutePath,
                totalBytes = file.sizeBytes,
                // 只有群晖下载接口有字节回调；网络协议没有，显示不确定进度。
                hasByteProgress = ctx == FileContext.SYNO
            )
        )
        lastOpeningProgressAt = 0L
        launchTransferTask(
            mode = TransferMode.DOWNLOAD,
            title = file.name,
            detail = "在线打开 · 缓存到本机",
            itemCount = 1,
            byteCount = file.sizeBytes,
            verb = "打开",
            quiet = true,
            taskId = taskId,
            onDone = { ok ->
                finishOpening(taskId, ok, target.absolutePath)
                if (ok) {
                    val prepared = file.copy(path = target.absolutePath, sizeBytes = target.length())
                    // 图片只在应用缓存里中转后交给相册，不复制进公共目录：
                    // 3.9.19 曾落盘 Pictures/MyFileBox 换相册完整界面，结果本地堆出
                    // 重复图片（同一张图在相册出现两份），3.9.20 按需求回退。
                    // 其它类型缓存后交系统关联应用。
                    if (gallery || file.kind == FileKind.IMAGE) {
                        openImageGallery(prepared, app)
                    } else {
                        openLocalIntent(prepared, app)
                    }
                } else if (!TransferCancellation.isCancelled(taskId)) {
                    // quiet 任务不会写消息到 state.message，失败时必须手动提示，
                    // 否则用户看到的是「点了没反应」。取消的提示由 cancelOpening 给。
                    state = state.copy(
                        message = lastTransferError
                            ?: "打开「${file.name}」失败，请检查连接后重试"
                    )
                }
            }
        ) { reporter ->
            val ok = withContext(Dispatchers.IO) {
                when (ctx) {
                    FileContext.SYNO ->
                        session?.let {
                            dsm.download(
                                it, file.path, target,
                                onBytes = { b -> reportOpeningBytes(taskId, b) },
                                shouldAbort = { reporter.cancelled() }
                            )
                        } ?: false
                    FileContext.NET -> connection?.let {
                        network.download(it, file.path, target, abort = { reporter.cancelled() })
                    } ?: false
                    FileContext.LOCAL -> false
                }
            }
            if (!ok) {
                lastTransferError = dsm.lastError ?: network.lastError
                    ?: "无法读取该文件，请确认连接正常且文件有读权限"
            }
            ok
        }
    }


    /** 把下载字节数节流后写回可见的打开进度（IO 线程调用，切主线程改 state）。 */
internal fun AppViewModel.reportOpeningBytes(taskId: String, done: Long, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastOpeningProgressAt < 200L) return
        lastOpeningProgressAt = now
        viewModelScope.launch {
            val opening = state.openingFile
            if (opening?.taskId == taskId) {
                state = state.copy(openingFile = opening.copy(completedBytes = done))
            }
        }
    }


    /**
     * 结束打开：取消或失败时清掉成品和半截临时文件，再撤掉进度条。
     *
     * [targetPath] 由调用方显式传入，不依赖 state.openingFile——因为「取消」会先把
     * openingFile 清空再异步走到这里，靠 state 取不到路径就会漏删缓存。
     * 正常成功时 openingFile 仍在，这里顺手把它清空。
     */
internal fun AppViewModel.finishOpening(taskId: String, ok: Boolean, targetPath: String) {
        if (!ok || TransferCancellation.isCancelled(taskId)) deleteOpeningCache(targetPath)
        val opening = state.openingFile
        if (opening?.taskId == taskId) state = state.copy(openingFile = null)
    }


    /** 删除某次打开留下的缓存成品与 .part 临时文件。 */
internal fun AppViewModel.deleteOpeningCache(targetPath: String) {
        runCatching {
            val target = File(targetPath)
            if (target.exists()) target.delete()
            File("$targetPath.part").let { if (it.exists()) it.delete() }
        }
    }


    /**
     * 用户点进度条上的「取消」：立即撤掉「正在打开」浮层并给出「已取消」提示。
     *
     * 必须在这里就把 openingFile 清空——不能等 onDone 异步回来再清。下载线程收到
     * abort 后可能要过一小会儿才走到回调（网络读阻塞、chunk 间隔），这段时间里
     * 旧浮层还挂在屏幕上，和新出现的「已取消」提示叠成两条，正是用户看到的样子。
     * 缓存文件的删除仍交给 onDone 的 finishOpening——但那时 openingFile 已空，
     * 所以把目标路径一并传进去，让清理不依赖 state。
     */
internal fun AppViewModel.cancelOpening() {
        val opening = state.openingFile ?: return
        TransferCancellation.request(opening.taskId)
        state = state.copy(openingFile = null, message = "已取消打开「${opening.name}」")
    }


    /** Activity 销毁（用户退出）时调用：立刻取消并删缓存，不留半截文件。 */
internal fun AppViewModel.cancelOpeningOnExit() {
        val opening = state.openingFile ?: return
        TransferCancellation.request(opening.taskId)
        deleteOpeningCache(opening.targetPath)
        state = state.copy(openingFile = null)
    }


    /**
     * 本地图片在媒体库里的 content URI。
     *
     * 相册只对「媒体库里的一张图」给出完整查看界面（分享 / 编辑 / 收藏 / 删除）；
     * 拿到别的应用 FileProvider 出来的地址时，它只当成一个外部文件，退化成
     * 「只有保存 / 更多」的日期页。所以打开图片前必须先把这张图对应到媒体库条目。
     *
     * 两种查法都试：Android 11+ 对 DATA 列有额外限制，RELATIVE_PATH + DISPLAY_NAME 更稳。
     */
internal fun AppViewModel.mediaImageUri(path: String): android.net.Uri? {
        val resolver = getApplication<Application>().contentResolver
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun query(selection: String, args: Array<String>): android.net.Uri? = runCatching {
            resolver.query(collection, arrayOf(android.provider.MediaStore.Images.Media._ID), selection, args, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                    else null
                }
        }.getOrNull()
        // ① 老式 DATA 列（多数机型仍可查，最准）。
        query("${android.provider.MediaStore.Images.Media.DATA} = ?", arrayOf(path))?.let { return it }
        // ② Android 10+ 的 RELATIVE_PATH + 文件名。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val file = File(path)
            val root = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
            val parent = file.parent.orEmpty().trimEnd('/')
            if (parent.startsWith(root)) {
                val relative = parent.removePrefix(root).trim('/').let { if (it.isEmpty()) "" else "$it/" }
                query(
                    "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                        "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} = ?",
                    arrayOf(relative, file.name)
                )?.let { return it }
            }
        }
        return null
    }


    /**
     * 拿这张图的媒体库 URI；还没被收录（刚下载 / 刚传进手机 / PC 推过来的）就先请系统扫一次。
     * 必须跑在 IO 线程上（扫描是异步的，这里最多等约 1.2 秒）。
     *
     * 扫出来的条目会先挂「未发布」标记（is_pending=1，属主=本应用），系统后台才慢慢转正；
     * 未发布条目只有属主读得到，相册拿到会直接秒退（3.9.19 修：查到后若是自家未发布
     * 条目就立刻转正再交出去，字节已在磁盘上、只差发布标记，不等系统后台）。
     */
internal fun AppViewModel.ensureGalleryUri(target: File): android.net.Uri? {
        if (!target.isFile) return null
        fun usable(): android.net.Uri? {
            val uri = mediaImageUri(target.absolutePath) ?: return null
            if (!isPending(uri)) return uri
            return if (markPublished(uri)) uri else null
        }
        usable()?.let { return it }
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                getApplication(), arrayOf(target.absolutePath), null, null
            )
        }
        repeat(8) {
            Thread.sleep(150)
            usable()?.let { return it }
        }
        return null
    }


    /** 条目是否还挂着「未发布」标记（其它应用读不到）。旧系统没有这个概念，一律当已发布。 */
internal fun AppViewModel.isPending(uri: android.net.Uri): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf("is_pending"), null, null, null)
                ?.use { c -> c.moveToFirst() && c.getInt(0) == 1 } == true
        }.getOrDefault(false)
    }


    /** 把自家未发布条目转正。只有属主能改；失败返回 false，调用方退回 FileProvider。 */
internal fun AppViewModel.markPublished(uri: android.net.Uri): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return true
        return runCatching {
            val values = android.content.ContentValues().apply { put("is_pending", 0) }
            getApplication<Application>().contentResolver.update(uri, values, null, null) > 0
        }.getOrDefault(false)
    }


    /** 用（媒体库）地址把图片交给相册；拿不到媒体库地址时退回 FileProvider，至少能打开。 */
internal fun AppViewModel.openImageIntent(file: FileEntry, app: String, galleryUri: android.net.Uri?) {
        val context = getApplication<Application>()
        val uri = galleryUri ?: runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", File(file.path))
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            // ClipData 会随 Intent 扩散 URI 读权限，相册据此显示完整的图片浏览界面。
            clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (app.isNotBlank()) {
                setPackage(app)
                // 显式指定 Activity 组件：MIUI「启动应用」拦截放行后按原 Intent 送达目标页；
                // 只写 setPackage 时偶发退化成「拉起应用首页、丢掉数据」（3.9.18 同款修法）。
                resolveActivityComponent(app, this)?.let { component = it }
            }
        }
        runCatching {
            android.util.Log.i(
                "MFB-OPEN",
                "img open app=${app.ifBlank { "chooser" }} media=${galleryUri != null}"
            )
            if (app.isNotBlank() && hasHandler(intent)) {
                runCatching { context.grantUriPermission(app, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                context.startActivity(intent)
            } else {
                context.startActivity(
                    Intent.createChooser(intent, "打开 ${file.name}").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }.onFailure {
            // 个别设备没有可处理 image/* 的相册时，退回通用打开方式，避免「点了没反应」。
            openLocalIntent(file)
        }
    }


    /**
     * 打开缓存里的图片（群晖 / 网络文件）。
     *
     * 远程图片只在应用缓存里中转，**不复制进公共媒体目录**：3.9.19 为了让相册
     * 显示完整界面，会把图片落到 Pictures/MyFileBox，导致本地堆出重复图片
     * （相册里同一张图出现两份）。缓存目录进不了媒体库，相册拿到 FileProvider
     * 地址只给简化页，这是接受的取舍——要看完整界面可先「下载到本机」再打开
     * （3.9.20 按需求回退）。
     * [app] 非空时定向到「打开方式」里选中的应用，空则弹系统选择器。
     */
internal fun AppViewModel.openImageGallery(file: FileEntry, app: String = "") {
        openImageIntent(file, app, null)
    }


    /** 预览缓存：同样走传输中心，能在「进行中」看到下载进度，而不是只有一个转圈。 */
internal fun AppViewModel.preparePreview(file: FileEntry, ctx: FileContext, netId: String?) {
        if (file.path.startsWith("/") && File(file.path).exists()) return
        val session = state.session
        val connection = state.connections.firstOrNull { it.id == netId }
        // 缓存文件名带上 id，避免同名文件互相覆盖导致预览串图。
        val cache = File(getApplication<Application>().cacheDir, "preview-${file.id.hashCode()}-${file.name}")
        launchTransferTask(
            mode = TransferMode.DOWNLOAD,
            title = file.name,
            detail = "预览 · 缓存到本机",
            itemCount = 1,
            byteCount = file.sizeBytes,
            verb = "预览",
            quiet = true,
            onDone = { ok ->
                if (ok && cache.isFile && cache.length() > 0) {
                    state = state.copy(
                        preview = file.copy(
                            path = cache.absolutePath,
                            sizeLabel = LocalRepository.formatSize(cache.length())
                        )
                    )
                } else {
                    state = state.copy(preview = null)
                }
            }
        ) { reporter ->
            var ok = withContext(Dispatchers.IO) {
                when (ctx) {
                    FileContext.SYNO ->
                        session?.let { dsm.download(it, file.path, cache, onBytes = { b -> reporter.bytes(b) }) } ?: false
                    FileContext.NET -> connection?.let { network.download(it, file.path, cache) } ?: false
                    FileContext.LOCAL -> false
                }
            }
            if (!ok && ctx == FileContext.SYNO && renewSynoSession()) {
                ok = state.session?.let {
                    dsm.download(it, file.path, cache, onBytes = { b -> reporter.bytes(b) })
                } ?: false
            }
            if (!ok) lastTransferError = dsm.lastError ?: network.lastError ?: "无法读取该文件"
            ok
        }
    }


internal fun AppViewModel.closePreview() { state = state.copy(preview = null) }

internal fun AppViewModel.closeOpenWith() { state = state.copy(openWith = null) }


    /**
     * 远程图片列表缩略图：首次展示时按需下载到缓存目录，之后直接复用磁盘缓存。
     * 限制并发 3 个、单文件 8MB；远程视频不为列表下载整段视频（保留播放角标）。
     */
internal suspend fun AppViewModel.remoteThumbnail(file: FileEntry): String? {
        // 远端视频不需要预览图（用户要求），只显示类型图标即可。
        if (file.kind != FileKind.IMAGE) return null
        if (file.path.startsWith("/") && File(file.path).isFile) return file.path
        val dir = File(getApplication<Application>().cacheDir, "thumbs")
        val ext = file.name.substringAfterLast('.', "bin").take(8)
        // 缓存 key 带上原图大小：早期 ASCII 模式留下过「名字对、内容少一两字节」的
        // 半截缩略图，只按路径命中会让坏图一直复用，表现就是列表里有的图片没缩略图。
        val target = File(dir, "${(file.id + file.path + "@${file.sizeBytes}").hashCode()}.$ext")
        val (ctx, netId) = sourceContext(file)
        // 群晖 Thumb API 返回的是缩放图，大小不等于原图，不能按原图字节数校验。
        fun cachedUsable(): Boolean {
            if (!target.isFile || target.length() <= 0L) return false
            if (ctx == FileContext.SYNO) return true
            return file.sizeBytes <= 0L || target.length() == file.sizeBytes
        }
        if (cachedUsable()) return target.absolutePath
        val session = state.session
        val conn = state.connections.firstOrNull { it.id == netId }
        return thumbSemaphore.withPermit {
            if (cachedUsable()) return@withPermit target.absolutePath
            if (!dir.exists()) dir.mkdirs()
            // 群晖优先用服务端缩略图（Thumb API），不用整文件下载。
            if (ctx == FileContext.SYNO && session != null) {
                val bytes = dsm.thumbnail(session, file.path, 256)
                if (bytes != null && bytes.size > 100) {
                    target.writeBytes(bytes)
                    return@withPermit target.absolutePath
                }
            }
            // 其它协议 / Thumb 失败：小文件拉回来本地解码，大文件回退到图标。
            if (file.sizeBytes > 8L * 1024 * 1024) return@withPermit null
            val ok = withContext(Dispatchers.IO) {
                when (ctx) {
                    FileContext.SYNO -> session?.let { dsm.download(it, file.path, target) } ?: false
                    FileContext.NET -> conn?.let { network.download(it, file.path, target) } ?: false
                    FileContext.LOCAL -> false
                }
            }
            if (ok && cachedUsable()) {
                target.absolutePath
            } else {
                // 大小不符就是半截缓存：当场删掉，下次进列表会重新拉，避免坏图一直占着。
                runCatching { if (target.exists()) target.delete() }
                null
            }
        }
    }


    /**
     * 弹出「打开方式」：音乐 / 视频文件点一下先在这里选播放器。
     *
     * 之前的实现是写死一份假列表（系统音乐 / VLC / 网易云音乐…），真正点下去走的却是
     * 系统选择器，而系统选择器只认当前这次 Intent 的类型，很多已安装的音乐 App 因为
     * 只注册了 content:// 或 SEND 而被漏掉。现在改为用 PackageManager 真实枚举。
     */
    /** 这个文件能不能用自带解压打开：只有本地压缩包可以，远端文件要先下载。 */
internal fun AppViewModel.canExtractLocally(file: FileEntry): Boolean =
        !file.isFolder && isArchiveName(file.name) && file.path.startsWith(state.localRoot)


    /** 根组合取出待解压的文件后清空，防止重组时重复弹对话框。 */
internal fun AppViewModel.consumeExtractRequest() {
        if (state.extractRequest != null) state = state.copy(extractRequest = null)
    }


    /**
     * 「始终用 MyFileBox 解压」：把解压记成这一类文件的默认动作，下次点压缩包直接弹解压位置。
     *
     * 与其它应用的「始终」共用同一份 openDefaults，所以在「设置 → 默认打开方式」里
     * 可以一起改回来，不需要第二套装默认的机制。
     */
internal fun AppViewModel.extractAlways(file: FileEntry) {
        val key = openKeyOf(file)
        val next = state.prefs.openDefaults.toMutableMap()
        next[key] = EXTRACT_TARGET
        state = state.copy(
            fileSheet = null, preview = null,
            openWith = null, openWithApps = emptyList(), openWithLoading = false,
            prefs = state.prefs.copy(openDefaults = next),
            extractRequest = file,
            message = "以后「${openTypeLabel(key)}」默认用 MyFileBox 解压 · 可在设置里修改"
        )
        persist()
    }


internal fun AppViewModel.requestOpenWith(file: FileEntry) {
        // 用户之前点过「始终用这个应用打开」：直接开，不再每次都问。
        // 也不再每次弹「可在设置里修改」的提示 —— 那句话只在第一次设定时出现一次，
        // 之后每打开一次都提示纯属噪音（3.9.16 修）。
        //
        // 自带解压要单独判一次：它记的不是包名，PackageManager 校验不了，
        // 混在 rememberedOpenPackage 里会被当成「应用已卸载」而静默丢弃。
        if (state.prefs.openDefaults[openKeyOf(file)] == EXTRACT_TARGET) {
            if (canExtractLocally(file)) {
                state = state.copy(
                    fileSheet = null, preview = null, openWith = null,
                    openWithApps = emptyList(), openWithLoading = false,
                    extractRequest = file
                )
                return
            }
            // 记忆失效（文件改成了别的类型 / 已不在本地）：清掉它，回到正常询问。
            state = state.copy(
                prefs = state.prefs.copy(openDefaults = state.prefs.openDefaults - openKeyOf(file))
            )
            persist()
        }
        val remembered = rememberedOpenPackage(file)
        if (remembered != null) {
            state = state.copy(
                fileSheet = null, preview = null, openWith = null,
                openWithApps = emptyList(), openWithLoading = false
            )
            openWithPackage(file, remembered)
            return
        }
        state = state.copy(
            fileSheet = null, preview = null, openWith = file,
            openWithApps = emptyList(), openWithLoading = true
        )
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { resolveOpenApps(file) }
            // 用户可能已经关掉弹窗或换了文件，别把旧结果盖上去。
            if (state.openWith?.id == file.id) {
                state = state.copy(openWithApps = apps, openWithLoading = false)
            }
        }
    }


    /**
     * 「打开方式」里点了某个应用。
     *
     * [rememberForType] = true 表示「始终」：把它记成这一类文件的默认应用，下次直接打开；
     * 传空包名则清除记忆，恢复成每次询问。
     */
internal fun AppViewModel.launchWith(pkg: String, rememberForType: Boolean = false) {
        val file = state.openWith ?: return
        state = state.copy(openWith = null)
        if (rememberForType) {
            val key = openKeyOf(file)
            val next = state.prefs.openDefaults.toMutableMap()
            if (pkg.isBlank()) next.remove(key) else next[key] = pkg
            state = state.copy(
                prefs = state.prefs.copy(openDefaults = next),
                message = if (pkg.isBlank()) {
                    "已改为每次询问用哪个应用打开"
                } else {
                    "以后「${openTypeLabel(key)}」默认用「${appLabelOf(pkg)}」打开 · 可在设置里修改"
                }
            )
            persist()
        }
        openWithPackage(file, pkg)
    }


    /** 这一类文件记住的默认应用；应用被卸载就顺手清掉，避免点了没反应。 */
internal fun AppViewModel.rememberedOpenPackage(file: FileEntry): String? {
        val key = openKeyOf(file)
        val pkg = state.prefs.openDefaults[key]?.takeIf { it.isNotBlank() } ?: return null
        val installed = runCatching {
            getApplication<Application>().packageManager.getApplicationInfo(pkg, 0)
        }.isSuccess
        if (installed) return pkg
        state = state.copy(prefs = state.prefs.copy(openDefaults = state.prefs.openDefaults - key))
        persist()
        return null
    }


    /**
     * 默认打开方式的 key：音视频按大类，其它按具体 MIME。
     *
     * 上游给的 MIME 常常没有信息量（群晖下载接口、SMB/FTP 列表一律回
     * application/octet-stream），直接拿来当 key 会让所有「不太常见」的文件挤进同一个
     * 「未知类型文件」桶里：既分不清类型，记住的默认应用也会互相串。
     * 所以遇到这种通用 MIME 时，退回按后缀名再判一次（3.9.17）。
     */
internal fun AppViewModel.openKeyOf(file: FileEntry): String = when (file.kind) {
        FileKind.AUDIO -> "audio"
        FileKind.VIDEO -> "video"
        FileKind.IMAGE -> "image"
        else -> {
            val mime = file.mime.substringBefore(';').trim().lowercase()
            if (isGenericMime(mime)) mimeFromName(file.name) else mime
        }
    }


    /** 这些 MIME 只说明「有点数据」，说不清是什么类型，等于没给。 */
internal fun AppViewModel.isGenericMime(mime: String): Boolean = mime.isBlank() ||
        mime == "application/octet-stream" ||
        mime == "binary/octet-stream" ||
        mime == "application/unknown" ||
        mime == "application/x-unknown" ||
        mime == "unknown/unknown" ||
        mime == "*/*"


    /**
     * 设置页 / 提示里展示的类型名。
     *
     * 以前直接把 MIME 原样贴出来（application/octet-stream、application/zip），
     * 对普通用户等于天书。这里换成中文说法，查不到的类型统一说「未知类型文件」。
     */
internal fun AppViewModel.openTypeLabel(key: String): String {
        when (key) {
            "audio" -> return "音频"
            "video" -> return "视频"
            "image" -> return "图片"
        }
        val mime = key.substringBefore(';').trim().lowercase()
        mimeTypeLabels[mime]?.let { return it }
        return when {
            mime.startsWith("text/") -> "文本文件"
            mime.startsWith("image/") -> "图片"
            mime.startsWith("audio/") -> "音频"
            mime.startsWith("video/") -> "视频"
            mime.startsWith("font/") -> "字体文件"
            else -> "未知类型文件"
        }
    }


internal fun AppViewModel.appLabelOf(pkg: String): String {
        // 自带解压是个占位值，不是已装应用；设置页要看到一个像样的名字。
        if (pkg == EXTRACT_TARGET) return "MyFileBox 解压"
        return runCatching {
            val pm = getApplication<Application>().packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }


    /** 设置页展示用：已记住的默认打开方式（类型 → 应用名）。 */
internal fun AppViewModel.openDefaultEntries(): List<Pair<String, String>> =
        state.prefs.openDefaults.entries
            .sortedBy { it.key }
            .map { it.key to appLabelOf(it.value) }


internal fun AppViewModel.clearOpenDefault(key: String) {
        state = state.copy(
            prefs = state.prefs.copy(openDefaults = state.prefs.openDefaults - key),
            message = "已恢复为每次询问（${openTypeLabel(key)}）"
        )
        persist()
    }


    /** 默认打开方式积累很多时，设置页提供一次性清空，不必逐行点。 */
internal fun AppViewModel.clearAllOpenDefaults() {
        if (state.prefs.openDefaults.isEmpty()) return
        state = state.copy(
            prefs = state.prefs.copy(openDefaults = emptyMap()),
            message = "已清除全部默认打开方式"
        )
        persist()
    }


    /** 真正的打开动作：本机文件走 FileProvider，远端文件按需缓存 / 流式播放。 */
internal fun AppViewModel.openWithPackage(file: FileEntry, pkg: String) {
        // 传输记录 / 通知指定的端优先：记录自己知道文件在哪一端，
        // 不能按当前浏览页去猜（在群晖栏点开一条 SMB 记录会打开错端）。
        val forcedCtx = state.openForceCtx.takeIf { it.isNotBlank() }
            ?.let { runCatching { FileContext.valueOf(it) }.getOrNull() }
        val forcedNet = state.openForceNetId.takeIf { it.isNotBlank() }
        if (state.openForceCtx.isNotBlank()) {
            state = state.copy(openForceCtx = "", openForceNetId = "")
        }
        val local = file.path.startsWith("/") && File(file.path).exists()
        if (local) {
            if (file.kind == FileKind.IMAGE) {
                // 图片必须交给「媒体库 URI」，否则相册拿到的只是一份外部文件，
                // 会退化成只有「保存 / 更多」的日期页（3.9.16 修）。
                viewModelScope.launch {
                    val uri = withContext(Dispatchers.IO) { ensureGalleryUri(File(file.path)) }
                    openImageIntent(file, pkg, uri)
                }
                return
            }
            openLocalIntent(file, pkg)
            return
        }
        val (autoCtx, autoNetId) = sourceContext(file)
        val ctx = forcedCtx ?: autoCtx
        val netId = if (forcedCtx != null) forcedNet else autoNetId
        if (pkg.isBlank() && (file.kind == FileKind.VIDEO || file.kind == FileKind.AUDIO)) {
            // 选「系统选择器」= 沿用原来的流式播放（不下载整个文件）。
            openRemoteFile(file, gallery = false, forceCtx = forcedCtx, forceNetId = forcedNet)
            return
        }
        // 视频/音频优先流式（不下载整个文件）；点了具体播放器时若它不认 http 源，
        // 再退回「缓存到本机 + content:// 交给它」，保证点了一定有反应。
        if (pkg.isNotBlank() && (file.kind == FileKind.VIDEO || file.kind == FileKind.AUDIO)) {
            val source = when (ctx) {
                FileContext.SYNO -> state.session?.let { dsm.streamSource(it, file.path) }
                FileContext.NET -> state.connections.firstOrNull { it.id == netId }
                    ?.let { network.streamSource(it, file.path) }
                FileContext.LOCAL -> null
            }
            if (source != null) {
                val url = StreamProxy.publish(source.stampedWith(file))
                if (playRemoteStream(file, url, pkg)) return
            }
            // 大文件不缓存：直接给结论，避免静默启动一个几十 GB 的下载任务。
            if (file.sizeBytes > STREAM_FALLBACK_LIMIT) {
                state = state.copy(
                    message = "「${file.name}」无法用该应用在线播放，" +
                        "可换一个播放器，或先下载到本机再用它打开"
                )
                return
            }
        }
        // 图片走专用打开（image/* + ClipData + 显式组件），但只在应用缓存里中转、
        // 不落盘到公共目录（3.9.19 的落盘方案造成本地重复图片，3.9.20 回退）；
        // 其它类型缓存后交系统关联应用。
        cacheThenOpen(file, ctx, netId, gallery = file.kind == FileKind.IMAGE, app = pkg)
    }


    /**
     * 枚举「能打开这个文件」的已安装应用。
     *
     * 只查一种 Intent 一定会漏：不同 App 注册的入口差别很大（只注册 content://、
     * 只注册 file://、只接 ACTION_SEND、或只声明大类（audio、video 前缀）。这里把常见几种
     * 都查一遍再按包名去重，用户装了什么播放器就能看到什么。
     */
internal fun AppViewModel.resolveOpenApps(file: FileEntry): List<OpenApp> {
        val pm = getApplication<Application>().packageManager
        // 与「默认打开方式」共用同一套类型判定：上游给 application/octet-stream 时按后缀名判，
        // 否则弹窗里查出来的应用和真正能打开它的应用会对不上（.txt 只列出极少数 App）。
        val mime = openKeyOf(file).let {
            when (it) {
                "audio" -> "audio/*"
                "video" -> "video/*"
                "image" -> "image/*"
                else -> it
            }
        }
        val family = when (file.kind) {
            FileKind.AUDIO -> "audio/*"
            FileKind.VIDEO -> "video/*"
            FileKind.IMAGE -> "image/*"
            else -> mime
        }
        // 目标类型仍是通用 MIME（连后缀都判不出）时，没有「可能打得开」的应用：
        // 声明 application/octet-stream 的多是浏览器 / 网盘的通配收件箱，
        // 列出来全是用不到的凑数项。留空让弹窗提示走系统选择器（3.9.18）。
        val generic = isGenericMime(mime)
        if (generic) return emptyList()
        val targets = listOf(mime, family).distinct()
        val probeFile = "probe" + file.name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        // 两档查询必须**合并**，不能「先到先得」（3.9.21 修「打开方式缺很多软件」）：
        //
        // ① 只带类型：系统按声明的 MIME 精确匹配，命中即为真正的类型入口。
        // ② 带 content:// / file:// 假地址：只有这档才能查到「声明了 scheme=file、没写
        //    MIME」的万能查看器——完整版 WPS（cn.wps.moffice_eng）、系统文件管理的解压
        //    入口（ExtractFileActivity）、legado 这类阅读器全在这一档里。早期只在
        //    ① 为空时才用 ②，于是 .zip 列表整个是空的、.pptx 只剩定制版 WPS。
        //
        // 合并顺序固定为「① 先、② 补」，用户看到的仍然是按声明精确度排的。
        // ② 会带进「只声明 scheme、把任意文件当收件箱」的应用（网易云音乐、坚果云、
        //    聊天工具），交给 collectOpenApps 里的角色过滤清理。
        val strictIntents = mutableListOf<Intent>()
        targets.forEach { type ->
            strictIntents += Intent(Intent.ACTION_VIEW).setType(type)
            strictIntents += Intent(Intent.ACTION_VIEW).setTypeAndNormalize(type)
        }
        val looseIntents = mutableListOf<Intent>()
        targets.forEach { type ->
            listOf("content://com.yaoahos.myfilebox.probe/", "file:///storage/emulated/0/").forEach { prefix ->
                looseIntents += Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(prefix + probeFile), type)
            }
        }
        // 浏览器集合只算一次：内部要遍历已安装包，三档查询各算一次会明显变慢。
        val browsers = browserPackages(pm)
        val strictFound = collectOpenApps(pm, strictIntents, targets, generic, browsers)
        val looseFound = collectOpenApps(pm, looseIntents, targets, generic, browsers)
        val merged = LinkedHashMap<String, OpenApp>()
        strictFound.forEach { merged[it.pkg] = it }
        looseFound.forEach { merged.putIfAbsent(it.pkg, it) }
        android.util.Log.d(
            "MFB-OPEN",
            "resolve ${file.name} kind=${file.kind} mime=$mime targets=$targets generic=$generic " +
                "strict=${strictFound.map { it.pkg }} loose=${looseFound.map { it.pkg }}"
        )
        val found = merged.values.toList()
        if (found.isNotEmpty()) return found
        // ③ 兜底才查分享入口：只有「打开」类应用一个都没有时，才把只注册 SEND 的 App 列出来。
        // 分享类 App（浏览器 / 聊天工具）虽然能选中，但多半没有可导出的 View 入口，选了也打不开。
        val sendIntents = targets.map { Intent(Intent.ACTION_SEND).setType(it) }
        val sendFound = collectOpenApps(pm, sendIntents, targets, generic, browsers)
        android.util.Log.d("MFB-OPEN", "resolve ${file.name} sendFound=${sendFound.map { it.pkg }}")
        return sendFound
    }


    /**
     * 按包名去重地收集能处理这些 Intent 的应用，并做一次「角色过滤」。
     *
     * 排序：命中具体类型 > 只声明通配 > 混合声明。
     *
     * 过滤三类「声明了但打不开」的入口（3.9.21）：
     * 1. 浏览器：能处理 `https` 的包不参与打开方式。它们确实会响应文件类型
     *    （内置 PDF / PPT 阅读器），但用户要的是「用专门的应用打开」，不是跳浏览器。
     * 2. 云盘 / 网盘：只按包名与应用名识别（无系统 API 可判），命中即排除。
     * 3. 分享 / 发送 / 导入 / 打印 / 扫码入口：Activity 名里带
     *    share / send / import / upload / print / scan / dispense / redirect 的，
     *    是「把文件发给它」而不是「用它打开文件」。真机例证：支付宝
     *    `ShareDispenseActivity`、微信 `ShareScreenImgUI`、米家 `SendPrintActivity`、
     *    钱迹 `ImportHomeAct`、坚果云 `SendToNutstoreIndex`、网易云 `RedirectActivity`。
     *
     * 系统选择器入口始终保留在弹窗底部，所以这里的排除不会让用户无路可走。
     */
internal fun AppViewModel.collectOpenApps(
        pm: android.content.pm.PackageManager,
        probes: List<Intent>,
        targets: List<String>,
        generic: Boolean,
        browsers: Set<String> = browserPackages(pm)
    ): List<OpenApp> {
        val self = getApplication<Application>().packageName
        val found = LinkedHashMap<String, Pair<Int, OpenApp>>()
        probes.forEach { probe ->
            val resolved = runCatching {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, android.content.pm.PackageManager.GET_RESOLVED_FILTER)
            }.getOrDefault(emptyList())
            android.util.Log.d("MFB-OPEN", "probe [${probe.type}|${probe.data}] -> ${resolved.size}: ${resolved.map { it.activityInfo?.packageName }}")
            resolved.forEach { info ->
                val activity = info.activityInfo ?: return@forEach
                val pkg = activity.packageName ?: return@forEach
                if (pkg == self) return@forEach
                if (isBrowserPackage(pkg, browsers)) {
                    android.util.Log.d("MFB-OPEN", "  drop browser $pkg")
                    return@forEach
                }
                val filter = info.filter
                val declared = runCatching {
                    filter?.typesIterator()?.asSequence()?.toList()
                }.getOrNull()
                val level = declaredTypeLevel(declared, targets, generic, filterMissing = filter == null)
                if (level == 0) {
                    android.util.Log.d("MFB-OPEN", "  drop $pkg types=$declared")
                    return@forEach
                }
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                if (isCloudStorageApp(pkg, label)) {
                    android.util.Log.d("MFB-OPEN", "  drop cloud $pkg($label)")
                    return@forEach
                }
                if (isNonViewerPackage(pkg)) {
                    android.util.Log.d("MFB-OPEN", "  drop non-viewer $pkg($label)")
                    return@forEach
                }
                // 远程桌面 / 远程控制客户端会声明「任意文件」收件（把文件当待传附件），
                // 但它们不是查看器：真机上 Parallels Client 就出现在 apk 的打开方式里。
                if (isRemoteDesktopApp(pkg, label)) {
                    android.util.Log.d("MFB-OPEN", "  drop remote-desktop $pkg($label)")
                    return@forEach
                }
                if (isNonViewerActivity(activity.name)) {
                    android.util.Log.d("MFB-OPEN", "  drop entry $pkg/${activity.name}")
                    return@forEach
                }
                // 同一个包可能被多个 probe 命中：以更高的声明级别为准。
                val previous = found[pkg]
                if (previous != null && previous.first >= level) return@forEach
                found[pkg] = level to OpenApp(pkg, label)
            }
        }
        return found.values
            .sortedWith(
                compareByDescending<Pair<Int, OpenApp>> { it.first }
                    .thenBy { it.second.label.lowercase() }
            )
            .map { it.second }
    }


    /**
     * 能响应 `https` 的包（真正的浏览器）。
     *
     * 不能用 `getInstalledPackages()` 遍历再按包名筛：manifest 里只有 `<queries>`
     * 没有 `QUERY_ALL_PACKAGES`，Android 11+ 下它只看得到极少数包，浏览器一个都筛不出来
     * （真机实测：夸克、com.android.browser 都漏网）。所以只保留 https 查询结果，
     * 包名关键词在 [isBrowserPackage] 里逐包判断。
     */
internal fun browserPackages(pm: android.content.pm.PackageManager): Set<String> {
        val found = LinkedHashSet<String>()
        runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")),
                0
            ).forEach { it.activityInfo?.packageName?.let(found::add) }
        }
        return found
    }

    /**
     * 是否是浏览器（不该出现在「打开方式」里）。
     *
     * 用户的要求很明确：浏览器确实能开 PDF / 图片，但「用浏览器打开」不是文件管理
     * 里想要的动作。系统浏览器可能只用自建 scheme，查不到 https 入口，因此补一层
     * 包名关键词（chorme / firefox / quark / browser …）。
     */
internal fun isBrowserPackage(pkg: String, httpsHosts: Set<String>): Boolean {
        if (pkg in httpsHosts) return true
        val p = pkg.lowercase()
        return BROWSER_HINTS.any { p.contains(it) }
    }

    /** 只有「打开方式」在用的角色判定表，别处不要复用。 */
    private val BROWSER_HINTS = listOf(
        "chrome", "firefox", "opera", "brave", "edge", "quark", "ucmobile", "uc.browser",
        "browser", "mibrowser", "vivobrowser", "huaweibrowser", "baidubrowser", "sogou.explorer",
        // 轻量 / 第三方浏览器：包名里没有 browser 字样，只能按已知包名补。
        "mark.via", "alook", "kiwi", "yandex", "samsung.internet", "qute", "x.browser"
    )

    /**
     * 明确不是文件查看器、却常声明「任意类型」收件的应用：AI 助手、社交、支付、输入法、
     * 安全清理、自动化工具。这些包名是唯一可用的公开信号，命中即排除。
     *
     * 只收「绝不可能用来打开文件」的；判断不了的（阅读器、播放器、办公套件）一律不写，
     * 宁可多留也不误杀——弹窗底部始终有「用系统选择器打开」兜底。
     */
    private val NON_VIEWER_PACKAGES = listOf(
        // AI 助手 / 效率工具（把文件当附件收，不是查看器）
        "workbuddy", "hunyuan", "doubao", "kimi", "openai", "chatgpt", "claude",
        "wenxin", "ernie", "tongyi", "gkd",
        // 社交 / IM
        "tencent.mm", "mobileqq", "weibo", "dingtalk", "feishu", "lark", "telegram",
        "whatsapp", "snssdk", "kuaishou",
        // 支付 / 金融 / 钱包
        "alipay", "tenpay", "unionpay", "icbc", "ccb.", "abchina", "boc.", "wallet", "mipay",
        // 健康 / 运动（真机上「小米运动健康」曾出现在 txt 打开方式里）
        "mi.health", "health",
        // 输入法 / 键盘
        "inputmethod", "keyboard", "sogou.input",
        // 安全 / 清理
        "securitycenter", "cleaner", "antivirus", "optimizer"
    )

    /**
     * 远程桌面 / 远程控制客户端：能把文件当「待发送附件」收下，但不是文件查看器。
     * 包名 + 应用名一起匹配（Parallels Client 这种应用名只有 "Client"，只能靠包名认）。
     */
    private val REMOTE_DESKTOP_HINTS = listOf(
        "parallels", "rustdesk", "todesk", "anydesk", "teamviewer", "sunlogin", "oray",
        "parsec", "splashtop", "jumpdesktop", "moonlight", "rdclient", "msrdc",
        "mslgrdp", "remoteclient", "vnc", "远程桌面", "远程控制", "远程协助", "向日葵"
    )

    private fun isRemoteDesktopApp(pkg: String, label: String): Boolean {
        val key = (pkg + " " + label).lowercase()
        return REMOTE_DESKTOP_HINTS.any { key.contains(it) }
    }

    private val CLOUD_HINTS = listOf(
        "nutstore", "netdisk", "skydrive", "onedrive", "dropbox", "box.android", "databox",
        "weiyun", "pcloud", "alipan", "clouddrive", "115pan", "123pan",
        "坚果云", "网盘", "云盘", "云存储", "微云", "云同步"
    )

    /**
     * 分享 / 发送 / 导入 / 打印 / 扫码 / 跳转入口：这些 Activity 只是把文件「交给」
     * 应用，不是用应用打开它。名称是唯一可用的公开信号。
     */
internal fun isNonViewerActivity(name: String): Boolean {
        val simple = name.substringAfterLast('.').lowercase()
        return NON_VIEWER_WORDS.any { simple.contains(it) }
    }

    private val NON_VIEWER_WORDS = listOf(
        "share", "upload", "import", "print", "scan", "dispense", "sendto", "sendprint", "redirect"
    )

    private fun isCloudStorageApp(pkg: String, label: String): Boolean {
        val key = (pkg + " " + label).lowercase()
        return CLOUD_HINTS.any { key.contains(it) }
    }

    /** 命中 [NON_VIEWER_PACKAGES] 的应用：它能把文件当附件收下，但不是查看器。 */
internal fun isNonViewerPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return NON_VIEWER_PACKAGES.any { p.contains(it) }
    }


    /**
     * 应用声明的类型与目标的匹配级别（数值越大越靠前）。
     *
     * - `3`：命中具体类型（精确 MIME；`audio/mpeg` 命中 `audio` 大类目标；`text` 大类命中
     *   `text/plain`）。这类应用基本可以确定能正确打开该文件。
     * - `2`：**只**声明了任意类型通配 `*`。WPS Office 就用它声明「支持全部文档格式」，
     *   是万能查看器的典型写法——不能丢，也不能沉到最后，否则打开 pptx 时用户看不到 WPS。
     * - `1`：既声明了别的具体类型（都不匹配）又挂了个 `*`。多见于音乐/聊天类应用，
     *   靠通配兜底并不可靠，排最后。
     * - `0`：只声明了与目标无关的具体类型（音乐 App 之于文档）；**或者 filter 里
     *   一个 MIME 都没声明**（只写了 `scheme=file` 的收件箱）。后者是网易云音乐、
     *   腾讯混元、坚果云那类「能接收任何文件、但没有任何查看能力」的应用——
     *    过去把它们当通配档保留，用户看到的就是「打开方式里一堆打不开的软件」。
     *
     * `declared == null`（拿不到 filter，个别 ROM 不回填）按 `3` 处理，宁可多列也不误伤。
     */
internal fun declaredTypeLevel(
        declared: List<String>?,
        targets: List<String>,
        generic: Boolean,
        filterMissing: Boolean = declared == null
    ): Int {
        if (filterMissing || declared == null) return 3
        if (declared.isEmpty()) return 0
        var wildcard = false
        var onlyWildcard = true
        for (raw in declared) {
            val t = normalizeDeclaredType(raw)
            if (t.equals("*/*", true)) {
                wildcard = true
                continue
            }
            onlyWildcard = false
            for (target in targets) {
                if (t.equals(target, true)) return 3
                // filter「audio/mpeg」命中目标「audio 大类」
                if (target.endsWith("/*") && t.startsWith(target.dropLast(1), true)) return 3
                // filter「text 大类」命中目标「text/plain」；通用目标不收这种宽声明
                if (!generic && t.endsWith("/*") && target.startsWith(t.dropLast(1), true)) return 3
            }
        }
        if (wildcard) return if (onlyWildcard) 2 else 1
        return 0
    }


    /**
     * typesIterator() 吐出来的通配类型是「裸前缀」：audio 大类就是 audio、
     * 任意类型就是单个星号，只有精确类型才带斜杠（application/ogg 这类）。
     * 必须把裸前缀补成「前缀 + 斜杠 + 星号」再参与匹配，否则声明了
     * audio / text 大类的应用会被「以斜杠星号结尾」的判定整批误杀——
     * 表现就是打开方式里真播放器全部消失，只剩个别碰巧声明了具体类型
     * 的应用（3.9.18 修）。
     */
internal fun normalizeDeclaredType(raw: String): String {
        val t = raw.trim()
        return when {
            t.isEmpty() -> "*/*"
            '/' in t -> t
            else -> t + "/*"
        }
    }


internal fun AppViewModel.openLocalIntent(file: FileEntry, app: String = "") {
        val context = getApplication<Application>()
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(file.path))
            // 与「打开方式 / 默认打开方式」共用同一套判定：MIME 没信息量时按后缀名判。
            // 否则给目标应用发过去的是 application/octet-stream，播放器认不出是音频，
            // 不会自动播放，不少应用更会直接拒绝。
            val concrete = effectiveMime(file)
            // 很多播放器只声明大类（audio/*、video/*），用具体 MIME（audio/flac）去查
            // 系统会认为「没有应用能打开」，于是弹出空的系统选择器。这里两种都试。
            val family = when (file.kind) {
                FileKind.AUDIO -> "audio/*"
                FileKind.VIDEO -> "video/*"
                FileKind.IMAGE -> "image/*"
                else -> concrete
            }
            fun build(type: String) = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                // ClipData 让读权限随 Intent 一起扩散，个别 App（音乐播放器居多）靠它才能读到文件。
                clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (app.isNotBlank()) setPackage(app)
            }
            var intent = build(concrete)
            if (!hasHandler(intent) && family != concrete) intent = build(family)
            if (app.isNotBlank() && hasHandler(intent)) {
                // 指定了应用：单独授权并直接启动，不套 chooser。
                runCatching {
                    context.grantUriPermission(app, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // 显式指定 Activity 组件：MIUI 首次「启动应用」授权弹窗放行后，
                // 能把带数据的原 Intent 送达目标页；只写 setPackage 时偶发退化成
                // 「打开应用首页、丢掉数据」，表现就是进了播放器却不自动播。
                resolveActivityComponent(app, intent)?.let { intent.component = it }
                android.util.Log.i("MFB-OPEN", "launch $app type=${intent.type} component=${intent.component?.flattenToShortString()}")
                runCatching { context.startActivity(intent) }
                    .onFailure { e ->
                        android.util.Log.w("MFB-OPEN", "start $app failed: ${e.javaClass.simpleName} ${e.message}")
                        throw e
                    }
            } else {
                // 指定应用其实打不开这类文件（列表来自分享入口等）时退回系统选择器，
                // 而不是直接报「没有可打开的应用」。
                val chooser = Intent.createChooser(build(family), "打开 ${file.name}").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        }.onFailure {
            state = state.copy(message = "没有可打开「${file.name}」的应用")
        }
    }


    /** 这个 Intent 在当前系统里是否有应用能处理（含已限定包名的情况）。 */
internal fun AppViewModel.hasHandler(intent: Intent): Boolean = runCatching {
        getApplication<Application>().packageManager
            .queryIntentActivities(intent, 0)
            .isNotEmpty()
    }.getOrDefault(false)


    /** 这个包能否处理该 Intent（避免 setPackage 后直接 ActivityNotFound）。 */
internal fun AppViewModel.canPackageHandle(pkg: String, intent: Intent): Boolean = runCatching {
        getApplication<Application>().packageManager
            .queryIntentActivities(intent, 0)
            .any { it.activityInfo?.packageName == pkg }
    }.getOrDefault(false)


internal fun AppViewModel.mimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        // MimeTypeMap 对少数扩展名返回 null，手动补常见音频/视频/文档 MIME。
        val manual = when (ext) {
            "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "wav" -> "audio/x-wav"
            "aac" -> "audio/aac"; "m4a" -> "audio/mp4"; "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"; "wma" -> "audio/x-ms-wma"; "ape" -> "audio/x-ape"
            "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"; "webm" -> "video/webm"; "flv" -> "video/x-flv"
            "ts" -> "video/mp2t"; "m4v" -> "video/mp4"; "3gp" -> "video/3gpp"
            "apk" -> "application/vnd.android.package-archive"
            // 这几类系统的扩展名表里常常查不到，但用户手上一点不少。
            "txt" -> "text/plain"; "log" -> "text/plain"; "ini" -> "text/plain"
            "conf" -> "text/plain"; "md" -> "text/markdown"; "markdown" -> "text/markdown"
            "json" -> "application/json"; "xml" -> "application/xml"
            "csv" -> "text/csv"; "html" -> "text/html"; "htm" -> "text/html"
            "7z" -> "application/x-7z-compressed"; "rar" -> "application/x-rar-compressed"
            "tar" -> "application/x-tar"; "gz" -> "application/gzip"
            "epub" -> "application/epub+zip"
            else -> null
        }
        return manual ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }


    /**
     * 交给外部应用的 MIME。
     *
     * 上游给的 mime 常常只是 application/octet-stream（群晖下载接口、SMB/FTP 列表），
     * 直接透传会让播放器「不知道这是音频」而拒绝自动播放，弹出的打开方式列表也会
     * 混进大量通配应用。凡是没信息量的 MIME 一律按后缀名重判（3.9.18）。
     */
internal fun AppViewModel.effectiveMime(file: FileEntry): String {
        val mime = file.mime.substringBefore(';').trim().lowercase()
        return if (isGenericMime(mime)) mimeFromName(file.name) else mime
    }


internal fun AppViewModel.openWithSystem(file: FileEntry) {
        requestOpen(file)
    }
