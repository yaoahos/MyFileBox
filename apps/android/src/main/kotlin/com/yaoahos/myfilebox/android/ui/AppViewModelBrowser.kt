package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.MODULE_LABEL_LOCAL
import com.yaoahos.myfilebox.core.MODULE_LABEL_NET
import com.yaoahos.myfilebox.core.MODULE_LABEL_SYNO
import com.yaoahos.myfilebox.core.STORAGE_ROOT_LABEL
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
     * 全盘扫描并写回本地列表。
     *
     * @param pullToken 由下拉刷新传进来（[beginPull] 的令牌）：扫描结束、或发现已经在扫时，
     *   只收掉这一轮自己的转圈。不传的触发（删除、还原、权限变更、回前台）完全不碰转圈，
     *   结果从页面上的进度提示看。
     */
internal fun AppViewModel.refreshLocal(pullToken: Int? = null) {
        // 操作完成、切回前台、权限变更都会触发；全盘遍历不轻，并发跑没有意义。
        // 但绝不能静默丢弃：旧实现的 early return 会把「压缩/删除后的那次刷新」吞掉，
        // 界面因此一直显示旧数据，用户只能手动刷新。
        if (localScanInFlight) {
            if (pullToken != null) {
                // 已经在扫：这轮下拉直接收圈，扫描完成时列表自然会更新，不用再排一遍。
                // 只收自己这一轮——旧实现在这里把全局 refreshing 置 false，
                // 会把别处正在转的圈一起收掉。
                endPull(pullToken, "已刷新")
            } else {
                localScanPending = true
            }
            return
        }
        localScanInFlight = true
        viewModelScope.launch {
            try {
                // 先把根目录一层铺上，深扫描完成后再整体替换：
                // 否则文件多的设备上首页会空白几秒，看起来像「必须手动刷新」。
                if (state.localFiles.isEmpty() && state.localFolders.isEmpty()) {
                    val quick = withContext(Dispatchers.IO) { local.quickSnapshot() }
                    if (state.localFiles.isEmpty() && state.localFolders.isEmpty()) {
                        state = state.copy(
                            localFiles = quick.files,
                            localFolders = quick.folders,
                            localUsedGb = quick.usedGb,
                            localFreeGb = quick.freeGb,
                            localTotalGb = quick.totalGb,
                            localAccess = quick.hasAccess,
                            localRoot = quick.rootPath
                        )
                    }
                }
                val snapshot = withContext(Dispatchers.IO) { local.snapshot() }
                // 「最近」要在全盘结果上做时间窗、噪音名、缓存目录、备份副本判定
                // （每项多次 lowercase + split + 正则）再排序。以前这段跑在主线程，
                // 冷启动首帧会被它拖出上百毫秒——和扫描本身一样重，必须在 IO 里做完。
                val recentFastNow = recentFast
                val hideBackups = state.hideRecentBackups
                val hideCache = state.hideRecentCache
                val recent = withContext(Dispatchers.IO) {
                    RecentPolicy.merge(
                        fast = recentFastNow,
                        slow = snapshot.files,
                        now = System.currentTimeMillis(),
                        hideBackups = hideBackups,
                        hideCache = hideCache
                    )
                }
                // 首次启动时「所有文件访问权限」还没授予，downloadsDir() 会回退到
                // 应用私有目录（/Android/data/<pkg>/files/Download）并把它持久化。
                // 用户之后授予权限，下载仍会写进私有目录——文件管理器里根本看不到。
                // 一旦拿到真实存储权限，就把这类失效/私有的默认目录纠正回公共下载目录。
                val saved = state.prefs.downloadFolder
                val privateRoot = runCatching { getApplication<Application>().getExternalFilesDir(null)?.absolutePath }.getOrNull()
                val savedIsPrivate = saved.isNotBlank() && privateRoot != null && saved.startsWith(privateRoot)
                val needFix = saved.isBlank() ||
                    (snapshot.hasAccess && (savedIsPrivate || !File(saved).exists()))
                val download = if (needFix) local.downloadsDir().absolutePath else saved
                state = state.copy(
                    localFiles = snapshot.files,
                    photos = snapshot.photos,
                    localFolders = snapshot.folders,
                    categoryCounts = snapshot.counts,
                    recentFiles = recent,
                    localUsedGb = snapshot.usedGb,
                    localFreeGb = snapshot.freeGb,
                    localTotalGb = snapshot.totalGb,
                    localAccess = snapshot.hasAccess,
                    localRoot = snapshot.rootPath,
                    prefs = if (needFix) state.prefs.copy(downloadFolder = download) else state.prefs
                )
                if (needFix) persist()
                saveCounts(snapshot.counts)
                lastScanAt = System.currentTimeMillis()

                // 分类页的列表是从旧快照复制出来的，刷新后要按分类重算，
                // 否则停在「图片」页时新下载的图片不会出现。
                val browser = state.browser
                val category = browser?.localCategory
                if (browser != null && category != null) {
                    val fresh = filesForLocalCategory(category, snapshot)
                    if (fresh != null) {
                        val ordered = if (category == "最近") fresh else sortFiles(fresh)
                        state = state.copy(browser = browser.copy(items = ordered))
                    }
                } else if (browser != null && browser.ctx == FileContext.LOCAL) {
                    // 真实目录也要按磁盘重列：回收站还原、别的应用写入之后，
                    // 只更新内存缓存不会让「正停在这个目录」的列表发生变化，
                    // 表现就是「恢复了但原位置没刷新」。
                    val location = browser.location.ifBlank { snapshot.rootPath }
                    val fresh = withContext(Dispatchers.IO) {
                        runCatching { local.list(location) }.getOrDefault(emptyList())
                    }
                    val current = state.browser
                    if (current != null && current.ctx == FileContext.LOCAL &&
                        current.location == browser.location
                    ) {
                        state = state.copy(browser = current.copy(items = sortFiles(fresh)))
                    }
                }
            } finally {
                localScanInFlight = false
                // 下拉触发的那次扫描结束时收圈：转圈跟真实扫描同步，不额外等固定时长。
                if (pullToken != null) endPull(pullToken, "已刷新")
                // 忙时排队的刷新请求在这里补跑，保证最后一次操作一定被校准。
                if (localScanPending) {
                    localScanPending = false
                    refreshLocal()
                }
            }
        }
    }


    /**
     * 本地首页展示所需的派生数据：根目录直属文件、按偏好排序并筛选后的文件夹与文件、
     * 全盘按类型分组、本地收藏。
     *
     * 结果缓存在 ViewModel（见 [HomeDerivedCache]），扫描结果与排序 / 筛选偏好没变时
     * 切多少次底栏都是零计算；只有真正扫描完或改了排序 / 筛选才重算一次。
     */
internal fun AppViewModel.homeDerived(): HomeDerived {
        val s = state
        val rootPath = s.localRoot.trimEnd('/')
        return homeDerivedCache.getOrCompute(
            files = s.localFiles,
            folders = s.localFolders,
            prefs = s.prefs,
            rootPath = rootPath,
            nameFilter = s.nameFilter,
            extensionFilter = s.extensionFilter
        ) {
            // 根目录直属文件：只比一次父路径，比按名称匹配便宜得多。
            val rootFiles = s.localFiles.filter {
                it.path.trimEnd('/').substringBeforeLast('/') == rootPath
            }
            HomeDerived(
                rootFiles = rootFiles,
                folders = sortedFolders(s.localFolders).filter {
                    itemMatchesFilter(it.name, true, s.nameFilter, s.extensionFilter)
                },
                rootItems = sortFiles(rootFiles).filter {
                    itemMatchesFilter(it.name, it.kind == FileKind.FOLDER, s.nameFilter, s.extensionFilter)
                },
                byKind = s.localFiles.groupBy { it.kind },
                favorites = favoriteFiles(FileContext.LOCAL)
            )
        }
    }


    /**
     * 「最近」的媒体库查询：索引命中，与文件总量无关。
     * 查不到（未授权 / ROM 未索引）时保留已有列表，不清空。
     */
internal fun AppViewModel.refreshRecent() {
        viewModelScope.launch {
            val since = System.currentTimeMillis() - RecentPolicy.WINDOW_MS
            val fast = withContext(Dispatchers.IO) {
                runCatching { local.recentFromMediaStore(since) }.getOrDefault(emptyList())
            }
            if (fast.isEmpty() && recentFast.isEmpty()) return@launch
            recentFast = fast
            // 与 refreshLocal 同理：合并规则要遍历全盘结果，必须离开主线程。
            val files = state.localFiles
            val hideBackups = state.hideRecentBackups
            val hideCache = state.hideRecentCache
            val merged = withContext(Dispatchers.IO) {
                RecentPolicy.merge(fast, files, System.currentTimeMillis(), hideBackups, hideCache)
            }
            state = state.copy(recentFiles = merged)
        }
    }


    /** 设置项：「最近」里隐藏备份副本。 */
internal fun AppViewModel.setHideRecentBackups(on: Boolean) {
        if (state.hideRecentBackups == on) return
        state = state.copy(hideRecentBackups = on)
        prefsStore.edit().putBoolean("recent_hide_backups", on).apply()
        recomputeRecentAsync()
    }


    /** 设置项：「最近」里隐藏应用缓存目录。 */
internal fun AppViewModel.setHideRecentCache(on: Boolean) {
        if (state.hideRecentCache == on) return
        state = state.copy(hideRecentCache = on)
        prefsStore.edit().putBoolean("recent_hide_cache", on).apply()
        recomputeRecentAsync()
    }


    /** 按当前两个开关重算「最近」；合并规则遍历全盘，一律在 IO 线程完成。 */
internal fun AppViewModel.recomputeRecentAsync() {
        viewModelScope.launch {
            val files = state.localFiles
            val hideBackups = state.hideRecentBackups
            val hideCache = state.hideRecentCache
            val fast = recentFast
            val merged = withContext(Dispatchers.IO) {
                RecentPolicy.merge(fast, files, System.currentTimeMillis(), hideBackups, hideCache)
            }
            state = state.copy(recentFiles = merged)
        }
    }


    /** 按分类名从最新快照重算列表；「收藏」有独立来源，不走这里。 */
internal fun AppViewModel.filesForLocalCategory(category: String, snapshot: com.yaoahos.myfilebox.android.data.LocalSnapshot): List<FileEntry>? =
        when (category) {
            // 「最近」以合并结果为准；合并结果还没出来（冷启动第一帧）时按同一套规则现算。
            "最近" -> state.recentFiles.ifEmpty {
                RecentPolicy.filter(
                    snapshot.files, System.currentTimeMillis(),
                    state.hideRecentBackups, state.hideRecentCache
                )
            }
            else -> FileKind.entries
                .firstOrNull { fileKindLabel(it) == category }
                ?.let { kind -> snapshot.files.filter { it.kind == kind } }
        }


internal fun AppViewModel.openLocalCategory(title: String, items: List<FileEntry>, groupByDate: Boolean = false) {
        // 「最近」是时间流：一律按修改时间倒序，不受其它分类的排序偏好影响。
        // 之前按 sortFiles() 走，列表头会显示「名称 · 升序」，日期分组里的顺序也就乱了。
        val sorted = if (title == "最近") items.sortedByDescending { it.modified } else sortFiles(items)
        state = state.copy(
            browser = BrowserState(
                title, "$MODULE_LABEL_LOCAL / $title", FileContext.LOCAL, sorted,
                groupByDate = groupByDate, localCategory = title
            ),
            browserStack = emptyList(),
            selected = emptySet(), selectionActive = false,
            // 分类页绕过 pushBrowser，筛选词要在这里手工清：否则从目录带着「jpg」进「图片」
            // 分类，会看到一屏空（分类页的 items 已经是筛过一遍的结果，再筛一次多半为空）。
            nameFilter = "", extensionFilter = "",
            // 分类页绕过 pushBrowser，必须显式标为「进入」，否则动画方向沿用上一次返回。
            navDir = 1
        )
    }


internal fun AppViewModel.openLocalFolder(name: String) {
        val folder = state.localFolders.firstOrNull { it.name == name }
        if (folder == null) {
            state = state.copy(message = "文件夹不存在")
            return
        }
        openLocalFolderPath(folder.path, folder.name)
    }


internal fun AppViewModel.openStorage(path: String, title: String) {
        if (path.isBlank()) {
            state = state.copy(message = "存储位置不可用")
            return
        }
        state = state.copy(tab = TabKey.LOCAL)
        openLocalFolderPath(path, title)
    }


/**
 * 本地路径的「所在位置」名：存储根目录统一叫「内部存储」，其余取上一层目录名。
 *
 * 根目录是 /storage/emulated/0，末段是「0」——直接取末段会让根下的文件在详情里
 * 显示成「所在位置 0」。名字统一走 [STORAGE_ROOT_LABEL]，不要再写字面量。
 */
internal fun AppViewModel.localFolderLabel(path: String): String {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isBlank()) return ""
        return if (parent.trimEnd('/') == state.localRoot.trimEnd('/')) STORAGE_ROOT_LABEL
        else parent.substringAfterLast('/')
    }


internal fun AppViewModel.openLocalFolderPath(path: String, title: String) {
        // 存储根目录（/storage/emulated/0）的末段是「0」，直接拿来当标题会显示成
        // 「内部存储 / 0」。根目录就按用户能读懂的叫法显示，路径层级也一并归一。
        val rootPath = state.localRoot.trimEnd('/').ifBlank {
            Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        }
        val atRoot = path.trimEnd('/') == rootPath
        val pageTitle = if (atRoot) STORAGE_ROOT_LABEL else title
        pushBrowser(
            BrowserState(
                title = pageTitle,
                // 面包屑第一层是模块名，这里同步用它，避免 path 与面包屑两套叫法。
                path = "$MODULE_LABEL_LOCAL / $pageTitle",
                ctx = FileContext.LOCAL,
                items = emptyList(),
                location = path
            )
        )
        state = state.copy(loading = true)
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { local.list(path) }
            if (state.browser?.location == path) {
                state = state.copy(
                    loading = false,
                    browser = state.browser?.copy(items = sortFiles(items))
                )
            }
        }
    }
    /** 启动时把每个网络位置探一次，卡片直接显示在离线状态，不用一个个手动测。 */
internal fun AppViewModel.warmConnections() {
        val list = state.connections
        if (list.isEmpty()) return
        viewModelScope.launch {
            // 并发探测：连接数多时串行会等满一个个超时，非常慢。
            val results = withContext(Dispatchers.IO) {
                kotlinx.coroutines.coroutineScope {
                    list.map { connection ->
                        async {
                            val started = System.currentTimeMillis()
                            val ok = runCatching { network.test(connection) }.getOrDefault(false)
                            Triple(connection.id, ok, if (ok) (System.currentTimeMillis() - started).toInt() else 0)
                        }
                    }.awaitAll()
                }
            }
            val byId = results.associateBy { it.first }
            state = state.copy(
                connections = state.connections.map { connection ->
                    val hit = byId[connection.id] ?: return@map connection
                    if (hit.second) connection.copy(online = true, latency = hit.third)
                    else if (connection.online) connection  // 探测失败不降级已在线连接
                    else connection.copy(online = false, latency = 0)
                }
            )
            persist()
        }
    }


    /** 刷新群晖共享文件夹 / 卷；首页进入时若为空也会调用，避免冷启动要手动刷新。 */
    /**
     * 拉取群晖共享列表。
     *
     * [skipRecovery] 只在「自动恢复成功后重刷」时传 true，避免「刷新失败 → 触发恢复 →
     * 恢复成功又刷新失败」这种自我递归。
     */
internal fun AppViewModel.refreshSyno(skipRecovery: Boolean = false, retried: Boolean = false) {
        val session = state.session ?: return
        if (session.sid.isBlank()) return
        // 共享列表即将被替换，旧搜索索引作废（清单 11：下次搜索重建一次）。
        if (state.searchIndexSyno.isNotEmpty()) state = state.copy(searchIndexSyno = emptyList())
        viewModelScope.launch {
            state = state.copy(loading = true)
            // 三个请求并发（清单 10）：串行时每个都要等上一个往返，整体接近 3 倍时延。
            // 旧实现把异常整个吞成空列表，于是「切网后连不上」在界面上表现为
            // 「群晖上暂无共享文件夹」——既没有错误提示，也不会触发任何重连。
            val sharesDeferred = async { runCatching { dsm.shares(session) } }
            val volumesDeferred = async { runCatching { dsm.volumes(session) } }
            val favoriteDeferred = async { runCatching { dsm.supportsFavorite(session) } }
            val sharesResult = sharesDeferred.await()
            val volumes = volumesDeferred.await().getOrDefault(emptyList())
            val favoriteSupported = favoriteDeferred.await().getOrDefault(false)
            val shares = sharesResult.getOrDefault(emptyList())
            val failure = sharesResult.exceptionOrNull()
            if (failure != null && shares.isEmpty()) {
                state = state.copy(loading = false)
                android.util.Log.w(
                    "MFB-DSM",
                    "refreshSyno failed: ${failure.javaClass.simpleName}"
                )
                // 恢复流程可能刚好在我们请求在途时换了 SID（DSM 同一 session 名重新登录后，
                // 旧 SID 立即失效 → 119）。这不是故障，用新会话再拉一次即可。
                val latest = state.session
                if (!retried && latest != null && latest.sid.isNotBlank() && latest.sid != session.sid) {
                    android.util.Log.w("MFB-DSM", "refreshSyno retry with renewed sid")
                    refreshSyno(skipRecovery = true, retried = true)
                    return@launch
                }
                if (!skipRecovery) {
                    // 网络/通道问题的典型信号：连不上、超时、socket 被重置。
                    // 会话类错误码在 dsm 内部已各自处理，这里统一交给自愈流程。
                    requestSynoRecovery("刷新群晖失败：${failure.javaClass.simpleName}")
                }
                val reason = failure.message?.takeIf { it.isNotBlank() } ?: "网络无法连接"
                android.util.Log.w("MFB-DSM", "refreshSyno reason: $reason")
                // 读取失败统一说「连接超时」，不去解释是不是切网造成的；
                // 页面上同时挂着可点的「重试」，用户不用理解原因也能继续。
                val switched = recentNetworkChange()
                state = state.copy(
                    message = if (switched) "网络已变化，请刷新后重试" else "群晖连接失败：${friendlyNetError(failure)}",
                    connectHint = switched || state.connectHint
                )
                return@launch
            }
            state = state.copy(
                shares = shares,
                volumes = if (volumes.isNotEmpty()) volumes else state.volumes,
                synoFavoriteSupported = favoriteSupported,
                loading = false,
                // 列表回来了说明通道已经可用，切网提示不再需要。
                connectHint = false,
                // 不再用「未读取到」这种像连接失败的措辞（清单 2 精神）。
                // 保留已有提示：还原/上传等操作的成功提示常常和这次刷新同时到，
                // 以前这里一律写 null，会把「已还原…」瞬间抹掉，看起来像没提示（3.9.17）。
                message = if (shares.isEmpty()) "群晖上暂无共享文件夹" else state.message
            )
        }
    }


internal fun AppViewModel.openShare(share: ShareFolder) {
        viewModelScope.launch {
            // 没网/飞行模式时别去撞通道与 12 秒超时：结论在点下去那一刻就确定了，
            // 再让用户对着首页等十几秒，最后收到的还是同一句「连接不上」。
            val offline = offlineNotice()
            if (offline != null) {
                state = state.copy(loading = false, connectHint = false, message = offline)
                return@launch
            }
            state = state.copy(loading = true, connectHint = false)
            // 切网后进共享文件夹要重新解析通道再登录，实测 7~8 秒；这段时间首页
            // 不会有任何变化，用户只能反复点。1 秒还没结果就把「正在连接」摆出来。
            armNetTimeoutHint(share.path)
            detectNetworkSwitchNow()
            // 切网后先修通道，别拿失效的端点去撞 12 秒连接超时。
            if (!repairChannelIfNeeded()) {
                state = state.copy(
                    loading = false,
                    message = synoUnreachableMessage(),
                    connectHint = true
                )
                return@launch
            }
            val session = state.session
            if (session == null || session.sid.isBlank()) {
                state = state.copy(loading = false, message = "群晖登录状态已失效，请重新登录")
                return@launch
            }
            val path = share.path.ifBlank { "/${share.key}" }
            val result = runCatching { dsm.list(session, path, quick = recentNetworkChange()) }
            val failure = result.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) return@launch
            if (failure != null) {
                // 切网后点共享文件夹同样会卡在旧 socket 上：说清原因并触发自愈。
                val switched = recentNetworkChange()
                state = state.copy(
                    loading = false,
                    message = switchFailureMessage(switched, failure.message ?: "读取共享文件夹失败"),
                    connectHint = switched || state.connectHint
                )
                if (switched) requestSynoRecovery("读取共享失败（网络已切换）")
                return@launch
            }
            val items = result.getOrDefault(emptyList())
            state = state.copy(loading = false, connectHint = false)
            pushBrowser(
                BrowserState(
                    title = share.title,
                    path = "$MODULE_LABEL_SYNO / ${share.title}",
                    ctx = FileContext.SYNO,
                    items = sortFiles(items),
                    location = path
                )
            )
        }
    }


internal fun AppViewModel.openConnection(connection: NetworkConnection) {
        viewModelScope.launch {
            // 没网时不要先进「正在连接…」再等 4 秒——卡片上那行转圈会给用户
            // 「它正在努力」的错觉，而真正需要他做的是去打开 WiFi。
            val offline = offlineNotice()
            if (offline != null) {
                state = state.copy(connectHint = false, message = offline)
                return@launch
            }
            // 卡片上的「● 在线 · N ms」是上一次探测的结论，进目录不改写它：列目录只是
            // 读一次，通常几十毫秒，把状态翻成「正在连接…」既一闪而过，又让人以为掉线了。
            // message 清空：上一次「保存后测连」的失败提示可能还挂在屏幕上，
            // 用户刚点进来就看到「连接超时」，会以为这次点击已经失败了。
            state = state.copy(loading = true, connectHint = false, message = null)
            // 进入网络位置也按 1 秒给反馈：连不上时不该让用户对着转圈等 socket 超时。
            armNetTimeoutHint(connection.path)
            val started = System.currentTimeMillis()
            val result = runCatching { network.list(connection, connection.path) }
            val failure = result.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) return@launch
            if (failure != null) {
                onNetFailure(connection, failure)
                return@launch
            }
            val items = result.getOrDefault(emptyList())
            state = state.copy(loading = false, connectHint = false)
            val cost = (System.currentTimeMillis() - started).toInt()
            state = state.copy(
                connections = state.connections.map {
                    if (it.id == connection.id) it.copy(online = true, latency = cost) else it
                }
            )
            pushBrowser(
                BrowserState(
                    title = connection.name,
                    // 不带协议名：协议是连接属性，不是一层目录，放进面包屑只会占位。
                    path = "$MODULE_LABEL_NET / ${connection.name}",
                    ctx = FileContext.NET,
                    items = sortFiles(items),
                    netId = connection.id,
                    location = connection.path
                )
            )
        }
    }


internal fun AppViewModel.testConnection(connection: NetworkConnection) {
        viewModelScope.launch {
            // 手机没网时直接给结论：不发请求、不等 socket 超时。
            // 这里的等待（4 秒）对用户毫无信息量——结论早就定了，只差没说出来。
            val offline = offlineNotice()
            if (offline != null) {
                state = state.copy(
                    loading = false,
                    connections = state.connections.map {
                        if (it.id == connection.id) it.copy(online = false, latency = 0) else it
                    },
                    message = offline
                )
                persist()
                return@launch
            }
            state = state.copy(loading = true)
            val started = System.currentTimeMillis()
            // 测试失败要给出具体原因（解析失败 / 端口拒绝 / 认证失败 / 超时），
            // 只说「连接失败」等于让用户自己猜，等于什么都没说。
            var errorMsg = ""
            val ok = withContext(Dispatchers.IO) {
                runCatching { network.test(connection) }.getOrElse {
                    // 带上 host：同样是超时，「内网地址 + 移动数据」与「公网地址不通」
                    // 要给的下一步完全不同（前者是连回 Wi-Fi，后者才是查服务器）。
                    errorMsg = friendlyNetError(it, "无法连接该服务器", host = connection.host)
                    false
                }
            }
            val cost = (System.currentTimeMillis() - started).toInt()
            state = state.copy(
                loading = false,
                connections = state.connections.map {
                    if (it.id == connection.id) it.copy(online = ok, latency = if (ok) cost else 0) else it
                },
                message = if (ok) "连接可用 · $cost ms"
                else errorMsg.ifBlank { "连接超时" }
            )
            persist()
        }
    }


    /**
     * 用对话框里「正在编辑」的参数测连，不写回配置。
     *
     * 之前只能测试已保存的连接：改完地址点测试，用的还是旧配置，
     * 必须先保存修改才能验证新参数。
     */
internal fun AppViewModel.testConnectionDraft(
        protocol: String,
        host: String,
        port: String,
        path: String,
        username: String,
        password: String,
        secure: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        // 用户可能把完整 URL 粘进「服务器地址」（https://dav.xx.com/dav）。
        // 直接拿它当 host 会变成 https://https://…，报错是「Unable to resolve host "https"」，
        // 看起来像服务器挂了，其实只是输入没解析。这里和保存时走同一套拆分逻辑。
        val (parsedHost, parsedPort, parsedPath) = parseServerInput(host)
        val effectivePort = parsedPort.ifBlank { port }.toIntOrNull()
        val effectivePath = parsedPath.ifBlank { path }
        if (parsedHost.isBlank() || effectivePort == null || effectivePort !in 1..65535) {
            onResult(false, "请先填写有效的服务器地址和端口")
            return
        }
        val draft = NetworkConnection(
            id = "draft",
            name = "草稿连接",
            protocol = protocol,
            host = parsedHost.trim(),
            port = effectivePort,
            path = effectivePath.ifBlank { if (protocol == "SMB") "" else "/" },
            online = false,
            latency = 0,
            username = username,
            password = password,
            // URL 里写了 https:// 时按安全连接处理，和保存逻辑保持一致。
            secure = secure || parsedPort == "443" || host.trim().startsWith("https://", true)
        )
        // 没网时不必真的去握手：结论在点「测试」那一刻就确定了，直接回给对话框。
        val offline = offlineNetworkMessage()
        if (offline != null) {
            onResult(false, offline)
            return
        }
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            var errorMsg = ""
            val ok = withContext(Dispatchers.IO) {
                runCatching { network.test(draft) }.getOrElse {
                    // 具体原因（DNS / 端口 / 认证 / 超时）由 friendlyNetError 翻成人话，
                    // 不再回落到「请检查地址 / 端口 / 路径」——那句话用户没法执行。
                    errorMsg = friendlyNetError(it, "无法连接该服务器", host = parsedHost)
                    false
                }
            }
            val cost = (System.currentTimeMillis() - started).toInt()
            onResult(
                ok,
                if (ok) "连接可用 · $cost ms"
                else errorMsg.ifBlank { "连接超时" }
            )
        }
    }


internal fun AppViewModel.openFolder(entry: FileEntry) {
        val browser = state.browser ?: return
        when (browser.ctx) {
            FileContext.LOCAL -> openLocalFolderPath(entry.path, entry.name)
            FileContext.SYNO -> {
                val previous = state.browser
                val previousStack = state.browserStack
                pushBrowser(browser.copy(title = entry.name, path = "${browser.path} / ${entry.name}", items = emptyList(), location = entry.path))
                // 点目录这一刻先查一次网络：系统回调常常迟到，不该让用户先对着空白页等。
                detectNetworkSwitchNow()
                // 端点还指着刚离开的局域网时，接下来必然要先重建通道（命中中继缓存 1~2 秒，
                // 冷启动更久）。判断是同步的，所以能在发请求之前就把原因挂到页面上：
                // 用户看到的是「网络已切换，正在重建连接」，而不是一个不明所以的空白转圈。
                // 只认「真要修」这一种（走中继时不需要修，别虚报一次重建）；
                // 但刚切过网的 20 秒窗口内整段等待都要算进来——这时重建那一步往往只是
                // join 已经在跑的自愈任务（几十毫秒返回），真正让用户干等的是随后那次
                // 短超时（4 秒）的目录请求。从用户视角看，两者都是「切网后正在重建连接」。
                val rebuilding = willRepairChannel() || recentNetworkChange()
                state = state.copy(loading = true, connectHint = false, repairingChannel = rebuilding)
                browserLoadJob?.cancel()
                browserLoadJob = viewModelScope.launch {
                    // 刚切过网时端点可能还指着已经离开的局域网：先修通道再发请求。
                    // 不修就直接 list，这条请求会卡满连接/读超时才失败，用户看到的就是
                    // 「点目录没反应」。修不好也不必再撞超时，直接给可执行的提示。
                    if (!repairChannelIfNeeded()) {
                        state = state.copy(
                            loading = false,
                            repairingChannel = false,
                            browser = if (previous?.items.isNullOrEmpty() && previous?.location == entry.path) null else previous,
                            browserStack = previousStack,
                            message = synoUnreachableMessage(),
                            connectHint = true
                        )
                        return@launch
                    }
                    // 提示不在这里撤：重建只是等待的一部分，随后那次目录请求同样要等
                    // （切网窗口内是 4 秒短超时）。等列表真正落地、或确定失败时一起收。
                    // 自愈可能刚换掉 SID，必须用最新会话（旧 SID 只会再换回一个 119）。
                    val session = state.session
                    if (session == null || session.sid.isBlank()) {
                        state = state.copy(
                            loading = false,
                            repairingChannel = false,
                            browser = if (previous?.items.isNullOrEmpty() && previous?.location == entry.path) null else previous,
                            browserStack = previousStack,
                            message = "群晖登录状态已失效，请重新登录"
                        )
                        return@launch
                    }
                    // 切网窗口内用短超时客户端：宁可 4 秒失败给提示，也不干等读超时。
                    val result = runCatching {
                        dsm.list(session, entry.path, quick = recentNetworkChange())
                    }
                    val failure = result.exceptionOrNull()
                    // 切网时我们在途取消：取消不是「读取失败」，状态复位与提示交给 notifyNetworkChanged。
                    if (failure is kotlinx.coroutines.CancellationException) return@launch
                    if (failure != null) {
                        // 切网后的失败几乎都是旧 socket 失效：先把原因说清楚，
                        // 同时触发一次自愈，用户点「刷新」就能恢复正常。
                        val switched = recentNetworkChange()
                        state = state.copy(
                            loading = false,
                            repairingChannel = false,
                            browser = if (previous?.items.isNullOrEmpty() && previous?.location == entry.path) null else previous,
                            browserStack = previousStack,
                            message = switchFailureMessage(switched, "读取目录失败：${failure.message ?: "请检查登录状态"}"),
                            connectHint = switched || state.connectHint
                        )
                        if (switched) requestSynoRecovery("读取目录失败（网络已切换）")
                        return@launch
                    }
                    val items = result.getOrDefault(emptyList())
                    lastLoadedLocation = entry.path
                    if (state.browser?.location == entry.path) {
                        state = state.copy(
                            loading = false,
                            repairingChannel = false,
                            browser = state.browser?.copy(items = sortFiles(items)),
                            connectHint = false
                        )
                    }
                }
            }
            FileContext.NET -> {
                val connection = state.connections.firstOrNull { it.id == browser.netId } ?: return
                val previous = state.browser
                val previousStack = state.browserStack
                pushBrowser(browser.copy(title = entry.name, path = "${browser.path} / ${entry.name}", items = emptyList(), location = entry.path))
                state = state.copy(loading = true, connectHint = false)
                detectNetworkSwitchNow()
                armNetTimeoutHint(entry.path)
                browserLoadJob?.cancel()
                browserLoadJob = viewModelScope.launch {
                    val result = runCatching { network.list(connection, entry.path) }
                    val failure = result.exceptionOrNull()
                    if (failure is kotlinx.coroutines.CancellationException) return@launch
                    if (failure != null) {
                        state = state.copy(
                            browser = if (previous?.items.isNullOrEmpty() && previous?.location == entry.path) null else previous,
                            browserStack = previousStack
                        )
                        onNetFailure(connection, failure)
                        return@launch
                    }
                    val items = result.getOrDefault(emptyList())
                    markConnectionOnline(connection.id)
                    if (state.browser?.location == entry.path) {
                        state = state.copy(loading = false, browser = state.browser?.copy(items = sortFiles(items)), connectHint = false)
                    }
                }
            }
        }
    }


internal fun AppViewModel.pushBrowser(next: BrowserState) {
        val current = state.browser
        val tab = when (next.ctx) {
            FileContext.LOCAL -> TabKey.LOCAL
            FileContext.SYNO -> TabKey.SYNO
            FileContext.NET -> TabKey.NET
        }
        // 进目录是用户导航：进行中的「定位目标文件夹」要作废。
        state = state.copy(
            browser = next.copy(grid = viewMode(tab) == "grid"),
            browserStack = if (current != null) state.browserStack + current else state.browserStack,
            selected = emptySet(), selectionActive = false,
            // 进入新目录意味着换了「当前位置」，上一处的筛选词不能跟过来。
            nameFilter = "",
            extensionFilter = "",
            navDir = 1
        )
    }


internal fun AppViewModel.backFromBrowser() {
        if (state.browserStack.isNotEmpty()) {
            val previous = state.browserStack.last()
            val tab = when (previous.ctx) {
                FileContext.LOCAL -> TabKey.LOCAL
                FileContext.SYNO -> TabKey.SYNO
                FileContext.NET -> TabKey.NET
            }
            state = state.copy(
                browser = previous.copy(grid = viewMode(tab) == "grid"),
                browserStack = state.browserStack.dropLast(1),
                selected = emptySet(), selectionActive = false,
                // 回到上一页 = 换了一个位置，上一页的筛选词不能跟过来。
                // 不清理的话返回后列表还是被筛过的，用户会以为文件丢了。
                nameFilter = "", extensionFilter = "",
                navDir = -1
            )
            // 传输记录跳转时上层目录是按路径摊出来的（items 为空）：回到这一层补一次加载，
            // 否则会停在一屏空白上，看着像空文件夹。真·空目录重列一次也无害。
            if (previous.items.isEmpty() && !previous.favorites && previous.localCategory == null) {
                state = state.copy(loading = true)
                browserLoadJob?.cancel()
                browserLoadJob = viewModelScope.launch {
                    reloadBrowserItems()
                    if (state.browser?.location == previous.location) state = state.copy(loading = false)
                }
            }
        } else {
            state = state.copy(
                browser = null, selected = emptySet(), selectionActive = false,
                nameFilter = "", extensionFilter = "",
                navDir = -1
            )
        }
    }


    // ---------------------------------------------------------------- browser sort / views

internal fun AppViewModel.jumpToCrumb(index: Int) {
        val trail = state.browserStack + listOfNotNull(state.browser)
        if (index !in trail.indices) return
        val target = trail[index]
        val tab = when (target.ctx) {
            FileContext.LOCAL -> TabKey.LOCAL
            FileContext.SYNO -> TabKey.SYNO
            FileContext.NET -> TabKey.NET
        }
        state = state.copy(
            browser = target.copy(grid = viewMode(tab) == "grid"),
            browserStack = trail.take(index),
            selected = emptySet(), selectionActive = false,
            // 面包屑跳层同样是换位置，筛选条件不能跨层沿用。
            nameFilter = "", extensionFilter = "",
            // 面包屑往根走 = 返回动画，往深走 = 前进动画。
            navDir = if (index >= trail.size - 1) 1 else -1
        )
    }


    /** 新建文件夹；parentOverride 为空时用当前浏览目录（或本地根）。 */
internal fun AppViewModel.createFolder(name: String, parentOverride: String? = null) {
        val browser = state.browser
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val parent = parentOverride ?: browser?.location?.ifBlank { state.localRoot } ?: state.localRoot
        val ctx = browser?.ctx ?: FileContext.LOCAL
        viewModelScope.launch {
            state = state.copy(loading = true)
            val ok = withContext(Dispatchers.IO) {
                when (ctx) {
                    FileContext.LOCAL -> local.createFolder(parent, trimmed)
                    FileContext.SYNO -> state.session?.let { dsm.createFolder(it, parent, trimmed) } ?: false
                    FileContext.NET -> {
                        val connection = state.connections.firstOrNull { it.id == browser?.netId }
                        if (connection == null) false else network.createFolder(connection, parent, trimmed)
                    }
                }
            }
            state = state.copy(
                loading = false,
                message = if (ok) "已创建文件夹「$trimmed」" else
                    "新建文件夹失败" + if (ctx == FileContext.NET) "：${network.lastError ?: "请检查权限"}" else ""
            )
            if (ok) {
                when (ctx) {
                    FileContext.LOCAL -> {
                        val target = parent.trimEnd('/') + "/" + trimmed
                        applyLocalDelta(
                            addedFolders = listOf(
                                FolderCard(trimmed, "", FileKind.FOLDER, target, LocalRepository.formatStamp(System.currentTimeMillis()), 0)
                            )
                        )
                    }
                    FileContext.SYNO -> { refreshSyno(); reloadBrowserItems() }
                    FileContext.NET -> reloadBrowserItems()
                }
            }
        }
    }


    /** 新建空文件：本地直接创建，群晖/网络走上传通道（空文件）。 */
internal fun AppViewModel.createFile(name: String, parentOverride: String? = null) {
        val browser = state.browser
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val parent = parentOverride ?: browser?.location?.ifBlank { state.localRoot } ?: state.localRoot
        val ctx = browser?.ctx ?: FileContext.LOCAL
        viewModelScope.launch {
            state = state.copy(loading = true)
            when (ctx) {
                FileContext.LOCAL -> {
                    val created = withContext(Dispatchers.IO) { local.createFile(parent, trimmed) }
                    state = state.copy(
                        loading = false,
                        message = if (created != null) "已新建文件「${created.name}」" else "新建文件失败：请检查名称与权限"
                    )
                    if (created != null) {
                        applyLocalDelta(
                            addedFiles = listOf(
                                FileEntry(
                                    id = created.absolutePath,
                                    name = created.name,
                                    kind = FileKinds.of(created.name),
                                    sizeLabel = "0 B",
                                    timeLabel = LocalRepository.formatStamp(created.lastModified()),
                                    folder = localFolderLabel(created.absolutePath),
                                    path = created.absolutePath,
                                    sizeBytes = 0L,
                                    modified = created.lastModified()
                                )
                            )
                        )
                    }
                }
                FileContext.SYNO, FileContext.NET -> {
                    val staged = java.io.File(
                        getApplication<Application>().cacheDir,
                        "newfile-${System.currentTimeMillis()}-$trimmed"
                    )
                    val ok = try {
                        val stagedOk = withContext(Dispatchers.IO) {
                            if (!staged.createNewFile()) return@withContext false
                            // DSM 的上传接口对 0 字节文件可能拒绝，写入一个字节保证载荷非空。
                            staged.writeBytes(byteArrayOf(0x20))
                            true
                        }
                        if (!stagedOk) false else {
                            // 必须显式传 name：upload 默认用本地文件名，
                            // 否则远端会得到 "newfile-<时间戳>-<名字>" 这种临时名。
                            when (ctx) {
                                FileContext.SYNO -> uploadSynoWithRetry(staged, parent, trimmed)
                                else -> state.connections.firstOrNull { it.id == browser?.netId }
                                    ?.let { network.upload(it, staged, parent, trimmed) } ?: false
                            }
                        }
                    } finally {
                        runCatching { staged.delete() }
                    }
                    state = state.copy(
                        loading = false,
                        message = if (ok) "已新建文件「$trimmed」" else
                            "新建文件失败" + when (ctx) {
                                FileContext.NET -> "：${network.lastError ?: "请检查权限"}"
                                FileContext.SYNO -> "：${dsm.lastError ?: "请检查权限"}"
                                else -> ""
                            }
                    )
                    if (ok) {
                        if (ctx == FileContext.SYNO) refreshSyno()
                        reloadBrowserItems()
                    }
                }
            }
        }
    }


    /**
     * 解压压缩包。
     *
     * 先在 IO 线程扫一遍目标目录的同名冲突，再决定直接解还是先弹窗问用户。
     * 冲突扫描独立于解压循环：跑到一半再问，用户犹豫期间已经写进去半个文件，
     * 而且 IO 线程和主线程来回切换只会让取消变得更难处理。
     */
internal fun AppViewModel.extractArchive(file: FileEntry, destinationPath: String? = null) {
        val lowerName = file.name.lowercase()
        val supported = lowerName.endsWith(".zip") || lowerName.endsWith(".7z") ||
            lowerName.endsWith(".tar") || lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz")
        if (file.kind != FileKind.ARCHIVE || !supported) {
            state = state.copy(message = "支持解压 zip / 7z / tar / tar.gz 格式")
            return
        }
        val source = File(file.path)
        if (!source.exists()) {
            state = state.copy(message = "压缩包不存在")
            return
        }
        val destination = destinationPath?.let { File(it) } ?: source.parentFile ?: File(state.localRoot)
        closeFileSheet()
        pendingExtractPassword = null
        state = state.copy(selected = emptySet(), selectionActive = false, extractPassword = null)
        if (lowerName.endsWith(".7z") || lowerName.endsWith(".zip")) {
            // 加密包不解密就读不出条目名，先问密码，再扫同名冲突。
            viewModelScope.launch {
                val needsPassword = withContext(Dispatchers.IO) {
                    local.archiveNeedsPassword(source.absolutePath)
                }
                if (needsPassword) {
                    state = state.copy(
                        extractPassword = ExtractPasswordPrompt(
                            file.name, source.absolutePath, destination.absolutePath
                        )
                    )
                } else {
                    scanExtractConflicts(file.name, source, destination)
                }
            }
            return
        }
        scanExtractConflicts(file.name, source, destination)
    }


    /** 扫同名冲突后决定直接解压还是先弹窗。 */
internal fun AppViewModel.scanExtractConflicts(archiveName: String, source: File, destination: File) {
        viewModelScope.launch {
            val scan = withContext(Dispatchers.IO) {
                runCatching {
                    // 必须在问「同名怎么处理」之前验证口令：加密条目列名字不解密，
                    // 若用户选跳过，正式解压可能一次流都不开，错密码永远不会提示。
                    val pwd = pendingExtractPassword
                    if (pwd != null && (source.name.endsWith(".zip", true) || source.name.endsWith(".7z", true))) {
                        if (!local.verifyArchivePassword(source.absolutePath, pwd)) {
                            throw ArchivePasswordRequired("密码错误，请重新输入")
                        }
                    }
                    local.archiveConflictNames(
                        source.absolutePath, destination, password = pwd
                    )
                }
            }
            if (scan.isFailure) {
                val e = scan.exceptionOrNull()
                pendingExtractPassword = null
                if (e is ArchivePasswordRequired) {
                    // 错误只在密码弹窗内提示；顶部那条会被弹窗窗口挡住，看不见。
                    // 不把 extractPassword 置空——弹窗留在原处，只换 message/revision，
                    // 否则会出现「密码框闪一下又重新弹出」。
                    val prev = state.extractPassword
                    state = state.copy(
                        extractPassword = ExtractPasswordPrompt(
                            archiveName, source.absolutePath, destination.absolutePath,
                            e.message ?: "密码错误，请重新输入",
                            (prev?.revision ?: 0) + 1
                        )
                    )
                } else {
                    // 非密码错误：关掉密码弹窗再给顶部提示，否则同样被挡住。
                    state = state.copy(
                        extractPassword = null,
                        message = "解压失败：${e?.message ?: "未知错误"}"
                    )
                }
                return@launch
            }
            val conflicts = scan.getOrNull().orEmpty()
            if (conflicts.isEmpty()) {
                startExtract(archiveName, source.absolutePath, destination, ExtractConflict.OVERWRITE)
            } else {
                state = state.copy(
                    // 密码已验过，收起密码弹窗再让用户选同名处理方式。
                    extractPassword = null,
                    extractConflict = ExtractConflictPrompt(
                        archiveName = archiveName,
                        sourcePath = source.absolutePath,
                        destinationPath = destination.absolutePath,
                        destinationName = destination.name,
                        conflicts = conflicts
                    )
                )
            }
        }
    }


    /** 用户在冲突弹窗里选定处理方式后继续解压。 */
internal fun AppViewModel.confirmExtractConflict(conflict: ExtractConflict) {
        val prompt = state.extractConflict ?: return
        startExtract(prompt.archiveName, prompt.sourcePath, File(prompt.destinationPath), conflict)
    }


    /** 关掉冲突弹窗，不解压。 */
internal fun AppViewModel.cancelExtractConflict() {
        if (state.extractConflict != null) {
            pendingExtractPassword = null
            state = state.copy(extractConflict = null)
        }
    }


    /** 加密压缩包：确认密码后重新扫冲突并解压。密码不写进 State。 */
internal fun AppViewModel.confirmExtractPassword(password: String) {
        val prompt = state.extractPassword ?: return
        if (password.isEmpty()) {
            state = state.copy(
                extractPassword = prompt.copy(message = "请输入密码", revision = prompt.revision + 1)
            )
            return
        }
        pendingExtractPassword = password.toCharArray()
        // 弹窗保持挂载：先验密码再决定是否收起，错了就留在原弹窗里改，不会闪一下又弹出。
        scanExtractConflicts(prompt.archiveName, File(prompt.sourcePath), File(prompt.destinationPath))
    }


    /** 放弃输入密码。 */
internal fun AppViewModel.cancelExtractPassword() {
        pendingExtractPassword = null
        state = state.copy(extractPassword = null)
    }


    /**
     * 真正开解。
     *
     * `abort` 传的是「这个协程还活着吗」：解压是整段阻塞 IO，`job.cancel()` 没有挂起点
     * 可以生效，取消只能靠每读一块查一次旗标（[copyWithAbort] 内部实现）。
     */
internal fun AppViewModel.startExtract(
        archiveName: String,
        sourcePath: String,
        destination: File,
        conflict: ExtractConflict
    ) {
        state = state.copy(
            selected = emptySet(),
            selectionActive = false,
            extractConflict = null,
            extractPassword = null,
            localTaskLabel = "正在解压 $archiveName…",
            localTaskRunning = true
        )
        localTaskJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val ctx = currentCoroutineContext()
                runCatching {
                    local.extractArchive(
                        sourcePath, destination, { !ctx.isActive }, conflict, pendingExtractPassword,
                        // 密码在冲突扫描阶段已经验过；再验一遍等于把整包条目读两遍，
                        // 几百上千个文件的包光这一遍就要好几秒。
                        passwordVerified = pendingExtractPassword != null
                    )
                }
            }
            result.onSuccess {
                pendingExtractPassword = null
                reloadBrowserItems()
                refreshLocal()
                state = state.copy(localTaskLabel = "", localTaskRunning = false, message = "已解压到「${displayLeaf(destination.absolutePath, state.localRoot)}」")
            }.onFailure {
                pendingExtractPassword = null
                if (it is ArchivePasswordRequired) {
                    // 只放进弹窗；顶部提示会被密码弹窗盖住，不必重复。
                    state = state.copy(
                        localTaskLabel = "", localTaskRunning = false,
                        extractPassword = ExtractPasswordPrompt(
                            archiveName, sourcePath, destination.absolutePath,
                            it.message ?: "密码错误，请重新输入",
                            (state.extractPassword?.revision ?: 0) + 1
                        )
                    )
                } else if (it is kotlinx.coroutines.CancellationException || it is TransferAbortedException) {
                    state = state.copy(localTaskLabel = "", localTaskRunning = false, message = "已取消解压")
                } else {
                    state = state.copy(localTaskLabel = "", localTaskRunning = false, message = "解压失败：${it.message ?: "未知错误"}")
                }
            }
        }
    }


    /** 取消当前压缩/解压任务。 */
internal fun AppViewModel.cancelLocalTask() {
        localTaskJob?.cancel()
        localTaskJob = null
        pendingExtractPassword = null
        state = state.copy(localTaskLabel = "", localTaskRunning = false, message = "已取消操作")
    }


    /**
     * 当前底部任务条能不能取消。
     *
     * 只有压缩/解压挂了自己的 Job 才能中断；批量删除是一次性调用，没有取消语义，
     * 此时任务条不该出现取消按钮——点了也停不下来，只会误报「已取消」。
     */
internal fun AppViewModel.isLocalTaskCancellable(): Boolean = localTaskJob != null


    /** 每个底栏独立排序：传入 tabKey 后只改该 Tab 的排序，不影响其它底栏。 */
internal fun AppViewModel.setSort(key: String, dir: String, tabKey: String = "local") {
        val nextSorts = state.prefs.tabSorts + ("${tabKey}_key" to key) + ("${tabKey}_dir" to dir)
        val next = state.prefs.copy(tabSorts = nextSorts, sortKey = key, sortDir = dir)
        val browser = state.browser
        state = state.copy(
            prefs = next,
            browser = browser?.copy(items = sortFiles(browser.items, key, dir))
        )
        persist()
    }


    /** 当前 Tab 的排序 key / dir：优先取 tabSorts，没有则回退到全局 sortKey/sortDir。 */
internal fun AppViewModel.sortKeyFor(tabKey: String): String = state.prefs.tabSorts["${tabKey}_key"] ?: state.prefs.sortKey

internal fun AppViewModel.sortDirFor(tabKey: String): String = state.prefs.tabSorts["${tabKey}_dir"] ?: state.prefs.sortDir


internal fun AppViewModel.viewMode(tab: TabKey): String = state.prefs.tabViews[tab.name.lowercase()] ?: "grid"


internal fun AppViewModel.viewModeFor(ctx: FileContext): String = viewMode(
        when (ctx) {
            FileContext.LOCAL -> TabKey.LOCAL
            FileContext.SYNO -> TabKey.SYNO
            FileContext.NET -> TabKey.NET
        }
    )


internal fun AppViewModel.toggleViewFor(ctx: FileContext) {
        toggleView(
            when (ctx) {
                FileContext.LOCAL -> TabKey.LOCAL
                FileContext.SYNO -> TabKey.SYNO
                FileContext.NET -> TabKey.NET
            }
        )
    }


internal fun AppViewModel.toggleView(tab: TabKey) {
        val next = if (viewMode(tab) == "grid") "list" else "grid"
        val map = state.prefs.tabViews.toMutableMap()
        map[tab.name.lowercase()] = next
        val browserTab = state.browser?.ctx?.let {
            when (it) {
                FileContext.LOCAL -> TabKey.LOCAL
                FileContext.SYNO -> TabKey.SYNO
                FileContext.NET -> TabKey.NET
            }
        }
        state = state.copy(
            prefs = state.prefs.copy(viewMode = next, tabViews = map),
            browser = if (browserTab == tab) state.browser?.copy(grid = next == "grid") else state.browser
        )
        persist()
    }


internal fun AppViewModel.toggleViewMode() {
        val current = state.browser?.ctx?.let {
            when (it) {
                FileContext.LOCAL -> TabKey.LOCAL
                FileContext.SYNO -> TabKey.SYNO
                FileContext.NET -> TabKey.NET
            }
        } ?: state.tab
        toggleView(current)
    }


internal fun AppViewModel.toggleSectionView(key: String) {
        val tab = when {
            key.startsWith("local") -> TabKey.LOCAL
            key.startsWith("syno") -> TabKey.SYNO
            else -> TabKey.NET
        }
        toggleView(tab)
    }


internal fun AppViewModel.setGrid(grid: Boolean) {
        val tab = state.browser?.ctx?.let {
            when (it) {
                FileContext.LOCAL -> TabKey.LOCAL
                FileContext.SYNO -> TabKey.SYNO
                FileContext.NET -> TabKey.NET
            }
        } ?: state.tab
        setViewMode(tab, if (grid) "grid" else "list")
    }


internal fun AppViewModel.setViewMode(tab: TabKey, mode: String) {
        val map = state.prefs.tabViews.toMutableMap()
        map[tab.name.lowercase()] = mode
        state = state.copy(
            prefs = state.prefs.copy(viewMode = mode, tabViews = map),
            browser = state.browser?.copy(grid = mode == "grid")
        )
        persist()
    }


internal fun AppViewModel.favoriteKey(file: FileEntry): String = if (file.path.isNotBlank()) file.path else file.id


internal fun AppViewModel.isFavorite(file: FileEntry): Boolean {
        val key = favoriteKey(file)
        if (key in state.prefs.favorites) return true
        // 群晖一侧还要认服务器自己的收藏列表：在 DSM 网页或另一台设备收藏过的文件夹，
        // 本机没有记录，只看本地就会当成「未收藏」，再点收藏必然撞 800。
        val synoCtx = state.browser?.ctx == FileContext.SYNO ||
            (state.browser == null && state.tab == TabKey.SYNO)
        return synoCtx && key in state.synoFavoritePaths
    }


internal fun AppViewModel.toggleFavorite(file: FileEntry) {
        val key = favoriteKey(file)
        val nowFav = key !in state.prefs.favorites
        val next = if (nowFav) state.prefs.favorites + key else state.prefs.favorites - key
        state = state.copy(
            prefs = state.prefs.copy(favorites = next),
            message = if (nowFav) "已收藏「${file.name}」" else "已取消收藏「${file.name}」"
        )
        persist()
        viewModelScope.launch { reloadBrowserItems() }
    }


internal fun AppViewModel.favoriteFolderKey(folder: FolderCard): String =
        "folder:${folder.path.ifBlank { folder.name }}"


internal fun AppViewModel.isFavoriteFolder(folder: FolderCard): Boolean =
        favoriteFolderKey(folder) in state.prefs.favorites


internal fun AppViewModel.toggleFavoriteFolder(folder: FolderCard) {
        val key = favoriteFolderKey(folder)
        val nowFav = key !in state.prefs.favorites
        val next = if (nowFav) state.prefs.favorites + key else state.prefs.favorites - key
        state = state.copy(
            prefs = state.prefs.copy(favorites = next),
            message = if (nowFav) "已收藏「${folder.name}」" else "已取消收藏「${folder.name}」"
        )
        persist()
        viewModelScope.launch { reloadBrowserItems() }
    }


internal fun AppViewModel.folderEntry(folder: FolderCard): FileEntry = FileEntry(
        id = "folder:${folder.path.ifBlank { folder.name }}",
        name = folder.name,
        kind = FileKind.FOLDER,
        sizeLabel = "",
        timeLabel = folder.date,
        folder = "",
        childCount = folder.count,
        path = folder.path
    )


internal fun AppViewModel.favoriteFiles(ctx: FileContext = FileContext.LOCAL): List<FileEntry> {
        val keys = state.prefs.favorites
        return when (ctx) {
            // 本地收藏只包含本地文件（清单 5）。
            FileContext.LOCAL -> {
                val folders = state.localFolders
                    .filter { favoriteFolderKey(it) in keys }
                    .map { folderEntry(it) }
                folders + state.localFiles.filter { favoriteKey(it) in keys }
            }
            // 群晖收藏展示已收藏的群晖文件与文件夹（清单 5）：由 refreshSynoFavorites 解析。
            // 解析结果要按当前 favorites 集合再过一遍 —— 取消收藏后 synoFavorites 里
            // 还留着旧条目，不筛就会「取消了还在」（退出重进才重新解析，3.9.17 修）。
            FileContext.SYNO -> {
                val all = keys + state.synoFavoritePaths
                val resolved = state.synoFavorites.filter { favoriteKey(it) in all }
                if (resolved.isNotEmpty()) resolved
                else state.shares.flatMap { it.items }.filter { favoriteKey(it) in all }
            }
            FileContext.NET -> emptyList()
        }
    }


    /**
     * 解析群晖收藏：逐个 key 反查它的父目录拿到 FileEntry，
     * 这样深层路径和文件都能显示（shares.items 只覆盖已加载的浅层项）。
     */
internal fun AppViewModel.refreshSynoFavorites() {
        val session = state.session ?: return
        val root = state.localRoot.trimEnd('/')
        val keys = state.prefs.favorites.filter {
            !it.startsWith("folder:") && !it.startsWith("net:") && !it.startsWith(root)
        }
        viewModelScope.launch {
            // 先拿服务器上的真实收藏：本机记录之外的收藏也能列出来，
            // 同时让「已收藏」的判断与服务端一致（不再出现点收藏回 800 的情况）。
            val serverPaths = withContext(Dispatchers.IO) { dsm.listFavoritePaths(session) }
            // 本机收藏与服务器收藏会有重叠（本应用加的文件夹两边都记着），
            // 列表直接相加会让同一文件夹出现两次、还多一次 dsm.list 请求。
            val all = (keys.toSet() + (serverPaths ?: state.synoFavoritePaths)).toList()
            val resolved = withContext(Dispatchers.IO) {
                all.mapNotNull { path ->
                    val parent = path.substringBeforeLast('/', "")
                    val name = path.substringAfterLast('/')
                    if (parent.isBlank() || name.isBlank()) return@mapNotNull null
                    runCatching { dsm.list(session, parent) }.getOrDefault(emptyList())
                        .firstOrNull { it.name == name }
                }.distinctBy { favoriteKey(it) }
                // distinctBy 兜底：收藏列表页用 LazyColumn(key = id)，同一条目出现两次会
                // 抛「Key was already used」直接闪退。本机与服务器收藏重叠时尤其容易触发。
            }
            // 本机记着、服务器上没有的文件夹收藏，补传上去。
            //
            // 官方收藏只收文件夹（文件只能留在本机），所以这里只补文件夹；
            // 不做这一步的话，早先版本加过的收藏永远只存在这台设备上，
            // 另一台平板登录同一个账号自然看不到。补传是幂等的：服务器已经有了会回 800，
            // setFavorite 内部按「已收藏」处理，不会报错。
            val server = serverPaths ?: state.synoFavoritePaths
            val missing = resolved
                .filter { it.isFolder && it.path.isNotBlank() }
                .filter { it.path.trimEnd('/') !in server }
                .map { it.path.trimEnd('/') }
                .take(40)
            val pushed = if (serverPaths != null && missing.isNotEmpty()) {
                val ok = withContext(Dispatchers.IO) { dsm.setFavorite(session, missing, true) }
                if (ok) missing else emptyList()
            } else emptyList()

            state = state.copy(
                synoFavorites = resolved,
                synoFavoritePaths = server + pushed,
                // 收藏页是打开时的快照：解析完成后同步刷新，否则新收藏看不到。
                browser = if (state.browser?.favorites == true) {
                    state.browser?.copy(items = resolved)
                } else state.browser
            )
        }
    }


internal fun AppViewModel.openFavorites(ctx: FileContext = FileContext.LOCAL) {
        if (ctx == FileContext.SYNO) refreshSynoFavorites()
        val files = favoriteFiles(ctx)
        val path = when (ctx) {
            FileContext.LOCAL -> state.localRoot
            FileContext.SYNO -> "收藏"
            FileContext.NET -> "收藏"
        }
        // 从设置等二级页面进入收藏时保留来源页，返回仍回到原处。
        val origin = state.screen
        state = state.copy(
            screen = Screen.MAIN,
            screenStack = if (origin == Screen.MAIN) state.screenStack else state.screenStack + origin
        )
        pushBrowser(
            BrowserState(
                title = "收藏",
                path = path,
                ctx = ctx,
                items = files,
                grid = state.prefs.tabViews[ctx.name.lowercase()] == "grid",
                favorites = true
            )
        )
    }


    /** 当前浏览器上下文对应的排序 tabKey。 */
internal fun AppViewModel.currentSortTab(): String = when (state.browser?.ctx) {
        FileContext.SYNO -> "syno"
        FileContext.NET -> "net"
        else -> "local"
    }


/**
 * 目录排序。
 *
 * 这里刻意不用 `compareBy { it.name.lowercase() }`：那是在**比较器里**做 lowercase，
 * 一次排序要跑 O(n log n) 次比较，等于几万次字符串分配。1752 张图片的分类页在
 * 主线程上算一次要 30～40ms，正是「点进图片分类，画面先顿一下再滑进来」的来源。
 * 现在先把 key 算成与 items 等长的数组（O(n) 次分配），比较只比数组下标。
 *
 * 文件夹恒在文件前面，`dir` 只翻转同一类内部的值顺序（保持原有口径）。
 */
internal fun AppViewModel.sortFiles(items: List<FileEntry>, key: String = sortKeyFor(currentSortTab()), dir: String = sortDirFor(currentSortTab())): List<FileEntry> {
        if (items.size < 2) return items
        val asc = dir == "asc"
        val names = if (key == "name") items.map { it.name.lowercase() } else null
        val order = items.indices.sortedWith { a, b ->
            val folderA = items[a].isFolder
            val folderB = items[b].isFolder
            if (folderA != folderB) return@sortedWith if (folderA) -1 else 1
            val cmp = when (key) {
                "name" -> names!![a].compareTo(names[b])
                "size" -> items[a].sizeBytes.compareTo(items[b].sizeBytes)
                else -> items[a].modified.compareTo(items[b].modified)
            }
            if (asc) cmp else -cmp
        }
        return order.map { items[it] }
    }


    /**
     * 首页文件夹卡按当前 Tab 的排序偏好排。
     *
     * 与 [sortFiles] 同一套做法：名称排序先算一遍小写数组，比较时不再逐次 `lowercase()`。
     * 根目录有几百个文件夹时，这一处也是主线程上实打实的开销。
     */
internal fun AppViewModel.sortedFolders(items: List<FolderCard>, tabKey: String = currentSortTab()): List<FolderCard> {
        if (items.size < 2) return items
        val key = sortKeyFor(tabKey)
        val asc = sortDirFor(tabKey) == "asc"
        val names = if (key == "name") items.map { it.name.lowercase() } else null
        val order = items.indices.sortedWith { a, b ->
            val cmp = when (key) {
                "name" -> names!![a].compareTo(names[b])
                "size" -> items[a].count.compareTo(items[b].count)
                else -> items[a].date.compareTo(items[b].date)
            }
            if (asc) cmp else -cmp
        }
        return order.map { items[it] }
    }


    /** 网络连接卡按名称排序（大小 / 时间无意义时退化为名称）。 */
internal fun AppViewModel.sortedConnections(items: List<NetworkConnection>, tabKey: String = "net"): List<NetworkConnection> {
        val comparator: Comparator<NetworkConnection> = when (sortKeyFor(tabKey)) {
            "size" -> compareBy { it.host.lowercase() }
            "time" -> compareBy { it.protocol }
            else -> compareBy { it.name.lowercase() }
        }
        return if (sortDirFor(tabKey) == "asc") items.sortedWith(comparator) else items.sortedWith(comparator.reversed())
    }
