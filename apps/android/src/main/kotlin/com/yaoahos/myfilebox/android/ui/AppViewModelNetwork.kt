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


internal fun AppViewModel.registerNetworkCallback() {
        val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                android.util.Log.w("MFB-NET", "onAvailable $network")
                networkChangedAt = System.currentTimeMillis()
                // 注册时会为「当前默认网络」立刻回调一次，那不是切网：
                // 冷启动的恢复由 restoreConnectionsOnLaunch 负责，这里直接跳过，
                // 免得每次启动都多登录一次。
                if (System.currentTimeMillis() - startedAt < 6_000L) return
                // 先给反馈、先踢死连接，再去等网络真正可用。
                // 旧 socket 在这一刻就已经不可达，而 VALIDATED 最长要等 10 秒；
                // 把提示放在它后面，用户会先对着转圈或空白干等十几秒才看到「网络已变化」。
                // 必须在 viewModelScope（主线程）里改 state：回调线程直接改会踩
                // Compose 状态的读-改-写竞态（2.4.2 那次「连接列表被清空」就是这个）。
                viewModelScope.launch {
                    notifyNetworkChanged()
                    dsm.evictConnections()
                    // 页面自己先恢复（内部带 0.6s / 2s 两次重试），不能排在
                    // 「等 VALIDATED」后面——那段最长 10 秒，用户会以为又卡死了。
                    autoReloadAfterSwitch("网络已切换")
                    // 等新网络真正可用（VALIDATED）再自动重连，最多等 10 秒。
                    // 移动数据 onAvailable 后还要拨号 / 分配 IP，立刻连必然失败。
                    var waited = 0
                    while (waited < 10_000) {
                        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                        if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                            break
                        }
                        kotlinx.coroutines.delay(500)
                        waited += 500
                    }
                    // 中继通道（quickconnect）跨网络依然可达，缓存必须留着：
                    // 清掉它下次刷新就要重做整套解析（解析服务 + 直连/中继探测），十几秒起步。
                    if (shouldRecoverOnNetworkChange()) {
                        requestSynoRecovery("网络已切换", force = true)
                    }
                }
            }

            override fun onLost(network: android.net.Network) {
                android.util.Log.w("MFB-NET", "onLost $network")
                // 掉网（WiFi 关闭 / 飞行模式切换）同样记一次时间点：之后任何一次
                // 请求失败都可以据此判定「是切网造成的，值得重连一次」。
                networkChangedAt = System.currentTimeMillis()
                // 掉网瞬间旧连接就废了。不能等新网络 onAvailable——MIUI 上有时根本不回调，
                // 用户会一直盯着无反应的列表。
                viewModelScope.launch {
                    notifyNetworkChanged()
                    // 也排一次自动重读：若新网络马上回来（WiFi→蜂窝通常 300ms 内），
                    // onAvailable 会把这次任务取消；真断网时它会失败并把提示条挂出来，
                    // 用户至少有一个能点的「刷新」。
                    autoReloadAfterSwitch("网络断开")
                }
            }
        }
        networkCallback = callback
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
        }
    }


internal fun AppViewModel.startNetworkWatchdog() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_500)
                val signature = currentNetworkSignature()
                if (signature.isBlank()) continue
                if (networkSignature.isBlank()) {
                    networkSignature = signature
                    continue
                }
                if (signature == networkSignature) continue
                android.util.Log.w("MFB-NET", "watchdog: $networkSignature -> $signature")
                networkSignature = signature
                networkChangedAt = System.currentTimeMillis()
                dsm.evictConnections()
                notifyNetworkChanged()
                // 看门狗是 MIUI 上最可靠的一条路径（回调常常整个不来）：
                // 一旦判定切网，就直接把当前远端目录读回来，别让用户手动刷新。
                autoReloadAfterSwitch("检测到网络变化")
                if (shouldRecoverOnNetworkChange()) {
                    requestSynoRecovery("检测到网络变化", force = true)
                }
            }
        }
    }


    /**
     * 切网后是否真的需要重连：
     * - 端点是内网地址 → 离开局域网后必然失效，必须重解析；
     * - 端点走 QuickConnect 中转、而手机又回到 WiFi/以太网 → 重新解析可能拿到局域网地址，
     *   局域网比中转快得多，值得切一次（解析失败会自动落回原来的候选顺序）。
     */
internal fun AppViewModel.shouldRecoverOnNetworkChange(): Boolean {
        val endpoint = state.session?.endpoint ?: return false
        if (endpointLooksPrivate(endpoint)) return true
        return endpoint.contains("quickconnect", ignoreCase = true) && onLanTransport()
    }


    /** 当前是否接在 WiFi / 以太网上。 */
internal fun AppViewModel.onLanTransport(): Boolean = runCatching {
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)


    /** 传输类型 + 接口名：WiFi→蜂窝、WiFi 换 AP、插拔网线都会变。 */
internal fun AppViewModel.currentNetworkSignature(): String = runCatching {
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return@runCatching ""
        val network = cm.activeNetwork ?: return@runCatching ""
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching ""
        val transports = buildString {
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) append('w')
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) append('c')
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) append('e')
        }
        val iface = runCatching { cm.getLinkProperties(network)?.interfaceName }.getOrNull().orEmpty()
        "$transports|$iface"
    }.getOrDefault("")


    /**
     * 群晖「断线自愈」。
     *
     * 三条触发路径共用这里：
     * 1. 网络切换回调（[registerNetworkCallback]）；
     * 2. 任何一次群晖请求失败（[refreshSyno] / 打开共享 / 读取目录）；
     * 3. App 回到前台时发现「endpoint 是内网地址，但当前不在 WiFi / 以太网上」
     *    —— 这一条最关键：它不依赖系统回调，用户切网后一打开 App 就会被纠正。
     */
internal fun AppViewModel.requestSynoRecovery(reason: String, force: Boolean = false) {
        if (state.session == null) return
        if (recoveryJob?.isActive == true) {
            android.util.Log.w("MFB-NET", "recover skip (running): $reason")
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastRecoveryAt < 15_000L) {
            android.util.Log.w("MFB-NET", "recover skip (too soon): $reason")
            return
        }
        lastRecoveryAt = now
        recoveryJob = viewModelScope.launch {
            android.util.Log.w("MFB-NET", "recover start: $reason")
            // 旧 socket 指向的可能是已经离开的局域网，先全部踢掉再重新解析通道。
            // 中继缓存（quickconnect）不清：它跨网络依然可达，清了只会让重连多绕一圈探测。
            dsm.evictConnections()
            for (attempt in 0 until 3) {
                if (renewSynoSession(force = true)) {
                    android.util.Log.w("MFB-NET", "recover ok: $reason attempt=$attempt")
                    // 恢复后把群晖列表重刷一遍；skipRecovery 防止「刷新失败→再恢复」死循环。
                    // 用户若停在某个共享目录里，刷共享列表他看不见——直接把那一页读回来，
                    // 否则会出现「已经重连成功，但页面还是空的」。
                    val open = state.browser?.takeIf {
                        it.ctx == FileContext.SYNO && it.location.isNotBlank()
                    }
                    if (open != null) loadFolder(open.location) else refreshSyno(skipRecovery = true)
                    return@launch
                }
                dsm.clearQcCache()
                kotlinx.coroutines.delay(1_500L * (attempt + 1))
            }
            android.util.Log.w("MFB-NET", "recover failed: $reason")
            val endpoint = state.session?.endpoint.orEmpty()
            val privateOnCellular = onCellularTransport() && endpointLooksPrivate(endpoint)
            // 停在远端浏览页时，页内提示条已经说明了情况：别再弹一条全局提示盖住列表。
            // 唯一例外是「内网地址 + 移动网络」，那句话是可执行的改法，必须让用户看到。
            val remotePageOpen = state.browser?.let { it.ctx != FileContext.LOCAL } == true
            state = state.copy(
                connectHint = remotePageOpen || state.connectHint,
                message = when {
                    privateOnCellular ->
                        "内网地址 $endpoint 在移动数据下不可达，请改用 QuickConnect"
                    remotePageOpen -> state.message
                    else -> "网络已切换，请下拉刷新重试"
                }
            )
        }
    }


    /**
     * 给一次**网络位置**（SMB / WebDAV / FTP / SFTP）读取装 1 秒的「没反应就提示」定时器。
     *
     * 网络文件不像群晖走通道自愈：连不上就是连不上，用户对「点了没反应」的容忍度
     * 大约只有 1 秒，而 socket 连接超时是 4s，等它返回再提示，用户早在反复点。
     * 到点先挂「连接超时，请检查网络」，请求真回来了由成功分支自动撤掉。
     *
     * 不能用「browser.location 还是不是这个位置」来判定：从连接列表点卡片进来时，
     * 浏览页还没建起来（location 是 null 或上一个目录），断言必然不成立，
     * 定时器到点就空转，用户只能等 socket 超时——提示等于没挂。
     * 这里靠 cancel 前一个定时器保证不串台：同一时刻只留最近一次请求的计时。
     *
     * 群晖页面不走这条：那边靠切网自动重读 + 页内「网络已变化」提示，语义不同。
     */
internal fun AppViewModel.armNetTimeoutHint(location: String) {
        connectHintJob?.cancel()
        // 手机根本没网时不必等满 1 秒：结论在点下去那一刻就已经确定。
        // 让用户先看 1 秒转圈再读到「连接超时」，等于把一个已知答案又拖了一遍。
        if (offlineNotice() != null) {
            android.util.Log.w("MFB-NET", "offline")
            state = state.copy(connectHint = true)
            return
        }
        connectHintJob = viewModelScope.launch {
            kotlinx.coroutines.delay(connectHintMs)
            if (!state.loading) return@launch
            android.util.Log.w("MFB-NET", "net slow: no result in ${connectHintMs}ms")
            state = state.copy(connectHint = true)
        }
    }


internal fun AppViewModel.cancelTimeoutHint() {
        connectHintJob?.cancel()
        connectHintJob = null
    }


    /** 切网后 20 秒内，失败基本都能归因于「旧 socket 失效 / 新通道未就绪」。 */
internal fun AppViewModel.recentNetworkChange(windowMs: Long = 20_000L): Boolean =
        networkChangedAt > 0L && System.currentTimeMillis() - networkChangedAt < windowMs


    /**
     * 切网时把在途的目录请求撤掉。
     *
     * 旧 socket 指向已经离开的局域网，不撤会一直卡到超时。群晖页面随后由
     * [autoReloadAfterSwitch] 静默重读，真恢复不了才挂「网络已变化」提示。
     */
internal fun AppViewModel.notifyNetworkChanged() {
        // 先把这一笔记进 state：网络首页的「没网 / 飞行模式」说明是现查的，不推状态
        // 就永远不会重组，恢复联网后那句话会一直挂着。
        state = state.copy(netEpoch = state.netEpoch + 1)
        val browser = state.browser
        if (browser == null || browser.ctx == FileContext.LOCAL) return
        cancelTimeoutHint()
        if (state.loading) {
            browserLoadJob?.cancel()
            browserLoadJob = null
            // 在途请求被撤掉时「正在重建通道」也要一起撤：那条提示是为某一次请求挂的，
            // 请求没了还留着，用户会以为它一直卡在重建上。
            state = state.copy(loading = false, repairingChannel = false)
        }
    }


internal fun AppViewModel.dismissNetworkHint() { state = state.copy(connectHint = false) }


    /**
     * 设备完全没联网时的一句话；有网返回 null。
     *
     * 界面用它把提示条从「连接超时，请检查网络」换成真正的原因——手机没网时
     * 那句「请检查网络」会把用户往错的方向带：网络本身没问题，是根本没连上。
     */
internal fun AppViewModel.offlineNotice(): String? = offlineNetworkMessage()


    /**
     * 页面常驻的设备状态说明，只用于「告诉用户现在什么情况」，**不参与是否发请求**。
     *
     * 飞行模式下把 WiFi 单独打开时 activeNetwork 还在，内网 NAS 依然可达；
     * 这时 [offlineNotice] 必须返回 null（否则会把合法的内网请求拦掉），
     * 但界面又必须显示「飞行模式已开启」，否则用户只看到过期的在线状态与超时。
     */
internal fun AppViewModel.networkNotice(): String? = networkStatusNotice()


    /** 当前是否完全没有网络（列表提示条、连接入口据此决定要不要提前拦下）。 */
internal fun AppViewModel.deviceHasNoNetwork(): Boolean = NetStatus.current() == DeviceNet.OFFLINE

    /**
     * 用户操作时主动比对一次网络指纹，发现换网就地处理。
     *
     * 系统回调在 MIUI / HyperOS 上经常迟到，甚至 WiFi 切蜂窝时两条回调都不来；
     * 等回调意味着用户先对着空白页干等。点目录 / 点刷新这一刻就能查，成本只有两个系统 API。
     */
internal fun AppViewModel.detectNetworkSwitchNow(): Boolean {
        val signature = currentNetworkSignature()
        if (signature.isBlank()) return false
        if (networkSignature.isBlank()) {
            networkSignature = signature
            return false
        }
        if (signature == networkSignature) return false
        networkSignature = signature
        networkChangedAt = System.currentTimeMillis()
        dsm.evictConnections()
        notifyNetworkChanged()
        return true
    }


    /**
     * 切网窗口内的读取失败该说什么。
     *
     * 有浏览页时只靠页内提示条（带「重试」），不再往全局 message 里塞——
     * 否则停在连接列表 / 设置页时，底下残留的浏览页会把提示推到别的页面上。
     */
internal fun AppViewModel.switchFailureMessage(switched: Boolean, other: String): String? = when {
        !switched -> other
        state.browser == null -> "网络已变化，请稍后重试"
        else -> null
    }


    /**
     * 群晖通道修不好时该说什么。
     * 「用内网 IP 登录、人已经离开该局域网」是最常见的一种，此时任何重试都不会成功，
     * 只能直接告诉用户两条可行路径，而不是让他反复点刷新。
     */
internal fun AppViewModel.synoUnreachableMessage(): String {
        val endpoint = state.session?.endpoint.orEmpty()
        return if (hostLooksPrivate(endpoint) && !onLanTransport()) {
            "不在 NAS 所在局域网，请连同一 WiFi 或用 QuickConnect"
        } else {
            "群晖连接尚未恢复，请稍后重试"
        }
    }


    /** 超时提示条上的「重试」：按当前页面的上下文重新拉一次目录内容。 */
internal fun AppViewModel.reloadCurrentFolder() {
        val browser = state.browser ?: return
        // 重试这一刻再查一次网络：回调迟到时也能先把旧通道踢掉。
        detectNetworkSwitchNow()
        // 提示条先留着（界面会显示「正在连接…」），等目录真正读回来再收起。
        // 提前撤掉提示会让用户对着空白页干等，等于回到原来的体验。
        when (browser.ctx) {
            FileContext.LOCAL -> refresh()
            FileContext.SYNO -> {
                val location = browser.location
                if (location.isBlank()) {
                    refreshSyno()
                    return
                }
                if (state.session?.sid.isNullOrBlank()) {
                    state = state.copy(message = "群晖登录状态已失效，请重新登录")
                    return
                }
                loadFolder(location)
            }
            FileContext.NET -> {
                val connection = state.connections.firstOrNull { it.id == browser.netId } ?: return
                val location = browser.location
                if (location.isBlank()) return
                loadNetworkFolder(connection, location)
            }
        }
    }


    /**
     * 切网后自动把当前远端目录读回来（静默，不提示「网络已切换」）。
     *
     * 新网络刚起来时拨号 / IP 可能还没就绪，先等 0.6 秒试一次，失败再等 2 秒试一次。
     * 两次都失败才挂「网络已变化，请刷新后重试」；读回成功则提示自动撤掉，
     * 正常切网用户基本看不到任何东西。
     *
     * 只在远端页面（群晖 / 网络位置）触发：本地目录与网络无关，重读纯属浪费。
     */
internal fun AppViewModel.autoReloadAfterSwitch(reason: String) {
        val browser = state.browser ?: return
        if (browser.ctx == FileContext.LOCAL) return
        val location = browser.location
        if (location.isBlank()) return
        // 多选时不自动重读（会打断选择）；此时列表还在，不打扰用户，等他自己操作。
        if (state.selectionActive) return
        autoReloadJob?.cancel()
        autoReloadJob = viewModelScope.launch {
            // 切网前读回过同目录也算「读过」，必须清掉才能判断这一轮是否成功。
            lastLoadedLocation = ""
            for (attempt in 0 until 2) {
                kotlinx.coroutines.delay(if (attempt == 0) 600L else 2_000L)
                // 期间用户可能已经翻到别的目录 / 退出浏览页
                if (state.browser?.location != location) return@launch
                android.util.Log.w("MFB-NET", "auto reload: $reason attempt=$attempt")
                reloadCurrentFolder()
                val loading = browserLoadJob
                if (loading != null) runCatching { loading.join() }
                if (lastLoadedLocation == location) {
                    android.util.Log.w("MFB-NET", "auto reload ok: $reason")
                    return@launch
                }
                // 两次都不成：挂「连接超时」，用户点一下就能重试。
                if (!state.connectHint) state = state.copy(connectHint = true)
            }
            android.util.Log.w("MFB-NET", "auto reload gave up: $reason")
        }
    }


    /**
     * 纯同步预判：接下来这次请求是不是得先重建通道。
     *
     * 只查端点与当前传输方式，不发任何请求。点目录那一刻用它决定要不要立刻挂上
     * 「网络已切换，正在重建连接」——[repairChannelIfNeeded] 是挂起的，等它跑完
     * 再提示就等于让用户先干等完那几秒。
     */
internal fun AppViewModel.willRepairChannel(): Boolean {
        val endpoint = state.session?.endpoint ?: return false
        val need = needsChannelRepair(endpoint, onLanTransport())
        if (need) {
            android.util.Log.w("MFB-NET", "channel repair needed: endpoint=$endpoint onLan=${onLanTransport()}")
        }
        return need
    }


    /**
     * 切网后先把通道修好，再发业务请求。
     *
     * 两种判据：端点是内网地址而当前不在局域网；或刚切过网且走 QuickConnect 中继。
     * 这两种情况下旧端点必然不可达，先重解析通道（命中中继缓存时 1~2 秒）远比
     * 先吃满连接超时再回头重连快。
     */
internal suspend fun AppViewModel.repairChannelIfNeeded(): Boolean {
        val endpoint = state.session?.endpoint ?: return true
        if (!needsChannelRepair(endpoint, onLanTransport())) return true
        // 已经有一路在重连就等它完成，避免几路同时重登。
        // 但不能 join 自己：自愈任务成功后会回来重读目录，那时 recoveryJob 就是当前协程，
        // join 自己会永久挂起（用户看到的就是「刷新一直转圈」）。
        val running = recoveryJob
        val self = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        if (running != null && running.isActive) {
            // 自愈任务自己回来重读目录：通道刚修好，不能再登一次，直接放行。
            if (running === self) return true
            runCatching { running.join() }
            return true
        }
        return renewSynoSession(force = true)
    }


    /** 重新读取群晖某个目录（切网后刷新用）。 */
internal fun AppViewModel.loadFolder(location: String, successMessage: String? = null) {
        browserLoadJob?.cancel()
        // 同 openFolder：先用同步判断算出「这次得不得重建通道」，需要就先挂提示再干活。
        // 刚切过网时把整段等待都算作「重建中」——重建往往只是 join 已在跑的自愈任务，
        // 真正耗时的是随后那次短超时的目录请求。
        val rebuilding = willRepairChannel() || recentNetworkChange()
        state = state.copy(loading = true, repairingChannel = rebuilding)
        browserLoadJob = viewModelScope.launch {
            // 端点还指着刚离开的局域网时，先修通道再读目录：直接发请求只会把
            // 12 秒连接超时耗完，用户看到的仍是「刷新半天没反应」。
            if (!repairChannelIfNeeded()) {
                state = state.copy(
                    loading = false,
                    repairingChannel = false,
                    message = synoUnreachableMessage(),
                    connectHint = true
                )
                return@launch
            }
            // 提示在这里不撤：后面那次目录请求同样在等，等它落地或失败再一起收。
            // 会话可能刚被切网自愈换掉（重新登录 = 新 SID），必须取最新一份：
            // 拿旧 SID 去请求只会再换回一个 119，用户又白等一轮。
            val session = state.session
            if (session == null || session.sid.isBlank()) {
                state = state.copy(
                    loading = false,
                    repairingChannel = false,
                    message = "群晖登录状态已失效，请重新登录"
                )
                return@launch
            }
            // 切网窗口内用短超时客户端：宁可 4 秒失败给出提示，也不干等 12 秒。
            val result = runCatching { dsm.list(session, location, quick = recentNetworkChange()) }
            val failure = result.exceptionOrNull()
            // 取消不是读取失败：状态复位与提示由 notifyNetworkChanged 负责。
            if (failure is kotlinx.coroutines.CancellationException) return@launch
            if (failure != null) {
                val switched = recentNetworkChange()
                state = state.copy(
                    loading = false,
                    repairingChannel = false,
                    message = switchFailureMessage(switched, "读取目录失败：${friendlyNetError(failure)}"),
                    connectHint = switched || state.connectHint
                )
                if (switched) requestSynoRecovery("刷新目录失败（网络已切换）")
                return@launch
            }
            val items = result.getOrDefault(emptyList())
            lastLoadedLocation = location
            if (state.browser?.location == location) {
                state = state.copy(
                    loading = false,
                    repairingChannel = false,
                    browser = state.browser?.copy(items = sortFiles(items)),
                    connectHint = false,
                    // 只在「下拉刷新」这条路径上传入；点进目录时不提示，
                    // 否则每打开一个文件夹都弹一次「已刷新」，纯噪声。
                    message = successMessage ?: state.message
                )
            } else {
                state = state.copy(loading = false, repairingChannel = false)
            }
        }
    }


    /**
     * 网络位置（SMB / WebDAV / FTP / SFTP）的失败收口。
     *
     * 与群晖不同，这边没有「切网自愈」那套：连不上就是连不上，所以要
     * 1）把这条连接同步成离线——列表卡片上的「在线」不能继续骗人；
     * 2）给出具体原因（解析失败 / 端口拒绝 / 认证失败 / 超时），
     *    不再用「请检查所有参数」这种用户无法执行的话。
     */
internal fun AppViewModel.onNetFailure(connection: NetworkConnection, error: Throwable?) {
        markConnectionOffline(connection.id)
        state = state.copy(
            loading = false,
            connectHint = false,
            message = friendlyNetError(error, "无法连接该服务器", host = connection.host)
        )
    }


    /** 读目录成功说明这条连接当前可用：把卡片状态同步回在线。 */
internal fun AppViewModel.markConnectionOnline(id: String) {
        val changed = state.connections.any { it.id == id && !it.online }
        if (!changed) return
        state = state.copy(
            connections = state.connections.map {
                if (it.id == id) it.copy(online = true) else it
            }
        )
        persist()
    }


    /** 连接失败后把卡片状态同步成离线；本来就离线就不重复写盘。 */
internal fun AppViewModel.markConnectionOffline(id: String) {
        val needsUpdate = state.connections.any { it.id == id && (it.online || it.latency != 0) }
        if (!needsUpdate) return
        state = state.copy(
            connections = state.connections.map {
                if (it.id == id) it.copy(online = false, latency = 0) else it
            }
        )
        persist()
    }


    /** 重新读取网络位置（WebDAV / SMB / FTP / SFTP）的某个目录。 */
internal fun AppViewModel.loadNetworkFolder(
        connection: NetworkConnection,
        location: String,
        successMessage: String? = null
    ) {
        browserLoadJob?.cancel()
        state = state.copy(loading = true)
        armNetTimeoutHint(location)
        browserLoadJob = viewModelScope.launch {
            val result = runCatching { network.list(connection, location) }
            val failure = result.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) return@launch
            if (failure != null) {
                onNetFailure(connection, failure)
                return@launch
            }
            val items = result.getOrDefault(emptyList())
            lastLoadedLocation = location
            markConnectionOnline(connection.id)
            if (state.browser?.location == location) {
                state = state.copy(
                    loading = false,
                    browser = state.browser?.copy(items = sortFiles(items)),
                    connectHint = false,
                    // 同群晖：只有下拉刷新才带文案，进目录时不弹提示。
                    message = successMessage ?: state.message
                )
            } else {
                state = state.copy(loading = false)
            }
        }
    }


    /** App 回到前台时调用：端点还停在内网地址、而手机已经只有蜂窝网络时立即自愈。 */
internal fun AppViewModel.healSynoChannelOnForeground() {
        // 在后台切的网，系统回调常常整个不来（MIUI/HyperOS）：回前台先自己对一次指纹，
        // 变了就自动把当前目录读回来，不用用户再点一次刷新。
        if (detectNetworkSwitchNow()) autoReloadAfterSwitch("回到前台发现网络变化")
        val session = state.session ?: return
        if (session.sid.isBlank()) return
        if (!endpointLooksPrivate(session.endpoint ?: return)) return
        // 必须确认「真的在蜂窝上」：刚启动时 ConnectivityManager 可能还没给出
        // 当前网络的能力，用「不是 WiFi」判断会误命中，白发一次重连。
        if (!onCellularTransport()) return
        val now = System.currentTimeMillis()
        if (now - networkChangedAt < 3_000L) return
        requestSynoRecovery("已切到移动网络")
    }


    /** endpoint 的 host 是否内网地址（判定在 [hostLooksPrivate]，抽成纯函数便于单测）。 */
internal fun AppViewModel.endpointLooksPrivate(endpoint: String): Boolean = hostLooksPrivate(endpoint)


    /** 当前是否「只有」蜂窝网络（没有 WiFi / 以太网可用）。 */
internal fun AppViewModel.onCellularTransport(): Boolean = runCatching {
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        val lan = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
        !lan && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
    }.getOrDefault(false)



    /**
     * 清理「在线打开 / 预览 / 缩略图」留下的缓存文件。
     *
     * 每次点远端文件都会往 cacheDir 落一份完整文件（open-* / preview-*），
     * 缩略图在 cacheDir/thumbs，系统选择器上传的临时副本在 cacheDir/uploads。
     * 保留天数由设置里的「缓存自动清理」决定（0 = 永不），冷启动与前台恢复时各跑一次。
     */
internal fun AppViewModel.pruneTransferCache() {
        val days = state.prefs.cacheKeepDays
        if (days <= 0) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { pruneCacheBlocking(days, false) } }
    }


    /** 真正删缓存的实现：返回释放的字节数。all = true 表示不看到期时间、全清。 */
internal suspend fun AppViewModel.pruneCacheBlocking(days: Int, all: Boolean): Long = withContext(Dispatchers.IO) {
        val cacheDir = getApplication<Application>().cacheDir
        val cutoff = System.currentTimeMillis() - days.coerceAtLeast(1) * 24L * 60 * 60 * 1000
        var freed = 0L
        fun purge(file: File) {
            if (!file.exists()) return
            if (file.isDirectory) {
                file.listFiles().orEmpty().forEach { purge(it) }
                runCatching { if (file.listFiles().orEmpty().isEmpty()) file.delete() }
            } else {
                val expired = all || file.lastModified() < cutoff
                if (expired) {
                    val size = runCatching { file.length() }.getOrDefault(0L)
                    if (runCatching { file.delete() }.getOrDefault(false)) freed += size
                }
            }
        }
        // 只动这三类缓存，别把 okhttp / 崩溃日志之类的其它缓存当垃圾清掉。
        cacheDir.listFiles().orEmpty().forEach { entry ->
            val name = entry.name
            val managed = name.startsWith("open-") || name.startsWith("preview-") ||
                name == "thumbs" || name == "uploads"
            if (managed) purge(entry)
        }
        freed
    }


    /** 缓存目录当前占用（字节）。设置页用来显示「已占用 xx」。 */
internal suspend fun AppViewModel.cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        val cacheDir = getApplication<Application>().cacheDir
        fun sizeOf(file: File): Long = if (file.isDirectory) {
            file.listFiles().orEmpty().sumOf { sizeOf(it) }
        } else runCatching { file.length() }.getOrDefault(0L)
        cacheDir.listFiles().orEmpty()
            .filter {
                it.name.startsWith("open-") || it.name.startsWith("preview-") ||
                    it.name == "thumbs" || it.name == "uploads"
            }
            .sumOf { sizeOf(it) }
    }


internal fun AppViewModel.setDownloadConcurrency(value: Int) {
        val n = value.coerceIn(1, 6)
        state = state.copy(
            prefs = state.prefs.copy(downloadConcurrency = n),
            message = "下载并发数已设为 $n"
        )
        persist()
    }


internal fun AppViewModel.setCacheKeepDays(days: Int) {
        state = state.copy(
            prefs = state.prefs.copy(cacheKeepDays = days.coerceAtLeast(0)),
            message = if (days <= 0) "缓存不再自动清理" else "缓存保留 $days 天，过期自动清理"
        )
        persist()
        pruneTransferCache()
    }


    /** 手动清理缓存：立刻清空，不受保留天数限制。 */
internal fun AppViewModel.cleanCacheNow() {
        viewModelScope.launch {
            val freed = pruneCacheBlocking(state.prefs.cacheKeepDays.coerceAtLeast(1), true)
            state = state.copy(
                cacheBytes = cacheSizeBytes(),
                message = if (freed <= 0L) "缓存已经是干净的"
                else "已清理缓存 ${LocalRepository.formatSize(freed)}"
            )
        }
    }


    /** 设置页进入时算一次缓存占用，避免每次都显示 0。 */
internal fun AppViewModel.refreshCacheSize() {
        viewModelScope.launch { state = state.copy(cacheBytes = cacheSizeBytes()) }
    }


    /**
     * 冷启动恢复连接，避免「每次重开都要重新创建」：
     * 群晖用记住的密码静默换一个可用 SID，网络位置重新探一次在线状态。
     */
internal fun AppViewModel.restoreConnectionsOnLaunch() {
        if (connectionsRestored) return
        connectionsRestored = true
        // 用户手动退出过：密码留着方便下次预填，但不要偷偷把人重新登进去。
        if (autoLoginSuppressed) {
            warmConnections()
            return
        }
        val session = state.session
        if (session != null) {
            val saved = savedLogin()
            if (saved.third.isNotBlank()) {
                silentRelogin(saved.first.ifBlank { session.server }, saved.second.ifBlank { session.account }, saved.third)
            } else if (session.sid.isNotBlank()) {
                refreshSyno()
            }
        }
        warmConnections()
    }


    /** 静默换 SID：失败不打扰用户，进入群晖页时会再明确提示。 */
internal fun AppViewModel.silentRelogin(server: String, account: String, password: String) {
        viewModelScope.launch {
            val parsed = dsm.parseServer(server, state.session?.https ?: true)
            val candidates = if (parsed.quickConnect) {
                dsm.quickConnectCandidates(parsed.host)
            } else listOf(QuickConnectEndpoint(parsed.baseUrl, parsed.display))
            val fallback = state.session ?: Session(
                server = parsed.display, account = account, name = account,
                nas = "", mode = "", https = parsed.https
            )
            for (endpoint in candidates) {
                val provisional = fallback.copy(
                    server = parsed.display,
                    account = account,
                    endpoint = endpoint.baseUrl,
                    mode = if (parsed.quickConnect) endpoint.label else fallback.mode
                )
                val result = runCatching { dsm.login(provisional, password) }.getOrNull() ?: continue
                if (result.success && result.sid != null) {
                    cachedPassword = password
                    state = state.copy(
                        session = provisional.copy(
                            sid = result.sid,
                            synoToken = result.synoToken.orEmpty(),
                            nas = "DSM 已连接"
                        )
                    )
                    persist()
                    refreshSyno()
                    return@launch
                }
            }
        }
    }
