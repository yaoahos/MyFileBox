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


    /**
     * 一次性全量刷新：本地快照 + 群晖共享/卷 + 网络连接测连。
     *
     * **不再作为下拉刷新的入口**（各页走 [refreshLocalTab] / [refreshSynoTab] /
     * [refreshNetTab] / [refreshBrowser]）。旧版所有页面共用这一个函数，一次下拉要
     * 串行做完四件事——全盘扫描、拉共享、拉卷、逐个连接完整测连——任何一项慢都算进
     * 转圈时间，中继冷启动或某个连接不可达时就是 8-9 秒，用户以为「刷新这么慢」。
     *
     * 这里保留给「需要整套数据一起对齐」的内部场景，三段并行，转圈等最慢的一段。
     */
internal fun AppViewModel.refresh() {
        detectNetworkSwitchNow()
        val token = beginPull()
        viewModelScope.launch {
            try {
                kotlinx.coroutines.coroutineScope {
                    launch { pullLocalSnapshot() }
                    launch { pullSynoSummary() }
                    launch { pullNetStatus() }
                }
            } finally {
                // 拉取过程中要是已经写了失败原因，就别用「已刷新」把它盖掉。
                endPull(token, state.message ?: "已刷新")
            }
        }
    }


    /** 下拉刷新打点：日志里能看到「转圈多久」，排查「刷新慢」不用靠手感描述。 */
internal fun AppViewModel.logPull(page: String, started: Long) {
        android.util.Log.i("MFB-REFRESH", "$page ${System.currentTimeMillis() - started}ms")
    }


    /**
     * 开始一轮下拉刷新，返回这一轮的令牌。
     *
     * 这里刻意**不做**「已经在刷就忽略」：用户又拉一下，正说明他判断的是「没反应」，
     * 此刻最不该做的就是什么都不做。重复请求由 [loadFolder] 内部先 cancel 旧 job 收口。
     */
internal fun AppViewModel.beginPull(): Int {
        refreshToken++
        val token = refreshToken
        refreshTimeoutJob?.cancel()
        pullStartedAt = System.currentTimeMillis()
        state = state.copy(refreshing = true, message = null)
        refreshTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(pullWatchdogMs)
            if (refreshToken != token) return@launch
            android.util.Log.w("MFB-REFRESH", "watchdog timeout")
            if (state.refreshing || state.repairingChannel) {
                state = state.copy(refreshing = false, repairingChannel = false)
            }
        }
        return token
    }


    /**
     * 收掉某一轮的转圈。令牌不是最新一轮就什么都不做——绝不替别人收圈。
     *
     * 旧实现里 `refreshLocal` 会在「扫描已在进行」时直接把 `refreshing` 置 false，
     * 那正是「图标转一半突然没了」的来源。
     */
internal fun AppViewModel.endPull(token: Int, message: String? = null) {
        if (refreshToken != token) return
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        fun commit() {
            // 顺手把「正在重建通道」也收掉：能走到收圈，说明这一轮请求已经结束，
            // 那条提示再挂着就变成假状态（群晖首页下拉时由 pullSynoSummary 挂的那条
            // 也存在这里统一兜底）。
            state = if (message != null) {
                state.copy(refreshing = false, repairingChannel = false, message = message)
            } else {
                state.copy(refreshing = false, repairingChannel = false)
            }
        }
        // 撑满最短时长再落下：原因见 minPullVisibleMs。
        // 正常路径（网络/群晖目录、慢连接）本来就超过 450ms，这里直接收；
        // 只有本地目录这种几十毫秒就结束的，才会在这里补一点时间。
        val remain = minPullVisibleMs - (System.currentTimeMillis() - pullStartedAt)
        if (remain <= 0L) {
            commit()
            return
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(remain)
            // 这中间用户又拉了一下：当前轮已经换成新的了，收圈归新的一轮，别替它收。
            if (refreshToken != token) return@launch
            commit()
        }
    }


    /**
     * 本地页下拉：先刷「根目录这一屏」，全量扫描交给后台。
     *
     * 首页可见的只有根目录一层文件、文件夹卡片和容量三项，这些都来自 [local.quickSnapshot]
     * （只 readdir 一层，毫秒级）；分类计数与深层文件列表需要全盘 stat，动辄两三秒。
     * 让转圈陪着全盘扫描没有意义——用户拉一下等三秒，看到的还是同一屏。
     * 所以先收圈，再让 [refreshLocal] 在后台扫完静默补齐分类数字。
     */
internal fun AppViewModel.refreshLocalTab() {
        val token = beginPull()
        val started = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                val quick = withContext(Dispatchers.IO) { local.quickSnapshot() }
                // 只替换根目录那一层：深层条目保留旧值（分类页可能正开着），
                // quick 里没有分类计数，也不能拿空 map 覆盖已有数字。
                val rootPath = quick.rootPath.trimEnd('/')
                fun rootOf(path: String) = path.trimEnd('/').substringBeforeLast('/')
                val deeper = state.localFiles.filterNot { rootOf(it.path) == rootPath }
                state = state.copy(
                    localFiles = deeper + quick.files,
                    localFolders = quick.folders,
                    localUsedGb = quick.usedGb,
                    localFreeGb = quick.freeGb,
                    localTotalGb = quick.totalGb,
                    localAccess = quick.hasAccess,
                    localRoot = quick.rootPath
                )
            } finally {
                // 提示和收圈一次写完：先置文案、下一行才收圈的话，中间那一帧就是
                // 「已刷新」和还在转的图标同屏，看起来正是「刷新完卡住」。
                endPull(token, "已刷新")
                logPull("local-home", started)
            }
        }
        // 后台补齐：全盘扫描完成后会整体替换列表与分类计数。
        refreshLocal()
    }


    /**
     * 群晖首页下拉：只拉共享列表与卷信息，并行发出。
     *
     * 旧版这两步串在本地全盘扫描之后，切网后还要先修通道，用户拉一下就得等近十秒。
     */
internal fun AppViewModel.refreshSynoTab() {
        val token = beginPull()
        val started = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                pullSynoSummary()
            } finally {
                // 修通道失败 / 会话失效时 pullSynoSummary 已经写了原因，保留它。
                endPull(token, state.message ?: "已刷新")
                logPull("syno-home", started)
            }
        }
    }


    /** 网络文件首页下拉：只探活已有连接，并行探测，最慢一个连接决定转圈时长。 */
internal fun AppViewModel.refreshNetTab() {
        if (state.connections.isEmpty()) {
            state = state.copy(message = "还没有网络位置")
            return
        }
        val token = beginPull()
        val started = System.currentTimeMillis()
        // 探测必须挂在 viewModelScope 上，不能挂在下面这个「最多等 1.2 秒」的协程里：
        // 子协程随父结束被取消，探测会被腰斩，卡片状态永远停在旧值——
        // 用户看到的就是「下拉了，但离线还是离线」。
        netProbeJob?.cancel()
        val probe = viewModelScope.launch { pullNetStatus() }
        netProbeJob = probe
        viewModelScope.launch {
            // 转圈最多等 1.2 秒：不可达连接要等满 4 秒连接超时，那一个连接不该把
            // 整页按住不放。等不到就先收圈，探测结果回来时卡片自己会变。
            try {
                kotlinx.coroutines.withTimeoutOrNull(1_200L) { probe.join() }
            } finally {
                endPull(token, "已刷新")
                logPull("net-home", started)
            }
        }
    }


    /**
     * 浏览页下拉：重读当前目录。
     *
     * 停在目录里下拉，用户要的是「这个目录有没有新文件」，不是重扫整个存储：
     * 本地目录只需列一层，群晖 / 网络只发一次目录请求。旧实现统一走 [refresh]，
     * 于是站在任意目录下拉都要连带全盘扫描与所有连接测连。
     */
internal fun AppViewModel.refreshBrowser() {
        val browser = state.browser ?: return refresh()
        detectNetworkSwitchNow()
        // 分类页的内容就是从全量快照算出来的：这里必须等扫描，不能只刷根目录一层。
        if (browser.localCategory != null) {
            refreshLocal(beginPull())
            return
        }
        when (browser.ctx) {
            FileContext.LOCAL -> {
                val token = beginPull()
                val started = System.currentTimeMillis()
                viewModelScope.launch {
                    // 结果文案统一在 finally 里跟收圈一起提交（见 refreshLocalTab 的说明）。
                    var outcome: String? = "已刷新"
                    try {
                        val location = browser.location.ifBlank { state.localRoot }
                        val result = withContext(Dispatchers.IO) { runCatching { local.list(location) } }
                        val failure = result.exceptionOrNull()
                        // 被取消（切页 / 又拉了一次）不算刷新成功，别弹「已刷新」。
                        if (failure is kotlinx.coroutines.CancellationException) {
                            outcome = null
                            return@launch
                        }
                        if (failure != null) {
                            // 读失败不能再用 getOrDefault(空表)：那会把整页刷成「空文件夹」，
                            // 用户以为文件全没了，比「没反应」严重得多。
                            outcome = "刷新失败：${friendlyNetError(failure, "无法读取该目录")}"
                            return@launch
                        }
                        val items = result.getOrDefault(emptyList())
                        // 期间用户可能已经切走目录：只回写同一位置的列表。
                        if (state.browser?.location == browser.location) {
                            state = state.copy(browser = state.browser?.copy(items = sortFiles(items)))
                        }
                    } finally {
                        endPull(token, outcome)
                        logPull("local-dir", started)
                    }
                }
            }
            FileContext.SYNO -> {
                if (browser.location.isBlank()) return refreshSynoTab()
                val token = beginPull()
                loadFolder(browser.location, successMessage = "已刷新")
                // loadFolder 自己管 loading 与失败提示，读回 / 失败 / 取消都会结束它，
                // 转圈跟着那次请求一起收。
                watchBrowserLoadJob(token, "syno-dir")
            }
            FileContext.NET -> {
                val connection = state.connections.firstOrNull { it.id == browser.netId }
                    ?: return refreshNetTab()
                if (browser.location.isBlank()) return refreshNetTab()
                val token = beginPull()
                loadNetworkFolder(connection, browser.location, successMessage = "已刷新")
                watchBrowserLoadJob(token, "net-dir")
            }
        }
    }


    /** 让页面的下拉转圈跟随当前目录请求：请求一结束就收圈，不额外等一个固定时长。 */
internal fun AppViewModel.watchBrowserLoadJob(token: Int, page: String) {
        val started = System.currentTimeMillis()
        val job = browserLoadJob
        if (job == null) {
            // 没有发出请求（例如位置为空被提前拦下）：立刻收圈，不留一个空转的图标。
            endPull(token)
            logPull(page, started)
            return
        }
        job.invokeOnCompletion {
            // 完成回调可能在任意线程：改 state 必须回主线程。
            viewModelScope.launch {
                endPull(token)
                logPull(page, started)
            }
        }
    }


    /** 本地全量快照 → 写回首页各模块用的数据。 */
internal suspend fun AppViewModel.pullLocalSnapshot() {
        val snapshot = withContext(Dispatchers.IO) { local.snapshot() }
        state = state.copy(
            localFiles = snapshot.files,
            photos = snapshot.photos,
            localFolders = snapshot.folders,
            categoryCounts = snapshot.counts,
            localUsedGb = snapshot.usedGb,
            localFreeGb = snapshot.freeGb,
            localTotalGb = snapshot.totalGb,
            localAccess = snapshot.hasAccess,
            localRoot = snapshot.rootPath
        )
    }


    /** 群晖共享 + 卷：两个请求互不依赖，并行发，转圈等慢的那个。 */
internal suspend fun AppViewModel.pullSynoSummary() {
        // 同浏览页：要重建通道就先说明，别让用户只看一个转圈。
        val rebuilding = willRepairChannel()
        if (rebuilding) state = state.copy(repairingChannel = true)
        if (!repairChannelIfNeeded()) {
            state = state.copy(
                repairingChannel = false,
                message = synoUnreachableMessage(),
                connectHint = true
            )
            return
        }
        if (rebuilding) state = state.copy(repairingChannel = false)
        val session = state.session
        if (session == null || session.sid.isBlank()) {
            state = state.copy(message = "群晖登录状态已失效，请重新登录")
            return
        }
        kotlinx.coroutines.coroutineScope {
            val sharesJob = async { runCatching { dsm.shares(session) } }
            val volumesJob = async { runCatching { dsm.volumes(session) } }
            val shares = sharesJob.await().getOrNull()
            val volumes = volumesJob.await().getOrNull()
            // 一次失败不清空已有列表：留在界面上的旧数据比空白有用。
            state = state.copy(
                shares = shares ?: state.shares,
                volumes = volumes ?: state.volumes,
                connectHint = false
            )
        }
    }


    /** 网络连接探活：并行测连，失败的连接不降级已在线的状态。 */
internal suspend fun AppViewModel.pullNetStatus() {
        val connections = state.connections
        if (connections.isEmpty()) return
        val tested = withContext(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                connections.map { connection ->
                    async {
                        val started = System.currentTimeMillis()
                        val ok = runCatching { network.test(connection) }.getOrDefault(false)
                        Triple(connection, ok, (System.currentTimeMillis() - started).toInt())
                    }
                }.awaitAll()
            }
        }
        state = state.copy(connections = tested.map { (conn, ok, cost) ->
            // 偶发超时不代表真断了：失败的连接保持原状态，避免「明明能打开却显示离线」。
            if (ok) conn.copy(online = true, latency = cost) else conn
        })
    }


    // ---------------------------------------------------------------- 更新检查

    /** 启动时是否自动检查更新（关于页可关）。 */
internal fun AppViewModel.updateCheckOnStartup(): Boolean = prefsStore.getBoolean("update_check_startup", true)


internal fun AppViewModel.setUpdateCheckStartup(enabled: Boolean) {
        prefsStore.edit().putBoolean("update_check_startup", enabled).apply()
        state = state.copy(message = if (enabled) "已开启：每次冷启动检查一次更新" else "已关闭：启动时不再检查更新")
    }


    /** 手动检查：有新版弹 updateInfo；没有/失败都给明确提示。自动检查静默。 */
internal fun AppViewModel.checkForUpdate(manual: Boolean) {
        viewModelScope.launch {
            if (manual) state = state.copy(loading = true)
            val result = runCatching {
                withContext(Dispatchers.IO) { com.yaoahos.myfilebox.android.update.GitHubUpdater.fetchLatest() }
            }
            if (manual) state = state.copy(loading = false)
            result.onSuccess { info ->
                val current = com.yaoahos.myfilebox.android.BuildConfig.VERSION_NAME
                if (com.yaoahos.myfilebox.android.update.GitHubUpdater.isNewer(info.version, current)) {
                    state = state.copy(
                        updateInfo = info, updateError = null,
                        message = if (manual) "发现新版本 ${info.version}" else null
                    )
                } else if (manual) {
                    state = state.copy(message = "当前已是最新版本 $current")
                }
                prefsStore.edit().putLong("update_last_check", System.currentTimeMillis()).apply()
            }.onFailure { e ->
                if (manual) {
                    val msg = (e as? com.yaoahos.myfilebox.android.update.UpdateException)?.message
                        ?: "检查更新失败：${e.message ?: "网络异常"}"
                    state = state.copy(message = msg)
                }
            }
        }
    }


    /** 冷启动自动检查：每次冷启动检查一次（清单 6，3.2.0 去掉 24h 限流），且遵守开关。 */
internal fun AppViewModel.maybeAutoCheckUpdate() {
        if (!updateCheckOnStartup()) return
        checkForUpdate(manual = false)
    }


internal fun AppViewModel.dismissUpdateDialog() {
    state = state.copy(updateInfo = null, updateError = null)
}


    /** 下载安装包到 cache/updates/ 后走应用既有的 APK 安装流。 */
internal fun AppViewModel.downloadUpdate() {
        val info = state.updateInfo ?: return
        if (state.updateDownloading) return
        val app = getApplication<Application>()
        if (info.apkUrl.isBlank()) {
            state = state.copy(updateError = "该版本没有安装包，请到发布页下载")
            return
        }
        viewModelScope.launch {
            state = state.copy(updateDownloading = true, updateProgress = 0, updateError = null)
            val target = java.io.File(app.cacheDir, "updates/MyFileBox-${info.version}.apk")
            // 通知栏进度与对话框进度同源：对话框可以随时关掉，关掉后也要能看到下载还在跑。
            com.yaoahos.myfilebox.android.update.UpdateNotifications.ensureChannel(app)
            com.yaoahos.myfilebox.android.update.UpdateNotifications.progress(app, -1)
            var lastNotified = -2
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    com.yaoahos.myfilebox.android.update.GitHubUpdater.download(info.apkUrl, target) { percent ->
                        // 下载回调在 IO 线程；state 只能在主线程写，否则会和 Compose 读取竞争。
                        viewModelScope.launch { state = state.copy(updateProgress = percent) }
                        if (percent != lastNotified) {
                            lastNotified = percent
                            com.yaoahos.myfilebox.android.update.UpdateNotifications.progress(app, percent)
                        }
                    }
                }
            }
            state = state.copy(updateDownloading = false)
            result.onSuccess {
                com.yaoahos.myfilebox.android.update.UpdateNotifications.completed(app, info, target.absolutePath)
                state = state.copy(updateInfo = null, message = "下载完成，正在打开安装…")
                requestOpen(
                    FileEntry(
                        id = target.absolutePath,
                        name = target.name,
                        kind = FileKind.APK,
                        path = target.absolutePath,
                        sizeLabel = LocalRepository.formatSize(target.length()),
                        timeLabel = ""
                    )
                )
            }.onFailure { e ->
                com.yaoahos.myfilebox.android.update.UpdateNotifications.clear(app)
                runCatching { target.delete() }
                val msg = (e as? com.yaoahos.myfilebox.android.update.UpdateException)?.message
                    ?: "下载失败：${e.message ?: "网络异常"}"
                // 弹窗被关掉后只剩通知栏，失败原因必须改用顶部提示，否则用户看不到。
                state = if (state.updateInfo == null) {
                    state.copy(message = msg)
                } else {
                    state.copy(updateError = msg)
                }
            }
        }
    }


    /**
     * 点通知栏「已下载」进入：校验缓存路径后直接拉起安装器。
     * 没有「安装未知应用」权限时复用应用内引导，从系统页回来自动续装。
     */
internal fun AppViewModel.installUpdateApk(rawPath: String) {
        val app = getApplication<Application>()
        val file = com.yaoahos.myfilebox.android.update.UpdateNotifications
            .resolveUpdateApk(app.cacheDir, rawPath)
        if (file == null) {
            state = state.copy(message = "安装包已失效，请在应用内重新下载")
            return
        }
        val entry = FileEntry(
            id = file.absolutePath,
            name = file.name,
            kind = FileKind.APK,
            path = file.absolutePath,
            sizeLabel = LocalRepository.formatSize(file.length()),
            timeLabel = ""
        )
        if (!canInstallPackages()) {
            state = state.copy(installFile = entry)
            return
        }
        val error = com.yaoahos.myfilebox.android.update.ApkInstaller.install(app, file).exceptionOrNull()
        if (error != null) state = state.copy(message = error.message ?: "无法启动安装器，请手动安装")
    }


    /** 更新日志：优先内置 assets/updateLog.md，联网失败也有的看。 */
internal fun AppViewModel.loadUpdateLog() {
        val local = runCatching {
            com.yaoahos.myfilebox.android.update.UpdateLog.load(getApplication())
        }.getOrDefault(emptyList())
        state = state.copy(
            updateLogNotes = com.yaoahos.myfilebox.android.update.UpdateLog.merge(local, emptyList()),
            updateLogOpen = true,
            updateLogLoading = true
        )
        viewModelScope.launch {
            val remote = runCatching {
                withContext(Dispatchers.IO) {
                    com.yaoahos.myfilebox.android.update.GitHubUpdater.fetchReleases()
                }
            }.getOrNull()
            // 本地与远端合并后按版本号降序，最多 5 个版本（更早的走「查看更早的版本」）。
            state = state.copy(
                updateLogNotes = com.yaoahos.myfilebox.android.update.UpdateLog.merge(local, remote.orEmpty()),
                updateLogLoading = false
            )
        }
    }


    // ------------------------------------------------------------ 云端设备配置

    /** 本机在云端的配置名。 */
internal fun AppViewModel.deviceName(): String = DeviceProfile.name(prefsStore)


    /**
     * 拉取云端各设备的配置列表。
     *
     * 打开「从云端恢复」时调用。失败原因原样留给界面显示——目录不存在、
     * 认证失败、网络不通在用户看来都是「没反应」，必须说清是哪一种。
     */
internal fun AppViewModel.refreshCloudFiles() {
        val settings = state.sync
        if (!settings.configured) {
            state = state.copy(cloudFiles = emptyList(), cloudFilesError = "请先填写 WebDAV 地址")
            return
        }
        if (state.cloudFilesLoading) return
        state = state.copy(cloudFilesLoading = true, cloudFilesError = null)
        viewModelScope.launch {
            val result = runCatching { ConfigCloudSync.list(prefsStore, settings) }
            state = state.copy(
                cloudFilesLoading = false,
                cloudFiles = result.getOrDefault(emptyList()),
                cloudFilesError = result.exceptionOrNull()?.let { it.message ?: "无法读取云端目录" }
            )
        }
    }


    /** 改本机在云端的配置名；下一次上传就写新文件名，旧文件由上传流程顺手清掉。 */
internal fun AppViewModel.setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        DeviceProfile.setName(prefsStore, trimmed)
        state = state.copy(
            sync = state.sync.copy(deviceName = trimmed),
            message = "配置名已改为「$trimmed」，下次上传生效"
        )
    }


    /**
     * 从用户选中的云端配置恢复（默认是本机那份，也可选别的设备）。
     *
     * 与「恢复本机那份」共用同一条解密 / 覆盖路径，区别只是文件名由用户指定；
     * 密码用界面输入的，不拿本机存的那份去试——用户可能就是想用另一台设备的密码。
     */
internal fun AppViewModel.syncRestoreFrom(
    fileName: String,
    cipher: String,
    onResult: (Boolean, String) -> Unit = { _, _ -> }
) {
        if (state.backupBusy) return
        val settings = state.sync
        if (!settings.configured) {
            state = state.copy(message = "请先填写 WebDAV 地址")
            return
        }
        if (cipher.length < BackupCrypto.MIN_PASSWORD_LENGTH) {
            state = state.copy(message = "配置密码至少 ${BackupCrypto.MIN_PASSWORD_LENGTH} 位")
            return
        }
        state = state.copy(backupBusy = true)
        viewModelScope.launch {
            val key = cipher.toCharArray()
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = ConfigCloudSync.fetch(settings, fileName)
                    val (info, data) = ConfigBackup.inspect(bytes, key)
                    val applied = ConfigBackup.apply(prefsStore, data)
                    info to applied
                }
            }.onSuccess { (info, applied) ->
                applyRestoredPrefs()
                val when_ = if (info.exportedAt > 0) "（备份于 ${formatSyncTime(info.exportedAt)}）" else ""
                state = state.copy(backupBusy = false, message = "已恢复 ${applied.size} 类配置$when_")
                onResult(true, "")
            }.onFailure { e ->
                // 弹窗还开着：错误只回给弹窗自己显示，写进 state.message 会被弹窗窗口挡住。
                val err = backupMessage(e)
                state = state.copy(backupBusy = false)
                onResult(false, err)
            }
            key.fill('\u0000')
        }
    }


    /**
     * 上传本机配置到云端（同步页的「立即上传」）。
     *
     * 四种失败在用户看来都是「点了没反应」，所以逐段校验、逐段给原因：
     * 没填地址 / 没设配置密码 / WebDAV 拒绝 / 网络不通。
     * 上传成功只写自己那份文件，别人的设备配置不受影响。
     */
internal fun AppViewModel.uploadConfigNow() {
        if (state.syncBusy || state.backupBusy) return
        val settings = state.sync
        if (!settings.configured) {
            state = state.copy(message = "请先在云同步里填写 WebDAV 地址")
            return
        }
        val cipher = syncStore.cipher()
        if (cipher.size < BackupCrypto.MIN_PASSWORD_LENGTH) {
            cipher.fill('\u0000')
            state = state.copy(
                message = "请先在云同步设置里填写配置密码（至少 ${BackupCrypto.MIN_PASSWORD_LENGTH} 位）"
            )
            return
        }
        val version = runCatching {
            val app = getApplication<android.app.Application>()
            app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        state = state.copy(syncBusy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ConfigCloudSync.push(prefsStore, settings, cipher, version)
            }
            cipher.fill('\u0000')
            when (result) {
                is ConfigCloudSync.Push.Ok -> {
                    syncStore.markResult(result.at, "已上传")
                    state = state.copy(
                        syncBusy = false,
                        sync = syncStore.load(),
                        message = "已上传本机配置（${DeviceProfile.fileName(prefsStore)}）"
                    )
                }
                is ConfigCloudSync.Push.Fail -> {
                    syncStore.markResult(System.currentTimeMillis(), result.message)
                    state = state.copy(
                        syncBusy = false,
                        sync = syncStore.load(),
                        message = result.message
                    )
                }
            }
        }
    }


internal fun AppViewModel.closeUpdateLog() { state = state.copy(updateLogOpen = false) }


    // ---------------------------------------------------------------- 备份与同步

internal fun AppViewModel.openBackupSync() { openScreen(Screen.BACKUP_SYNC) }


    /**
     * 进页面 / 回前台时重读一次同步状态。
     *
     * 后台定时任务会直接写 `sync_last_at`，界面若不重读，用户看到的永远是上次进页面时的旧时间。
     */
internal fun AppViewModel.refreshSyncState() { state = state.copy(sync = syncStore.load()) }


    /** 本地备份的落地目录：优先用户在设置里指定的默认下载位置。 */
internal fun AppViewModel.backupTargetDir(): File {
        val configured = state.prefs.downloadFolder
        if (configured.isNotBlank()) {
            val dir = File(configured)
            if (dir.isDirectory || dir.mkdirs()) return dir
        }
        return local.downloadsDir()
    }


    /** 备份到本机（默认下载位置）。密码只参与派生密钥，不会以任何形式写进文件。 */
internal fun AppViewModel.backupToLocal(password: String) {
        if (state.backupBusy) return
        if (password.length < BackupCrypto.MIN_PASSWORD_LENGTH) {
            state = state.copy(message = "配置密码至少 ${BackupCrypto.MIN_PASSWORD_LENGTH} 位")
            return
        }
        state = state.copy(backupBusy = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ConfigBackup.export(
                        prefsStore, password.toCharArray(), BuildConfig.VERSION_NAME
                    )
                    val file = File(backupTargetDir(), ConfigBackup.suggestedFileName())
                    file.writeBytes(bytes)
                    file
                }
            }
            state = state.copy(backupBusy = false)
            outcome.onSuccess { file ->
                state = state.copy(message = "已备份到 ${file.absolutePath}")
            }.onFailure { e ->
                state = state.copy(message = "备份失败：${backupMessage(e)}")
            }
        }
    }


    /**
     * 从备份文件恢复。
     *
     * 失败时区分三种情况：密码不对、文件损坏、不是备份文件——用户据此知道是重输
     * 密码还是换个文件，而不是对着同一句「导入失败」反复重试。
     */
internal fun AppViewModel.restoreFromLocal(
    bytes: ByteArray,
    password: String,
    onResult: (Boolean, String) -> Unit = { _, _ -> }
) {
        if (state.backupBusy) return
        state = state.copy(backupBusy = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val (info, data) = ConfigBackup.inspect(bytes, password.toCharArray())
                    val applied = ConfigBackup.apply(prefsStore, data)
                    info to applied
                }
            }
            state = state.copy(backupBusy = false)
            outcome.onSuccess { (info, applied) ->
                applyRestoredPrefs()
                val when_ = if (info.exportedAt > 0) "（备份于 ${formatSyncTime(info.exportedAt)}）" else ""
                state = state.copy(message = "已恢复 ${applied.size} 类配置$when_")
                onResult(true, "")
            }.onFailure { e ->
                // 错误只在弹窗内显示；顶部提示会被 AlertDialog 的独立窗口盖住。
                val err = backupMessage(e)
                state = state.copy(backupBusy = false)
                onResult(false, err)
            }
        }
    }


    /** 密码错误这类错误必须说得明白，用户才知道下一步该做什么。 */
internal fun AppViewModel.backupMessage(e: Throwable): String = when {
        e is BackupCryptoException && e.kind == BackupError.WRONG_PASSWORD -> "配置密码不正确"
        e is BackupCryptoException && e.kind == BackupError.CORRUPTED -> "备份文件已损坏或被篡改"
        e is BackupCryptoException -> e.message ?: "备份文件无法读取"
        else -> e.message ?: "操作失败"
    }


    /**
     * 导入后把内存状态接回新配置。
     *
     * 只重读偏好与网络位置，不动群晖会话：备份里刻意不含 SID（换设备本就失效），
     * 强行塞回一个空会话只会让群晖页显示成「已登录但读不到目录」。
     */
internal suspend fun AppViewModel.applyRestoredPrefs() {
        // 读配置要解一次 Keystore（保存的连接凭据），是 IPC；放在主线程会在
        // 「恢复成功」那一下明显卡住。这里只把读取挪到 IO，赋值仍回主线程做。
        val (prefs, connections) = withContext(Dispatchers.IO) {
            loadPrefs() to loadConnections()
        }
        val visible = prefs.tabOrder.filter {
            when (it) {
                TabKey.LOCAL -> prefs.showLocal
                TabKey.SYNO -> prefs.showSyno
                TabKey.NET -> prefs.showNet
            }
        }
        val tab = when {
            state.tab in visible -> state.tab
            prefs.defaultTab in visible -> prefs.defaultTab
            else -> visible.firstOrNull() ?: TabKey.LOCAL
        }
        state = state.copy(prefs = prefs, connections = connections, tab = tab)
        // 导入的配置里可能改了「允许自签名证书」，DSM 客户端与流代理要同步这份开关。
        syncTlsSetting(prefsStore.getBoolean("allow_self_signed", true))
    }


    /** 保存云同步配置。密码留空表示不修改；配置密码只在首次设置或主动修改时传入。 */
internal fun AppViewModel.saveSyncConfig(
        host: String,
        port: String,
        path: String,
        username: String,
        password: String,
        secure: Boolean,
        cipher: String
    ) {
        if (host.isBlank()) {
            state = state.copy(message = "请填写 WebDAV 服务器地址")
            return
        }
        if (cipher.isNotBlank() && cipher.length < BackupCrypto.MIN_PASSWORD_LENGTH) {
            state = state.copy(message = "配置密码至少 ${BackupCrypto.MIN_PASSWORD_LENGTH} 位")
            return
        }
        val current = state.sync
        val next = current.copy(
            host = host.trim(),
            port = port.trim().toIntOrNull() ?: SyncSettings.DEFAULT_WEBDAV_PORT,
            path = path.trim().ifBlank { SyncSettings.DEFAULT_SYNC_PATH },
            username = username.trim(),
            password = password,
            secure = secure
        )
        syncStore.save(next, cipher.takeIf { it.isNotBlank() })
        val stored = syncStore.load()
        state = state.copy(sync = stored, message = "已保存云同步设置")
        ConfigSyncScheduler.apply(getApplication(), if (stored.configured) stored.autoHours else 0)
    }


internal fun AppViewModel.setSyncInterval(hours: Int) {
        val current = state.sync
        syncStore.save(current.copy(autoHours = hours))
        val stored = syncStore.load()
        state = state.copy(
            sync = stored,
            message = "自动同步：${SyncInterval.ofHours(hours).label}"
        )
        ConfigSyncScheduler.apply(getApplication(), if (stored.configured) hours else 0)
    }


    /** 断开云同步：清掉地址、账号、密码与配置密码，并撤销后台任务。 */
internal fun AppViewModel.clearSyncConfig() {
        syncStore.clear()
        state = state.copy(sync = syncStore.load(), message = "已清除云同步设置")
        ConfigSyncScheduler.apply(getApplication(), 0)
    }


    /**
     * 立即上传到云端。
     *
     * [cipher] 传空则用本机已保存的配置密码；两者都没有时由界面先弹出输入框，
     * 这里只负责给出明确结论，绝不偷偷用一个空密码加密。
     */
internal fun AppViewModel.syncUploadNow(cipher: String? = null) {
        if (state.syncBusy) return
        val settings = state.sync
        if (!settings.configured) {
            state = state.copy(message = "请先填写 WebDAV 连接信息")
            return
        }
        val key = when {
            !cipher.isNullOrBlank() -> cipher.toCharArray()
            settings.hasCipher -> syncStore.cipher()
            else -> {
                state = state.copy(message = "请先设置配置密码")
                return
            }
        }
        if (key.size < BackupCrypto.MIN_PASSWORD_LENGTH) {
            state = state.copy(message = "配置密码至少 ${BackupCrypto.MIN_PASSWORD_LENGTH} 位")
            return
        }
        state = state.copy(syncBusy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ConfigCloudSync.push(prefsStore, settings, key, BuildConfig.VERSION_NAME)
            }
            when (result) {
                is ConfigCloudSync.Push.Ok -> {
                    syncStore.markResult(result.at, "手动上传完成")
                    state = state.copy(
                        syncBusy = false,
                        sync = syncStore.load(),
                        message = "已上传到云端"
                    )
                }
                is ConfigCloudSync.Push.Fail -> {
                    syncStore.markResult(System.currentTimeMillis(), result.message)
                    state = state.copy(
                        syncBusy = false,
                        sync = syncStore.load(),
                        message = "上传失败：${result.message}"
                    )
                }
            }
        }
    }


    /** 从云端恢复：覆盖本机对应配置项（调用方必须先让用户确认）。 */
