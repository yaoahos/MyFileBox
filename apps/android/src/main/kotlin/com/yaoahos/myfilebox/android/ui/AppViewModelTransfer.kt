package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.displayLeaf
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
import kotlinx.coroutines.NonCancellable
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


internal fun AppViewModel.openTransferCenter() {
        detectMissingFiles()
        state = state.copy(transferCenterOpen = true)
    }


internal fun AppViewModel.closeTransferCenter() {
        state = state.copy(transferCenterOpen = false)
    }


internal fun AppViewModel.clearTransferHistory() {
        transfers = transfers.filter { it.status == TransferStatus.RUNNING || it.status == TransferStatus.PAUSED }
        persistTransfers()
    }


    /** 暂停传输：取消协程但保留记录与已传文件，之后可恢复。 */
internal fun AppViewModel.pauseTransfer(id: String) {
        TransferCancellation.request(id)
        transferJobs.remove(id)?.cancel()
        childControls.remove(id)
        updateTransfer(id) { task ->
            if (task.status == TransferStatus.RUNNING) {
                task.copy(status = TransferStatus.PAUSED, error = "已暂停")
            } else task
        }
        persistTransfers()
        val app = getApplication<Application>()
        // 真正停下来：更新通知 + 没有其它任务就摘掉前台服务。
        transfers.firstOrNull { it.id == id }?.takeIf { !it.quiet }?.let {
            TransferNotifications.notifyFinal(app, it, replacesProgress = !hasOtherRunning(id))
        }
        stopTransferServiceIfIdle(app)
        val pausedMode = transfers.firstOrNull { it.id == id }?.mode
        val pauseVerb = when (pausedMode) {
            TransferMode.UPLOAD -> "上传"
            TransferMode.COPY -> "复制"
            TransferMode.MOVE -> "移动"
            else -> "下载"
        }
        state = state.copy(message = "已暂停$pauseVerb")
    }


    /**
     * 应用重启或界面重建后，内存里的传输请求会丢失（记录本身还在磁盘上）。
     * 这里按记录里持久化的源/目的端字段把请求重建出来，恢复按钮才不会变成死路。
     */
/** 下载任务真正的落地目录：优先用记录里的 to；老记录再按 target 与子项数量回推。 */
internal fun downloadDirOf(task: TransferTask): String? {
    val raw = task.to.trim()
    if (raw.startsWith("/")) return raw.trimEnd('/').ifBlank { "/" }
    val target = task.target.takeIf { it.startsWith("/") } ?: return null
    return if (task.children.isNotEmpty() || task.total > 1) {
        target.trimEnd('/').ifBlank { "/" }
    } else {
        target.substringBeforeLast('/').takeIf { it.isNotBlank() }
    }
}

internal fun AppViewModel.rebuildTransferContext(task: TransferTask): Pair<TransferRequest, Destination>? {
        val sources = task.sources.filter { it.isNotBlank() }
        if (sources.isEmpty()) return null
        val items = sources.map { path ->
            val name = path.trimEnd('/').substringAfterLast('/').ifBlank { task.title }
            // 能反推出大小就带上：恢复时「同名同大小即已完成」全靠这个值，
            // 缺了它会把已经下完的文件当成半截删掉重下一遍。
            val known = task.children
                .firstOrNull { it.name == name || it.key == name || it.key.endsWith("/$name") }
                ?.sizeBytes?.takeIf { it > 0L }
                ?: (if (sources.size == 1) {
                    File(task.target).takeIf { it.isFile }?.length()?.takeIf { it > 0L }
                } else null)
                ?: 0L
            FileEntry(
                id = path, name = name, kind = FileKinds.of(name),
                sizeLabel = "", timeLabel = "", folder = "", path = path,
                sizeBytes = known, modified = 0L
            )
        }
        return when (task.mode) {
            TransferMode.DOWNLOAD -> {
                val ctx = runCatching { FileContext.valueOf(task.sourceCtx) }.getOrNull() ?: return null
                if (ctx == FileContext.LOCAL) return null
                val destPath = downloadDirOf(task)
                    ?: state.prefs.downloadFolder.ifBlank { "/sdcard/Download" }
                val dest = Destination(DestType.LOCAL, "", "本地", "下载位置", destPath)
                TransferRequest(
                    mode = TransferMode.DOWNLOAD, items = items, ctx = ctx,
                    netId = task.sourceNet.takeIf { it.isNotBlank() }, destinations = listOf(dest)
                ) to dest
            }
            TransferMode.UPLOAD -> {
                val type = when (task.targetCtx) {
                    FileContext.SYNO.name -> DestType.SYNO
                    FileContext.NET.name -> DestType.NET
                    else -> return null
                }
                // launchTransferTask 把目的目录记在 to 里；空值或被填成标签名时退回 target 的父目录。
                val raw = task.to.trim()
                val rootDir = when {
                    raw.isBlank() || raw == "本地" || raw == "群晖" || raw == "网络" ->
                        task.target.substringBeforeLast('/')
                    else -> raw
                }
                val dest = Destination(
                    type = type, key = task.targetNet,
                    label = if (type == DestType.SYNO) "群晖" else "网络",
                    meta = "上传位置", path = rootDir
                )
                TransferRequest(
                    mode = TransferMode.UPLOAD, items = items, ctx = FileContext.LOCAL,
                    netId = null, destinations = listOf(dest)
                ) to dest
            }
            else -> null
        }
    }


    /** 恢复传输：从上次进度继续。目前支持从暂停/取消状态重新发起。 */
internal fun AppViewModel.resumeTransfer(task: TransferTask) {
        var restartFromZero = false
        val request: TransferRequest
        val destination: Destination
        val cachedRequest = transferRequests[task.id]
        val cachedDestination = transferDestinations[task.id]
        if (cachedRequest != null && cachedDestination != null) {
            request = cachedRequest
            destination = cachedDestination
        } else {
            // 请求丢了（重启 / 界面重建）就按记录重建，而不是让用户从头再传一遍。
            val rebuilt = rebuildTransferContext(task)
            if (rebuilt == null) {
                state = state.copy(message = "无法恢复：这条记录的原始位置未保存，请在文件列表里重新发起")
                return
            }
            request = rebuilt.first
            destination = rebuilt.second
            transferRequests[task.id] = request
            transferDestinations[task.id] = destination
            // 上传无法从远端字节偏移续传，重发即从头开始，进度也要跟着归零。
            restartFromZero = task.mode == TransferMode.UPLOAD
        }
        // 保留原任务 id，只重置状态后重新执行。
        updateTransfer(task.id) {
            it.copy(
                status = TransferStatus.RUNNING, error = null, finishedAt = null,
                completed = if (restartFromZero) 0 else it.completed,
                completedBytes = if (restartFromZero) 0L else it.completedBytes
            )
        }
        TransferCancellation.clear(task.id)
        lastTransferError = null
        val job = viewModelScope.launch {
            var reportedTotalBytes = transfers.firstOrNull { it.id == task.id }?.totalBytes ?: 0L
            val ok = withTimeoutOrNull(6 * 60 * 60 * 1000L) {
                withContext(Dispatchers.IO) {
                    // 恢复也必须带上进度回调与 taskId，否则进度条永远不动、暂停也不生效。
                    val progress: (Int) -> Unit = { done ->
                        viewModelScope.launch {
                            updateTransfer(task.id) { it.copy(completed = done) }
                            pushTransferNotification(task.id)
                        }
                    }
                    val total: (Int) -> Unit = { count ->
                        viewModelScope.launch {
                            updateTransfer(task.id) { it.copy(total = count.coerceAtLeast(1)) }
                            pushTransferNotification(task.id)
                        }
                    }
                    val bytes: (Long) -> Unit = { done ->
                        viewModelScope.launch {
                            updateTransfer(task.id) {
                                it.copy(completedBytes = if (reportedTotalBytes > 0L) done.coerceAtMost(reportedTotalBytes) else done.coerceAtLeast(0L))
                            }
                            pushTransferNotification(task.id)
                        }
                    }
                    val totalBytesUpdate: (Long) -> Unit = { sum ->
                        if (sum > 0L) {
                            reportedTotalBytes = sum
                            viewModelScope.launch {
                                updateTransfer(task.id) { it.copy(totalBytes = sum) }
                            }
                        }
                    }
                    when {
                        request.mode == TransferMode.DOWNLOAD ->
                            downloadToLocal(request.items, destination, request.ctx, request.netId, progress, bytes, task.id, total, totalBytesUpdate,
                                onTarget = { path ->
                                    // 恢复后子项可能被改名成「名字 (1)」，target 必须跟着落到真实路径，
                                    // 否则 detectMissingFiles 会拿旧的已删路径把任务误判成「文件丢失」。
                                    if (path.isNotBlank()) updateTransfer(task.id) { it.copy(target = path) }
                                }, resume = true)
                        request.mode == TransferMode.UPLOAD ->
                            uploadToRemote(
                                request.items, destination, progress, total, bytes, totalBytesUpdate,
                                // 恢复上传同样要回写目标：这里以前传的是空回调，
                                // 恢复过的记录 target 永远停在旧值（或被清空），点开定位不到。
                                onTarget = { path ->
                                    if (path.isNotBlank()) updateTransfer(task.id) { it.copy(target = path) }
                                }, taskId = task.id,
                                onUploaded = { path ->
                                    updateTransfer(task.id) {
                                        if (it.uploadedTargets.contains(path)) it
                                        else it.copy(uploadedTargets = it.uploadedTargets + path)
                                    }
                                }
                            )
                        else -> copyOrMove(request, destination)
                    }
                }
            } ?: false
            val status = when {
                TransferCancellation.isCancelled(task.id) -> TransferStatus.PAUSED
                ok -> TransferStatus.SUCCESS
                else -> TransferStatus.FAILED
            }
            val resumeVerb = when (request.mode) {
                TransferMode.UPLOAD -> "上传"
                TransferMode.COPY -> "复制"
                TransferMode.MOVE -> "移动"
                else -> "下载"
            }
            updateTransfer(task.id) {
                it.copy(status = status, finishedAt = System.currentTimeMillis(),
                    error = if (status == TransferStatus.SUCCESS) null else lastTransferError ?: "恢复${resumeVerb}失败")
            }
            persistTransfers()
            if (status == TransferStatus.SUCCESS) {
                state = state.copy(message = "恢复${resumeVerb}完成")
                refreshLocal(); reloadBrowserItems()
            }
        }
        transferJobs[task.id] = job
        job.invokeOnCompletion { transferJobs.remove(task.id) }
    }


    /** 只删除传输记录，不动已下载的文件。暂停任务也要停掉残留协程，避免记录没了还在写。 */
internal fun AppViewModel.deleteTransferRecord(id: String) {
        TransferCancellation.request(id)
        transferJobs.remove(id)?.cancel()
        childControls.remove(id)
        transferRequests.remove(id)
        transferDestinations.remove(id)
        val app = getApplication<Application>()
        TransferNotifications.dismiss(app, id)
        transfers = transfers.filterNot { it.id == id }
        persistTransfers()
        stopTransferServiceIfIdle(app)
        state = state.copy(message = "已删除传输记录")
    }


    /**
     * 删除传输记录 + 对应的文件。
     *
     * - 下载任务：删本机落地文件、半截文件与 `.part` 临时文件；
     * - 上传任务：删远端已上传（或已写了一半）的文件，绝不碰本机源文件；
     * - 复制 / 移动：只删记录，源与目标都不是「这次传输产生的临时物」，不能替用户删。
     *
     * 进行中的任务要先把传输真正停住再删：边写边删只会删掉正在写的那个句柄，
     * 随后又被重建出一份残留（这正是「删了任务还留着 .part」的原因）。
     */
internal fun AppViewModel.deleteTransferFile(task: TransferTask) {
        val job = transferJobs[task.id]
        // 传进来的 task 通常是列表渲染时的快照：上传进度一直在更新 uploadedTargets，
        // 删之前取一次实时记录，否则最近传完的几个文件不在快照里，远端就会漏删。
        val live = transfers.firstOrNull { it.id == task.id } ?: task
        val wasRunning = live.status == TransferStatus.RUNNING || live.status == TransferStatus.PAUSED
        TransferCancellation.request(task.id)
        transferJobs.remove(task.id)?.cancel()
        childControls.remove(task.id)
        val app = getApplication<Application>()
        TransferNotifications.dismiss(app, task.id)
        // 记录先消失，用户不用等网络删除完成。
        transfers = transfers.filterNot { it.id == task.id }
        persistTransfers()
        stopTransferServiceIfIdle(app)
        if (!wasRunning) {
            // 已完成的任务没有协程在写文件，直接清理即可。
            viewModelScope.launch {
                val note = withContext(Dispatchers.IO) { deleteTransferPayload(live) }
                state = state.copy(message = note)
                refreshBrowserAfterDelete(live)
            }
            return
        }
        viewModelScope.launch {
            // 等传输协程真正退出（最多 10 秒），再去删文件。
            withTimeoutOrNull(10_000) { job?.join() }
            var note = withContext(Dispatchers.IO) { deleteTransferPayload(live) }
            // 阻塞式网络 IO 有时会晚一拍才把最后一块写进 .part，
            // 删完再核一次，避免「删了任务又冒出一个半截文件」。
            if (live.mode == TransferMode.DOWNLOAD) {
                val paths = downloadCleanupPaths(live)
                for (round in 0 until 3) {
                    delay(700)
                    val gone = withContext(Dispatchers.IO) {
                        paths.files.none { it.exists() } && paths.parts.none { it.exists() }
                    }
                    if (gone) break
                    note = withContext(Dispatchers.IO) { deleteTransferPayload(live) }
                }
            }
            // 上传侧同理：服务端关闭写句柄比客户端断开晚 1~2 秒，SMB / FTP 会直接拒绝
            // 这次 DELE。以前只有下载有复核，上传删失败就永久留着整批文件。
            if (live.mode == TransferMode.UPLOAD && note.contains("失败")) {
                for (round in 0 until 3) {
                    delay(800)
                    note = withContext(Dispatchers.IO) { deleteTransferPayload(live) }
                    if (!note.contains("失败")) break
                }
            }
            state = state.copy(message = note)
            refreshBrowserAfterDelete(live)
        }
    }


    /**
     * 删掉文件后同步刷新正在看的那个目录。
     *
     * 之前远端文件已经删了，但列表还是进入时的快照，用户必须退出再进来才能看到变化，
     * 看起来就像「没删掉」。这里只刷与任务同源的目录，避免误刷到别的连接。
     */
internal suspend fun AppViewModel.refreshBrowserAfterDelete(task: TransferTask) {
        if (task.mode == TransferMode.DOWNLOAD) {
            refreshLocal()
            return
        }
        if (task.mode != TransferMode.UPLOAD) return
        val browser = state.browser ?: return
        val sameContext = when (task.targetCtx) {
            FileContext.SYNO.name -> browser.ctx == FileContext.SYNO
            FileContext.NET.name -> browser.ctx == FileContext.NET && browser.netId == task.targetNet
            else -> false
        }
        if (sameContext) reloadBrowserItems()
    }


    /**
     * 删除远端指定路径。用于清理上传中断后留在远端的半截文件。
     *
     * 刻意不抛异常，只用返回值表达结果：清理是尽力而为，失败不应该让「暂停 / 取消」
     * 这个动作本身看起来失败了。调用方在已取消的协程里调用它时请包 NonCancellable。
     */
internal suspend fun AppViewModel.deleteRemoteAt(destination: Destination, paths: List<String>): Boolean {
        if (paths.isEmpty()) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                when (destination.type) {
                    DestType.SYNO -> state.session?.let { dsm.delete(it, paths) }
                    DestType.NET -> state.connections
                        .firstOrNull { it.id == destination.key }
                        ?.let { network.delete(it, paths) }
                    else -> null
                }
            }.getOrNull() ?: false
        }
    }


    /**
     * 回收上传留下的远端空目录（尽力而为），返回真正删掉的个数。
     *
     * 只删「列一遍确实是空」的目录、由深到浅逐个来；列不出来（连接已失效、
     * 权限不足）就跳过——宁可留一个空目录，也不能凭猜删除用户的数据。
     */
internal suspend fun AppViewModel.removeEmptyRemoteDirs(task: TransferTask, destination: Destination): Int {
        val dirs = uploadDirCleanupPaths(task)
        if (dirs.isEmpty()) return 0
        var removed = 0
        for (dir in dirs) {
            val empty = withContext(Dispatchers.IO) {
                runCatching {
                    when (destination.type) {
                        DestType.SYNO -> state.session?.let { dsm.list(it, dir) }?.isEmpty()
                        DestType.NET -> state.connections.firstOrNull { it.id == destination.key }
                            ?.let { network.list(it, dir) }?.isEmpty()
                        else -> null
                    }
                }.getOrNull()
            } ?: false
            if (!empty) continue
            if (deleteRemoteAt(destination, listOf(dir))) removed++
        }
        return removed
    }


    /** 真正落地删除，返回给用户的提示文案。 */
internal suspend fun AppViewModel.deleteTransferPayload(task: TransferTask): String = withContext(Dispatchers.IO) {
        when (task.mode) {
            TransferMode.UPLOAD -> {
                // 远端已上传（含写了一半）的文件。正常情况下 uploadedTargets 是写文件前
                // 就登记好的；但它只在进度回调里累加，老记录 / 刚恢复过的记录可能是空的，
                // 那时退回子项目标（每个文件实际落到的远端路径），否则会「记录删了、远端一份没清」。
                val paths = uploadCleanupTargets(task)
                if (paths.isEmpty()) return@withContext "已删除记录（没有可删除的远端文件）"
                // 端与连接都按记录来：老记录可能没写 targetCtx / targetNet，
                // 这时用 remoteConnectionIdFor 的单连接兜底，绝不拿当前页的连接。
                val targetCtx = targetContextOf(task)
                val netKey = remoteConnectionIdFor(task).orEmpty()
                val destination = Destination(
                    type = if (targetCtx == FileContext.SYNO) DestType.SYNO else DestType.NET,
                    key = netKey, label = "远端", meta = "", path = task.to
                )
                val ok = when (targetCtx) {
                    FileContext.SYNO -> state.session?.let { dsm.delete(it, paths) } ?: false
                    FileContext.NET -> {
                        state.connections.firstOrNull { it.id == netKey }?.let { conn ->
                            network.delete(conn, paths)
                        } ?: false
                    }
                    else -> false
                }
                // 文件删掉后，上传时顺带建出来的目录会留在远端成了空壳。
                // 只回收确认是空的目录，非空的一概不碰（SMB 的 rmdir 会递归删）。
                if (ok) {
                    // 空目录回收是删除记录应做的收尾，不在顶部提示里单独报数。
                    removeEmptyRemoteDirs(task, destination)
                    "已删除记录和远端文件 · ${paths.size} 个"
                } else {
                    "记录已删除，远端文件删除失败"
                }
            }
            TransferMode.DOWNLOAD -> {
                val paths = downloadCleanupPaths(task)
                // 只有空目录的下载任务没有任何 files / parts，不能因此跳过空目录回收。
                if (paths.files.isEmpty() && paths.parts.isEmpty() && paths.dirs.isEmpty())
                    return@withContext "已删除记录"
                var deleted = 0
                // 只删文件：任务 target 在文件夹下载时是下载目录，绝不能整目录清空。
                paths.files.forEach { file ->
                    runCatching { if (file.isFile && file.delete()) deleted++ }
                }
                paths.parts.forEach { part ->
                    runCatching { if (part.isFile) part.delete() }
                }
                // 回收本次任务新建的空目录：由深到浅，只删真正空了的，
                // 且目标目录本身永远不删（那是用户选的下载位置）。
                paths.dirs.forEach { dir ->
                    runCatching {
                        if (!dir.isDirectory) return@runCatching
                        // list() 在 MIUI 的 FUSE 上偶发返回 null（目录明明空着），
                        // 早先按「null != 空」直接跳过，空文件夹就一直留着。
                        // 这里放宽到「读不到也尝试删」：File.delete() 对非空目录恒为
                        // false，不会删掉用户的东西，最坏就是删不掉。
                        val children = dir.list()
                        if (children == null || children.isEmpty()) dir.delete()
                    }
                }
                if (deleted > 0 || paths.parts.isNotEmpty()) "已删除记录和文件" else "已删除记录"
            }
            else -> "已删除记录"
        }
    }


/**
 * 远端路径统一成绝对形式。
 *
 * 目录选择器回来的 path 有时不带前导 `/`（记录里就成了「Backup/Temp/Temp」）。而所有
 * 「这是不是远端路径」的判断都靠 `startsWith("/")`，缺了它：任务打开时推不出目标文件夹
 * （`remoteTransferTargetFolder` 直接判 null）、子项目标被整批丢弃（`remoteChildrenFolder`
 * 返回 null）、上传记录里的 target 也存成相对路径。根因就在这里，兜底只会各补一层。
 */
internal fun normalizeRemotePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        // WebDAV 的远端路径是绝对 URL：加前导 `/` 会把它变成 `/https://…`，
        // 上传与删除全部失效；这类路径原样保留，只去掉尾部斜杠。
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return trimmed.trimEnd('/')
        }
        if (trimmed == "/") return "/"
        val absolute = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return absolute.trimEnd('/').ifBlank { "/" }
    }


/**
 * 上传记录在远端要清掉的文件路径。
 *
 * 主路径是 `uploadedTargets`——它在每个文件开始写之前就登记，连写了一半的也算在内。
 * 这个集合只在进度回调里累加，老版本记录、恢复过的记录、以及删除时拿到的旧快照都可能
 * 是空的；那时退回子项 target（每个文件实际落到的远端路径），单文件任务再退回 target。
 *
 * 刻意不用子项兜底去覆盖非空的情况：`uploadedTargets` 是「本次任务写的全部路径」，
 * 子项里可能混进同名跳过的项，扩大删除面就等于删用户自己的文件。
 */
internal fun uploadCleanupTargets(task: TransferTask): List<String> {
        if (task.mode != TransferMode.UPLOAD) return emptyList()
        val fallback = if (task.uploadedTargets.isEmpty()) {
            task.children.mapNotNull { child -> child.target.takeIf { it.isNotBlank() } } +
                listOfNotNull(task.target.takeIf { it.isNotBlank() && task.children.isEmpty() })
        } else {
            emptyList()
        }
        // 老记录里这两处都可能是相对路径：删除前统一补成绝对形式，
        // 否则群晖 / FTP 会拿到「Backup/Temp/x.apk」直接失败，文件根本删不掉。
        return (task.uploadedTargets + fallback)
            .map { normalizeRemotePath(it) }
            .distinct()
    }


    /** 重新下载（用于文件丢失的记录）：从原位置重新拉一份。 */
internal fun AppViewModel.redownloadTransfer(task: TransferTask) {
        if (task.mode != TransferMode.DOWNLOAD) {
            state = state.copy(message = "只有下载记录可以重新下载")
            return
        }
        // 同一会话里还留着原始请求就复用；冷启动后请求已丢，靠记录里持久化的源路径重建。
        // 旧实现在「请求已不存在」时把 task.targetCtx 当成源端，而下载记录的 targetCtx
        // 永远是 LOCAL，于是走到「无法重新下载」；更糟的是先删记录再调 performTransfer
        // （此时 state.transfer 为 null 直接 return），结果记录没了、文件也没下。
        val request = transferRequests[task.id]
        val destination = transferDestinations[task.id]
        val ctx = request?.ctx ?: runCatching { FileContext.valueOf(task.sourceCtx) }.getOrNull()
        val netId = request?.netId ?: task.sourceNet.takeIf { it.isNotBlank() }
        val items = request?.items ?: task.sources.map { path ->
            val name = path.trimEnd('/').substringAfterLast('/').ifBlank { task.title }
            FileEntry(
                id = path, name = name, kind = FileKinds.of(name),
                sizeLabel = "", timeLabel = "", folder = "", path = path,
                sizeBytes = 0L, modified = 0L
            )
        }
        if (ctx == null || ctx == FileContext.LOCAL || items.isEmpty()) {
            state = state.copy(
                message = "无法重新下载：这条记录的原始位置未保存（旧版本记录），请在文件列表里重新下载"
            )
            return
        }
        // 回到原来那个下载目录；取不到就用设置的默认下载目录。
        val destPath = destination?.path?.takeIf { it.isNotBlank() }
            ?: downloadDirOf(task)
            ?: state.prefs.downloadFolder.ifBlank { "/sdcard/Download" }
        val dest = Destination(
            type = DestType.LOCAL, key = "", label = "本地",
            meta = "下载位置", path = destPath
        )
        // 旧记录（文件丢失）先移除，新任务以「进行中」出现在传输中心，进度可见。
        transfers = transfers.filterNot { it.id == task.id }
        state = state.copy(message = "重新下载 · ${task.title}")
        persistTransfers()
        launchTransferTask(
            mode = TransferMode.DOWNLOAD,
            title = task.title,
            detail = "重新下载",
            itemCount = items.size,
            byteCount = items.sumOf { it.sizeBytes.coerceAtLeast(0L) },
            verb = "下载",
            from = task.from,
            to = destPath,
            targetCtx = FileContext.LOCAL.name,
            sources = items.map { it.path },
            // 顶层条目沿用原记录：重新下载一个文件夹时不能把夹内文件当成「多个条目」。
            sourceItems = task.sourceItems.ifEmpty { task.sources },
            sourceCtx = ctx.name,
            sourceNet = netId ?: "",
            // 文件 / 文件夹的标记要跟着记录走，否则重新下载后退化成「单文件」，
            // 点击记录就不会再打开目标文件夹了。
            folderTask = task.folderTask || task.children.isNotEmpty(),
            carryChildren = task.children
        ) { reporter ->
            downloadToLocal(
                items = items,
                destination = dest,
                ctx = ctx,
                netId = netId,
                onProgress = { reporter.progress(it) },
                onBytes = { reporter.bytes(it) },
                taskId = reporter.id,
                onTotal = { reporter.total(it) },
                onTotalBytes = { reporter.totalBytes(it) },
                onTarget = { reporter.target(it) }
            )
        }
    }


    /**
     * 把一条记录标记为「文件丢失」，供点击打开时发现目标已不存在时调用。
     * 只改状态与错误说明，不动产物路径 / 子项，方便之后「重新下载」复用原目标。
     * 已是 MISSING 时不重复写，避免无谓的持久化。
     */
internal fun AppViewModel.markTransferMissing(taskId: String) {
        val task = transfers.firstOrNull { it.id == taskId } ?: return
        if (task.status == TransferStatus.MISSING) return
        updateTransfer(taskId) {
            it.copy(status = TransferStatus.MISSING, error = "文件已丢失或被删除")
        }
        persistTransfers()
    }


    /** 检测已下载文件是否被外部删除，更新记录状态为 MISSING。 */
internal fun AppViewModel.detectMissingFiles() {
        // 每条成功的下载记录都要 stat 一次目标文件（模拟存储上每次数毫秒）。记录一多，
        // 这一步放在 UI 线程就会让「进传输中心」卡一下——与回收站读清单是同一类问题，
        // 因此只取快照，判断放到 IO，回来再改状态。
        val candidates = transfers.filter {
            it.status == TransferStatus.SUCCESS &&
                it.mode == TransferMode.DOWNLOAD &&
                it.target.isNotBlank()
        }
        if (candidates.isEmpty()) return
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) {
                candidates.filter { !File(it.target).exists() }.map { it.id }.toSet()
            }
            if (missing.isEmpty()) return@launch
            transfers = transfers.map { task ->
                if (task.id in missing && task.status == TransferStatus.SUCCESS) {
                    task.copy(status = TransferStatus.MISSING, error = "文件已丢失或被删除")
                } else task
            }
            persistTransfers()
        }
    }


/**
 * 点击传输记录 / 通知：文件夹进目录，文件按应用自己的打开方式打开。
 *
 * 位置只有一处来源 `transferTargetPathOf`（与详情里的「目标」完全一致）。
 * 进来之后就是一次普通页面切换：不记「来自传输记录」，切底栏也不会被拉回这里。
 */
internal fun AppViewModel.openTransferTask(task: TransferTask) {
        if (task.status != TransferStatus.SUCCESS) {
            state = state.copy(
                transferCenterOpen = true,
                message = when (task.status) {
                    TransferStatus.RUNNING, TransferStatus.QUEUED -> "该传输仍在进行中，点开可看逐文件进度"
                    TransferStatus.PAUSED -> "该传输已暂停，长按记录可恢复或重试"
                    else -> "该传输未完成，长按记录可重试"
                }
            )
            return
        }
        val raw = transferTargetPathOf(task).trim()
        if (raw.isBlank()) {
            state = state.copy(message = "${task.title} · ${task.detail}")
            return
        }
        // 目标端按记录来，绝不拿当前浏览页的端去猜：在本地栏点开一条群晖上传记录，
        // 也必须按群晖打开。
        val ctx = targetContextOf(task) ?: FileContext.LOCAL
        val netId = if (ctx == FileContext.NET) remoteConnectionIdFor(task) else null
        val path = if (ctx == FileContext.LOCAL) raw.trimEnd('/') else normalizeRemotePath(raw)
        val isFolder = transferIsFolderTask(task)
        // 本地产物已被删掉：标记丢失并说明，不跳到一个空目录里再报「打不开」。
        if (ctx == FileContext.LOCAL && !File(path).exists()) {
            markTransferMissing(task.id)
            state = state.copy(transferCenterOpen = false, message = "「${task.title}」已被移动或删除")
            return
        }
        // 文件：不跳目录、也不关传输页，就地弹「打开方式」——记录列表留在原处，
        // 取消或从外部应用回来看到的还是刚才那一屏。
        if (!isFolder) {
            val name = path.substringAfterLast('/').ifBlank { path }
            requestOpen(
                FileEntry(
                    id = path,
                    name = name,
                    kind = FileKinds.of(name),
                    sizeLabel = LocalRepository.formatSize(task.totalBytes),
                    path = path,
                    sizeBytes = task.totalBytes
                ),
                forceCtx = ctx,
                forceNetId = netId
            )
            return
        }
        enterTransferFolder(ctx, netId, path)
    }


/**
 * 直接进入某个绝对目录（传输记录跳转用）。
 *
 * 这就是一次普通进入：目录内容异步加载，上层目录一并摊成返回栈，
 * 所以返回一次退一层、与面包屑一一对应，不会一按就弹回栏首页。
 * 目录已被删除 / 连接不可用时，加载结果为空并在页面上提示，不做额外预检。
 */
internal fun AppViewModel.enterTransferFolder(ctx: FileContext, netId: String?, path: String) {
        val clean = if (ctx == FileContext.LOCAL) path.trim().trimEnd('/') else normalizeRemotePath(path)
        // 端根只对本地有意义：存储根上面是手机系统目录，不该出现在文件树里。
        // 群晖 / 网络端本身就是一级级真实目录（共享名 / 子目录），按路径逐段摊开即可，
        // 不再去比「连接的起始路径」——SMB 存的是 UNC、WebDAV 是绝对 URL，越比越容易整段丢层级。
        val rootPath = if (ctx == FileContext.LOCAL) state.localRoot.trimEnd('/') else ""
        val relative = when {
            rootPath.isBlank() -> clean
            clean == rootPath -> ""
            clean.startsWith("$rootPath/") -> clean.removePrefix(rootPath)
            // 不在存储根下面（例如 SD 卡）：不编造层级，只显示这一层的名字。
            else -> ""
        }
        val segments = relative.split('/').filter { it.isNotBlank() }
        val title = segments.lastOrNull()
            ?: clean.trimEnd('/').substringAfterLast('/').ifBlank { transferCtxLabel(ctx) }
        // 副标题就是面包屑：端名 + 逐级目录。
        fun crumbOf(levels: List<String>) = buildString {
            append(transferCtxLabel(ctx))
            levels.forEach { append(" / ").append(it) }
        }
        fun joinPath(base: String, name: String): String =
            if (base.isBlank() || base == "/") "/$name" else "$base/$name"
        val grid = state.prefs.tabViews[ctx.name.lowercase()] == "grid"
        // 返回栈 = 逐级上层目录（items 留空，回到那一层时再加载）。
        // 以前这里是空栈，于是从记录进来后按一次返回就弹回栏首页，路径链直接断掉。
        val parents = buildList {
            var acc = rootPath
            segments.dropLast(1).forEachIndexed { index, name ->
                acc = joinPath(acc, name)
                add(
                    BrowserState(
                        title = name, path = crumbOf(segments.take(index + 1)), ctx = ctx,
                        items = emptyList(), grid = grid, netId = netId, location = acc
                    )
                )
            }
        }
        val tab = when (ctx) {
            FileContext.LOCAL -> TabKey.LOCAL
            FileContext.SYNO -> TabKey.SYNO
            FileContext.NET -> TabKey.NET
        }
        val targetBrowser = BrowserState(
            title = title,
            path = crumbOf(segments),
            ctx = ctx,
            items = emptyList(),
            grid = grid,
            netId = netId,
            location = clean
        )
        // 把「首页 / 各级父目录 / 目标」整条 pageKey 交给转场宿主重建页面栈。
        // 少了这一步，深页 key 若以前访问过会被判成返回（从左边进），
        // 而返回的父级没入栈又被判成前进（从右边进）——就是「一会左一会右」。
        val pageChain = buildList {
            add("home/${tab.name}")
            parents.forEach { add(browserKeyOf(it)) }
            add(browserKeyOf(targetBrowser))
        }
        state = state.copy(
            tab = tab,
            transferCenterOpen = false,
            transfer = null,
            browser = targetBrowser,
            pendingPageChain = pageChain,
            browserStack = parents,
            selected = emptySet(),
            selectionActive = false,
            homeSelected = emptySet(),
            homeSelecting = false,
            search = null,
            nameFilter = "",
            extensionFilter = "",
            navDir = 1,
            loading = true
        )
        viewModelScope.launch {
            reloadBrowserItems()
            if (state.browser?.location == clean) state = state.copy(loading = false)
        }
    }


private fun AppViewModel.transferCtxLabel(ctx: FileContext): String = when (ctx) {
        FileContext.LOCAL -> "内部存储"
        FileContext.SYNO -> "群晖 NAS"
        FileContext.NET -> "网络存储"
    }

/**
 * 记录的目标端。
 *
 * 新记录一定带 `targetCtx`。老记录可能为空，这时只有「写了 targetNet」能确定是网络端；
 * 其余情形一律当成「推不出来」，由调用方给提示，而不是拿当前浏览页的端去猜——
 * 猜错的代价就是把群晖的记录打开成 SMB 里的同名目录。
 */
internal fun AppViewModel.targetContextOf(task: TransferTask): FileContext? = when (task.targetCtx) {
        FileContext.SYNO.name -> FileContext.SYNO
        FileContext.NET.name -> FileContext.NET
        FileContext.LOCAL.name -> null
        else -> if (task.targetNet.isNotBlank()) FileContext.NET else null
    }


/**
 * 记录的目标网络连接 id。
 *
 * 严格用 `targetNet`。老记录没写时，只有在设备上**只有一条**网络连接的情况下才兜底——
 * 那条连接是唯一正确答案；用 `sourceNet` 兜底等于拿源端当目标端，用当前页的连接兜底
 * 则完全取决于用户此刻停在哪一栏，两者都是「点开记录打开到别处」的来源。
 */
internal fun AppViewModel.remoteConnectionIdFor(task: TransferTask): String? {
        task.targetNet.takeIf { it.isNotBlank() }?.let { return it }
        return state.connections.singleOrNull()?.id
    }


internal fun AppViewModel.fileEntryAt(path: String): FileEntry {
        val file = File(path)
        val local = file.exists()
        val name = file.name
        return FileEntry(
            id = path,
            name = name,
            kind = if (local && file.isDirectory) FileKind.FOLDER else FileKinds.of(name),
            sizeLabel = if (local && file.isDirectory) "文件夹"
                else LocalRepository.formatSize(if (local) file.length() else 0L),
            timeLabel = if (local) LocalRepository.formatTime(file.lastModified()) else "",
            folder = localFolderLabel(path),
            path = path,
            sizeBytes = if (local) file.length() else 0L,
            modified = if (local) file.lastModified() else 0L
        )
    }


internal fun AppViewModel.performTransfer(destination: Destination) {
        val request = state.transfer ?: return
        // 复制 / 移动只能在同一端内落到 copyOrMove()。即使 UI 被绕过或目标列表
        // 来自旧状态，也不能让跨端的 COPY/MOVE 悄悄执行成「复制」。
        if (request.mode == TransferMode.COPY || request.mode == TransferMode.MOVE) {
            val sameSide = when (request.ctx) {
                FileContext.LOCAL -> destination.type == DestType.LOCAL
                FileContext.SYNO -> destination.type == DestType.SYNO
                FileContext.NET -> destination.type == DestType.NET && destination.key == request.netId
            }
            if (!sameSide) {
                state = state.copy(message = "跨端文件只能用上传 / 下载传输，粘贴仅支持同一端内")
                return
            }
        }
        if ((request.mode == TransferMode.DOWNLOAD && destination.type != DestType.LOCAL) ||
            (request.mode == TransferMode.UPLOAD && destination.type == DestType.LOCAL)) {
            state = state.copy(message = "目标位置与传输方式不匹配，请重新选择")
            return
        }
        lastTransferError = null
        val app = getApplication<Application>()
        // 远端根目录的 path 常常是空串：记录里「目标」就变成空的了。统一写成 "/"，
        // 去重与清理都按同一个口径比较。
        val destPath = destination.path.ifBlank {
            if (destination.type == DestType.LOCAL) destination.label else "/"
        }.let { raw -> if (destination.type == DestType.LOCAL) raw else normalizeRemotePath(raw) }
        // 去重：连点两次「上传 / 下载」不应该建两条任务把同一个文件传两遍。
        val firstTitle = request.items.firstOrNull()?.name ?: ""
        val duplicated = transfers.firstOrNull {
            (it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED) &&
                !it.quiet && it.mode == request.mode &&
                it.to == destPath && it.title == firstTitle &&
                System.currentTimeMillis() - it.startedAt < 3_000L
        }
        if (duplicated != null) {
            state = state.copy(transfer = null, message = "该传输已经在进行中，不用重复提交")
            return
        }
        val quiet = request.mode == TransferMode.COPY || request.mode == TransferMode.MOVE
        val sourceLabel = sourceLabelOf(request)
        val startVerb = when (request.mode) {
            TransferMode.DOWNLOAD -> "下载"
            TransferMode.UPLOAD -> "上传"
            TransferMode.COPY -> "复制"
            TransferMode.MOVE -> "移动"
        }

        // 多选：一个条目一条记录（M 个文件夹 + N 个文件 = M+N 条）。
        // 执行按顺序一条条来，进度、暂停、删除都各管自己那一条。
        if (!quiet && request.items.size > 1) {
            val parts = request.items.map { request.copy(items = listOf(it)) }
            // 选择状态只清一次；后续每条记录由自己负责。
            state = state.copy(
                transfer = null,
                loading = false,
                transferCenterOpen = false,
                selected = emptySet(),
                selectionActive = false,
                searchSelected = emptySet(),
                searchSelecting = false
            )
            val created = parts.map { part ->
                val task = buildTransferTask(part, destination, destPath, sourceLabel, TransferStatus.QUEUED)
                transferRequests[task.id] = part
                transferDestinations[task.id] = destination
                task
            }
            // 保持用户选择时的顺序；上限按本批条数放宽，避免刚建出来的记录被截掉。
            transfers = (created + transfers).take(maxOf(30, created.size))
            persistTransfers()
            TransferNotifications.ensureChannel(app)
            state = state.copy(message = "已加入${startVerb}队列 · ${created.size} 项")
            viewModelScope.launch {
                created.forEach { task ->
                    // 排队期间用户可能删除 / 暂停了这条记录：跳过，不再执行。
                    val live = transfers.firstOrNull { it.id == task.id } ?: return@forEach
                    if (live.status != TransferStatus.QUEUED) return@forEach
                    updateTransfer(task.id) { it.copy(status = TransferStatus.RUNNING) }
                    startTransferService(app, task)
                    val part = transferRequests[task.id] ?: return@forEach
                    startTransferWork(live.copy(status = TransferStatus.RUNNING), part, destination)?.join()
                }
            }
            return
        }

        val task = buildTransferTask(request, destination, destPath, sourceLabel, TransferStatus.RUNNING)
        transferRequests[task.id] = request
        transferDestinations[task.id] = destination
        if (!quiet) {
            transfers = (listOf(task) + transfers).take(30)
            persistTransfers()
            TransferNotifications.ensureChannel(app)
            startTransferService(app, task)
        }
        state = state.copy(
            transfer = null,
            loading = false,
            transferCenterOpen = false,
            selected = emptySet(),
            selectionActive = false,
            // 搜索页发起传输后勾选要收掉，否则回到搜索结果还挂着一批「已选择」。
            searchSelected = emptySet(),
            searchSelecting = false,
            // 同端复制 / 移动不进传输中心，但必须有过程反馈：顶部任务条从第一项开始显示，
            // 否则粘贴几十个文件时界面上什么都没有，只有最后一条「复制完成」。
            localTaskLabel = if (quiet) "${startVerb}中 · ${request.items.size} 项…" else state.localTaskLabel,
            localTaskRunning = if (quiet) true else state.localTaskRunning,
            // 顶部提示用户操作已开始
            message = if (quiet) state.message else "${startVerb}中 · ${task.title}"
        )
        startTransferWork(task, request, destination)
    }


    /** 按一条记录构造任务。一次调用只对应一个顶层条目（多选会在调用方拆开）。 */
private fun AppViewModel.buildTransferTask(
        request: TransferRequest,
        destination: Destination,
        destPath: String,
        sourceLabel: String,
        status: TransferStatus
    ): TransferTask {
        val totalBytes = request.items.filterNot { it.isFolder }.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        return TransferTask(
            id = UUID.randomUUID().toString(),
            mode = request.mode,
            title = request.items.firstOrNull()?.name ?: formatTransferTitle(request.mode),
            detail = destination.label,
            total = request.items.size,
            // 传之前就知道这一趟是文件还是文件夹：点击记录时才知道该开文件还是目录。
            folderTask = request.items.size > 1 || request.items.any { it.isFolder },
            totalBytes = totalBytes,
            completed = 0,
            completedBytes = 0,
            status = status,
            from = sourceLabel,
            to = destPath,
            targetCtx = when {
                request.mode == TransferMode.DOWNLOAD -> FileContext.LOCAL.name
                destination.type == DestType.SYNO -> FileContext.SYNO.name
                destination.type == DestType.NET -> FileContext.NET.name
                else -> FileContext.LOCAL.name
            },
            targetNet = if (destination.type == DestType.NET) destination.key else "",
            sourceCtx = request.ctx.name,
            sourceNet = request.netId ?: "",
            sources = request.items.map { it.path },
            // 顶层条目就是用户勾选的东西：文件夹按文件夹记一条，不展开成夹内文件。
            sourceItems = request.items.map { it.path },
            quiet = request.mode == TransferMode.COPY || request.mode == TransferMode.MOVE
        )
    }


    /**
     * 执行一条记录。
     *
     * 返回 Job：多选拆分后由调用方依次 `join()`，让多条记录串行执行，
     * 避免一次选 20 个文件就同时开 20 路请求。
     */
private fun AppViewModel.startTransferWork(
        task: TransferTask,
        request: TransferRequest,
        destination: Destination
    ): Job? {
        val app = getApplication<Application>()
        val quiet = task.quiet
        val verb = when (request.mode) {
            TransferMode.DOWNLOAD -> "下载"
            TransferMode.UPLOAD -> "上传"
            TransferMode.COPY -> "复制"
            TransferMode.MOVE -> "移动"
        }
        var reportedTarget = ""
        var reportedTotalBytes = task.totalBytes
        val job = viewModelScope.launch {
            val crossEnd = request.mode == TransferMode.COPY &&
                ((request.ctx != FileContext.LOCAL && destination.type == DestType.LOCAL) ||
                    (request.ctx == FileContext.LOCAL && destination.type != DestType.LOCAL))
            val ok = withTimeoutOrNull(6 * 60 * 60 * 1000L) {
                withContext(Dispatchers.IO) {
                    val progress: (Int) -> Unit = { done ->
                        viewModelScope.launch {
                            updateTransfer(task.id) { it.copy(completed = done) }
                            pushTransferNotification(task.id)
                        }
                    }
                    // 文件夹展开后才能知道真实文件数，进度条分母随之更新。
                    val total: (Int) -> Unit = { count ->
                        viewModelScope.launch {
                            updateTransfer(task.id) { it.copy(total = count.coerceAtLeast(1)) }
                            pushTransferNotification(task.id)
                        }
                    }
                    val bytes: (Long) -> Unit = { done ->
                        viewModelScope.launch {
                            updateTransfer(task.id) {
                                it.copy(completedBytes = if (reportedTotalBytes > 0L) done.coerceAtMost(reportedTotalBytes) else done.coerceAtLeast(0L))
                            }
                            pushTransferNotification(task.id)
                        }
                    }
                    // 文件夹上传时总字节数以真实文件为准，否则进度条只有 0% 和 100%。
                    val totalBytesUpdate: (Long) -> Unit = { sum ->
                        if (sum > 0L) {
                            reportedTotalBytes = sum
                            viewModelScope.launch {
                                updateTransfer(task.id) { it.copy(totalBytes = sum) }
                            }
                        }
                    }
                    // 真实落地路径要即时写回任务：删除「进行中」的下载时，
                    // 只能靠它找到半截文件与 .part 临时文件（任务还没成功时 target 是空的）。
                    val target: (String) -> Unit = { path ->
                        reportedTarget = path
                        if (path.isNotBlank()) {
                            viewModelScope.launch {
                                updateTransfer(task.id) { if (it.target != path) it.copy(target = path) else it }
                            }
                        }
                    }
                    val markUploaded: (String) -> Unit = { path ->
                        updateTransfer(task.id) {
                            if (it.uploadedTargets.contains(path)) it
                            else it.copy(uploadedTargets = it.uploadedTargets + path)
                        }
                    }
                    when {
                        crossEnd && destination.type == DestType.LOCAL ->
                            downloadToLocal(request.items, destination, request.ctx, request.netId, progress, bytes, task.id, total, totalBytesUpdate, target)
                        crossEnd -> uploadToRemote(
                            request.items, destination, progress, total, bytes, totalBytesUpdate, target,
                            taskId = task.id, onUploaded = markUploaded
                        )
                        request.mode == TransferMode.DOWNLOAD ->
                            downloadToLocal(request.items, destination, request.ctx, request.netId, progress, bytes, task.id, total, totalBytesUpdate, target)
                        request.mode == TransferMode.UPLOAD ->
                            uploadToRemote(
                                request.items, destination, progress, total, bytes, totalBytesUpdate, target,
                                taskId = task.id, onUploaded = markUploaded
                            )
                        else -> copyOrMove(request, destination)
                    }
                }
            } ?: false
            val current = transfers.firstOrNull { it.id == task.id }
            val cancelled = TransferCancellation.isCancelled(task.id) || current?.status == TransferStatus.PAUSED
            // 统一用 PAUSED 替代 CANCELLED：取消概念已去除。
            val status = when {
                cancelled -> TransferStatus.PAUSED
                ok -> TransferStatus.SUCCESS
                else -> TransferStatus.FAILED
            }
            updateTransfer(task.id) { it.copy(
                completed = if (status == TransferStatus.SUCCESS) it.total else it.completed,
                completedBytes = if (status == TransferStatus.SUCCESS) it.totalBytes else it.completedBytes,
                status = status,
                finishedAt = if (status == TransferStatus.PAUSED) null else System.currentTimeMillis(),
                // 文件夹任务：执行期间 target 被逐文件回调改成夹内某个文件，
                // 这里统一还原成文件夹本身（目标目录 + 文件夹名），详情与点击打开才一致。
                target = when {
                    status != TransferStatus.SUCCESS -> it.target
                    transferIsFolderTask(it) -> transferTargetPathOf(it)
                    else -> reportedTarget.ifBlank { it.target }
                },
                error = when {
                    status == TransferStatus.PAUSED -> "已暂停"
                    status == TransferStatus.SUCCESS -> null
                    else -> lastTransferError ?: "目标位置不可写、连接中断或登录已过期"
                }
            ) }
            state = state.copy(
                loading = false,
                selected = emptySet(),
                selectionActive = false,
                // 只清自己这一路：非 quiet（上传 / 下载）不碰 localTask，
                // 避免把同时进行的解压 / 压缩进度条一起抹掉。
                localTaskLabel = if (quiet) "" else state.localTaskLabel,
                localTaskRunning = if (quiet) false else state.localTaskRunning,
                message = when (status) {
                    TransferStatus.SUCCESS -> "${verb}完成 · ${task.title}"
                    TransferStatus.PAUSED -> "已暂停$verb"
                    else -> "${verb}失败：${cleanError(lastTransferError, verb)}"
                }
            )
            if (!quiet) {
                persistTransfers()
                val finished = transfers.firstOrNull { it.id == task.id }
                val othersRunning = hasOtherRunning(task.id)
                if (finished != null) {
                    TransferNotifications.notifyFinal(app, finished, replacesProgress = !othersRunning)
                }
                stopTransferServiceIfIdle(app)
            }
            TransferCancellation.clear(task.id)
            if (status == TransferStatus.SUCCESS) {
                refreshLocal()
                reloadBrowserItems()
            }
        }
        transferJobs[task.id] = job
        job.invokeOnCompletion { transferJobs.remove(task.id) }
        return job
    }


    /** 底层错误文案常常已经自带「下载失败：」，这里去掉重复前缀再拼接。 */
internal fun AppViewModel.cleanError(error: String?, verb: String): String {
        val raw = error?.takeIf { it.isNotBlank() } ?: return "请检查连接、权限与目标位置"
        return raw.removePrefix("${verb}失败：").removePrefix("$verb 失败：").trim()
    }


    /** 记录的「从哪来」：优先文件所在目录，多选时给出上级目录 + 数量。 */
internal fun AppViewModel.sourceLabelOf(request: TransferRequest): String {
        val root = when (request.ctx) {
            FileContext.LOCAL -> "内部存储"
            FileContext.SYNO -> "群晖 NAS"
            FileContext.NET -> state.connections.firstOrNull { it.id == request.netId }?.name ?: "网络存储"
        }
        val first = request.items.firstOrNull() ?: return root
        val dir = first.path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }
        return if (request.items.size > 1) "$root · $dir（${request.items.size} 项）" else "$root · ${first.path}"
    }


internal fun AppViewModel.pushTransferNotification(id: String) {
        transfers.firstOrNull { it.id == id }?.let {
            TransferNotifications.update(getApplication(), it)
        }
    }


internal fun AppViewModel.cancelTransfer(id: String, fromRegistry: Boolean = false) {
        if (!fromRegistry) TransferCancellation.request(id)
        transferJobs.remove(id)?.cancel()
        updateTransfer(id) { task ->
            if (task.status == TransferStatus.RUNNING) {
                task.copy(
                    status = TransferStatus.PAUSED,
                    error = "已暂停"
                )
            } else task
        }
        persistTransfers()
        val cancelledMode = transfers.firstOrNull { it.id == id }?.mode
        val cancelVerb = when (cancelledMode) {
            TransferMode.UPLOAD -> "上传"
            TransferMode.COPY -> "复制"
            TransferMode.MOVE -> "移动"
            else -> "下载"
        }
        state = state.copy(message = "已暂停$cancelVerb")
        val app = getApplication<Application>()
        val othersRunning = hasOtherRunning(id)
        transfers.firstOrNull { it.id == id }?.takeIf { !it.quiet }?.let {
            TransferNotifications.notifyFinal(app, it, replacesProgress = !othersRunning)
        }
        stopTransferServiceIfIdle(app)
    }


/**
 * 失败 / 已暂停任务的重试统一入口。
 * 下载走 redownloadTransfer（支持断点续传与子项重试重建），上传走 retryUploadTransfer；
 * 两者都优先用内存缓存，缓存丢了（应用重启）就从记录字段重建。
 */
internal fun AppViewModel.retryTransfer(task: TransferTask) {
        if (task.status != TransferStatus.FAILED && task.status != TransferStatus.PAUSED) return
        when (task.mode) {
            TransferMode.DOWNLOAD -> redownloadTransfer(task)
            TransferMode.UPLOAD -> retryUploadTransfer(task)
            else -> state = state.copy(message = "该类型任务不支持重试")
        }
    }


/** 上传失败 / 暂停后重新上传：从缓存或持久化字段重建请求，整批从头重传。 */
internal fun AppViewModel.retryUploadTransfer(task: TransferTask) {
        val request = transferRequests[task.id]
        val destination = transferDestinations[task.id]
        val rebuilt = if (request != null && destination != null) request to destination else rebuildTransferContext(task)
        if (rebuilt == null || rebuilt.first.mode != TransferMode.UPLOAD) {
            state = state.copy(message = "无法重试：这条记录的原始位置未保存，请在文件列表里重新上传")
            return
        }
        val (req, dest) = rebuilt
        transfers = transfers.filterNot { it.id == task.id }
        state = state.copy(message = "重新上传 · ${task.title}")
        persistTransfers()
        launchTransferTask(
            mode = TransferMode.UPLOAD,
            title = task.title,
            detail = "重新上传",
            itemCount = req.items.size,
            byteCount = req.items.sumOf { it.sizeBytes.coerceAtLeast(0L) },
            verb = "上传",
            from = task.from,
            to = normalizeRemotePath(dest.path),
            targetCtx = task.targetCtx,
            targetNet = task.targetNet,
            sources = task.sources,
            // 重新上传沿用上一轮的顶层条目（文件夹仍然是那个文件夹）。
            sourceItems = task.sourceItems.ifEmpty { task.sources },
            sourceCtx = FileContext.LOCAL.name,
            folderTask = task.folderTask || req.items.size > 1 || req.items.any { it.isFolder },
            carryChildren = task.children
        ) { reporter ->
            val ok = uploadToRemote(
                req.items, dest,
                { reporter.progress(it) }, { reporter.total(it) }, { reporter.bytes(it) },
                { reporter.totalBytes(it) }, { reporter.target(it) }, taskId = reporter.id,
                onUploaded = { reporter.uploaded(it) }
            )
            ok
        }
    }


/**
 * 单项重试：只重下 / 重传一个失败的子文件。
 * 用子项记录的源路径重建一个只含该文件的请求，避免整批重来。
 */
internal fun AppViewModel.retryTransferChild(task: TransferTask, child: TransferChild) {
        if (child.status != TransferChildStatus.FAILED) return
        val req = transferRequests[task.id]
        val ctx = req?.ctx ?: runCatching { FileContext.valueOf(task.sourceCtx) }.getOrNull()
        val netId = req?.netId ?: task.sourceNet.takeIf { it.isNotBlank() }
        if (ctx == null || child.path.isBlank()) {
            state = state.copy(message = "无法重试该项：来源信息已丢失，请整批重试")
            return
        }
        val item = FileEntry(
            id = child.path, name = child.name, kind = FileKinds.of(child.name.substringAfterLast('/')),
            sizeLabel = "", timeLabel = "", folder = "", path = child.path,
            sizeBytes = child.sizeBytes, modified = 0L
        )
        if (task.mode == TransferMode.DOWNLOAD && ctx != FileContext.LOCAL) {
            val destPath = transferDestinations[task.id]?.path
                ?: downloadDirOf(task)
                ?: state.prefs.downloadFolder.ifBlank { "/sdcard/Download" }
            val dest = Destination(DestType.LOCAL, "", "本地", "下载位置", destPath)
            launchTransferTask(
                mode = TransferMode.DOWNLOAD, title = child.name, detail = "重试该项",
                itemCount = 1, byteCount = child.sizeBytes, verb = "下载",
                from = task.from, to = destPath, targetCtx = FileContext.LOCAL.name,
                sources = listOf(child.path), sourceCtx = ctx.name, sourceNet = netId ?: "",
                // 单项重试：这一条就是用户看到的那个条目。
                sourceItems = listOf(child.path)
            ) { reporter ->
                downloadToLocal(
                    listOf(item), dest, ctx, netId,
                    { reporter.progress(it) }, { reporter.bytes(it) }, reporter.id,
                    { reporter.total(it) }, { reporter.totalBytes(it) }, { reporter.target(it) }
                )
            }
        } else if (task.mode == TransferMode.UPLOAD) {
            val dest = transferDestinations[task.id] ?: rebuildTransferContext(task)?.second
            if (dest == null) {
                state = state.copy(message = "无法重试该项：上传目标已丢失，请整批重试")
                return
            }
            launchTransferTask(
                mode = TransferMode.UPLOAD, title = child.name, detail = "重试该项",
                itemCount = 1, byteCount = child.sizeBytes, verb = "上传",
                from = task.from, to = dest.path, targetCtx = task.targetCtx, targetNet = task.targetNet,
                sources = listOf(child.path), sourceCtx = FileContext.LOCAL.name,
                sourceItems = listOf(child.path)
            ) { reporter ->
                uploadToRemote(
                    listOf(item), dest,
                    { reporter.progress(it) }, { reporter.total(it) }, { reporter.bytes(it) },
                    { reporter.totalBytes(it) }, { reporter.target(it) }, taskId = reporter.id,
                    onUploaded = { reporter.uploaded(it) }
                )
            }
        } else {
            state = state.copy(message = "该类型任务不支持单项重试")
        }
    }


    /**
     * 暂停单个正在下载 / 等待中的子项：下载中的会被 abortCheck 中断并保留 .part。
     * 控制器随任务进程存活，恢复时由原 worker 续传；控制器已消失（任务已结束）则提示。
     */
internal fun AppViewModel.pauseTransferChild(task: TransferTask, child: TransferChild) {
        if (task.mode != TransferMode.DOWNLOAD) {
            state = state.copy(message = "只有下载任务支持暂停单个文件")
            return
        }
        val ctrl = childControls[task.id]
        if (ctrl == null) {
            state = state.copy(message = "该任务已结束，无法暂停单个文件")
            return
        }
        ctrl.pause(child.key)
        updateTransferChild(task.id, child.key, TransferChildStatus.PAUSED)
        state = state.copy(message = "已暂停「${child.name.substringAfterLast('/')}」")
    }


    /** 恢复单个子项：回到队首并从 .part 续传。控制器已消失时回退为单文件重新下载。 */
internal fun AppViewModel.resumeTransferChild(task: TransferTask, child: TransferChild) {
        if (task.mode != TransferMode.DOWNLOAD) return
        val ctrl = childControls[task.id]
        if (ctrl != null && ctrl.resume(child.key)) {
            updateTransferChild(task.id, child.key, TransferChildStatus.QUEUED, 0L)
            state = state.copy(message = "已恢复「${child.name.substringAfterLast('/')}」，将从断点继续")
        } else {
            // 任务已结束 / 控制器已清理：按单文件重新下载（retryTransferChild 只收 FAILED，
            // 这里临时把子项视为失败项交给它重建请求）。
            retryTransferChild(task, child.copy(status = TransferChildStatus.FAILED))
        }
    }


    /** 提前开始：把等待中（或已暂停）的子项插到队首，下一个空出来的 worker 就会先下它。 */
internal fun AppViewModel.promoteTransferChild(task: TransferTask, child: TransferChild) {
        if (task.mode != TransferMode.DOWNLOAD) return
        val ctrl = childControls[task.id]
        if (ctrl == null || !ctrl.promote(child.key)) {
            state = state.copy(message = "该项已在下载中或任务已结束")
            return
        }
        updateTransferChild(task.id, child.key, TransferChildStatus.QUEUED)
        state = state.copy(message = "已将「${child.name.substringAfterLast('/')}」提到最前")
    }


    /**
     * 统一的后台传输入口。
     *
     * 点击即建任务并立刻出现在「传输 → 进行中」，进度实时更新；结束写入记录并给顶部提示。
     * 页面只负责发起，不再设置全局 loading（旧实现用 loading 转圈，什么都看不到）。
     */
internal fun AppViewModel.launchTransferTask(
        mode: TransferMode,
        title: String,
        detail: String,
        itemCount: Int,
        byteCount: Long,
        verb: String,
        from: String = "",
        to: String = "",
        target: String = "",
        targetCtx: String = "",
        targetNet: String = "",
        sources: List<String> = emptyList(),
        /**
         * 这次传输用户实际选中的顶层条目（文件夹就是文件夹本身）。
         * 详情「来源 / 目标」按它展示，一个条目一条，不展开文件夹里的文件。
         */
        sourceItems: List<String> = emptyList(),
        sourceCtx: String = "",
        sourceNet: String = "",
        /** 本次是否包含文件夹（多选也算）：记录里要存下来，点击记录才知道该开文件还是目录。 */
        folderTask: Boolean = false,
        quiet: Boolean = false,
        /**
         * 「重新下载 / 重试上传」这类沿用上一轮产物的任务要带上旧的逐文件明细：
         * 里面的 target / preExisting 说明哪些文件是本任务自己写的，丢了就分不清
         * 「用户本来就有的同名文件」和「上次已经下好的文件」，取消时后一种会被漏删。
         */
        carryChildren: List<TransferChild> = emptyList(),
        /**
         * 由调用方指定的任务 id。「在线打开」要在起任务前就把 id 登记到可见状态里，
         * 进度上报与取消按钮都靠它关联；普通任务留空即可。
         */
        taskId: String = UUID.randomUUID().toString(),
        onDone: suspend (Boolean) -> Unit = {},
        work: suspend (AppViewModel.TaskReporter) -> Boolean
    ): String {
        val app = getApplication<Application>()
        val task = TransferTask(
            id = taskId,
            mode = mode,
            title = title,
            detail = detail,
            total = itemCount.coerceAtLeast(1),
            totalBytes = byteCount.coerceAtLeast(0L),
            status = TransferStatus.RUNNING,
            from = from,
            to = to,
            target = target,
            targetCtx = targetCtx,
            targetNet = targetNet,
            sourceCtx = sourceCtx,
            sourceNet = sourceNet,
            sources = sources,
            // 没显式给顶层条目时按 sources 兜底：老调用点（重试 / 重新下载）本来
            // 就是一条对一个路径，展示口径与以前一致。
            sourceItems = sourceItems.ifEmpty { sources },
            children = carryChildren.map { it.copy(status = TransferChildStatus.QUEUED, completedBytes = 0L) },
            folderTask = folderTask,
            quiet = quiet
        )
        lastTransferError = null
        // 静默任务（在线打开缓存等）不进传输中心，也不落盘。
        if (!quiet) {
            transfers = (listOf(task) + transfers).take(30)
            // 上传/下载一开始就给顶部提示。之前只更新了任务列表，
            // 从文件页发起的上传没有任何反馈，看起来像点了没反应。
            state = state.copy(message = "${verb}中 · ${title}")
            persistTransfers()
            TransferNotifications.ensureChannel(app)
            startTransferService(app, task)
        }
        val reporter = this.TaskReporter(task.id)
        val job = viewModelScope.launch {
            val ok = runCatching {
                withTimeoutOrNull(6 * 60 * 60 * 1000L) {
                    withContext(Dispatchers.IO) { work(reporter) }
                } ?: false
            }.getOrElse { false }
            val current = transfers.firstOrNull { it.id == task.id }
            val cancelled = TransferCancellation.isCancelled(task.id) ||
                current?.status == TransferStatus.PAUSED
            // 统一用 PAUSED 替代 CANCELLED：取消概念已去除，暂停保留在进行中栏。
            val status = when {
                cancelled -> TransferStatus.PAUSED
                ok -> TransferStatus.SUCCESS
                else -> TransferStatus.FAILED
            }
            updateTransfer(task.id) {
                it.copy(
                    completed = if (status == TransferStatus.SUCCESS) it.total else it.completed,
                    completedBytes = if (status == TransferStatus.SUCCESS) it.totalBytes else it.completedBytes,
                    status = status,
                    finishedAt = if (status == TransferStatus.PAUSED) null else System.currentTimeMillis(),
                    error = when (status) {
                        TransferStatus.PAUSED -> "已暂停"
                        TransferStatus.SUCCESS -> null
                        else -> lastTransferError ?: "连接中断、目标不可写或登录已过期"
                    }
                )
            }
            if (!quiet) {
                persistTransfers()
                if (status != TransferStatus.PAUSED) {
                    state = state.copy(
                        message = when (status) {
                            TransferStatus.SUCCESS -> "${verb}完成 · " +
                                if (task.total > 1) "${task.title} 等 ${task.total} 项" else task.title
                            else -> "${verb}失败：${cleanError(lastTransferError, verb)}"
                        }
                    )
                }
                val finished = transfers.firstOrNull { it.id == task.id }
                val othersRunning = hasOtherRunning(task.id)
                if (finished != null) {
                    TransferNotifications.notifyFinal(app, finished, replacesProgress = !othersRunning)
                }
                stopTransferServiceIfIdle(app)
            }
            childControls.remove(task.id)
            runCatching { onDone(status == TransferStatus.SUCCESS) }
            // 清理取消标记必须放在 onDone 之后：在线打开要在完成回调里靠
            // isCancelled 分辨「用户主动取消」和「真的失败」，才能正确清缓存。
            TransferCancellation.clear(task.id)
        }
        transferJobs[task.id] = job
        job.invokeOnCompletion { transferJobs.remove(task.id) }
        return task.id
    }


    /** 上传 / 下载都要有通知栏进度：同一个前台服务，多个任务共用一条进行中通知。 */
internal fun AppViewModel.startTransferService(app: Application, task: TransferTask) {
        val intent = Intent(app, TransferService::class.java)
            .putExtra(TransferNotifications.EXTRA_TASK_ID, task.id)
            .putExtra(TransferNotifications.EXTRA_TASK_TITLE, task.title)
            .putExtra(TransferNotifications.EXTRA_TASK_TOTAL, task.totalBytes)
            .putExtra(TransferNotifications.EXTRA_TASK_MODE, task.mode.name)
        runCatching { ContextCompat.startForegroundService(app, intent) }
    }


    /** 还有正在进行的任务就不要停前台服务，否则其余任务的进度条会突然消失。 */
internal fun AppViewModel.stopTransferServiceIfIdle(app: Application) {
        if (transfers.any { it.status == TransferStatus.RUNNING && !it.quiet }) return
        // 不用 stopService 之外的花招：完成通知已经单独发了一条，
        // 这里要做的就是让服务把「前台」身份和它那条进度通知一起收掉（REMOVE），
        // 否则前台服务名下的通知用户既划不掉、也清不掉。
        runCatching {
            app.startService(
                Intent(app, TransferService::class.java).setAction(TransferService.ACTION_STOP)
            )
        }
    }


    /** 是否还有别的任务在跑（决定完成通知能不能复用前台通知那一条）。 */
internal fun AppViewModel.hasOtherRunning(excludeTaskId: String): Boolean =
        transfers.any {
            it.id != excludeTaskId && !it.quiet &&
                (it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED)
        }


    /** 单个文件上传到当前远程位置（含群晖会话续期）。 */
internal suspend fun AppViewModel.uploadOne(
        browser: BrowserState,
        file: File,
        dir: String,
        name: String,
        onBytes: ((Long) -> Unit)? = null,
        abort: (() -> Boolean)? = null
    ): Boolean {
        return when (browser.ctx) {
        FileContext.SYNO -> {
            val session = state.session
            if (session == null) {
                lastTransferError = "群晖登录已失效，请重新登录"
                false
            } else {
                var ok = runCatching { dsm.upload(session, file, dir, name, onBytes, abort) }.getOrDefault(false)
                // 用户暂停导致的中止：不要报错，也不要触发会话续期重试。
                if (!ok && abort?.invoke() == true) return false
                if (!ok) {
                    lastTransferError = dsm.lastError ?: "群晖拒绝上传，请检查目标共享是否可写"
                    if (renewSynoSession()) {
                        ok = state.session?.let {
                            runCatching { dsm.upload(it, file, dir, name, onBytes, abort) }.getOrDefault(false)
                        } ?: false
                        if (ok) lastTransferError = null else lastTransferError = dsm.lastError ?: lastTransferError
                    }
                }
                ok
            }
        }
        FileContext.NET -> withContext(Dispatchers.IO) {
            val conn = state.connections.firstOrNull { it.id == browser.netId }
            if (conn == null) {
                lastTransferError = "网络连接已不存在，请重新添加"
                false
            } else {
                val ok = runCatching {
                    network.upload(conn, file, dir, name, { bytes -> onBytes?.invoke(bytes) }, abort)
                }.getOrDefault(false)
                if (!ok && abort?.invoke() != true) {
                    lastTransferError = network.lastError ?: "${conn.protocol} 上传失败：目标目录不可写"
                }
                ok
            }
        }
        FileContext.LOCAL -> false
        }
    }


    /** 在远端建一级目录（上传文件夹时补齐结构）。 */
internal suspend fun AppViewModel.createRemoteFolder(browser: BrowserState, parent: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            when (browser.ctx) {
                FileContext.SYNO -> state.session?.let { dsm.createFolder(it, parent, name) } ?: false
                FileContext.NET -> state.connections.firstOrNull { it.id == browser.netId }
                    ?.let { network.createFolder(it, parent, name) } ?: false
                FileContext.LOCAL -> false
            }
        }


/**
 * 文件夹展开后一次性写入逐文件子项；单文件（children 为空）不调用。
 * 主线程更新，避免在 IO 工作线程里整份 copy state。
 *
 * [keepKnown] 用于「恢复」：上一轮已经记下的 target / preExisting 必须保留。
 * 它们在上一轮是准的（这一份到底是本任务下的，还是用户本来就有的同名文件），
 * 恢复时重新枚举远端会把它们抹掉，已下好的文件就再也认不回来——取消传输时
 * 只删得掉本次新下的那几个，之前下好的一直留在原地。
 */
internal fun AppViewModel.setTransferChildren(
    taskId: String,
    children: List<TransferChild>,
    keepKnown: Boolean = false
) {
    if (taskId.isBlank() || children.isEmpty()) return
    viewModelScope.launch {
        updateTransfer(taskId) { task ->
            val merged = if (!keepKnown) children else {
                val known = task.children.associateBy { it.key }
                children.map { child ->
                    val prev = known[child.key] ?: return@map child
                    child.copy(
                        target = prev.target,
                        preExisting = prev.preExisting,
                        conflictOriginal = prev.conflictOriginal
                    )
                }
            }
            task.copy(children = merged)
        }
    }
}

/** 清空子项：沿用上一轮明细（carryChildren）后，这次只剩一个文件时要把旧表撤掉。 */
internal fun AppViewModel.clearTransferChildren(taskId: String) {
    if (taskId.isBlank()) return
    viewModelScope.launch {
        updateTransfer(taskId) { if (it.children.isEmpty()) it else it.copy(children = emptyList()) }
    }
}

/** 下载开始时记下本次新建的目录，删除任务时只回收这些目录里已经空掉的层级。 */
internal fun AppViewModel.setTransferCreatedDirs(taskId: String, dirs: List<String>) {
    if (taskId.isBlank()) return
    viewModelScope.launch { updateTransfer(taskId) { it.copy(createdDirs = it.createdDirs + dirs) } }
}

/** 更新单个子文件的状态 / 字节 / 错误。全部在主线程串行执行。 */
internal fun AppViewModel.updateTransferChild(
    taskId: String,
    key: String,
    status: TransferChildStatus? = null,
    bytes: Long? = null,
    error: String? = null,
    target: String? = null,
    preExisting: Boolean? = null,
    conflictOriginal: String? = null
) {
    if (taskId.isBlank()) return
    viewModelScope.launch {
        updateTransfer(taskId) { task ->
            val updated = task.children.map { c ->
                if (c.key != key) c else c.copy(
                    status = status ?: c.status,
                    completedBytes = bytes ?: c.completedBytes,
                    error = error ?: c.error,
                    target = target ?: c.target,
                    preExisting = preExisting ?: c.preExisting,
                    conflictOriginal = conflictOriginal ?: c.conflictOriginal
                )
            }
            task.copy(children = updated)
        }
    }
}

internal suspend fun AppViewModel.downloadToLocal(
        items: List<FileEntry>,
        destination: Destination,
        ctx: FileContext,
        netId: String?,
        onProgress: (Int) -> Unit = {},
        onBytes: (Long) -> Unit = {},
        taskId: String = "",
        onTotal: (Int) -> Unit = {},
        onTotalBytes: (Long) -> Unit = {},
        onTarget: (String) -> Unit = {},
        resume: Boolean = false
    ): Boolean {
        val dir = destination.path

        // 文件夹也要能整体下载：先递归展开远端目录，再逐文件落盘。
        // second == null 表示目录占位，用来把空文件夹也建出来。
        val pending = mutableListOf<Pair<String, FileEntry?>>()
        suspend fun collect(entry: FileEntry, rel: String, depth: Int) {
            if (depth > 8 || pending.size >= 3000) return
            val children = runCatching {
                when (ctx) {
                    FileContext.SYNO -> state.session?.let { dsm.list(it, entry.path) }.orEmpty()
                    FileContext.NET -> state.connections.firstOrNull { it.id == netId }
                        ?.let { network.list(it, entry.path) }.orEmpty()
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            if (children.isEmpty()) {
                pending += rel to null
                return
            }
            children.forEach { child ->
                val childRel = "$rel/${child.name}"
                if (child.isFolder) collect(child, childRel, depth + 1) else pending += childRel to child
            }
        }
        // 多选里不同目录可能有同名文件：直接用文件名当 key 会让子项互相覆盖。
        // 给重名的加上来源目录前缀，既让 key 唯一，也避免它们落到同一个目标文件。
        val duplicateNames = items.filter { !it.isFolder }
            .groupingBy { it.name }.eachCount().filter { it.value > 1 }.keys
        val usedRel = HashSet<String>()
        fun relFor(item: FileEntry): String {
            val name = item.name
            if (name !in duplicateNames && name !in usedRel) {
                usedRel += name
                return name
            }
            val parent = item.path.trimEnd('/', '\\').substringBeforeLast('/').substringAfterLast('/')
                .ifBlank { "来源" }
            var candidate = "$parent/$name"
            var index = 2
            while (candidate in usedRel) {
                candidate = "$parent/$name ($index)"
                index++
            }
            usedRel += candidate
            return candidate
        }
        items.forEach { item ->
            if (item.isFolder) collect(item, item.name, 0) else pending += relFor(item) to item
        }
        val fileEntries = pending.mapNotNull { it.second }
        val fileCount = fileEntries.size
        onTotal(fileCount.coerceAtLeast(1))
        // 文件夹展开后的真实字节数才是进度分母，否则 totalBytes=0 会让进度条永远 0%。
        val realBytes = fileEntries.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        if (realBytes > 0L) onTotalBytes(realBytes)

        // 记录「完成后能直接打开哪个文件」：单文件就是它本身，多文件 / 文件夹是目标目录。
        onTarget(dir)

        // 文件夹任务即使只含 1 个文件也要建子项：删除记录要靠它精确知道
        // 「这次下进来的是哪个文件」，只按文件夹路径没法删里面的东西。
        // 纯单文件保持空，UI 不显示明细。
        val withChildren = fileCount > 1 || items.any { it.isFolder }
        // 恢复 / 重新下载前先记下上一轮对每个文件的判定：跳过分支要用它区分
        // 「本任务下好的」和「用户本来就有的同名文件」。整表重建会把这两个语义冲掉。
        val knownPreExisting = if (taskId.isNotBlank()) {
            transfers.firstOrNull { it.id == taskId }?.children
                ?.associate { it.key to it.preExisting }.orEmpty()
        } else emptyMap()
        // 恢复时上一轮已完成的文件绝不能当成半截删掉：重建上下文偶尔拿不到条目大小，
        // 大小校验会失效，这时子项状态是唯一可信的完成凭据。
        val knownDone = if (taskId.isNotBlank()) {
            transfers.firstOrNull { it.id == taskId }?.children
                ?.filter { it.status == TransferChildStatus.SUCCESS }
                ?.map { it.key }?.toSet().orEmpty()
        } else emptySet()
        if (withChildren && taskId.isNotBlank()) {
            setTransferChildren(
                taskId,
                pending.mapNotNull { (rel, e) ->
                    e?.let {
                        TransferChild(
                            key = rel, name = rel, path = it.path,
                            sizeBytes = it.sizeBytes.coerceAtLeast(0L)
                        )
                    }
                },
                keepKnown = knownPreExisting.isNotEmpty()
            )
        } else if (taskId.isNotBlank() && knownPreExisting.isNotEmpty()) {
            // 这次只剩一个文件：上一轮带过来的逐文件明细已经不适用，撤掉。
            clearTransferChildren(taskId)
        }

        // 记录本次新建的目录（相对目标目录），供删除任务时回收空文件夹。
        // 在 mkdirs 之前采样：只记原本不存在的，用户预先建好的目录一个都不动。
        if (taskId.isNotBlank()) {
            val relDirs = linkedSetOf<String>()
            pending.forEach { (rel, e) ->
                var cur = if (e == null) rel else rel.substringBeforeLast('/', "")
                while (cur.isNotBlank()) {
                    relDirs += cur
                    cur = cur.substringBeforeLast('/', "")
                }
            }
            val newDirs = relDirs.filter { rel -> !File(dir, rel).exists() }
            setTransferCreatedDirs(taskId, newDirs)
        }

        // 空目录占位先建出来（不占并发名额，纯 mkdirs）。
        if (taskId.isBlank() || !TransferCancellation.isCancelled(taskId)) {
            pending.forEach { (rel, e) ->
                if (e == null) runCatching { File(dir, rel).mkdirs() }
            }
        }

        // 并发度可在设置里调（1~6）：默认 3 兼顾速度与 NAS/中继压力，弱网可调低、
        // 内网高速 NAS 可调高。每个文件独立协程 + 并发闸门；会话/连接每次调用新建，并发安全。
        val parallelism = state.prefs.downloadConcurrency.coerceIn(1, 6)

        // 多文件用可抢占队列调度：支持单文件暂停 / 恢复续传 / 提前开始。
        // 单文件（children 为空）不需要控制器，走原来的单趟下载。
        val control: ChildDownloadControl? = if (withChildren && taskId.isNotBlank()) {
            (childControls.getOrPut(taskId) { ChildDownloadControl() }).also { c ->
                c.enqueueAll(pending.mapNotNull { (rel, e) -> e?.let { rel } })
            }
        } else null
        // key 就是相对路径；供 worker 按 key 取回文件条目。
        val entryByKey: Map<String, FileEntry> = pending.mapNotNull { (rel, e) -> e?.let { rel to it } }.toMap()

        // 每个子文件已写字节（只增不减）；父进度 = 各子项之和，天然单调，乱序完成也不会回退。
        val childBytes = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val finishedCount = java.util.concurrent.atomic.AtomicInteger(0)
        fun aggregateBytes(): Long = childBytes.values.sum()
        // 并行下同名的源文件（从不同目录多选）可能落到同一个目标名，先到先得地占用，
        // 否则两个协程同时 uniqueTarget() 会拿到同一路径、互相覆盖。
        val reservedTargets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun reserveTarget(target: File): File {
            var attempt = 0
            while (true) {
                val candidate = if (attempt == 0) target else {
                    val base = target.nameWithoutExtension
                    val ext = target.extension
                    val n = " ($attempt)"
                    File(target.parentFile, if (ext.isEmpty()) "$base$n" else "$base$n.$ext")
                }
                // 磁盘上不存在且没被别的协程占用，才算抢到这个名字。
                if (!candidate.exists() && reservedTargets.add(candidate.absolutePath)) return candidate
                attempt++
            }
        }

        fun reportChild(
            rel: String, status: TransferChildStatus, bytes: Long,
            error: String? = null, target: String? = null, preExisting: Boolean? = null
        ) {
            if (withChildren) updateTransferChild(
                taskId, key = rel, status = status, bytes = bytes,
                error = error, target = target, preExisting = preExisting
            )
        }
        fun bumpFinished(): Int = finishedCount.incrementAndGet()

        val allOk = java.util.concurrent.atomic.AtomicBoolean(true)

        /** 单个子文件的下载主体。返回 true=成功，false=失败或暂停中断。 */
        suspend fun runOne(rel: String, entry: FileEntry, resumeFromPart: Boolean): Boolean {
            if (TransferCancellation.isCancelled(taskId)) {
                reportChild(rel, TransferChildStatus.PAUSED, 0L)
                return false
            }
            val target = File(dir, rel)
            reportChild(rel, TransferChildStatus.RUNNING, 0L, preExisting = false)
            // 同名去重：本地已有同名且同大小的文件，直接视为已完成，不重复下载。
            // **恢复时同样适用**：上次已经下完的文件（以及本地本来就有的同大小文件）
            // 必须跳过。以前恢复时把这条跳过整个关掉，结果每一个已完成文件都被重下一遍，
            // 又因为目标已存在而改名成「名字 (1)」——恢复一次多一份，越恢复越多，
            // 这正是「恢复后出现 (1)、过一会失败、删记录还清不掉原名那份」的来源。
            // 半截文件不可能与远端同大小（否则它已经下完了），不会被这条误判跳过。
            if (target.exists() && target.isFile &&
                entry.sizeBytes > 0L && target.length() == entry.sizeBytes
            ) {
                childBytes[rel] = entry.sizeBytes
                reportChild(
                    rel, TransferChildStatus.SUCCESS, entry.sizeBytes,
                    target = target.absolutePath,
                    // 只有「任务开始前它就在」才算用户文件（preExisting=true，取消时保留）：
                    // 首次执行时子项表还是空的，能走到这条跳过分支说明文件本来就在本地 → true；
                    // 恢复时子项表非空，沿用上一轮的判定；表里有别的键却没有这一条，
                    // 说明它多半是上一轮下的、记录没跟上，按本任务产物处理。
                    preExisting = knownPreExisting[rel] ?: knownPreExisting.isEmpty()
                )
                onProgress(bumpFinished()); onBytes(aggregateBytes())
                if (fileCount == 1) {
                    // 单文件没有 children 可记 preExisting：必须把 target 指向这份原有文件并打标，
                    // 否则删除任务会拿 target 当本任务产物删掉用户文件。
                    onTarget(target.absolutePath)
                    updateTransfer(taskId) { it.copy(targetPreExisting = true) }
                }
                return true
            }
            // 走到这里说明目标要么不存在，要么大小与远端不一致 = 上次没下完的半截。
            // 恢复时它是本任务自己的残留（暂停那一刻的删除可能被系统占用挡下，也可能
            // 这条记录是修复前留下的）：必须先删掉，否则下面 reserveTarget 会改名成
            // 「名字 (1)」，原名那份半截从此失联，用户看到的就是「恢复一次多一个文件、
            // 删任务还清不掉那个原名」。全新下载不清：那是用户自己的文件，保留原名、
            // 另存「名字 (1)」才是预期行为。
            if (resumeFromPart && target.exists() && target.isFile && rel !in knownDone) {
                runCatching { target.delete() }
            }
            val unique = reserveTarget(target)
            // 另存为「名字 (n)」说明原名那份在下载前就存在（用户文件或旧半截）。
            // 记下被避开的原名：删任务时绝不能把它当成本任务产物删掉。
            val conflictOriginal = if (unique.absolutePath != target.absolutePath) target.absolutePath else null
            if (conflictOriginal != null) {
                if (withChildren) updateTransferChild(taskId, rel, conflictOriginal = conflictOriginal)
                else updateTransfer(taskId) { it.copy(conflictOriginal = conflictOriginal) }
            }
            unique.parentFile?.mkdirs()
            if (fileCount == 1) onTarget(unique.absolutePath)
            /**
             * 网络协议直接写最终文件名、没有 .part：一旦中断，磁盘上留下的就是
             * 「名字对、内容不全」的文件，恢复时会被当成已存在而改名，用户看到的
             * 就是一串「xxx (1).zip」。所以暂停 / 取消 / 失败一律当场删掉它。
             * 群晖相反：半截数据只在 .part 里，是断点续传的凭据，必须保留——
             * 只清掉连一个字节都没写成的空壳。
             */
            fun discardPartial() {
                if (ctx == FileContext.SYNO) {
                    runCatching { if (unique.exists() && unique.length() == 0L) unique.delete() }
                } else {
                    runCatching { if (unique.exists()) unique.delete() }
                }
            }
            /**
             * 暂停后磁盘上还剩多少字节：网络协议的半截已被 discardPartial 删掉，
             * 群晖的进度留在 .part 里。任务卡与子项进度都必须回退到这个值，
             * 否则会出现「文件已删、卡片还显示 421 MB、恢复后却从 0 开始」的自相矛盾。
             */
            fun keptBytes(): Long {
                val part = File(unique.parentFile, "${unique.name}.part")
                val own = if (unique.exists()) unique.length().coerceAtLeast(0L) else 0L
                val partLen = if (part.exists()) part.length().coerceAtLeast(0L) else 0L
                return maxOf(own, partLen)
            }
            // 本文件写入量写入共享表；父任务进度取所有子项之和（单调、乱序安全）。
            // 子项行也要同步：只更新父级字节时，详情里传输中的文件会一直显示 0 B。
            var lastChildReport = 0L
            fun reportBytes(written: Long) {
                childBytes[rel] = written
                onBytes(aggregateBytes())
                val now = System.currentTimeMillis()
                if (now - lastChildReport >= 500L || written == 0L) {
                    lastChildReport = now
                    reportChild(rel, TransferChildStatus.RUNNING, written)
                }
            }
            val fileBytes: (Long) -> Unit = { written -> reportBytes(written) }
            childBytes[rel] = 0L
            // 任务级暂停 或 本子项被单独暂停，都要立刻中断底层下载。
            val abortCheck = { TransferCancellation.isCancelled(taskId) || control?.isPaused(rel) == true }
            var ticker: kotlinx.coroutines.Job? = null
            val ok = try {
                when (ctx) {
                    FileContext.SYNO -> state.session?.let {
                        // 只有「任务续传」或「本子项恢复」才从 .part 续；全新下载若残留旧 .part
                        // 视为脏数据（远端文件可能已变化），直接从头下。
                        val partFile = File(unique.parentFile, "${unique.name}.part")
                        if (!resumeFromPart && partFile.exists()) runCatching { partFile.delete() }
                        dsm.download(it, entry.path, unique, fileBytes, abortCheck, resume = resumeFromPart && partFile.exists())
                    } ?: false
                    FileContext.NET -> {
                        // 直接写最终文件，不再用 .part 中转（各网络协议都不支持中途续传，
                        // 且 MIUI FUSE 下 rename 刚写完的临时文件必失败——真机实证）。
                        ticker = viewModelScope.launch {
                            while (isActive) {
                                reportBytes(unique.length().coerceAtLeast(0L))
                                delay(350)
                            }
                        }
                        val conn = state.connections.firstOrNull { it.id == netId }
                        if (conn == null) {
                            lastTransferError = "连接已失效，请返回后重新进入"
                            android.util.Log.w(
                                "MyFileBox",
                                "download: NET connection missing"
                            )
                        }
                        var netOk = false
                        if (conn != null) {
                            runCatching { if (unique.exists()) unique.delete() }
                            netOk = network.download(conn, entry.path, unique, abortCheck, entry.sizeBytes)
                            // 网络协议直写目标、没有 .part：服务端提前正常 EOF 时底层可能只返回
                            // 「写过了字节」就报成功，留下半截文件。这里按条目大小复核，不完整
                            // 一律判失败并删掉，避免恢复下载时把它当成完整文件或触发同名跳过。
                            // 复核要留一点余量：MIUI 的 FUSE 上刚关闭的输出流，length() 会短暂
                            // 读到旧值（真机实测少几十到一百多字节），当场判失败会把完整文件
                            // 删掉——用户看到的就是「明明下完了却报下载不完整，文件也没了」。
                            if (netOk && entry.sizeBytes > 0L) {
                                var actual = unique.length()
                                var tries = 0
                                while (actual != entry.sizeBytes && tries < 4) {
                                    delay(120)
                                    actual = unique.length()
                                    tries++
                                }
                                if (actual != entry.sizeBytes) {
                                    lastTransferError = "下载不完整（期望 ${entry.sizeBytes} 字节，实得 $actual）"
                                    netOk = false
                                }
                            }
                        }
                        if (!netOk) runCatching { if (unique.exists()) unique.delete() }
                        netOk
                    }
                    else -> false
                }
            } catch (ce: CancellationException) {
                // 暂停 / 取消 / 超时都会走到这里。网络协议必须把半截目标文件删掉，
                // 否则它会被下次恢复当成「已存在」而改名，留下一份永远清不掉的残留。
                discardPartial()
                val kept = keptBytes()
                childBytes[rel] = kept
                onBytes(aggregateBytes())
                reportChild(
                    rel, TransferChildStatus.PAUSED, kept,
                    target = if (unique.exists()) unique.absolutePath else null
                )
                val childPaused = control?.isPaused(rel) == true
                control?.abortPaused(rel)
                // 子项暂停只让当前文件退出，不能把整个 worker 协程取消；
                // 否则同批其它文件会跟着全部停住，与「单独暂停」的语义相反。
                if (childPaused && !TransferCancellation.isCancelled(taskId)) return false
                throw ce
            } finally {
                ticker?.cancel()
            }

            // 本子项被单独暂停：网络协议已删掉半截目标文件，群晖保留 .part 供续传。
            // 这里登记 unique 的实际路径（可能是 reserveTarget 改过的「(1)」名），
            // 否则删除任务时只能按原名回推，改名文件会成孤儿留在磁盘上。
            if (control?.isPaused(rel) == true) {
                discardPartial()
                control.abortPaused(rel)
                val kept = keptBytes()
                childBytes[rel] = kept
                onBytes(aggregateBytes())
                reportChild(
                    rel, TransferChildStatus.PAUSED, kept,
                    target = if (unique.exists()) unique.absolutePath else null
                )
                return false
            }
            if (TransferCancellation.isCancelled(taskId)) {
                discardPartial()
                val kept = keptBytes()
                childBytes[rel] = kept
                onBytes(aggregateBytes())
                reportChild(
                    rel, TransferChildStatus.PAUSED, kept,
                    target = if (unique.exists()) unique.absolutePath else null
                )
                control?.abortPaused(rel)
                return false
            }
            val actualBytes = unique.length().coerceAtLeast(0L)
            if (!ok) {
                allOk.set(false)
                if (lastTransferError == null) {
                    lastTransferError = dsm.lastError ?: network.lastError
                        ?: "源文件不可读、连接已断开或目录不可写"
                }
                val err = lastTransferError ?: "源文件不可读"
                android.util.Log.w(
                    "MyFileBox",
                    "download failed size=${entry.sizeBytes}"
                )
                // 失败同样不留半截文件：网络协议直接删目标，群晖删空壳 + .part。
                discardPartial()
                runCatching { File(unique.parentFile, "${unique.name}.part").delete() }
                childBytes[rel] = actualBytes
                reportChild(
                    rel, TransferChildStatus.FAILED, actualBytes, error = err,
                    target = if (unique.exists()) unique.absolutePath else null
                )
                onProgress(bumpFinished()); onBytes(aggregateBytes())
                return false
            }
            // 用真实落盘字节数，不再用 maxOf(期望, 实际) 把不完整文件伪装成 100%。
            childBytes[rel] = actualBytes
            reportChild(rel, TransferChildStatus.SUCCESS, actualBytes, target = unique.absolutePath)
            onProgress(bumpFinished()); onBytes(aggregateBytes())
            return true
        }

        withContext(Dispatchers.IO) {
            if (control == null) {
                // 只有 1 个文件：没有子项调度，一次下载即可。
                // rel 必须用队列里的 key（可能是「文件夹名/文件名」），
                // 不能用 entry.name——否则「只有一个文件的文件夹」会被拍平到目标根目录。
                val onlyKey = pending.firstOrNull { it.second != null }?.first
                val only = onlyKey?.let { entryByKey[it] }
                if (onlyKey != null && only != null) runOne(onlyKey, only, resume)
            } else {
                val ctrl = control
                val workers = (0 until parallelism).map {
                    async {
                        while (!TransferCancellation.isCancelled(taskId)) {
                            val key = ctrl.nextKey()
                            if (key == null) {
                                // 全部终结则退出；否则（全被暂停 / 还没入队）短暂等待后重试。
                                if (ctrl.allFinished()) break
                                delay(80)
                                continue
                            }
                            val entry = entryByKey[key]
                            if (entry == null) { ctrl.finish(key); continue }
                            try {
                                val resumePart = resume || ctrl.consumeResume(key)
                                runOne(key, entry, resumePart)
                            } finally {
                                // 成功/失败都从在途移除；暂停的项在 runOne 里已调用 abortPaused。
                                if (!ctrl.isPaused(key)) ctrl.finish(key)
                            }
                        }
                    }
                }
                workers.awaitAll()
            }
        }
        if (control != null) {
            // 还有子项停在「已暂停」：整批没下完，任务应留在暂停态供用户恢复，
            // 不能因为「没有失败项」就被 launchTransferTask 判成 SUCCESS。
            if (control.pausedCount() > 0 && !TransferCancellation.isCancelled(taskId)) {
                updateTransfer(taskId) {
                    if (it.status == TransferStatus.RUNNING) it.copy(status = TransferStatus.PAUSED, error = "已暂停")
                    else it
                }
            }
            childControls.remove(taskId)
        }
        return allOk.get()
    }


internal fun AppViewModel.uniqueTarget(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension
        val ext = target.extension
        var index = 1
        var candidate: File
        do {
            val name = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
            candidate = File(target.parentFile, name)
            index++
        } while (candidate.exists())
        return candidate
    }


internal suspend fun AppViewModel.uploadToRemote(
        items: List<FileEntry>,
        destination: Destination,
        onProgress: (Int) -> Unit = {},
        onTotal: (Int) -> Unit = {},
        onBytes: (Long) -> Unit = {},
        onTotalBytes: (Long) -> Unit = {},
        onTarget: (String) -> Unit = {},
        taskId: String = "",
        onUploaded: (String) -> Unit = {}
    ): Boolean {
        // Flatten folders into (local file, relative parent) pairs so folders
        // can be uploaded too instead of silently counting as failures.
        val pending = mutableListOf<Pair<File, String>>()
        fun collect(f: File, parentRel: String) {
            if (f.isDirectory) {
                val nextRel = if (parentRel.isBlank()) f.name else "$parentRel/${f.name}"
                f.listFiles().orEmpty()
                    .filter { !it.name.startsWith('.') }
                    .forEach { collect(it, nextRel) }
            } else if (f.isFile) {
                pending += f to parentRel
            }
        }
        items.forEach { entry ->
            val f = File(entry.path)
            if (f.exists()) collect(f, "")
        }
        if (pending.isEmpty()) {
            lastTransferError = "没有可上传的文件：源文件不存在或文件夹为空"
            return false
        }
        // 文件夹展开后的真实文件数才是进度分母，否则进度条会提前到 100%。
        onTotal(pending.size)
        // 总字节数同样按真实文件重算：文件夹的 sizeBytes 是 0，会让进度条永远没进度。
        val realBytes = pending.sumOf { runCatching { it.first.length() }.getOrDefault(0L) }
        if (realBytes > 0L) onTotalBytes(realBytes)
        // 文件夹任务即使只含 1 个文件也要建子项：详情要能列出里面的文件，
        // 删除记录也靠它知道这次传了哪些文件。纯单文件保持空，UI 不显示明细。
        val withChildren = pending.size > 1 || items.any { it.isFolder }
        if (withChildren && taskId.isNotBlank()) {
            setTransferChildren(
                taskId,
                pending.map { (file, rel) ->
                    val name = if (rel.isBlank()) file.name else "$rel/${file.name}"
                    TransferChild(key = name, name = name, path = file.absolutePath,
                        sizeBytes = runCatching { file.length() }.getOrDefault(0L))
                }
            )
        }
        // 目标路径按绝对形式拼：目录选择器可能给「Backup/Temp/Temp」这种相对路径，
        // 不补前导 `/` 的话子项 target / uploadedTargets 全是相对路径，
        // 之后记录点开、删除清理都定位不到（详见 normalizeRemotePath）。
        val root = normalizeRemotePath(destination.path)
        network.clearError()
        var all = true
        var completed = 0
        var sentBytes = 0L
        fun uploadChildKey(file: File, rel: String): String =
            if (rel.isBlank()) file.name else "$rel/${file.name}"
        // 每个远端目录只列一次，避免整批上传时每个文件都多一次 LIST 请求。
        val existingNamesByDir = HashMap<String, Set<String>>()
        suspend fun existingRemoteNames(dir: String): Set<String> {
            existingNamesByDir[dir]?.let { return it }
            val names = runCatching {
                when (destination.type) {
                    DestType.SYNO -> state.session?.let { s -> dsm.list(s, dir) }
                    DestType.NET -> state.connections.firstOrNull { it.id == destination.key }
                        ?.let { c -> network.list(c, dir) }
                    else -> null
                }.orEmpty().filter { !it.isFolder }.map { it.name }.toSet()
            }.getOrDefault(emptySet())
            existingNamesByDir[dir] = names
            return names
        }
        for ((file, rel) in pending) {
            // 暂停/取消要真的停下来：每个文件开始前先看标记。
            if (TransferCancellation.isCancelled(taskId)) return false
            val childKey = uploadChildKey(file, rel)
            if (withChildren) updateTransferChild(taskId, childKey, TransferChildStatus.RUNNING, 0L)
            val dir = when {
                rel.isBlank() -> root
                root == "/" -> "/$rel"
                else -> "$root/$rel"
            }
            val remotePath = if (dir == "/") "/${file.name}" else "${dir.trimEnd('/')}/${file.name}"
            // 远端原本就有同名文件时，失败/暂停绝不能删它——那是用户自己的文件，
            // 本次上传只是覆盖写入，删掉就成了不可逆的数据丢失。
            val remoteExistedBefore = file.name in existingRemoteNames(dir)
            // 先登记远端目标：万一传到一半被暂停，远端已经留下半截文件，
            // 删除任务时要能把它一起清掉。
            onUploaded(remotePath)
            val abortCheck = { TransferCancellation.isCancelled(taskId) }
            val base = sentBytes
            // 上传是直接写远端最终文件名的（FTP STOR / SFTP put / SMB OVERWRITE_IF /
            // WebDAV PUT / 群晖 multipart），没有「临时名 + rename」这一层。
            // 所以暂停、取消、失败都会在远端留下「名字对、内容不全」的文件；
            // 下一次传同名文件会把它覆盖掉，但在那之前它就是个坏文件。下面每条
            // 中断路径都当场删掉它。
            suspend fun discardRemotePartial() {
                // 用户原有的同名文件绝不当半截删：上传失败时它至少还在（哪怕内容被覆盖）。
                if (remoteExistedBefore) return
                // 中断后立刻发 DELE 会撞上服务端还没关闭的 STOR 句柄：Windows 服务端
                // 直接拒绝（文件被占用），Unix 服务端也可能删到一个正在写的文件上。
                // 真机实测「客户端断开数据连接」到「服务端收尾关文件」能差到一两秒，
                // 所以按递增退避重试几次；删除幂等，多试不会出错，只在中断时发生。
                withContext(NonCancellable) {
                    for (waitMs in longArrayOf(0L, 400L, 1200L, 2500L)) {
                        if (waitMs > 0L) delay(waitMs)
                        if (deleteRemoteAt(destination, listOf(remotePath))) return@withContext
                    }
                }
            }
            /**
             * 中断当前文件：删掉远端半截，并把任务进度回退到「已完成文件」的位置。
             * 远端那份已经不存在了，进度继续停在它上面就是假的——恢复后本来也是
             * 从这个文件的开头重传。
             */
            suspend fun abortCurrentFile() {
                discardRemotePartial()
                onBytes(base)
            }
            val ok = try {
            when (destination.type) {
                DestType.SYNO -> {
                    val s = state.session
                    if (s == null) {
                        lastTransferError = "群晖登录已失效，请重新登录"
                        false
                    } else {
                        val result = dsm.upload(s, file, dir, file.name, { bytes -> onBytes(base + bytes) }, abortCheck)
                        if (!result) {
                            if (TransferCancellation.isCancelled(taskId)) {
                                abortCurrentFile()
                                return false
                            }
                            lastTransferError = dsm.lastError
                                ?: "群晖拒绝上传，请检查目标共享是否可写"
                        }
                        result
                    }
                }
                DestType.NET -> {
                    val conn = state.connections.firstOrNull { it.id == destination.key }
                    if (conn == null) {
                        lastTransferError = "网络连接已不存在，请重新添加"
                        false
                    } else {
                        val result = network.upload(conn, file, dir, file.name, { bytes -> onBytes(base + bytes) }, abortCheck)
                        if (!result) {
                            // 用户主动暂停不是错误，别写「上传失败」。
                            if (TransferCancellation.isCancelled(taskId)) {
                                abortCurrentFile()
                                return false
                            }
                            lastTransferError = network.lastError
                                ?: "${conn.protocol} 上传失败：目录「$dir」不可写"
                        }
                        result
                    }
                }
                else -> {
                    lastTransferError = "上传目标必须是群晖或网络位置"
                    false
                }
            }
            } catch (ce: CancellationException) {
                // 阻塞中的流不响应 abort 时，取消会在挂起点抛出来——这时候远端
                // 已经写了一半。协程已取消，删除必须放在 NonCancellable 里才发得出去。
                abortCurrentFile()
                throw ce
            }
            if (!ok) {
                abortCurrentFile()
                // 失败的文件没有落到远端，进度不能按它算满，否则失败任务会显示 100%。
                sentBytes = base
                onBytes(sentBytes)
            } else {
                sentBytes = base + runCatching { file.length() }.getOrDefault(0L)
                onBytes(sentBytes)
            }
            // 单文件上传记录远端目标路径，完成后可直接在线打开 / 安装。
            // 多文件 / 文件夹上传只在第一个文件开始时记一次目标目录：以前这里只处理
            // 单文件，其余记录的 target 一直是空的，点开记录只会弹一句「标题 · 详情」，
            // 完全定位不到上传到了哪里（用户看到的就是「点了没反应，只提示上传」）。
            if (pending.size == 1) onTarget(remotePath) else if (completed == 0) onTarget(root)
            if (withChildren) {
                if (ok) updateTransferChild(taskId, childKey, TransferChildStatus.SUCCESS, sentBytes - base, target = remotePath)
                else updateTransferChild(
                    taskId, childKey, TransferChildStatus.FAILED, 0L,
                    error = lastTransferError ?: "上传失败", target = remotePath
                )
            }
            if (!ok) all = false
            completed++
            onProgress(completed)
        }
        return all
    }


internal suspend fun AppViewModel.copyOrMove(request: TransferRequest, destination: Destination): Boolean {
        return when (request.ctx) {
            FileContext.LOCAL -> {
                // 同端复制 / 移动不进传输中心（quiet），过去只靠结束时的提示，
                // 中途没有任何反馈：粘贴一个大文件夹时界面像没点动。
                // 这里逐项 + 目录内逐文件回报进度，由顶部任务条显示。
                val verb = if (request.mode == TransferMode.MOVE) "正在移动" else "正在复制"
                val total = request.items.size
                var done = 0
                request.items.all { item ->
                    val ok = local.copy(item, destination.path, request.mode == TransferMode.MOVE) { copied, count ->
                        publishLocalTaskProgress(verb, done, total, copied, count)
                    }
                    done++
                    publishLocalTaskProgress(verb, done, total)
                    ok
                }
            }
            FileContext.SYNO -> {
                val session = state.session ?: return false
                dsm.copyMove(session, request.items.map { it.path }, destination.path, request.mode == TransferMode.MOVE)
            }
            FileContext.NET -> {
                val conn = state.connections.firstOrNull { it.id == request.netId } ?: return false
                network.copyMove(conn, request.items.map { it.path }, destination.path, request.mode == TransferMode.MOVE)
            }
        }
    }


internal fun AppViewModel.destinationsFor(mode: TransferMode, ctx: FileContext): List<Destination> {
        // 只认真实绝对路径：localFolders 里万一混进「最近 / 收藏」这类虚拟区域，
        // 复制 / 移动会写到非法位置（或干脆建出一个叫「最近」的目录），这里直接挡掉。
        val locals = state.localFolders.filter { it.path.startsWith("/") }
            .map { Destination(DestType.LOCAL, it.path, it.name, "内部存储 / ${it.name}", it.path) }
            .ifEmpty { listOf(Destination(DestType.LOCAL, local.downloadsDir().absolutePath, "下载", "内部存储 / 下载", local.downloadsDir().absolutePath)) }
        val syno = if (state.session != null) state.shares.map {
            Destination(DestType.SYNO, it.path, it.title, "群晖 NAS / ${it.title}", it.path)
        } else emptyList()
        // 不要只放 online 的连接：从未测试过的连接 online=false，会让「上传」
        // 直接变成「没有可用的目标位置」。离线的连接仍可选，失败时给明确原因。
        val net = state.connections.map {
            // WebDAV 的根必须是绝对 URL（否则 OkHttp 直接抛异常 → 上传失败）；
            // FTP / SFTP / SMB 用远端路径，空路径回退到 "/"。
            val root = network.rootPath(it)
            val suffix = if (it.online) "" else " · 未连接"
            Destination(DestType.NET, it.id, it.name, "${it.protocol} / ${it.host}$suffix", root)
        }
        return when (mode) {
            TransferMode.DOWNLOAD -> locals
            TransferMode.UPLOAD -> syno + net
            // 同端复制/移动只给同端目标，跨端一律走上传/下载。
            TransferMode.COPY -> when (ctx) {
                FileContext.LOCAL -> locals
                FileContext.SYNO -> syno
                FileContext.NET -> net.filter { it.key == state.browser?.netId }
            }
            TransferMode.MOVE -> when (ctx) {
                FileContext.LOCAL -> locals
                FileContext.SYNO -> syno
                FileContext.NET -> net.filter { it.key == state.browser?.netId }
            }
        }
    }


internal fun AppViewModel.joinRemote(base: String, name: String): String {
        val left = base.trimEnd('/')
        return "$left/$name"
    }


    /** Directory tree used by the upload / download / copy target picker. */
internal suspend fun AppViewModel.childDirectories(dest: Destination): List<Destination> = withContext(Dispatchers.IO) {
        when (dest.type) {
            DestType.LOCAL -> runCatching {
                File(dest.path).listFiles().orEmpty()
                    .filter { it.isDirectory && !it.name.startsWith('.') && it.canRead() }
                    .sortedBy { it.name.lowercase() }
                    .map { child ->
                        Destination(
                            DestType.LOCAL, child.absolutePath, child.name,
                            "内部存储 / ${child.name}", child.absolutePath
                        )
                    }
            }.getOrDefault(emptyList())
            DestType.SYNO -> {
                val session = state.session ?: return@withContext emptyList()
                runCatching {
                    dsm.list(session, dest.path).filter { it.isFolder }.map { item ->
                        val child = joinRemote(dest.path, item.name)
                        Destination(DestType.SYNO, child, item.name, "群晖 NAS / ${item.name}", child)
                    }
                }.getOrDefault(emptyList())
            }
            DestType.NET -> {
                val conn = state.connections.firstOrNull { it.id == dest.key }
                    ?: return@withContext emptyList()
                runCatching {
                    network.list(conn, dest.path).filter { it.isFolder }.map { item ->
                        val child = joinRemote(dest.path, item.name)
                        Destination(DestType.NET, dest.key, item.name, "${conn.protocol} / ${item.name}", child)
                    }
                }.getOrDefault(emptyList())
            }
        }
    }


internal fun AppViewModel.parentDestination(dest: Destination): Destination? {
        val trimmed = dest.path.trimEnd('/')
        val isUrl = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
        val parent = if (isUrl) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val up = path.substringBeforeLast('/', "")
            if (up.isBlank() || up == path) return null
            "${uri.scheme}://${uri.authority}$up"
        } else {
            trimmed.substringBeforeLast('/', "")
        }
        if (parent.isBlank() || parent == trimmed) return null
        if (dest.type == DestType.LOCAL && !parent.startsWith(state.localRoot.trimEnd('/'))) return null
        if (isUrl) {
            // 不允许「上一级」逃出该连接的根 URL，否则会拼出 https:/ 之类的坏路径。
            val root = state.connections.firstOrNull { it.id == dest.key }
                ?.let { network.rootPath(it).trimEnd('/') }
            if (!root.isNullOrBlank() && !parent.startsWith(root)) return null
        }
        val name = if (isUrl) parent.trimEnd('/').substringAfterLast('/')
            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        else displayLeaf(parent, state.localRoot, "上级目录")
        val label = name.ifBlank { "上级目录" }
        return dest.copy(
            label = label,
            meta = dest.meta.substringBefore(" / ") + " / " + label,
            path = parent
        )
    }


    /** Create a sub-folder inside a picker destination (local storage only). */
internal fun AppViewModel.createDirectoryAt(dest: Destination, name: String): Boolean {
        if (name.isBlank() || dest.type != DestType.LOCAL) return false
        return runCatching { File(dest.path, name).mkdirs() }.getOrDefault(false)
    }


internal fun AppViewModel.setDownloadFolder(path: String, silent: Boolean = false) {
        if (path.isBlank()) return
        local.setDownloadFolder(path)
        state = state.copy(
            prefs = state.prefs.copy(downloadFolder = path),
            message = if (silent) null else "默认下载位置已更新"
        )
        persist()
    }


internal fun AppViewModel.downloadFolderCandidates(): List<Pair<String, String>> {
        val base = state.localFolders.map { it.path to it.name }.toMutableList()
        val downloads = local.downloadsDir()
        if (base.none { it.first == downloads.absolutePath }) {
            base += downloads.absolutePath to downloads.name
        }
        return base.distinctBy { it.first }
    }
