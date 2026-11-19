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


    // ---------------------------------------------------------------- delete / share

internal fun AppViewModel.deleteFile(file: FileEntry) {
        state = state.copy(
            pendingDelete = listOf(file),
            pendingDeleteLabel = file.name
        )
    }


internal fun AppViewModel.deleteSelected() = deleteEntries(selectedItems())


    /**
     * 删除一组条目（浏览页 / 首页 / 搜索页共用）。先弹确认，不点一下就删。
     *
     * [fromSearch] 让确认后一并退出搜索页的独立勾选态。
     */
internal fun AppViewModel.deleteEntries(entries: List<FileEntry>, fromSearch: Boolean = false) {
        if (entries.isEmpty()) return
        state = state.copy(
            pendingDelete = entries,
            pendingDeleteLabel = if (entries.size == 1) entries.first().name else "${entries.size} 个项目",
            pendingDeleteSearch = fromSearch
        )
    }


internal fun AppViewModel.cancelDelete() {
        state = state.copy(
            pendingDelete = emptyList(),
            pendingDeleteLabel = "",
            pendingDeleteConnection = null,
            pendingDeleteHome = false,
            pendingDeleteSearch = false,
            pendingDeleteNet = null
        )
    }


internal fun AppViewModel.confirmDelete() {
        val items = state.pendingDelete
        if (items.isEmpty()) return
        val fromHome = state.pendingDeleteHome
        // 搜索页删除要按搜索的端走：此刻浏览页可能停在另一个端，甚至根本没有（browser == null）。
        val fromSearch = state.pendingDeleteSearch
        val searchCtx = state.search?.ctx
        val searchNet = state.pendingDeleteNet
        state = state.copy(
            pendingDelete = emptyList(),
            pendingDeleteLabel = "",
            pendingDeleteHome = false,
            pendingDeleteSearch = false,
            pendingDeleteNet = null,
            selected = emptySet(),
            selectionActive = false,
            homeSelected = if (fromHome) emptySet() else state.homeSelected,
            homeSelecting = if (fromHome) false else state.homeSelecting,
            searchSelected = if (fromSearch) emptySet() else state.searchSelected,
            searchSelecting = if (fromSearch) false else state.searchSelecting
        )
        deleteFiles(
            items,
            ctxOverride = if (fromSearch) searchCtx else null,
            netIdOverride = searchNet
        )
    }


    /**
     * 搜索结果的「删除」入口。
     *
     * 网络位置的搜索结果可能横跨多个连接，`deleteFiles` 一次只认一个连接：
     * 先把条目按所属连接分组，跨连接就明确告诉用户分开选，别静默只删掉一半。
     */
internal fun AppViewModel.deleteSearchEntries(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        val ctx = state.search?.ctx ?: return
        if (ctx != FileContext.NET) {
            deleteEntries(entries, fromSearch = true)
            return
        }
        val groups = entries.groupBy { netConnectionIdOf(it.path) }
        if (groups.keys.any { it == null } || groups.size != 1) {
            state = state.copy(message = "这些文件来自不同的网络位置，请分开选择后再删除")
            return
        }
        val connId = groups.keys.first()!!
        state = state.copy(
            pendingDelete = entries,
            pendingDeleteLabel = if (entries.size == 1) entries.first().name else "${entries.size} 个项目",
            pendingDeleteSearch = true,
            pendingDeleteNet = connId
        )
    }


    /** 某个远端路径属于哪个网络连接（搜索结果没有 netId，只能反查索引）。 */
internal fun AppViewModel.netConnectionIdOf(path: String): String? {
        state.searchIndexNet.forEach { (id, list) ->
            if (list.any { it.path == path }) return id
        }
        state.netFiles.forEach { (id, list) ->
            if (list.any { it.path == path }) return id
        }
        return null
    }


internal fun AppViewModel.requestDeleteConnection(connection: NetworkConnection) {
        state = state.copy(pendingDeleteConnection = connection.id)
    }


internal fun AppViewModel.confirmDeleteConnection() {
        val id = state.pendingDeleteConnection ?: return
        val conn = state.connections.firstOrNull { it.id == id } ?: return
        state = state.copy(
            pendingDeleteConnection = null,
            connections = state.connections.filterNot { it.id == id },
            netFiles = state.netFiles - id,
            message = "已删除连接「${conn.name}」"
        )
        persist()
    }


internal fun AppViewModel.stageFolderClipboard(mode: TransferMode, folder: FolderCard) {
        val entry = FileEntry(
            id = "home-folder:${folder.path}",
            name = folder.name,
            kind = FileKind.FOLDER,
            sizeLabel = "文件夹",
            timeLabel = folder.date,
            folder = folder.meta,
            childCount = folder.count,
            path = folder.path
        )
        state = state.copy(
            clipboard = ClipboardState(mode, listOf(entry), FileContext.LOCAL, null, state.localRoot),
            selected = emptySet(),
            selectionActive = false,
            message = if (mode == TransferMode.COPY) "已复制，进入目标文件夹后点粘贴" else "已剪切，进入目标文件夹后点粘贴"
        )
    }


internal fun AppViewModel.startHomeFolderTransfer(mode: TransferMode, folder: FolderCard) {
        val entry = FileEntry(
            id = "home-folder:${folder.path}",
            name = folder.name,
            kind = FileKind.FOLDER,
            sizeLabel = "文件夹",
            timeLabel = folder.date,
            folder = folder.meta,
            childCount = folder.count,
            path = folder.path
        )
        val destinations = state.localFolders
            .filter { it.path != folder.path }
            .map { Destination(DestType.LOCAL, it.path, it.name, "内部存储 / ${it.name}", it.path) }
            .ifEmpty {
                val downloads = local.downloadsDir().absolutePath
                listOf(Destination(DestType.LOCAL, downloads, "下载", "内部存储 / 下载", downloads))
            }
        state = state.copy(
            transfer = TransferRequest(mode, listOf(entry), FileContext.LOCAL, null, destinations)
        )
    }


internal fun AppViewModel.deleteHomeFolder(folder: FolderCard) {
        val entry = FileEntry(
            id = "home-folder:${folder.path}",
            name = folder.name,
            kind = FileKind.FOLDER,
            path = folder.path
        )
        state = state.copy(pendingDelete = listOf(entry), pendingDeleteLabel = folder.name)
    }


    /**
     * 删除一组条目。
     *
     * [ctxOverride] / [netIdOverride] 给搜索页用：搜索页没有 `browser`，
     * 来源端与网络连接都得由调用方传进来，否则会按「当前浏览页」删错端。
     */
internal fun AppViewModel.deleteFiles(
        items: List<FileEntry>,
        ctxOverride: FileContext? = null,
        netIdOverride: String? = null
    ) {
        if (items.isEmpty()) return
        val ctx = ctxOverride ?: state.browser?.ctx ?: FileContext.LOCAL
        val total = items.size
        // 批量删除没有传输任务可看：不给进度条的话，文件一多就是「点了没反应」，
        // 用户只能干等着猜。本地删除逐项可报进度，远端删除是一次请求只能给不定进度。
        state = state.copy(
            localTaskLabel = "正在删除 $total 项…",
            localTaskRunning = true
        )
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                when (ctx) {
                    FileContext.LOCAL -> local.recycle(items) { done, doneTotal ->
                        publishLocalTaskProgress("正在删除", done, doneTotal)
                    } > 0
                    FileContext.SYNO -> state.session?.let { dsm.delete(it, items.map { f -> f.path }) } ?: false
                    FileContext.NET -> {
                        val conn = state.connections.firstOrNull { it.id == (netIdOverride ?: state.browser?.netId) }
                        if (conn == null) false else network.delete(conn, items.map { it.path })
                    }
                }
            }
            state = state.copy(
                localTaskRunning = false,
                localTaskLabel = "",
                selected = emptySet(),
                selectionActive = false,
                fileSheet = null,
                message = if (ok) {
                    if (ctx == FileContext.LOCAL) "已移至回收站 · ${items.size} 项" else "已删除 ${items.size} 项"
                } else "删除失败"
            )
            if (ok) {
                if (ctx == FileContext.LOCAL) {
                    // 增量更新瞬时生效（清单 14/20）；分类页由 applyLocalDelta→refreshLocal 重算，
                    // 不再闪现根目录（清单 1）。
                    applyLocalDelta(
                        removed = items.filter { !it.isFolder },
                        removedFolders = items.filter { it.isFolder }.map {
                            FolderCard(
                                name = it.name, meta = "", kind = FileKind.FOLDER,
                                path = it.path, date = it.timeLabel, count = it.childCount
                            )
                        }
                    )
                    // 分类页/真实目录再按磁盘实况校准一次，确保「提示成功」与「列表消失」同步。
                    reloadBrowserItems()
                } else {
                    if (ctx == FileContext.SYNO) {
                        // 记下本次删除单元：回收站按整只文件夹展示/还原（#recycle 目录树区分不出来）。
                        synoRecycleIndex.add(items.mapNotNull { synoRecycleRecordOf(it) })
                        refreshSyno()
                    }
                    reloadBrowserItems()
                }
            }
        }
    }


/**
 * 逐项 / 逐文件的进度提示。
 *
 * 由 IO 线程上的回调调用，这里负责切回主线程写状态并做节流：几百个文件时
 * 不能每处理一个就重组一次界面，大约 20 次更新足够让人看出操作在推进。
 *
 * [itemDone]/[itemTotal] 用于目录内部进度：粘贴一个大文件夹时外层只有 1 项，
 * 只按项计数会一直停在「1/1」，用户依然觉得卡住。
 */
internal fun AppViewModel.publishLocalTaskProgress(
        prefix: String,
        done: Int,
        total: Int,
        itemDone: Int = 0,
        itemTotal: Int = 0
    ) {
        val inner = itemTotal > 1
        if (total <= 1 && !inner) return
        val unit = if (inner) itemTotal else total
        val at = if (inner) itemDone else done
        val step = (unit / 20).coerceAtLeast(1)
        if (at != unit && at % step != 0) return
        val label = when {
            total > 1 && inner -> "$prefix $done/$total 项 · 当前 $itemDone/$itemTotal 个文件…"
            total > 1 -> "$prefix $done/$total 项…"
            else -> "$prefix $itemDone/$itemTotal 个文件…"
        }
        viewModelScope.launch {
            // 操作可能已经结束（收尾已清空），别把标签又写回去。
            if (state.localTaskRunning) {
                state = state.copy(localTaskLabel = label)
            }
        }
    }


internal suspend fun AppViewModel.reloadBrowserItems() {
        val browser = state.browser ?: return
        // 收藏页不是真实目录：它的内容是 favorites 集合算出来的快照。
        // 以前这里走 local.list(root)，刷新后要么把根目录灌进收藏、要么纹丝不动，
        // 于是取消收藏的条目要退出重进才消失。现在一律按集合重算。
        if (browser.favorites) {
            val fresh = sortFiles(favoriteFiles(browser.ctx))
            val current = state.browser
            if (current != null && current.favorites && current.ctx == browser.ctx) {
                state = state.copy(browser = current.copy(items = fresh))
            }
            return
        }
        // 分类页（localCategory）没有真实目录路径：早期版本误走 local.list(root)
        // 会把根目录文件夹灌进分类列表（删除后「闪一下根目录」再变空的根因）。
        // 分类页必须按分类规则从快照重算。
        val category = browser.localCategory
        if (browser.ctx == FileContext.LOCAL && category != null) {
            val fresh = filesForLocalCategory(category, localSnapshotOf()) ?: return
            val ordered = if (category == "最近") fresh else sortFiles(fresh)
            val current = state.browser
            if (current != null && current.localCategory == category) {
                state = state.copy(browser = current.copy(items = ordered))
            }
            return
        }
        val items = withContext(Dispatchers.IO) {
            runCatching {
                when (browser.ctx) {
                    FileContext.LOCAL -> local.list(browser.location.ifBlank { state.localRoot })
                    FileContext.SYNO -> state.session?.let { dsm.list(it, browser.location) }.orEmpty()
                    FileContext.NET -> {
                        val connection = state.connections.firstOrNull { it.id == browser.netId }
                        if (connection == null) emptyList()
                        else network.list(connection, browser.location.ifBlank { connection.path })
                    }
                }
            }.getOrDefault(emptyList())
        }
        val current = state.browser
        if (current != null && current.ctx == browser.ctx && current.location == browser.location) {
            state = state.copy(browser = current.copy(items = sortFiles(items)))
        }
    }


    /** 由当前 state 的本地缓存拼出分类页所需快照（分类页重算用，避免误走目录列表）。 */
internal fun AppViewModel.localSnapshotOf(): com.yaoahos.myfilebox.android.data.LocalSnapshot =
        com.yaoahos.myfilebox.android.data.LocalSnapshot(
            files = state.localFiles,
            photos = state.photos,
            folders = state.localFolders,
            counts = state.categoryCounts,
            usedGb = state.localUsedGb,
            freeGb = state.localFreeGb,
            totalGb = state.localTotalGb,
            hasAccess = state.localAccess,
            rootPath = state.localRoot
        )


    /**
     * 本地增删的增量更新（清单 14/20）：创建/删除后先把内存缓存改对，UI 瞬时刷新；
     * 全量扫描移到后台静默校准，不再让每次操作都等整盘遍历。
     */
internal fun AppViewModel.applyLocalDelta(
        removed: List<FileEntry> = emptyList(),
        removedFolders: List<FolderCard> = emptyList(),
        addedFiles: List<FileEntry> = emptyList(),
        addedFolders: List<FolderCard> = emptyList()
    ) {
        val removedPaths = removed.map { it.path.trimEnd('/') }.toSet() +
            removedFolders.map { it.path.trimEnd('/') }.toSet()
        val files = state.localFiles.filterNot { it.path.trimEnd('/') in removedPaths } + addedFiles
        val folders = state.localFolders.filterNot { it.path.trimEnd('/') in removedPaths } + addedFolders
        // 分类计数同步增减，首页分类卡数字立即正确。
        val counts = state.categoryCounts.toMutableMap()
        removed.forEach { e -> counts[e.kind] = ((counts[e.kind] ?: 0) - 1).coerceAtLeast(0) }
        addedFiles.forEach { e -> counts[e.kind] = (counts[e.kind] ?: 0) + 1 }
        // 当前浏览页若是本地真实目录，条目同步增删；若是分类页（图片/安装包/最近…），
        // 没有真实目录路径，必须按分类规则从刚更新的缓存立即重算——否则「删除成功但仍显示」
        // 要等全盘扫描结束甚至手动刷新才消失。
        val browser = state.browser
        val category = browser?.takeIf { it.ctx == FileContext.LOCAL }?.localCategory
        val browserItems = if (browser != null && browser.ctx == FileContext.LOCAL && category == null) {
            val gone = removed.map { it.id }.toSet() + removedFolders.map { "folder:${it.path}" }.toSet()
            browser.items.filterNot { it.id in gone } + addedFiles.filter {
                it.path.trimEnd('/').substringBeforeLast('/') == browser.location.trimEnd('/')
            }
        } else if (browser != null && category != null) {
            val snapshot = localSnapshotOf().copy(files = files, folders = folders, counts = counts)
            val fresh = filesForLocalCategory(category, snapshot)
            if (fresh == null) browser.items
            else if (category == "最近") fresh else sortFiles(fresh)
        } else browser?.items
        state = state.copy(
            localFiles = files,
            localFolders = folders,
            categoryCounts = counts,
            browser = browser?.copy(items = browserItems ?: browser.items)
        )
        // 后台校准全量数据（不阻塞 UI）。
        refreshLocal()
    }
