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
import com.yaoahos.myfilebox.core.MODULE_LABEL_LOCAL
import com.yaoahos.myfilebox.core.MODULE_LABEL_NET
import com.yaoahos.myfilebox.core.MODULE_LABEL_SYNO
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


    // ---------------------------------------------------------------- selection

internal fun AppViewModel.toggleSelected(file: FileEntry) {
        val selected = state.selected.toMutableSet()
        if (!selected.add(file.id)) selected.remove(file.id)
        state = state.copy(selected = selected, selectionActive = true)
    }


internal fun AppViewModel.clearSelection() {
        state = state.copy(selected = emptySet(), selectionActive = false)
    }


    // ---- 搜索结果的多选：结果不在 browser.items 里，勾选单独存一份 ----

    /**
     * 搜索结果的勾选键。
     *
     * 不能直接用 `file.id`：搜索结果可能来自不同根（多共享 / 多连接），
     * 同名同大小文件的 id 未必唯一，用「路径 + 名称」才稳。
     */
internal fun AppViewModel.searchKeyOf(file: FileEntry): String = "${file.path}|${file.name}"


internal fun AppViewModel.toggleSearchSelected(file: FileEntry) {
        val key = searchKeyOf(file)
        val next = state.searchSelected.toMutableSet()
        if (!next.add(key)) next.remove(key)
        state = state.copy(
            searchSelected = next,
            searchSelecting = true
        )
    }


    /** 全选 / 取消全选：ids 由界面按「当前可见结果」算好传进来（含排序后的列表）。 */
internal fun AppViewModel.setSearchSelection(ids: Set<String>) {
        val deselectAll = ids.isEmpty() || state.searchSelected.containsAll(ids)
        state = state.copy(
            searchSelected = if (deselectAll) emptySet() else ids,
            searchSelecting = true
        )
    }


internal fun AppViewModel.clearSearchSelection() {
        state = state.copy(searchSelected = emptySet(), searchSelecting = false)
    }


internal fun AppViewModel.selectAll() {
        state = state.copy(
            selected = browserVisibleIds(),
            selectionActive = true
        )
    }


    /** 取消全选只清空勾选，不退出选择模式，方便继续逐个点选。 */
internal fun AppViewModel.toggleSelectAll() {
        // 按「当前可见」算：筛选后点全选只选筛出来的那些，再点一次才是取消。
        val visible = browserVisibleIds()
        val deselectAll = visible.isEmpty() || state.selected.containsAll(visible)
        state = state.copy(
            selected = if (deselectAll) emptySet() else visible,
            selectionActive = true
        )
    }


    // ---- 首页「内部存储」的多选：行为与浏览页一致（长按进入多选 + 全选） ----

internal fun AppViewModel.homeFolderId(folder: FolderCard) = "home-folder:${folder.path}"

internal fun AppViewModel.homeFileId(file: FileEntry) = file.id


    /**
     * 首页根层的条目 id（文件夹卡 + 根目录文件），已按当前筛选条件过滤。
     *
     * 全选必须走这一份：筛选生效时把被筛掉的条目也塞进勾选，用户点「删除」会
     * 眼睁睁看着没显示的文件一起没了。
     */
internal fun AppViewModel.homeIdsWith(name: String, ext: String): Set<String> {
        val rootPath = state.localRoot.trimEnd('/')
        val rootFiles = state.localFiles.filter { it.path.trimEnd('/').substringBeforeLast('/') == rootPath }
        val folders = state.localFolders
            .filter { itemMatchesFilter(it.name, true, name, ext) }
            .map { homeFolderId(it) }
        val files = rootFiles
            .filter { itemMatchesFilter(it.name, it.isFolder, name, ext) }
            .map { homeFileId(it) }
        return folders.toSet() + files
    }


internal fun AppViewModel.homeAllIds(): Set<String> = homeIdsWith(state.nameFilter, state.extensionFilter)


internal fun AppViewModel.toggleHomeSelected(id: String) {
        val next = state.homeSelected.toMutableSet()
        if (!next.add(id)) next.remove(id)
        state = state.copy(homeSelected = next, homeSelecting = true)
    }


internal fun AppViewModel.clearHomeSelection() {
        state = state.copy(homeSelected = emptySet(), homeSelecting = false)
    }


internal fun AppViewModel.toggleHomeSelectAll() {
        val all = homeAllIds()
        state = state.copy(
            homeSelected = if (all.isNotEmpty() && state.homeSelected.containsAll(all)) emptySet() else all,
            homeSelecting = true
        )
    }


internal fun AppViewModel.homeAllSelected(): Boolean {
        val all = homeAllIds()
        return all.isNotEmpty() && state.homeSelected.containsAll(all)
    }


internal fun AppViewModel.homeSelectedEntries(): List<FileEntry> {
        val selected = state.homeSelected
        val folders = state.localFolders.filter { homeFolderId(it) in selected }.map { folder ->
            FileEntry(
                id = homeFolderId(folder),
                name = folder.name,
                kind = FileKind.FOLDER,
                sizeLabel = "文件夹",
                timeLabel = folder.date,
                folder = "内部存储",
                childCount = folder.count,
                path = folder.path
            )
        }
        val rootPath = state.localRoot.trimEnd('/')
        val files = state.localFiles.filter {
            it.path.trimEnd('/').substringBeforeLast('/') == rootPath && homeFileId(it) in selected
        }
        return folders + files
    }


internal fun AppViewModel.stageHomeClipboard(move: Boolean) {
        val entries = homeSelectedEntries()
        if (entries.isEmpty()) return
        stageClipboard(if (move) TransferMode.MOVE else TransferMode.COPY, entries)
        clearHomeSelection()
    }


internal fun AppViewModel.uploadHomeSelection() {
        val entries = homeSelectedEntries()
        if (entries.isEmpty()) return
        startTransfer(TransferMode.UPLOAD, entries)
        clearHomeSelection()
    }


internal fun AppViewModel.shareHomeSelection() {
        val entries = homeSelectedEntries().filterNot { it.isFolder }
        if (entries.isEmpty()) return
        shareFiles(entries)
        clearHomeSelection()
    }


internal fun AppViewModel.deleteHomeSelection() {
        val entries = homeSelectedEntries()
        if (entries.isEmpty()) return
        // 和其他层级一样先弹确认，不能点一下就删。
        state = state.copy(
            pendingDelete = entries,
            pendingDeleteLabel = if (entries.size == 1) entries.first().name else "${entries.size} 个项目",
            pendingDeleteHome = true
        )
    }


    /** 首页根目录也要能压缩：与内部文件夹同一套逻辑，文件夹同样支持。 */
internal fun AppViewModel.compressHomeSelection(name: String, format: String, password: String = "") {
        val entries = homeSelectedEntries()
        if (entries.isEmpty()) return
        clearHomeSelection()
        compressFiles(entries, name, format, password)
    }


    /** 首页选中单个压缩包时解压；destinationPath 为空表示解压到当前文件夹。 */
internal fun AppViewModel.extractHomeSelection(destinationPath: String? = null) {
        val entries = homeSelectedEntries()
        if (entries.size != 1) return
        val archive = entries.first()
        clearHomeSelection()
        extractArchive(archive, destinationPath)
    }


internal fun AppViewModel.showHomeDetail() {
        val entries = homeSelectedEntries()
        if (entries.size != 1) return
        state = state.copy(detailFile = entries.first())
    }


internal fun AppViewModel.selectedItems(): List<FileEntry> = state.browser?.items?.filter { it.id in state.selected }.orEmpty()


    /** 是否已经配置了远端目标（群晖已登录或有网络位置），决定「上传」能不能点。 */
internal fun AppViewModel.hasRemoteTarget(): Boolean = state.session != null || state.connections.isNotEmpty()


    /**
     * 文件列表里的条目都是「共享文件夹内部的文件」，可以正常多选。
     * 真正不能操作的是根部的共享文件夹 / 连接本身，它们在首页只是卡片：
     * 长按不进入多选，点一下才会打开。
     */
internal fun AppViewModel.canSelectHere(): Boolean = true


    /**
     * 群晖收藏：文件夹走官方 SYNO.FileStation.Favorite 接口同步，
     * 文件仅记本机 key（官方收藏只收文件夹）；两者都进「群晖收藏」页。
     */
internal fun AppViewModel.favoriteSynoSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        val session = state.session ?: return
        val allFavorited = items.all { isFavorite(it) }
        val folders = items.filter { it.isFolder && it.path.isNotBlank() }
        viewModelScope.launch {
            if (folders.isNotEmpty() && state.synoFavoriteSupported) {
                val ok = dsm.setFavorite(session, folders.map { it.path }, !allFavorited)
                if (!ok) {
                    state = state.copy(
                        selected = emptySet(),
                        selectionActive = false,
                        message = dsm.lastError ?: "收藏失败，请稍后重试"
                    )
                    return@launch
                }
            }
            val next = state.prefs.favorites.toMutableSet()
            items.forEach { file ->
                if (allFavorited) next.removeAll(favoriteKeysOf(file)) else next.add(favoriteKey(file))
            }
            // 服务器上的收藏集合同步更新，否则刚点完星标要等下一次进收藏页才亮。
            val folderPaths = if (state.synoFavoriteSupported) folders.map { it.path.trimEnd('/') } else emptyList()
            state = state.copy(
                prefs = state.prefs.copy(favorites = next),
                synoFavoritePaths = if (folderPaths.isEmpty()) {
                    state.synoFavoritePaths
                } else if (allFavorited) {
                    state.synoFavoritePaths - folderPaths.toSet()
                } else {
                    state.synoFavoritePaths + folderPaths
                },
                selected = emptySet(),
                selectionActive = false,
                message = if (allFavorited) "已取消群晖收藏 ${items.size} 项"
                else "已加入群晖收藏 ${items.size} 项"
            )
            persist()
            // 收藏页的条目靠反查解析，操作后立即解析一次，进收藏页无需等待。
            refreshSynoFavorites()
        }
    }


    /** 单条群晖收藏（文件操作面板入口）：文件夹同步官方接口，文件记本机 key。 */
internal fun AppViewModel.favoriteSynoSingle(file: FileEntry) {
        state = state.copy(selected = setOf(file.id))
        favoriteSynoSelected()
    }


    /** 收藏选中项：全部已收藏时执行取消收藏。 */
internal fun AppViewModel.favoriteSelected() = favoriteEntries(selectedItems())


    /**
     * 收藏一组条目（浏览页 / 首页 / 搜索页共用）。
     *
     * [fromSearch] 只为清掉搜索页那份独立勾选；勾选存在 state.searchSelected 里，
     * 不复用 selected，所以这里必须显式收尾。
     */
/**
 * 群晖文件夹收藏必须同步到 DSM（官方收藏只收文件夹）。
 *
 * 只改本机集合会造成「本机显示已收藏、服务器没有」，之后再从服务器角度点收藏
 * 就会互相打架。返回 null = 成功；否则是要展示给用户的文案，此时不要改本地状态。
 */
private suspend fun AppViewModel.pushSynoFavorite(folders: List<FileEntry>, on: Boolean): String? {
    if (folders.isEmpty()) return null
    val session = state.session ?: return null
    if (!state.synoFavoriteSupported) return null
    val ok = dsm.setFavorite(session, folders.map { it.path.trimEnd('/') }, on)
    return if (ok) null else (dsm.lastError ?: "收藏失败，请稍后重试")
}


internal fun AppViewModel.favoriteEntries(entries: List<FileEntry>, fromSearch: Boolean = false) {
        if (entries.isEmpty()) return
        val allFavorited = entries.all { isFavorite(it) }
        val folders = entries.filter { it.isFolder && it.path.isNotBlank() }
        val syncSyno = state.browser?.ctx == FileContext.SYNO

        fun commit() {
            val next = state.prefs.favorites.toMutableSet()
            entries.forEach { file -> if (allFavorited) next.remove(favoriteKey(file)) else next.add(favoriteKey(file)) }
            val folderPaths = if (syncSyno && state.synoFavoriteSupported) {
                folders.map { it.path.trimEnd('/') }
            } else emptyList()
            state = state.copy(
                prefs = state.prefs.copy(favorites = next),
                synoFavoritePaths = when {
                    folderPaths.isEmpty() -> state.synoFavoritePaths
                    allFavorited -> state.synoFavoritePaths - folderPaths.toSet()
                    else -> state.synoFavoritePaths + folderPaths
                },
                selected = emptySet(),
                selectionActive = false,
                searchSelected = if (fromSearch) emptySet() else state.searchSelected,
                searchSelecting = if (fromSearch) false else state.searchSelecting,
                message = if (allFavorited) "已取消收藏 ${entries.size} 项" else "已收藏 ${entries.size} 项"
            )
            persist()
            // 多选面板里的「收藏 / 取消收藏」也只改集合，不重算列表的话，
            // 在收藏页取消收藏后条目会赖着不走（3.9.17）。
            if (state.browser?.favorites == true) viewModelScope.launch { reloadBrowserItems() }
        }

        // 群晖浏览页：先让服务器点头，再改本机集合，避免两边状态打架。
        if (syncSyno && folders.isNotEmpty()) {
            viewModelScope.launch {
                val err = pushSynoFavorite(folders, !allFavorited)
                if (err != null) {
                    state = state.copy(message = err, selected = emptySet(), selectionActive = false)
                } else commit()
            }
        } else {
            commit()
        }
    }


internal fun AppViewModel.favoriteHomeSelection() {        val entries = homeSelectedEntries()
        if (entries.isEmpty()) return
        val allFavorited = entries.all { isFavorite(it) }
        val folders = entries.filter { it.isFolder && it.path.isNotBlank() }
        val syncSyno = state.tab == TabKey.SYNO && state.session != null

        fun commit() {
            val next = state.prefs.favorites.toMutableSet()
            entries.forEach { file -> if (allFavorited) next.remove(favoriteKey(file)) else next.add(favoriteKey(file)) }
            val folderPaths = if (syncSyno && state.synoFavoriteSupported) {
                folders.map { it.path.trimEnd('/') }
            } else emptyList()
            state = state.copy(
                prefs = state.prefs.copy(favorites = next),
                synoFavoritePaths = when {
                    folderPaths.isEmpty() -> state.synoFavoritePaths
                    allFavorited -> state.synoFavoritePaths - folderPaths.toSet()
                    else -> state.synoFavoritePaths + folderPaths
                },
                homeSelected = emptySet(),
                homeSelecting = false,
                message = if (allFavorited) "已取消收藏 ${entries.size} 项" else "已收藏 ${entries.size} 项"
            )
            persist()
        }

        if (syncSyno && folders.isNotEmpty()) {
            viewModelScope.launch {
                val err = pushSynoFavorite(folders, !allFavorited)
                if (err != null) {
                    state = state.copy(message = err, homeSelected = emptySet(), homeSelecting = false)
                } else commit()
            }
        } else {
            commit()
        }
    }


    /**
     * 群晖「共享」（清单 4）：共享设置确认后才生成链接并复制。
     * [password] 空 = 不设密码；[expireMinutes] 0 = 永不失效。
     */
internal fun AppViewModel.shareSynoLink(file: FileEntry, password: String = "", expireMinutes: Int = 0) {
        val session = state.session ?: run {
            state = state.copy(message = "请先登录群晖 NAS")
            return
        }
        viewModelScope.launch {
            // 生成共享链接是一次网络往返，列表非空时 loading 并不显示任何东西，
            // 用户点了「共享」之后界面完全没有反应，直到结果冒出来。
            state = state.copy(
                loading = true,
                localTaskLabel = "正在创建共享链接…",
                localTaskRunning = true
            )
            try {
                val link = withContext(Dispatchers.IO) {
                    dsm.createShareLink(session, file.path, password, expireMinutes)
                }
                state = state.copy(loading = false, selected = emptySet(), selectionActive = false)
                if (link.isNullOrBlank()) {
                    state = state.copy(message = dsm.lastError ?: "生成共享链接失败，请检查共享权限")
                } else {
                    val clipboard = getApplication<Application>()
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("共享链接", link))
                    val expiry = when (expireMinutes) {
                        0 -> "永久有效"
                        else -> "${expireMinutes / 1440} 天内有效"
                    }
                    state = state.copy(message = "共享链接已复制（$expiry）")
                    // 清单 13（3.2.0）：创建后立即刷新共享列表，保持同步。
                    refreshShareLinks()
                }
            } finally {
                state = state.copy(localTaskRunning = false, localTaskLabel = "")
            }
        }
    }


    // ---------------------------------------------------------------- 群晖共享管理（清单 4）

internal fun AppViewModel.openShareManager() {
        state = state.copy(shareManagerOpen = true, shareLinksLoading = true)
        refreshShareLinks()
    }


internal fun AppViewModel.closeShareManager() { state = state.copy(shareManagerOpen = false) }


    /**
     * 共享管理刷新：走 beginPull/endPull，与其它页同款最短转圈时长、看门狗与提示。
     * [notice] 仅用户主动下拉时为 true——进入页面 / 建链接后的自动刷新不弹「已刷新」。
     */
internal fun AppViewModel.refreshShareLinks(forceClean: Boolean = false, notice: Boolean = false) {
        // beginPull 会清 message；自动刷新（进页面 / 建链接后）不能把刚写的
        // 「共享链接已复制」这类成功提示抹掉，先存后还原。
        val keepMessage = if (!notice) state.message else null
        val token = beginPull()
        if (keepMessage != null) state = state.copy(message = keepMessage)
        val session = state.session ?: run {
            state = state.copy(shareLinksLoading = false, shareLinks = emptyList())
            endPull(token)
            return
        }
        dsm.lastError = null
        state = state.copy(shareLinksLoading = true)
        viewModelScope.launch {
            val links = withContext(Dispatchers.IO) { dsm.listShareLinks(session, forceClean) }
            state = state.copy(shareLinks = links, shareLinksLoading = false)
            val message = if (notice) (dsm.lastError?.takeIf { it.isNotBlank() } ?: "已刷新") else null
            endPull(token, message)
        }
    }


internal fun AppViewModel.cancelShareLink(link: com.yaoahos.myfilebox.core.ShareLink) {
        val session = state.session ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { dsm.deleteShareLinks(session, listOf(link.id)) }
            val links = withContext(Dispatchers.IO) { dsm.listShareLinks(session) }
            state = state.copy(
                shareLinks = links,
                message = if (ok) "已取消共享" else dsm.lastError ?: "取消共享失败"
            )
        }
    }


    /** 清除无效链接：DSM 的 force_clean 会顺带移除目标已不存在的共享。 */
internal fun AppViewModel.clearInvalidShareLinks() {
        val session = state.session ?: return
        viewModelScope.launch {
            state = state.copy(shareLinksLoading = true)
            val links = withContext(Dispatchers.IO) { dsm.listShareLinks(session, forceClean = true) }
            state = state.copy(
                shareLinks = links,
                shareLinksLoading = false,
                message = "已清除无效链接"
            )
        }
    }


    /**
     * 群晖上传的会话兜底：官方错误码 106 会话超时 / 107 重复登录 / 119 会话无效 /
     * 150 来源 IP 变更，都会让上传接口拒绝请求。用记住的密码静默换一个新 SID 再重试一次。
     * 注意 state 写回必须在主线程（Compose mutableStateOf 红线），IO 只做网络与解析。
     */
internal suspend fun AppViewModel.renewSynoSession(force: Boolean = false): Boolean {
        // 三种情况允许重连：
        // 1. 显式 force（网络切换回调）；
        // 2. DSM 会话错误码（106/107/119/150）；
        // 3. 刚切过网（2 分钟窗口内）——切网后请求先报网络异常（错误码 0），
        //    旧逻辑只认错误码导致永不重连，这正是「切网后必须手动登录」的根因。
        val networkRecentlyChanged = System.currentTimeMillis() - networkChangedAt < 2 * 60_000L
        if (!force && !networkRecentlyChanged && dsm.lastErrorCode !in setOf(106, 107, 119, 150)) return false
        val current = state.session ?: return false
        val saved = savedLogin()
        // 密码优先用保存的，其次用本次进程登录时缓存的（用户没勾记住密码时也能自动重连）。
        val password = saved.third.ifBlank { cachedPassword }
        // 无密码可重登时：只更新 endpoint（SID 对同一 NAS 仍有效），不能完全不响应。
        if (password.isBlank()) {
            if (!force) return false
            // 网络切换后重解析 QuickConnect 端点并更新 session.endpoint。
            val parsed = dsm.parseServer(saved.first.ifBlank { current.server }, current.https)
            if (parsed.quickConnect) {
                val candidates = dsm.quickConnectCandidates(parsed.host)
                candidates.firstOrNull()?.let { endpoint ->
                    state = state.copy(session = current.copy(endpoint = endpoint.baseUrl, mode = endpoint.label))
                    persist()
                    return true
                }
            }
            return false
        }
        val renewed = withContext(Dispatchers.IO) {
            val parsed = dsm.parseServer(saved.first.ifBlank { current.server }, current.https)
            suspend fun tryLogin(endpoint: QuickConnectEndpoint): Session? {
                val provisional = current.copy(
                    server = parsed.display,
                    account = saved.second.ifBlank { current.account },
                    endpoint = endpoint.baseUrl,
                    mode = endpoint.label
                )
                val login = runCatching { dsm.login(provisional, password) }.getOrNull()
                return if (login != null && login.success && login.sid != null) {
                    provisional.copy(
                        sid = login.sid,
                        synoToken = login.synoToken.orEmpty(),
                        nas = "DSM 已连接"
                    )
                } else null
            }
            if (!parsed.quickConnect) {
                // 内网地址 + 已经不在局域网：这个地址必然不可达，别拿它去撞连接超时
                // （https / http 各试一次就是十几秒），直接判失败，让上层给出可执行提示。
                if (hostLooksPrivate(parsed.baseUrl) && !onLanTransport()) return@withContext null
                return@withContext tryLogin(QuickConnectEndpoint(parsed.baseUrl, parsed.display))
            }
            // 切网后先试内置中继：3 秒可达就直接登录，省掉整套解析
            // （Serv.php + 直连探测 4s + 中继探测 8s，占总等待的绝大部分）。
            if (networkRecentlyChanged) {
                dsm.fastRelayCandidate(parsed.host)?.let { fast ->
                    tryLogin(fast)?.let { return@withContext it }
                }
            }
            dsm.quickConnectCandidates(parsed.host).firstNotNullOfOrNull { tryLogin(it) }
        }
        if (renewed == null) return false
        state = state.copy(session = renewed)
        persist()
        return true
    }


    /** 所有群晖上传（新建文件 / 上传文件 / 上传文件夹）统一走这里，带会话失效重试。 */
internal suspend fun AppViewModel.uploadSynoWithRetry(file: File, dest: String, name: String = file.name): Boolean {
        if (state.session?.let { dsm.upload(it, file, dest, name) } == true) return true
        if (!renewSynoSession()) return false
        return state.session?.let { dsm.upload(it, file, dest, name) } ?: false
    }


    /** 把本机文件上传到当前远程目录（群晖 / 网络位置），全程在传输中心展示进度。 */
internal fun AppViewModel.uploadLocalFilesToCurrent(files: List<File>) {
        val browser = state.browser ?: return
        if (browser.ctx == FileContext.LOCAL || files.isEmpty()) return
        val dest = browser.location.trimEnd('/').ifBlank { "/" }
        val totalBytes = files.sumOf { runCatching { it.length() }.getOrDefault(0L) }
        val remoteTarget = if (files.size == 1) joinRemote(dest, files.first().name) else dest
        launchTransferTask(
            mode = TransferMode.UPLOAD,
            title = files.singleOrNull()?.name ?: "${files.size} 个文件",
            detail = "${browser.title} · ${files.size} 项",
            itemCount = files.size,
            byteCount = totalBytes,
            verb = "上传",
            from = "内部存储 · " + files.first().parentFile?.absolutePath.orEmpty(),
            to = dest,
            target = remoteTarget,
            targetCtx = browser.ctx.name,
            targetNet = browser.netId ?: "",
            sourceCtx = FileContext.LOCAL.name,
            sources = files.map { it.absolutePath },
            onDone = { ok ->
                if (ok) reloadBrowserItems()
            }
        ) { reporter ->
            var done = 0
            var sent = 0L
            var all = true
            for (file in files) {
                if (reporter.cancelled()) return@launchTransferTask false
                val base = sent
                reporter.uploaded(joinRemote(dest, file.name))
                val ok = uploadOne(
                    browser, file, dest, file.name,
                    onBytes = { bytes -> reporter.bytes(base + bytes) },
                    abort = { reporter.cancelled() }
                )
                if (!ok && reporter.cancelled()) return@launchTransferTask false
                if (!ok) all = false
                sent += runCatching { file.length() }.getOrDefault(0L)
                done++
                reporter.progress(done)
                reporter.bytes(sent)
            }
            all
        }
    }


    /**
     * 上传本机文件夹：目录在应用内选（点一下进目录、右侧「选择」即选中当前文件夹），
     * 不再弹系统选择器的「使用此文件夹」。上传保留子目录结构。
     */
internal fun AppViewModel.uploadLocalFolderToCurrent(dirPath: String) {
        val browser = state.browser ?: return
        if (browser.ctx == FileContext.LOCAL) return
        val root = File(dirPath)
        if (!root.isDirectory || !root.canRead()) {
            showMessage("该文件夹不可读取，请换一个目录")
            return
        }
        // 连接根目录的 location 常常是空串：直接拿来当目标，记录里「目标」就是空的。
        // 远端根统一写成 "/"，上传本来也落在根上，两边口径一致。
        val dest = browser.location.trimEnd('/').ifBlank { "/" }
        // 扫描前先给反馈：大文件夹枚举要几秒，不提示就像卡死。
        state = state.copy(loading = true, message = "正在扫描「${root.name}」…")
        // 枚举在 IO 线程执行：文件夹文件多时主线程递归会 ANR。
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) {
                val result = mutableListOf<Pair<File, String>>()
                fun collect(dir: File, rel: String, depth: Int) {
                    if (depth > 12 || result.size >= 20000) return
                    dir.listFiles().orEmpty().filter { !it.name.startsWith('.') }.forEach { child ->
                        val childRel = if (rel.isBlank()) child.name else "$rel/${child.name}"
                        if (child.isDirectory) collect(child, childRel, depth + 1) else {
                            result += child to rel
                            // 每 500 个文件回报一次进度，用户能看到数字在涨。
                            if (result.size % 500 == 0) {
                                viewModelScope.launch {
                                    state = state.copy(message = "正在扫描「${root.name}」… 已发现 ${result.size} 个文件")
                                }
                            }
                        }
                    }
                }
                collect(root, root.name, 0)
                // 字节数统计也是 IO（上万个 stat 不能丢主线程）。
                val bytes = result.sumOf { runCatching { it.first.length() }.getOrDefault(0L) }
                result to bytes
            }
            val (files, totalBytes) = pending
            state = state.copy(loading = false)
            if (files.isEmpty()) {
                showMessage("「${root.name}」里没有可上传的文件")
                return@launch
            }
            launchTransferTask(
                mode = TransferMode.UPLOAD,
                title = root.name,
                detail = "${browser.title} · ${files.size} 个文件",
                itemCount = files.size,
                byteCount = totalBytes,
                verb = "上传",
                from = dirPath,
                to = dest,
                target = joinRemote(dest, root.name),
                targetCtx = browser.ctx.name,
                targetNet = browser.netId ?: "",
                // 源路径与来源端要一起记：否则重启后「重试 / 恢复」无从重建请求。
                sources = files.map { it.first.absolutePath },
                // 详情里这是一条「文件夹」记录：来源 = 那个文件夹，不列夹内文件。
                sourceItems = listOf(root.absolutePath),
                // 上传的是文件夹（哪怕里面只有一个文件），点记录要进目标文件夹。
                folderTask = true,
                sourceCtx = FileContext.LOCAL.name,
                onDone = { ok -> if (ok) reloadBrowserItems() }
            ) { reporter ->
                // 文件夹上传同样要逐个登记子文件：只有一条「文件夹」记录而没有
                // 文件明细，用户在详情里看不到「文件夹中的文件」，也看不出哪个失败。
                val taskId = reporter.id
                fun childKey(file: File, rel: String): String =
                    if (rel.isBlank()) file.name else "$rel/${file.name}"
                if (taskId.isNotBlank()) {
                    setTransferChildren(
                        taskId,
                        files.map { (file, rel) ->
                            val name = childKey(file, rel)
                            TransferChild(
                                key = name, name = name, path = file.absolutePath,
                                sizeBytes = runCatching { file.length() }.getOrDefault(0L)
                            )
                        }
                    )
                }
                val dirs = files.map { it.second }.filter { it.isNotBlank() }.distinct()
                    .sortedBy { rel -> rel.count { it == '/' } }
                var created = 0
                for (rel in dirs) {
                    if (reporter.cancelled()) return@launchTransferTask false
                    val full = if (rel.isBlank()) dest else joinRemote(dest, rel)
                    runCatching { createRemoteFolder(browser, full.substringBeforeLast('/'), full.substringAfterLast('/')) }
                    created++
                    reporter.note("${browser.title} · 已建 $created/${dirs.size} 个目录")
                }
                reporter.note("${browser.title} · 上传中")
                var done = 0
                var sent = 0L
                var all = true
                for ((file, rel) in files) {
                    val key = childKey(file, rel)
                    if (reporter.cancelled()) {
                        updateTransferChild(taskId, key, TransferChildStatus.PAUSED, 0L)
                        return@launchTransferTask false
                    }
                    // 目标目录也要走 joinRemote：dest 是连接根时写的是 "/"，
                    // 直接拼 "$dest/$rel" 会变成 "//Avatar"，详情里整条路径都带双斜杠。
                    val target = if (rel.isBlank()) dest else joinRemote(dest, rel)
                    val remote = joinRemote(target, file.name)
                    val size = runCatching { file.length() }.getOrDefault(0L)
                    val base = sent
                    updateTransferChild(taskId, key, TransferChildStatus.RUNNING, 0L)
                    reporter.uploaded(remote)
                    val ok = uploadOne(
                        browser, file, target, file.name,
                        onBytes = { bytes -> reporter.bytes(base + bytes) },
                        abort = { reporter.cancelled() }
                    )
                    if (!ok && reporter.cancelled()) {
                        updateTransferChild(taskId, key, TransferChildStatus.PAUSED, 0L)
                        return@launchTransferTask false
                    }
                    updateTransferChild(
                        taskId, key,
                        if (ok) TransferChildStatus.SUCCESS else TransferChildStatus.FAILED,
                        size, target = remote
                    )
                    if (!ok) all = false
                    sent += size
                    done++
                    reporter.progress(done)
                    reporter.bytes(sent)
                }
                all
            }
        }
    }


internal fun AppViewModel.unfavorite(file: FileEntry) {
        val keys = favoriteKeysOf(file)
        val path = file.path.trimEnd('/')
        val synoFolder = state.browser?.ctx == FileContext.SYNO && file.isFolder && path.isNotBlank()

        fun commit() {
            state = state.copy(
                prefs = state.prefs.copy(favorites = state.prefs.favorites - keys),
                synoFavoritePaths = if (synoFolder) state.synoFavoritePaths - path else state.synoFavoritePaths,
                selected = state.selected - file.id,
                message = "已取消收藏"
            )
            persist()
            viewModelScope.launch { reloadBrowserItems() }
        }

        // 群晖文件夹在服务器上也有这条收藏：必须让服务器先删掉，
        // 否则本地刚点掉、下次刷新又从服务器列表里冒出来。
        if (synoFolder && state.synoFavoriteSupported) {
            viewModelScope.launch {
                val err = pushSynoFavorite(listOf(file), false)
                if (err != null) state = state.copy(message = err) else commit()
            }
        } else {
            commit()
        }
    }


internal fun AppViewModel.unfavoriteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        val removed = items.flatMap { favoriteKeysOf(it) }.toSet()
        val folders = items.filter { it.isFolder && it.path.isNotBlank() }
        val syno = state.browser?.ctx == FileContext.SYNO && state.synoFavoriteSupported

        fun commit() {
            state = state.copy(
                prefs = state.prefs.copy(favorites = state.prefs.favorites - removed),
                synoFavoritePaths = if (syno) {
                    state.synoFavoritePaths - folders.map { it.path.trimEnd('/') }.toSet()
                } else state.synoFavoritePaths,
                selected = emptySet(),
                selectionActive = false,
                message = "已取消收藏 ${items.size} 项"
            )
            persist()
            viewModelScope.launch { reloadBrowserItems() }
        }

        if (syno && folders.isNotEmpty()) {
            viewModelScope.launch {
                val err = pushSynoFavorite(folders, false)
                if (err != null) state = state.copy(message = err) else commit()
            }
        } else {
            commit()
        }
    }


    // ---------------------------------------------------------------- transfer

    /**
     * 发起传输。
     *
     * [ctxOverride] 给搜索页用：搜索页没有 `browser`，来源端只能从 `search.ctx` 取；
     * 不传时沿用浏览页的端（原有行为）。
     */
internal fun AppViewModel.startTransfer(mode: TransferMode, items: List<FileEntry>, ctxOverride: FileContext? = null) {
        val ctx = ctxOverride ?: state.browser?.ctx ?: FileContext.LOCAL
        val destinations = destinationsFor(mode, ctx)
        if (destinations.isEmpty()) {
            state = state.copy(message = "没有可用的目标位置")
            return
        }
        if (mode == TransferMode.DOWNLOAD) {
            // Downloads now use the same directory picker as uploads, with the
            // saved default pre-selected. Users can still pick any local folder.
            val targetPath = state.prefs.downloadFolder.ifBlank { local.downloadsDir().absolutePath }
            val preferred = Destination(
                type = DestType.LOCAL,
                key = targetPath,
                label = java.io.File(targetPath).name.ifBlank { "下载" },
                meta = "内部存储 / ${java.io.File(targetPath).name.ifBlank { "下载" }}",
                path = targetPath
            )
            val all = (listOf(preferred) + destinations).distinctBy { it.path }
            state = state.copy(
                fileSheet = null,
                transfer = TransferRequest(mode, items, ctx, state.browser?.netId, all, preferred.path)
            )
            return
        }
        state = state.copy(fileSheet = null, transfer = TransferRequest(mode, items, ctx, state.browser?.netId, destinations))
    }


internal fun AppViewModel.startSelectedTransfer(mode: TransferMode) {
        val items = selectedItems()
        if (items.isNotEmpty()) startTransfer(mode, items)
    }


    /**
     * 暂存剪贴板（复制 / 剪切）。
     *
     * [ctxOverride] 给搜索页用：搜索页没有浏览上下文，端要由调用方指定，
     * 否则会按「浏览页停在哪个端」记错来源。
     */
internal fun AppViewModel.stageClipboard(mode: TransferMode, items: List<FileEntry>, ctxOverride: FileContext? = null) {
        if (items.isEmpty()) return
        val browser = state.browser
        val ctx = ctxOverride ?: browser?.ctx ?: FileContext.LOCAL
        // 来源目录：浏览页用当前 location/path；首页复制根目录文件则记本地根；
        // 搜索页（有 ctxOverride）留空，避免拿「当前浏览目录」误判成同目录。
        val sourceDir = if (ctxOverride != null) ""
        else if (browser != null && !browserIsVirtual()) browser.location.ifBlank { browser.path }
        else if (ctx == FileContext.LOCAL) state.localRoot
        else ""
        // 网络端的连接 id：搜索页里没有 browser 上下文，之前直接写 null。
        // 结果是从搜索结果复制后再粘到同一个网络位置时，canPasteHere 拿
        // 「空 id」跟真实连接 id 比不相等，被判成跨端、提示只能用上传 / 下载
        // （坚果云这类 WebDAV 位置就是这么被挡的）。这里按文件路径反查所属连接，
        // 与 openSearchResult 用的是同一套判定。
        val resolvedNetId = if (ctx != FileContext.NET) null
        else browser?.netId ?: state.connections.firstOrNull { conn ->
            val root = network.rootPath(conn)
            items.any { it.path.startsWith(root) || (conn.host.isNotBlank() && it.path.contains(conn.host)) }
        }?.id
        state = state.copy(
            clipboard = ClipboardState(mode, items, ctx, resolvedNetId, sourceDir),
            selected = emptySet(),
            selectionActive = false,
            searchSelected = if (ctxOverride != null) emptySet() else state.searchSelected,
            searchSelecting = if (ctxOverride != null) false else state.searchSelecting,
            fileSheet = null,
            // 首页根目录也能粘贴，所以提示不再限定「进入子文件夹」。
            message = if (mode == TransferMode.COPY) "已复制 ${items.size} 项，到目标位置点粘贴"
                      else "已剪切 ${items.size} 项，到目标位置点粘贴"
        )
    }


    /**
     * 当前页面是不是「虚拟集合」：最近 / 分类页（图片、视频…）/ 收藏。
     *
     * 它们只是从快照或收藏集合里筛出来的展示视图，`location` 要么为空、要么是
     * 「内部存储 / 最近」这种人类可读的假路径。把它们当文件夹用会拼出非法路径
     * （本地写到一个叫「最近」的目录、群晖往不存在的 "收藏" 目录里写），
     * 所以：不能粘贴进来，也不能出现在「移动 / 复制到」的目标列表里。
     */
internal fun AppViewModel.browserIsVirtual(): Boolean {
        val browser = state.browser ?: return false
        return browser.favorites || browser.localCategory != null
    }


    /** 两条路径指向同一个目录（都能有末尾斜杠），尾部斜杠不影响比较。 */
internal fun AppViewModel.sameDir(a: String, b: String): Boolean =
        a.isNotBlank() && b.isNotBlank() && a.trimEnd('/') == b.trimEnd('/')


    /** 粘贴只在同一端内可用；跨端一律走上传 / 下载，不提供粘贴按钮。 */
internal fun AppViewModel.canPasteHere(): Boolean {
        val clip = state.clipboard ?: return false
        val browser = state.browser ?: return false
        if (browserIsVirtual()) return false
        if (clip.ctx != browser.ctx) return false
        // 连接 id 可能有一边是 null（进连接首页时就复制、老状态），按空串比较，
        // 否则「根目录复制 → 子目录粘贴」这种最普通的同端操作会被判成跨端。
        if (clip.ctx == FileContext.NET && clip.netId.orEmpty() != browser.netId.orEmpty()) return false
        if (!clip.sourceDir.isBlank()) {
            val here = browser.location.ifBlank { browser.path }
            if (sameDir(clip.sourceDir, here)) return false
        }
        return true
    }


    /** 当前浏览页是否正是剪贴板来源目录——用于给「不能原地粘贴」一句说明。 */
internal fun AppViewModel.isPasteSourceHere(): Boolean {
        val clip = state.clipboard ?: return false
        val browser = state.browser ?: return false
        if (clip.ctx != browser.ctx) return false
        if (clip.ctx == FileContext.NET && clip.netId.orEmpty() != browser.netId.orEmpty()) return false
        return sameDir(clip.sourceDir, browser.location.ifBlank { browser.path })
    }


    /** 长按粘贴按钮 / 提示条里的「取消」：丢掉暂存的复制 / 移动。 */
internal fun AppViewModel.cancelClipboard() {
        if (state.clipboard == null) return
        state = state.copy(clipboard = null, message = "已取消复制 / 移动")
    }


internal fun AppViewModel.clipboardLabel(): String {
        val clip = state.clipboard ?: return ""
        val kind = when (clip.ctx) {
            FileContext.LOCAL -> MODULE_LABEL_LOCAL
            FileContext.SYNO -> MODULE_LABEL_SYNO
            FileContext.NET -> MODULE_LABEL_NET
        }
        return "${if (clip.mode == TransferMode.COPY) "已复制" else "已剪切"} ${clip.items.size} 项 · $kind"
    }


internal fun AppViewModel.stageSelectedTransfer(mode: TransferMode) {
        stageClipboard(mode, selectedItems())
    }


internal fun AppViewModel.pasteClipboard() {
        val clip = state.clipboard ?: return
        val browser = state.browser
        if (browser == null) {
            state = state.copy(message = "请先进入目标文件夹")
            return
        }
        if (browserIsVirtual()) {
            // 「最近」「收藏」「图片」这些是展示视图，背后没有可写入的目录。
            state = state.copy(message = "「${browser.title}」不是文件夹，不能粘贴到这里")
            return
        }
        if (!canPasteHere()) {
            // 三种拒绝原因分开说：同一目录 / 两个不同的网络位置 / 跨端。
            // 之前一律报「跨端」，从 A 网络位置粘到 B 网络位置时用户会以为是自己弄错了端。
            val sameCtx = clip.ctx == browser.ctx
            val message = when {
                isPasteSourceHere() -> "已在来源目录，换个位置再粘贴"
                sameCtx && clip.ctx == FileContext.NET ->
                    "不同网络位置之间只能用上传 / 下载传输"
                else -> "跨端文件只能用上传 / 下载传输，粘贴仅支持同一端内"
            }
            state = state.copy(message = message)
            return
        }
        if (clip.mode == TransferMode.MOVE && clip.ctx != browser.ctx) {
            state = state.copy(message = "跨端文件只能用上传 / 下载传输")
            return
        }
        val type = when (browser.ctx) {
            FileContext.LOCAL -> DestType.LOCAL
            FileContext.SYNO -> DestType.SYNO
            FileContext.NET -> DestType.NET
        }
        // NET 端的「连接 id」要兜底到当前浏览页：粘贴请求里的 netId 和目标的 key
        // 必须取同一个值，否则 performTransfer 的 sameSide 判定会把同端粘贴当跨端。
        // 复制时没记到 netId（老状态、搜索页进来等）就用当前浏览页的连接。
        val netId = if (clip.ctx == FileContext.NET) clip.netId ?: browser.netId else clip.netId
        val destination = Destination(
            type = type,
            // NET 端的 key 是「连接 id」——destinationsFor / childDirectories 都按这个口径构造，
            // performTransfer 也用 destination.key 跟 request.netId 比来判断是不是同端。
            // 这里原来填的是目标路径，于是同一次修剪贴也会被判成跨端、提示
            // 「只能用上传 / 下载传输」（坚果云等 WebDAV 位置就是这么被挡的）。
            key = when (type) {
                DestType.NET -> netId.orEmpty()
                else -> browser.location.ifBlank { browser.path }
            },
            label = browser.title,
            meta = browser.title,
            path = browser.location.ifBlank { browser.path }
        )
        state = state.copy(
            transfer = TransferRequest(clip.mode, clip.items, clip.ctx, netId, listOf(destination)),
            clipboard = null
        )
        performTransfer(destination)
    }


    /**
     * 首页（内部存储根目录）也是合法的粘贴目标。之前只在浏览页提供粘贴，
     * 用户在首页复制后无处可贴，必须先随便进一个子目录才行。
     * 群晖 / 网络首页对应多个根（多个共享 / 多个连接），无法确定唯一目标，
     * 因此只在本地端提供根目录粘贴。
     */
internal fun AppViewModel.canPasteOnHome(): Boolean {
        val clip = state.clipboard ?: return false
        if (clip.ctx != FileContext.LOCAL || state.localRoot.isBlank()) return false
        // 从本地根目录复制的文件，粘贴回根目录无意义。
        if (sameDir(clip.sourceDir, state.localRoot)) return false
        return true
    }


internal fun AppViewModel.pasteToHome() {
        val clip = state.clipboard ?: return
        if (!canPasteOnHome()) {
            state = state.copy(
                message = if (sameDir(clip.sourceDir, state.localRoot)) "已在来源目录，换个位置再粘贴"
                          else "跨端文件只能用上传 / 下载传输，粘贴仅支持同一端内"
            )
            return
        }
        val root = state.localRoot
        val destination = Destination(
            type = DestType.LOCAL,
            key = root,
            label = "内部存储",
            meta = "内部存储",
            path = root
        )
        state = state.copy(
            transfer = TransferRequest(clip.mode, clip.items, clip.ctx, null, listOf(destination)),
            clipboard = null
        )
        performTransfer(destination)
    }


internal fun AppViewModel.closeTransfer() { state = state.copy(transfer = null) }


    /** 删除的群晖条目 → 回收站删除单元记录（trashPath 按 #recycle 保留原路径规则推算）。 */
internal fun AppViewModel.synoRecycleRecordOf(entry: FileEntry): RecycleItem? {
        val share = state.shares.map { it.path }
            .filter { entry.path.startsWith("$it/") }
            .maxByOrNull { it.length } ?: return null
        val rel = entry.path.removePrefix("$share/")
        return RecycleItem(
            id = entry.path,
            name = entry.name,
            originalPath = entry.path,
            trashPath = "$share/#recycle/$rel",
            deletedAt = System.currentTimeMillis(),
            sizeBytes = entry.sizeBytes,
            isFolder = entry.isFolder
        )
    }


    /** 拉一份群晖回收站列表（含删除单元合并），并把已消失的记录从索引里清掉。 */
internal suspend fun AppViewModel.freshSynoRecycleItems(): List<RecycleItem> {
        val items = state.session?.let {
            dsm.listRecycleBin(it, state.shares.map { s -> s.path }, tracked = synoRecycleIndex.all())
        }.orEmpty()
        pruneSynoRecycleIndex(items)
        return items
    }


    /** 列表为空且带错误 = 拉取失败，绝不能拿空列表把索引清光。 */
internal fun AppViewModel.pruneSynoRecycleIndex(items: List<RecycleItem>) {
        if (items.isEmpty() && dsm.lastError != null) return
        synoRecycleIndex.retain(items.map { it.trashPath })
    }


internal fun AppViewModel.shareSelected() { shareFiles(selectedItems()) }


internal fun AppViewModel.shareFile(file: FileEntry) { shareFiles(listOf(file)) }


internal fun AppViewModel.shareFiles(files: List<FileEntry>) {
        // 全是远程文件时立刻给提示，不必进协程。
        if (files.none { it.path.startsWith("/") }) {
            state = state.copy(message = "远程文件请先下载后再分享")
            return
        }
        // 判断文件还在不在要对每个条目 stat 一次；多选几十上百个时这一步不能占着 UI 线程。
        viewModelScope.launch {
            val localFiles = withContext(Dispatchers.IO) {
                files.filter { it.path.startsWith("/") && File(it.path).exists() }
            }
            if (localFiles.isEmpty()) {
                state = state.copy(message = "远程文件请先下载后再分享")
                return@launch
            }
            val context = getApplication<Application>()
            runCatching {
                val uris = ArrayList<Uri>()
                localFiles.forEach { file ->
                    uris.add(FileProvider.getUriForFile(context, "${context.packageName}.files", File(file.path)))
                }
                val clip = ClipData.newRawUri("MyFileBox", uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                val intent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = localFiles.first().mime.ifBlank { "application/octet-stream" }
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    }
                }
                intent.clipData = clip
                intent.putExtra(Intent.EXTRA_SUBJECT, "来自 MyFileBox 的分享")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(intent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { state = state.copy(message = "无法调用系统分享") }
        }
    }


    // ---------------------------------------------------------------- 名称筛选

    /** 一次性设置两个筛选条件：名称 + 后缀（空串 = 不限）。 */
internal fun AppViewModel.setFilters(name: String, extensions: String) {
        val n = name.trim()
        val e = extensions.trim()
        if (n == state.nameFilter && e == state.extensionFilter) return
        // 筛选一变，被筛掉的条目必须退出勾选：否则「已选择 N 项」里混着一堆看不见的文件，
        // 接下来的删除 / 移动会把它们一起带走——这正是用户反馈「筛选后全选还是全选全部」的根。
        state = state.copy(
            nameFilter = n,
            extensionFilter = e,
            selected = state.selected.intersect(visibleIdsIn(state.browser?.items.orEmpty(), n, e)),
            homeSelected = state.homeSelected.intersect(homeIdsWith(n, e))
        )
    }


    /**
     * 一组条目在筛选条件下真正可见的 id。
     *
     * 没有筛选时就是全部——不能返回空集，否则「不筛选时点全选」会一个都选不上。
     */
internal fun AppViewModel.visibleIdsIn(items: List<FileEntry>, name: String, ext: String): Set<String> =
        filterVisible(items, name, ext, { it.name }, { it.isFolder }).map { it.id }.toSet()


    /** 当前浏览页可见条目的 id：全选 / 取消全选都按它算，筛选因此对全选生效。 */
internal fun AppViewModel.browserVisibleIds(): Set<String> =
        visibleIdsIn(state.browser?.items.orEmpty(), state.nameFilter, state.extensionFilter)


    /** 当前浏览页的可见条目是否已全部选中（全选按钮据此显示「全选 / 取消全选」）。 */
internal fun AppViewModel.browserAllSelected(): Boolean {
        val visible = browserVisibleIds()
        return visible.isNotEmpty() && state.selected.containsAll(visible)
    }


    /** 设置当前页面的名称筛选词（空串 = 取消筛选）。 */
internal fun AppViewModel.setNameFilter(text: String) {
        val q = text.trim()
        if (q == state.nameFilter) return
        state = state.copy(nameFilter = q)
    }


internal fun AppViewModel.clearNameFilter() {
        if (state.nameFilter.isNotEmpty() || state.extensionFilter.isNotEmpty()) {
            state = state.copy(nameFilter = "", extensionFilter = "")
        }
    }


    /** 当前筛选词；界面直接读 state.nameFilter 过滤，这里只供需要判定的逻辑用。 */
internal fun AppViewModel.matchesFilter(name: String): Boolean = nameMatchesFilter(name, state.nameFilter)
