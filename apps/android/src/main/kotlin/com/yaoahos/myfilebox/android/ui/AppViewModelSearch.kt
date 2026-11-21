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
import com.yaoahos.myfilebox.core.displayLeaf
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


    // ---------------------------------------------------------------- search

internal fun AppViewModel.openSearch(ctx: FileContext = contextOf(state.tab)) {
        state = state.copy(
            search = SearchState(ctx),
            selected = emptySet(),
            selectionActive = false,
            searchSelected = emptySet(),
            searchSelecting = false,
            searchIndexing = ctx != FileContext.LOCAL
        )
        ensureSearchIndex()
    }


internal fun AppViewModel.closeSearch() {
        searchIndexJob?.cancel()
        state = state.copy(
            search = null,
            searchIndexing = false,
            searchSelected = emptySet(),
            searchSelecting = false
        )
    }


internal fun AppViewModel.setSearchQuery(query: String) {
        // 换了关键词，上一批结果里的勾选就看不到也说不清了：直接清掉，
        // 免得「删除 5 项」把屏幕上看不见的文件一起删了。
        state = state.copy(
            search = state.search?.copy(query = query),
            searchSelected = emptySet(),
            searchSelecting = false
        )
        ensureSearchIndex()
    }


internal fun AppViewModel.setSearchFolder(folder: String, label: String = "") {
        state = state.copy(
            search = state.search?.copy(
                folder = folder,
                folderLabel = when {
                    folder == "all" -> "所有位置"
                    label.isNotBlank() -> label
                    else -> state.search?.folderLabel ?: "所有位置"
                }
            ),
            // 换搜索位置 = 换结果集，旧勾选不再可见，一并清掉。
            searchSelected = emptySet(),
            searchSelecting = false
        )
    }


internal fun AppViewModel.setSearchExt(ext: String) {
        state = state.copy(
            search = state.search?.copy(ext = ext),
            searchSelected = emptySet(),
            searchSelecting = false
        )
    }


    /**
     * 搜索位置选择器的第一层：本地直接列出存储根目录下的文件夹（不再提供
     * 与「所有位置」语义冲突的「内部存储」条目），群晖列共享文件夹，网络列连接。
     */
internal fun AppViewModel.searchRootDestinations(ctx: FileContext): List<Destination> = when (ctx) {
        FileContext.LOCAL -> runCatching {
            File(state.localRoot.ifBlank { Environment.getExternalStorageDirectory().absolutePath })
                .listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith('.') && it.canRead() }
                .sortedBy { it.name.lowercase() }
                .map { Destination(DestType.LOCAL, it.absolutePath, it.name, "$MODULE_LABEL_LOCAL / ${it.name}", it.absolutePath) }
        }.getOrDefault(emptyList())
        FileContext.SYNO -> state.shares.map {
            Destination(DestType.SYNO, it.path, it.title, "$MODULE_LABEL_SYNO / ${it.title}", it.path)
        }
        FileContext.NET -> state.connections.map {
            Destination(DestType.NET, it.id, it.name, "${it.protocol} / ${it.host}", network.rootPath(it))
        }
    }


    /** 搜索位置选择器的唯一键：网络端要带上连接 id，否则不同连接的同一路径会串。 */
internal fun AppViewModel.searchFolderKey(dest: Destination): String =
        if (dest.type == DestType.NET) "net:${dest.key}|${dest.path}" else dest.path


internal fun AppViewModel.searchFolderValueOf(dest: Destination, isRoot: Boolean): String = when (dest.type) {
        DestType.NET -> if (isRoot) dest.key else "net:${dest.key}|${dest.path}"
        else -> dest.path
    }


    /** Load the top level of Synology shares / network roots so local search works there too. */
internal fun AppViewModel.ensureSearchIndex() {
        val search = state.search ?: return
        if (search.ctx == FileContext.LOCAL) {
            if (state.searchIndexing) state = state.copy(searchIndexing = false)
            return
        }
        // 索引只建一次、一次写回全部结果（清单 3/11）：
        // 索引存独立字段（searchIndexSyno/searchIndexNet），不再塞进 shares.items——
        // 那样每次浏览刷新都会把索引覆盖掉，下次搜索又得重建（重复消耗网络的根因）。
        if (search.ctx == FileContext.SYNO) {
            if (state.searchIndexSyno.isNotEmpty()) {
                if (state.searchIndexing) state = state.copy(searchIndexing = false)
                return
            }
            val session = state.session ?: run {
                if (state.searchIndexing) state = state.copy(searchIndexing = false)
                return
            }
            if (searchIndexJob?.isActive == true) return
            state = state.copy(searchIndexing = true)
            searchIndexJob = viewModelScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    val all = mutableListOf<FileEntry>()
                    state.shares.forEach { share ->
                        val queue = ArrayDeque<Pair<String, Int>>()
                        queue.add(share.path to 0)
                        while (queue.isNotEmpty() && all.size < 1200) {
                            val (path, depth) = queue.removeFirst()
                            val items = runCatching { dsm.list(session, path) }.getOrDefault(emptyList())
                            all += items
                            if (depth < 4) {
                                items.filter { it.isFolder }.forEach { queue.add(it.path to depth + 1) }
                            }
                        }
                    }
                    all
                }
                state = state.copy(searchIndexSyno = loaded, searchIndexing = false)
            }
        } else if (search.ctx == FileContext.NET) {
            if (state.searchIndexNet.isNotEmpty()) {
                if (state.searchIndexing) state = state.copy(searchIndexing = false)
                return
            }
            if (searchIndexJob?.isActive == true) return
            state = state.copy(searchIndexing = true)
            searchIndexJob = viewModelScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    state.connections.associate { conn ->
                        val all = mutableListOf<FileEntry>()
                        val queue = ArrayDeque<Pair<String, Int>>()
                        queue.add(conn.path to 0)
                        while (queue.isNotEmpty() && all.size < 900) {
                            val (path, depth) = queue.removeFirst()
                            val items = runCatching { network.list(conn, path) }.getOrDefault(emptyList())
                            all += items
                            if (depth < 3) {
                                items.filter { it.isFolder }.forEach { queue.add(it.path to depth + 1) }
                            }
                        }
                        conn.id to all
                    }
                }
                state = state.copy(searchIndexNet = loaded, searchIndexing = false)
            }
        }
    }


    /**
     * 位置筛选项是固定的目录集合，绝不能被搜索关键词过滤。
     * 返回 Pair(用于过滤的值, 用户看到的友好名)。
     */
internal fun AppViewModel.searchFolders(search: SearchState): List<Pair<String, String>> {
        val raw = when (search.ctx) {
            FileContext.LOCAL -> buildList {
                val root = state.localRoot.trimEnd('/').ifBlank {
                    Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
                }
                // 不再提供「内部存储」这一项：它的语义和「所有位置」重复，
                // 而且和系统根目录同名，用户分不清选的是哪一个。
                state.localFolders.forEach { add(it.path.trimEnd('/') to it.name) }
                // 实际扫描到的文件所在目录也应可选。
                state.localFiles.mapNotNull { it.path.substringBeforeLast('/', "").ifBlank { null } }
                    .distinct()
                    .forEach { dir -> add(dir to File(dir).name.ifBlank { dir }) }
                // 系统常用目录即使暂时没扫描到文件也列为候选。
                listOf(
                    "Download" to "下载", "DCIM" to "相机", "Pictures" to "图片",
                    "Movies" to "视频", "Music" to "音乐", "Documents" to "文档"
                ).forEach { (dir, label) -> add("$root/$dir" to label) }
            }
            FileContext.SYNO -> state.shares.map { it.path.trimEnd('/') to it.title }
            FileContext.NET -> state.connections.map { it.id to it.name }
        }
        return raw.filter { it.first.isNotBlank() }.distinctBy { it.first }
    }


internal fun AppViewModel.matchesFolder(file: FileEntry, value: String, ctx: FileContext): Boolean {
        if (value == "all") return true
        if (ctx == FileContext.NET) {
            // 连接级过滤（纯 id）时结果池已按连接收敛；深一层用 net:id|path。
            if (!value.startsWith("net:")) return true
            val path = value.substringAfter('|', "").trimEnd('/')
            if (path.isBlank()) return true
            return file.path == path || file.path.startsWith("$path/")
        }
        val normalized = value.trimEnd('/')
        return file.path == normalized ||
            file.path.startsWith("$normalized/") ||
            file.folder == value ||
            file.folder == File(normalized).name
    }


internal fun AppViewModel.searchResults(search: SearchState): List<FileEntry> {
        val query = search.query.trim()
        val suffixes = search.ext.split(',', '，', ' ').map { it.trim().removePrefix(".").lowercase() }.filter { it.isNotBlank() }
        // 关键词和后缀至少要有一个，否则什么都不输就搜索会返回全部。
        if (query.isEmpty() && suffixes.isEmpty()) return emptyList()
        val localFolders = buildList {
            addAll(state.localFolders.map { folder ->
                FileEntry(
                    id = "search-folder:${folder.path}",
                    name = folder.name,
                    kind = FileKind.FOLDER,
                    sizeLabel = "文件夹",
                    timeLabel = folder.date,
                    folder = folder.meta,
                    childCount = folder.count,
                    path = folder.path
                )
            })
            state.localFiles.mapNotNull { it.path.substringBeforeLast('/', "").ifBlank { null } }
                .distinct()
                .forEach { dir ->
                    // 存储根目录的末段是「0」，直接拿末段当名字会出来一个叫「0」的文件夹。
                    val name = displayLeaf(dir, state.localRoot)
                    add(FileEntry(id = "search-folder:$dir", name = name, kind = FileKind.FOLDER, folder = "内部存储", path = dir))
                }
        }
        val pool = when (search.ctx) {
            FileContext.LOCAL -> state.localFiles + localFolders
            // 索引走独立字段（清单 11）：浏览刷新不再影响搜索结果。
            FileContext.SYNO -> state.shares.map { share ->
                FileEntry(
                    id = "search-share:${share.path}",
                    name = share.title,
                    kind = FileKind.FOLDER,
                    folder = MODULE_LABEL_SYNO,
                    path = share.path
                )
            } + state.searchIndexSyno
            FileContext.NET -> {
                val scoped = when {
                    search.folder == "all" -> state.connections
                    search.folder.startsWith("net:") -> state.connections.filter {
                        it.id == search.folder.removePrefix("net:").substringBefore('|')
                    }
                    else -> state.connections.filter { it.id == search.folder }
                }
                scoped.map { conn ->
                    FileEntry(
                        id = "search-net:${conn.id}",
                        name = conn.name,
                        kind = FileKind.FOLDER,
                        folder = conn.name,
                        path = conn.id
                    )
                } + scoped.flatMap { state.searchIndexNet[it.id].orEmpty() }
            }
        }
        val result = pool.distinctBy { it.id }.filter { file ->
            (query.isEmpty() || file.name.contains(query, ignoreCase = true)) &&
                matchesFolder(file, search.folder, search.ctx) &&
                (suffixes.isEmpty() || suffixes.any { file.name.lowercase().endsWith(".$it") })
        }
        return sortFiles(result)
    }


internal fun AppViewModel.openSearchResult(file: FileEntry) {
        val ctx = state.search?.ctx ?: contextOf(state.tab)
        val netId = state.browser?.netId ?: state.connections.firstOrNull { conn ->
            val root = network.rootPath(conn)
            file.path.startsWith(root) || (conn.host.isNotBlank() && file.path.contains(conn.host))
        }?.id
        val stackBefore = state.browserStack
        state = state.copy(search = null)
        if (file.isFolder) {
            // openFolder 需要一个 browser 作为来源上下文。这里先放一个临时根页，
            // 但绝不能让它进入返回栈，否则「返回」会落到一个空白目录页。
            state = state.copy(
                browser = BrowserState(
                    title = file.name,
                    path = file.name,
                    ctx = ctx,
                    items = emptyList(),
                    grid = state.prefs.tabViews[ctx.name.lowercase()] == "grid",
                    netId = netId,
                    location = file.path
                ),
                browserStack = stackBefore
            )
            openFolder(file)
            state = state.copy(browserStack = state.browserStack.take(stackBefore.size))
        } else {
            requestOpen(file)
        }
    }
