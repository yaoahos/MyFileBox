package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.displayParent
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


/**
 * 群晖回收站：把某一层的结果写进当前列表，同时更新分层缓存。
 *
 * 缓存只服务于「切换层级时的转场」：返回上一层如果还要等网络，滑进来的就是
 * 一张「正在加载回收站…」的空白页，和浏览页返回时直接显示已有列表的观感对不上。
 * 本地回收站没有层级，不写缓存。
 */
private fun AppViewModel.putRecycleLevel(dir: String, items: List<RecycleItem>, loading: Boolean = false) {
    state = state.copy(
        recycleItems = items,
        recycleLoading = loading,
        recycleLevels = if (state.recycleContext == FileContext.SYNO) {
            state.recycleLevels.trimmedLevel(dir, items)
        } else {
            state.recycleLevels
        }
    )
}


/** 条目内容变了（还原 / 彻底删除）：只更新当前这一层的缓存。 */
private fun MyFileBoxState.withRecycleItems(items: List<RecycleItem>): MyFileBoxState = copy(
    recycleItems = items,
    recycleLevels = if (recycleContext == FileContext.SYNO) recycleLevels.trimmedLevel(recycleDir, items) else recycleLevels
)


/** 分层缓存只留最近 12 层，长时间逐层浏览也不积压。 */
private fun Map<String, List<RecycleItem>>.trimmedLevel(dir: String, items: List<RecycleItem>): Map<String, List<RecycleItem>> {
    val next = this + (dir to items)
    if (next.size <= 12) return next
    return next.entries.drop(next.size - 12).associate { it.key to it.value }
}


internal fun AppViewModel.openRecycle(ctx: FileContext = FileContext.LOCAL) {
        if (ctx == FileContext.SYNO) {
            // 上一次看过的汇总层先顶上：转场滑进来时就有内容，随后静默刷新。
            val cached = if (state.recycleContext == FileContext.SYNO) state.recycleLevels[""] else null
            state = state.copy(
                recycleOpen = true,
                recycleSelected = emptySet(),
                recycleSelecting = false,
                // 每次进来都是全新的一次查看：上次的筛选不能让「回收站是空的」变成假象。
                recycleNameFilter = "",
                recycleExtFilter = "",
                recycleContext = FileContext.SYNO,
                recycleDir = "",
                recycleItems = cached ?: emptyList(),
                recycleLoading = cached == null
            )
            viewModelScope.launch {
                // 索引是本地 JSON 文件，读它和合并后的写回都不能留在 UI 线程。
                val items = withContext(Dispatchers.IO) { loadSynoRecycleSummary() }
                if (state.recycleContext == FileContext.SYNO && state.recycleDir.isBlank()) {
                    putRecycleLevel("", items)
                }
            }
        } else {
            // 同一次进入过的本地清单先顶上，避免多闪一帧空态；真列表在 IO 线程重读。
            val cached = if (state.recycleContext == FileContext.LOCAL) state.recycleItems else emptyList()
            state = state.copy(
                recycleOpen = true,
                recycleSelected = emptySet(),
                recycleSelecting = false,
                recycleNameFilter = "",
                recycleExtFilter = "",
                recycleContext = FileContext.LOCAL,
                recycleDir = "",
                recycleItems = cached,
                recycleLoading = true
            )
            viewModelScope.launch {
                val items = withContext(Dispatchers.IO) { readLocalRecycle() }
                if (state.recycleContext == FileContext.LOCAL) {
                    state = state.copy(recycleItems = items, recycleLoading = false)
                }
            }
        }
    }


/**
 * 读本地回收站清单（每个条目要读一次 .meta 并做两次 stat）。
 *
 * 条目多时是几百毫秒的活，只能在 IO 线程调用：放在 UI 线程会让进入回收站明显卡一下。
 */
private fun AppViewModel.readLocalRecycle(): List<RecycleItem> =
        runCatching { local.listRecycle() }.getOrDefault(emptyList())


internal fun AppViewModel.closeRecycle() {
        state = state.copy(
            recycleOpen = false,
            recycleSelected = emptySet(),
            recycleSelecting = false,
            // 下次进来从汇总视图开始，不能停在上次钻进去的子目录。
            recycleDir = ""
        )
    }


    /** 回收站下拉刷新：与其它文件列表页保持一致的手势。 */
/**
 * 群晖回收站：点进一个文件夹。
 *
 * DSM 的 #recycle 保留原路径，删 /photo/trip/a.jpg 后是 /photo/#recycle/trip/a.jpg。
 * 以前整棵树被 BFS 打平成一张清单，同名文件堆在一起看不出归属；现在文件夹点得进去，
 * 逐层展开。本地回收站不走这里——本地每个条目就是一个独立删除单元，夹内不是可浏览目录。
 */
internal fun AppViewModel.openRecycleFolder(item: RecycleItem) {
        if (state.recycleContext != FileContext.SYNO || !item.isFolder) return
        val dir = item.trashPath.trimEnd('/')
        // 之前进过这一层就直接用缓存顶上，转场期间就有内容，不等网络。
        val cached = state.recycleLevels[dir]
        state = state.copy(
            recycleDir = dir,
            recycleSelected = emptySet(),
            recycleSelecting = false,
            recycleItems = cached ?: emptyList(),
            recycleLoading = cached == null,
            // 换目录等于换内容，沿用筛选只会得到一屏空。
            recycleNameFilter = "",
            recycleExtFilter = ""
        )
        val session = state.session
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { loadSynoRecycleDir(session, dir) }
            if (state.recycleDir == dir) {
                putRecycleLevel(dir, items)
            }
        }
    }


    /**
     * 离开回收站页面时把「钻到第几层」重置掉。
     *
     * 只清 `recycleDir` 不够：列表里装的还是子目录内容，回来就会对不上。
     * 回收站仍开着（被设置 / 分享等页面盖住）时顺手重拉一次汇总视图，
     * 这样返回回收站看到的就是各共享 `#recycle` 的并列层，而不是上次那个子目录。
     */
internal fun AppViewModel.resetRecycleToSummary() {
        if (state.recycleDir.isBlank()) return
        val stillOpen = state.recycleOpen
        val cached = if (state.recycleContext == FileContext.SYNO) state.recycleLevels[""] else null
        state = state.copy(
            recycleDir = "",
            recycleItems = cached ?: emptyList(),
            recycleLoading = stillOpen && cached == null,
            recycleSelected = emptySet(),
            recycleSelecting = false
        )
        if (!stillOpen) return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { loadSynoRecycleSummary() }
            if (state.recycleOpen && state.recycleDir.isBlank()) {
                putRecycleLevel("", items)
            }
        }
    }


    /** 群晖回收站返回上一层：子目录 → 上级目录；已在 #recycle 根 → 回汇总视图。 */
internal fun AppViewModel.backRecycleFolder() {
        val dir = state.recycleDir
        if (dir.isBlank()) return
        val parent = dir.trimEnd('/').substringBeforeLast('/', "")
        // 到 share/#recycle 这一层就没有「上一级目录」可看了：汇总视图本来就是
        // 各共享 #recycle 的一层内容。
        val toSummary = parent.isBlank() || parent.endsWith("/#recycle")
        val target = if (toSummary) "" else parent
        // 上一层刚才就在屏幕上，缓存一定有；直接顶上，滑进来就是完整列表。
        val cached = state.recycleLevels[target]
        state = state.copy(
            recycleDir = target,
            recycleSelected = emptySet(),
            recycleSelecting = false,
            recycleItems = cached ?: emptyList(),
            recycleLoading = cached == null
        )
        val session = state.session
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                if (toSummary) {
                    loadSynoRecycleSummary()
                } else {
                    loadSynoRecycleDir(session, parent)
                }
            }
            if (state.recycleDir == target) {
                putRecycleLevel(target, items)
            }
        }
    }


/**
 * 群晖回收站的汇总视图：各共享 `#recycle` 下的**一层**条目 + 记录在案的删除单元。
 *
 * 以前这里是 BFS 全量摊平（上限 500 条），深层文件全平铺在一屏里，同名文件
 * 根本看不出归属。现在只列一层，文件夹点得进去，逐层展开就是原目录结构。
 * 记在索引里的「删除单元」仍直接列出来——那是用户真正删掉的那个文件夹，
 * 不该被埋进中间层目录里。
 */
internal suspend fun AppViewModel.loadSynoRecycleSummary(): List<RecycleItem> {
        val session = state.session ?: return emptyList()
        val items = dsm.listRecycleBin(
            session,
            state.shares.map { s -> s.path },
            topLevelOnly = true,
            tracked = synoRecycleIndex.all()
        )
        pruneSynoRecycleIndex(items)
        return items
    }


/** 列 `#recycle` 的某一层；每个条目仍带「删除前在哪」，还原照旧按它走。 */
private suspend fun AppViewModel.loadSynoRecycleDir(session: Session?, dir: String): List<RecycleItem> {
        if (session == null) return emptyList()
        val listed = runCatching { dsm.list(session, dir) }.getOrDefault(emptyList())
        return listed.filter { it.name != "#recycle" }.map { entry ->
            RecycleItem(
                id = entry.path,
                name = entry.name,
                originalPath = originalPathOfTrash(entry.path),
                trashPath = entry.path,
                deletedAt = entry.modified,
                sizeBytes = entry.sizeBytes,
                isFolder = entry.isFolder
            )
        }
    }


/**
 * 重新拉「当前这一屏」的回收列表。
 *
 * 钻在 `#recycle` 子目录里时只刷这一层——否则还原 / 删除一个条目之后，
 * 列表会突然被换回汇总视图，用户明明还停在文件夹里。
 */
internal suspend fun AppViewModel.recycleItemsNow(): List<RecycleItem> =
        if (state.recycleContext == FileContext.SYNO && state.recycleDir.isNotBlank()) {
            loadSynoRecycleDir(state.session, state.recycleDir)
        } else if (state.recycleContext == FileContext.SYNO) {
            loadSynoRecycleSummary()
        } else {
            readLocalRecycle()
        }


/**
 * `#recycle` 里的路径还原成删除前的原路径：
 * `/photo/#recycle/trip/a.jpg` → `/photo/trip/a.jpg`。
 */
internal fun originalPathOfTrash(trashPath: String): String {
        val marker = "/#recycle"
        val idx = trashPath.indexOf(marker)
        if (idx < 0) return trashPath
        val share = trashPath.substring(0, idx)
        val rel = trashPath.substring(idx + marker.length).trimStart('/')
        return if (rel.isBlank()) share else "$share/$rel"
    }


internal fun AppViewModel.refreshRecycle() {
        // 与其它页同一套下拉令牌：beginPull 开圈、endPull 收圈，endPull 会撑满
        // minPullVisibleMs 再落下「已刷新」，避免转圈还没转完提示就抢先出现。
        val token = beginPull()
        val ctx = state.recycleContext
        if (ctx == FileContext.SYNO) {
            val session = state.session ?: run { endPull(token); return }
            viewModelScope.launch {
                // 先清掉上一次操作残留的错误，本轮失败与否只看这一轮的 lastError。
                dsm.lastError = null
                val dir = state.recycleDir
                val atSummary = dir.isBlank()
                val items = withContext(Dispatchers.IO) {
                    if (atSummary) {
                        loadSynoRecycleSummary()
                    } else {
                        // 钻在子目录里刷新，刷的就该是这一层，别把用户从文件夹里弹出去。
                        loadSynoRecycleDir(session, dir)
                    }
                }
                // 索引保存的是「删除单元」（整只文件夹）。只有汇总层拿到的才是全量列表，
                // 子目录只是一层，拿它 retain 会把其它目录的删除单元记录全清掉。
                if (atSummary) withContext(Dispatchers.IO) { pruneSynoRecycleIndex(items) }
                // 刷新未回时可能已经返回到父目录：不能把子目录的结果写进父目录的列表和缓存。
                if (state.recycleDir == dir && state.recycleContext == FileContext.SYNO) {
                    // 列表数据先落（不等转圈），但 refreshing / message 交给 endPull 在转圈结束后统一收。
                    state = state.withRecycleItems(items)
                    val notice = dsm.lastError?.takeIf { it.isNotBlank() } ?: "已刷新"
                    endPull(token, notice)
                } else {
                    endPull(token)
                }
            }
        } else {
            // 本地同样不能在 UI 线程读清单：条目上千时读盘就是一次明显掉帧。
            viewModelScope.launch {
                val items = withContext(Dispatchers.IO) { readLocalRecycle() }
                if (state.recycleContext == FileContext.LOCAL) {
                    state = state.copy(recycleItems = items)
                    endPull(token, "已刷新")
                } else {
                    endPull(token)
                }
            }
        }
    }


internal fun AppViewModel.restoreRecycle(item: RecycleItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                when (state.recycleContext) {
                    FileContext.SYNO -> state.session?.let { dsm.restoreRecycleItem(it, item) } ?: false
                    else -> local.restoreRecycle(item)
                }
            }
            val destParent = displayParent(item.originalPath, state.localRoot)
            state = state.copy(
                message = if (ok) "已还原「${item.name}」到 $destParent" else
                    if (state.recycleContext == FileContext.SYNO) dsm.lastError ?: "恢复失败" else "恢复失败"
            )
            if (state.recycleContext == FileContext.SYNO) {
                if (ok) withContext(Dispatchers.IO) { synoRecycleIndex.remove(listOf(item.trashPath)) }
                val items = withContext(Dispatchers.IO) { recycleItemsNow() }
                state = state.withRecycleItems(items)
                // 群晖恢复后要让原目录重新拉一次列表，否则回到原位置还是旧内容。
                if (ok) {
                    refreshSyno()
                    reloadBrowserItems()
                    return@launch
                }
            } else {
                // 还原后重读清单同样走 IO：这一步和进入页面时的读盘是同一种开销。
                val items = withContext(Dispatchers.IO) { readLocalRecycle() }
                state = state.copy(recycleItems = items)
                if (ok) {
                    // 立即把还原项增量插回缓存：全盘扫描要几秒到几十秒，
                    // 只靠 refreshLocal 会让用户回到原位置时看不到刚恢复的文件。
                    applyLocalDelta(
                        addedFiles = listOfNotNull(localEntryOf(item)),
                        addedFolders = listOfNotNull(localFolderOf(item))
                    )
                } else {
                    refreshLocal()
                }
            }
            reloadBrowserItems()
        }
    }


    /** 刚还原的本地文件 → 缓存条目（供 applyLocalDelta 立即插回原位置）。 */
internal fun AppViewModel.localEntryOf(item: RecycleItem): FileEntry? {
        if (item.isFolder) return null
        val now = System.currentTimeMillis()
        return FileEntry(
            id = item.originalPath,
            name = item.name,
            kind = FileKinds.of(item.name),
            sizeLabel = LocalRepository.formatSize(item.sizeBytes),
            timeLabel = LocalRepository.formatStamp(now),
            folder = displayParent(item.originalPath, state.localRoot),
            path = item.originalPath,
            sizeBytes = item.sizeBytes,
            modified = now
        )
    }


    /** 刚还原的本地文件夹 → 缓存条目。 */
internal fun AppViewModel.localFolderOf(item: RecycleItem): FolderCard? {
        if (!item.isFolder) return null
        return FolderCard(
            name = item.name,
            meta = "",
            kind = FileKind.FOLDER,
            path = item.originalPath,
            date = LocalRepository.formatStamp(System.currentTimeMillis()),
            count = item.childCount
        )
    }


    /** 回收站多选操作（清单 6）。 */
internal fun AppViewModel.toggleRecycleSelected(item: RecycleItem) {
        val next = if (item.id in state.recycleSelected) state.recycleSelected - item.id
        else state.recycleSelected + item.id
        state = state.copy(recycleSelected = next, recycleSelecting = true)
    }


    /** 回收站在当前筛选条件下可见的条目：列表、计数、全选、空态全都读这一份。 */
internal fun AppViewModel.recycleVisibleItems(): List<RecycleItem> =
        filterVisible(
            state.recycleItems,
            state.recycleNameFilter.trim(),
            state.recycleExtFilter.trim(),
            { it.name },
            { it.isFolder }
        )


internal fun AppViewModel.setRecycleFilters(name: String, ext: String) {
        val n = name.trim()
        val e = ext.trim()
        if (n == state.recycleNameFilter && e == state.recycleExtFilter) return
        val visible = filterVisible(state.recycleItems, n, e, { it.name }, { it.isFolder })
            .map { it.id }.toSet()
        state = state.copy(
            recycleNameFilter = n,
            recycleExtFilter = e,
            recycleSelected = state.recycleSelected.intersect(visible)
        )
    }


internal fun AppViewModel.clearRecycleFilters() {
        if (state.recycleNameFilter.isNotEmpty() || state.recycleExtFilter.isNotEmpty()) {
            state = state.copy(recycleNameFilter = "", recycleExtFilter = "")
        }
    }


internal fun AppViewModel.toggleRecycleSelectAll() {
        // 与浏览页同一口径：全选只认筛出来的那些，否则筛选就形同虚设。
        val visible = recycleVisibleItems().map { it.id }.toSet()
        val deselectAll = visible.isEmpty() || state.recycleSelected.containsAll(visible)
        state = state.copy(
            recycleSelected = if (deselectAll) emptySet() else visible,
            recycleSelecting = true
        )
    }


internal fun AppViewModel.clearRecycleSelection() {
        state = state.copy(recycleSelected = emptySet(), recycleSelecting = false)
    }


internal fun AppViewModel.recycleSelectedItems(): List<RecycleItem> =
        state.recycleItems.filter { it.id in state.recycleSelected }


internal fun AppViewModel.restoreRecycleMany(items: List<RecycleItem>) {
        if (items.isEmpty()) return
        val total = items.size
        viewModelScope.launch {
            state = state.copy(
                recycleSelected = emptySet(), recycleSelecting = false,
                localTaskLabel = "正在还原 $total 项…", localTaskRunning = true
            )
            var ok = 0
            var failed = 0
            var done = 0
            val failedIds = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                when (state.recycleContext) {
                    FileContext.SYNO -> {
                        val session = state.session
                        items.forEach {
                            if (session != null && dsm.restoreRecycleItem(session, it)) ok++ else {
                                failed++; failedIds += it.id
                            }
                            publishLocalTaskProgress("正在还原", ++done, total)
                        }
                    }
                    else -> items.forEach {
                        if (local.restoreRecycle(it)) ok++ else {
                            failed++; failedIds += it.id
                        }
                        publishLocalTaskProgress("正在还原", ++done, total)
                    }
                }
            }
            val restored = items.filter { it.id !in failedIds }
            if (state.recycleContext == FileContext.SYNO && restored.isNotEmpty()) {
                withContext(Dispatchers.IO) { synoRecycleIndex.remove(restored.map { it.trashPath }) }
            }
            val fresh = withContext(Dispatchers.IO) {
                when (state.recycleContext) {
                    FileContext.SYNO -> recycleItemsNow()
                    else -> runCatching { local.listRecycle() }.getOrDefault(emptyList())
                }
            }
            state = state.copy(
                localTaskRunning = false,
                localTaskLabel = "",
                recycleItems = fresh,
                // 刚变过的是当前这一层，顺手更新缓存（群晖）。
                recycleLevels = if (state.recycleContext == FileContext.SYNO) {
                    state.recycleLevels + (state.recycleDir to fresh)
                } else {
                    state.recycleLevels
                },
                message = when {
                    failed == 0 -> "已还原 $ok 项"
                    ok == 0 -> "还原失败：${dsm.lastError ?: "请稍后重试"}"
                    else -> "已还原 $ok 项，$failed 项失败"
                }
            )
            if (state.recycleContext == FileContext.SYNO) {
                if (restored.isNotEmpty()) refreshSyno()
            } else if (restored.isNotEmpty()) {
                // 多选还原同样先增量插回缓存，原位置立刻可见。
                applyLocalDelta(
                    addedFiles = restored.mapNotNull { localEntryOf(it) },
                    addedFolders = restored.mapNotNull { localFolderOf(it) }
                )
            } else {
                refreshLocal()
            }
            reloadBrowserItems()
        }
    }


    /** 彻底删除选中的回收条目（清单 6）：本地删回收站文件，群晖删 #recycle 项。 */
internal fun AppViewModel.deleteRecycleItems(items: List<RecycleItem>) {
        if (items.isEmpty()) return
        val total = items.size
        viewModelScope.launch {
            state = state.copy(
                recycleSelected = emptySet(), recycleSelecting = false,
                localTaskLabel = "正在彻底删除 $total 项…", localTaskRunning = true
            )
            val ok = withContext(Dispatchers.IO) {
                when (state.recycleContext) {
                    FileContext.SYNO -> {
                        val session = state.session
                        session != null && dsm.deleteRecycleItems(session, items.map { it.trashPath })
                    }
                    else -> {
                        var done = true
                        var count = 0
                        items.forEach { item ->
                            val target = java.io.File(item.trashPath)
                            runCatching { if (target.exists()) target.deleteRecursively() }.onFailure { done = false }
                            publishLocalTaskProgress("正在彻底删除", ++count, total)
                        }
                        done
                    }
                }
            }
            if (state.recycleContext == FileContext.SYNO && ok) {
                withContext(Dispatchers.IO) { synoRecycleIndex.remove(items.map { it.trashPath }) }
            }
            val fresh = withContext(Dispatchers.IO) {
                when (state.recycleContext) {
                    FileContext.SYNO -> recycleItemsNow()
                    else -> runCatching { local.listRecycle() }.getOrDefault(emptyList())
                }
            }
            state = state.copy(
                localTaskRunning = false,
                localTaskLabel = "",
                recycleItems = fresh,
                recycleLevels = if (state.recycleContext == FileContext.SYNO) {
                    state.recycleLevels + (state.recycleDir to fresh)
                } else {
                    state.recycleLevels
                },
                message = if (ok) "已彻底删除 ${items.size} 项" else "删除失败：${dsm.lastError ?: "请稍后重试"}"
            )
        }
    }


internal fun AppViewModel.emptyRecycle() {
        // 群晖子目录里只允许逐项删；清空作用于整个 bin，站在子层执行会只删一层、
        // 却把删除单元索引清光，留下再也还原不了的残留项。UI 已隐藏该入口，这里兜底。
        if (state.recycleContext == FileContext.SYNO && state.recycleDir.isNotBlank()) return
        viewModelScope.launch {
            // 清空回收站是同类静默操作：本地要递归删掉整棵回收站目录，群晖是一次
            // deleteRecycleItems 请求，文件一多就是几秒钟没有任何反馈。顶部任务条兜住。
            val pending = state.recycleItems.size
            state = state.copy(
                localTaskLabel = "正在清空回收站 · $pending 项…",
                localTaskRunning = true
            )
            try {
                if (state.recycleContext == FileContext.SYNO) {
                    val session = state.session
                    val paths = state.recycleItems.map { it.trashPath }
                    val ok = withContext(Dispatchers.IO) {
                        if (session != null && paths.isNotEmpty()) dsm.deleteRecycleItems(session, paths) else true
                    }
                    if (ok) withContext(Dispatchers.IO) { synoRecycleIndex.clear() }
                    val fresh = withContext(Dispatchers.IO) {
                        session?.let {
                            dsm.listRecycleBin(it, state.shares.map { s -> s.path }, tracked = synoRecycleIndex.all())
                        }.orEmpty()
                    }
                    state = state.copy(
                        // 清空是跨层的破坏性操作，缓存整块作废，下次进页面重新拉。
                        recycleLevels = emptyMap(),
                        recycleItems = fresh,
                        message = if (ok) "已清空回收站 · ${paths.size} 项" else dsm.lastError ?: "清空失败"
                    )
                } else {
                    val count = withContext(Dispatchers.IO) { local.emptyRecycle() }
                    state = state.copy(
                        recycleItems = emptyList(),
                        message = "已清空回收站 · $count 项"
                    )
                }
            } finally {
                state = state.copy(localTaskRunning = false, localTaskLabel = "")
            }
        }
    }


internal fun AppViewModel.setRecycleDays(days: Int) {
        state = state.copy(prefs = state.prefs.copy(recycleDays = days))
        persist()
        // 0 = 永不，绝不触发清理；只有正数天数才按过期时间清理。
        if (days > 0) launchCleanup(days)
        else state = state.copy(message = "已设为永不自动清理")
    }


internal fun AppViewModel.launchCleanup(days: Int) {
        // 清理本身在 IO 线程做，但 state 更新必须回到主线程：
        // 在 IO 线程直接 state.copy(...) 会形成「读旧快照→写回」的竞态，
        // 曾把主线程刚恢复的网络连接列表整个覆盖掉（连接莫名消失的元凶）。
        viewModelScope.launch(Dispatchers.IO) {
            local.purgeRecycle(days)
            val items = local.listRecycle()
            withContext(Dispatchers.Main.immediate) {
                state = state.copy(recycleItems = items)
                // 自动清理删掉了过期文件，首页与当前目录要同步更新。
                refreshLocal()
            }
        }
    }
