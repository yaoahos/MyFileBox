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
import com.yaoahos.myfilebox.android.TransferCancellation
import com.yaoahos.myfilebox.android.TransferNotifications
import com.yaoahos.myfilebox.android.TransferService
import com.yaoahos.myfilebox.android.data.DsmRepository
import com.yaoahos.myfilebox.android.data.AppIconSwitcher
import com.yaoahos.myfilebox.android.data.nameMatchesFilter
import com.yaoahos.myfilebox.android.data.hostLooksPrivate
import com.yaoahos.myfilebox.android.data.needsChannelRepair
import com.yaoahos.myfilebox.android.data.LocalRepository
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

enum class Screen { MAIN, SETTINGS, APPEARANCE, ABOUT }
enum class FileContext { LOCAL, SYNO, NET }
enum class TransferMode { DOWNLOAD, UPLOAD, COPY, MOVE }
enum class DestType { LOCAL, SYNO, NET }

data class BrowserState(
    val title: String,
    val path: String,
    val ctx: FileContext,
    val items: List<FileEntry>,
    val grid: Boolean = false,
    val netId: String? = null,
    val location: String = "",
    val groupByDate: Boolean = false,
    /**
     * 非空表示这是一个「分类」页（图片 / 视频 / 最近…）。分类页的 items 是从
     * 首页快照复制出来的，快照刷新后必须按这个 key 重算，否则停在分类页时
     * 新下载的文件永远看不到。
     */
    val localCategory: String? = null,
    /**
     * 是否为「收藏」页。收藏页不是真实目录：取消收藏后不能靠重列目录刷新，
     * 必须按 favorites 集合重算 items，否则取消掉的文件会一直留在列表里
     * （退出重进才消失，3.9.17 修）。
     */
    val favorites: Boolean = false
)

data class Destination(
    val type: DestType,
    val key: String,
    val label: String,
    val meta: String,
    val path: String = key
)

data class TransferRequest(
    val mode: TransferMode,
    val items: List<FileEntry>,
    val ctx: FileContext,
    val netId: String?,
    val destinations: List<Destination>,
    val preferredKey: String? = null
)

enum class TransferStatus { RUNNING, PAUSED, SUCCESS, FAILED, CANCELLED, MISSING }

data class TransferTask(
    val id: String,
    val mode: TransferMode,
    val title: String,
    val detail: String,
    val total: Int,
    val completed: Int = 0,
    val totalBytes: Long = 0L,
    val completedBytes: Long = 0L,
    val status: TransferStatus = TransferStatus.RUNNING,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val error: String? = null,
    /** 源位置（人类可读，如「群晖 NAS / Backup/x.apk」），记录里显示「从哪」。 */
    val from: String = "",
    /** 目标位置（人类可读），记录里显示「到哪」。 */
    val to: String = "",
    /**
     * 完成后可直接打开的路径：
     * 下载 = 本机落地文件；上传 = 远端目标文件。为空表示只能打开传输记录。
     */
    val target: String = "",
    /** target 所属端（LOCAL / SYNO / NET），决定点击记录时按本机还是远端打开。 */
    val targetCtx: String = "",
    /** target 是网络位置时的连接 id（上传删远端文件要用）。 */
    val targetNet: String = "",
    /** 源所属端（FileContext.name）；「重新下载」要按它决定从哪取。 */
    val sourceCtx: String = "",
    /** 源是网络位置时对应的连接 id。 */
    val sourceNet: String = "",
    /** 源文件的真实路径（列表里可能是 WebDAV 绝对 URL），重新下载 / 重试用它重建请求。 */
    val sources: List<String> = emptyList(),
    /**
     * 上传任务已经写到远端的文件路径。
     * 删除上传任务时要按它删远端文件；上传中途暂停也会留一条（半截文件），
     * 一并清掉，避免远端出现打不开的残缺文件。
     */
    val uploadedTargets: List<String> = emptyList(),
    /**
     * 后台静默任务：同端复制 / 移动、在线打开缓存这类不需要出现在传输中心、
     * 不进记录、不出通知的任务。
     */
    val quiet: Boolean = false
) {
    val progress: Float
        get() = when {
            totalBytes > 0L -> (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            total > 0 -> (completed.toFloat() / total).coerceIn(0f, 1f)
            else -> 0f
        }
}

data class SearchState(
    val ctx: FileContext,
    val query: String = "",
    val folder: String = "all",
    val ext: String = "",
    /** 位置筛选的人类可读名称；支持任意层级目录后无法再从扁平列表反查。 */
    val folderLabel: String = "所有位置"
)

/** 「打开方式」里的一条：真实安装的应用。系统选择器用空包名表示。 */
data class OpenApp(val pkg: String, val label: String)

data class ClipboardState(
    val mode: TransferMode,
    val items: List<FileEntry>,
    val ctx: FileContext,
    val netId: String?
)

data class MyFileBoxState(
    val prefs: AppPrefs = AppPrefs(),
    val session: Session? = null,
    val tab: TabKey = TabKey.LOCAL,
    val screen: Screen = Screen.MAIN,
    val localFiles: List<FileEntry> = emptyList(),
    val photos: List<FileEntry> = emptyList(),
    val localFolders: List<FolderCard> = emptyList(),
    val categoryCounts: Map<FileKind, Int> = emptyMap(),
    val shares: List<ShareFolder> = emptyList(),
    val connections: List<NetworkConnection> = emptyList(),
    val netFiles: Map<String, List<FileEntry>> = emptyMap(),
    val volumes: List<Volume> = emptyList(),
    val browser: BrowserState? = null,
    val browserStack: List<BrowserState> = emptyList(),
    val fileSheet: FileEntry? = null,
    /** 「查看详情」弹窗；首页与内部文件夹共用同一个入口。 */
    val detailFile: FileEntry? = null,
    /** 点击 APK 但还没拿到「安装未知应用」权限时，用这个字段驱动引导弹窗。 */
    val installFile: FileEntry? = null,
    val transfer: TransferRequest? = null,
    val clipboard: ClipboardState? = null,
    val transfers: List<TransferTask> = emptyList(),
    val transferCenterOpen: Boolean = false,
    /** 压缩/解压等本地任务的进度标签，显示在页面底部进度条上。空 = 无任务。 */
    val localTaskLabel: String = "",
    val localTaskRunning: Boolean = false,
    /** 缓存目录占用字节数（设置页展示，进入设置时刷新）。 */
    val cacheBytes: Long = 0L,
    val recycleOpen: Boolean = false,
    val recycleItems: List<RecycleItem> = emptyList(),
    /** 回收站多选（清单 6）：id 集合 + 选择态开关。 */
    val recycleSelected: Set<String> = emptySet(),
    val recycleSelecting: Boolean = false,
    /** 回收站归属（清单 5）：本地回收只管本地，群晖回收走 #recycle。 */
    val recycleContext: FileContext = FileContext.LOCAL,
    /** 群晖共享管理（清单 4）：统一查看 / 取消共享链接。 */
    val shareManagerOpen: Boolean = false,
    val shareLinks: List<com.yaoahos.myfilebox.core.ShareLink> = emptyList(),
    val shareLinksLoading: Boolean = false,
    /** 群晖收藏解析结果（收藏页展示用，文件与文件夹都要能看）。 */
    val synoFavorites: List<FileEntry> = emptyList(),
    val search: SearchState? = null,
    val searchIndexing: Boolean = false,
    /**
     * 页面级连接提示（两种页面两种含义）：
     * - 群晖：切网后旧 socket 一直卡到超时，挂「网络已变化，请刷新后重试」；
     * - 网络文件：连接 / 读取超过 1 秒没结果，挂「连接超时，点击重试」。
     *
     * 与 message 的一次性提示不同，这条留在页面上并可点重试，直到读回列表或用户关闭。
     */
    val connectHint: Boolean = false,
    /**
     * 网络可用性变化序号，只用来让界面重新组合一次。
     *
     * 网络首页那条「手机没网 / 飞行模式已开启」是画的时候现查的（不走 state），
     * 而 Compose 只在 state 变化时重组：网络恢复了不推这一笔，那行会一直挂在列表上方，
     * 直到用户碰出别的状态变化才消失。
     */
    val netEpoch: Int = 0,
    /**
     * 正在重建群晖通道：切网后旧端点不可达，得重新解析通道 / 重新登录。
     *
     * 这一步要 1~2 秒（命中中继缓存），中继冷启动时能到十几秒。期间浏览页是空列表
     * 加一个转圈，用户既看不出在连也看不出卡住，只会反复点同一行。所以单独记一个
     * 状态，由页面把「网络已切换，正在重建连接」写出来。
     */
    val repairingChannel: Boolean = false,
    /** 名称筛选词，只作用于当前所在位置；进入下一级目录 / 切换底栏时清空。 */
    val nameFilter: String = "",
    val screenStack: List<Screen> = emptyList(),
    val selectionActive: Boolean = false,
    val homeSelected: Set<String> = emptySet(),
    val homeSelecting: Boolean = false,
    val editSynoOpen: Boolean = false,
    /** 「编辑连接」不再弹小窗，而是回到登录页；旧会话在重新登录成功前一直保留。 */
    val connectionEditing: Boolean = false,
    /** 当前 NAS 是否提供官方收藏接口（SYNO.FileStation.Favorite）。 */
    val synoFavoriteSupported: Boolean = false,
    /** 群晖搜索索引（清单 11）：与 shares.items 解耦，浏览刷新不再清掉索引。 */
    val searchIndexSyno: List<FileEntry> = emptyList(),
    /** 网络搜索索引（清单 11）：连接 id → 深层条目。 */
    val searchIndexNet: Map<String, List<FileEntry>> = emptyMap(),
    val selected: Set<String> = emptySet(),
    val preview: FileEntry? = null,
    val openWith: FileEntry? = null,
    /** 能打开 openWith 那个文件的已安装应用（含真实图标名称，替代写死的假列表）。 */
    val openWithApps: List<OpenApp> = emptyList(),
    /** 「打开方式」还在枚举安装包（包管理器查询最多几百毫秒）。 */
    val openWithLoading: Boolean = false,
    val pendingDelete: List<FileEntry> = emptyList(),
    val pendingDeleteLabel: String = "",
    /** 待删除项来自首页多选；确认后要一并退出首页选择态。 */
    val pendingDeleteHome: Boolean = false,
    val pendingDeleteConnection: String? = null,
    val refreshing: Boolean = false,
    /** 最近一次页面导航方向：1=前进（右往左入），-1=返回（左往右入）。动画据此决定滑入方向。 */
    val navDir: Int = 1,
    /** 发现新版本时非空，AppRoot 弹更新框。 */
    val updateInfo: com.yaoahos.myfilebox.android.update.UpdateInfo? = null,
    val updateDownloading: Boolean = false,
    val updateProgress: Int = 0,
    val updateLogOpen: Boolean = false,
    val updateLogNotes: List<com.yaoahos.myfilebox.android.update.ReleaseNote> = emptyList(),
    /** 更新日志还在拉远端发布正文（本地已有内容时界面先显示本地的）。 */
    val updateLogLoading: Boolean = false,
    val loading: Boolean = false,
    val localUsedGb: Double = 0.0,
    val localFreeGb: Double = 0.0,
    val localTotalGb: Double = 0.0,
    val localAccess: Boolean = false,
    val localRoot: String = "",
    val message: String? = null,
    /** 登录失败原因常驻在登录页（Snackbar 一闪而过，用户根本看不清为什么连不上）。 */
    val loginError: String? = null
)

/** MIME → 给普通用户看的类型名（设置页「打开方式」、各类提示文案共用）。 */
private val mimeTypeLabels = mapOf(
    "text/plain" to "文本文档",
    "text/html" to "网页文件",
    "text/csv" to "CSV 表格",
    "text/markdown" to "Markdown 文档",
    "text/x-markdown" to "Markdown 文档",
    "application/json" to "JSON 文件",
    "application/xml" to "XML 文件",
    "text/xml" to "XML 文件",
    "application/pdf" to "PDF 文档",
    "application/zip" to "ZIP 压缩包",
    "application/x-zip-compressed" to "ZIP 压缩包",
    "application/x-rar-compressed" to "RAR 压缩包",
    "application/vnd.rar" to "RAR 压缩包",
    "application/x-7z-compressed" to "7Z 压缩包",
    "application/gzip" to "GZIP 压缩包",
    "application/x-tar" to "TAR 归档包",
    "application/vnd.android.package-archive" to "安卓安装包",
    "application/msword" to "Word 文档",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "Word 文档",
    "application/vnd.ms-excel" to "Excel 表格",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "Excel 表格",
    "application/vnd.ms-powerpoint" to "PPT 演示文稿",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "PPT 演示文稿",
    "application/epub+zip" to "电子书",
    "application/x-msdownload" to "安装程序",
    "application/vnd.apple.mpegurl" to "播放列表",
    "application/octet-stream" to "未知类型文件"
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefsStore = app.getSharedPreferences("myfilebox_prefs", 0)
    private val local = LocalRepository(app)
    private val dsm = DsmRepository()
    /** 群晖删除单元索引：#recycle 里「整只文件夹」与「中间层目录」只能靠删时记录区分。 */
    private val synoRecycleIndex = SynoRecycleIndex(File(app.filesDir, "syno_recycle_index.json"))
    private val network = NetworkRepository(
        sftp = SftpRepository(File(app.filesDir, "known_hosts"))
    )
    private var rememberSession = true
    /** 内存密码缓存：切网后静默重连用。不落盘，进程结束即失效。 */
    @Volatile
    private var cachedPassword: String = ""
    /** 最近一次网络切换时刻：切换后短时间内网络类错误也允许自动重连。 */
    @Volatile
    private var networkChangedAt: Long = 0L
    private val startedAt = System.currentTimeMillis()
    /** 用户主动退出登录后置真：密码保留，但不允许冷启动自动登录，否则「退出」等于没退。 */
    private var autoLoginSuppressed = prefsStore.getBoolean("auto_login_suppressed", false)
    private val transferJobs = mutableMapOf<String, Job>()
    private val transferRequests = mutableMapOf<String, TransferRequest>()
    private val transferDestinations = mutableMapOf<String, Destination>()
    /** 当前压缩/解压任务的协程句柄，用于取消。 */
    private var localTaskJob: Job? = null
    private val cancelListener: (String) -> Unit = { id -> cancelTransfer(id, fromRegistry = true) }
    @Volatile private var lastTransferError: String? = null

    var state by mutableStateOf(MyFileBoxState())
        private set

    init {
        val prefs = loadPrefs()
        val visible = prefs.tabOrder.filter {
            when (it) {
                TabKey.LOCAL -> prefs.showLocal
                TabKey.SYNO -> prefs.showSyno
                TabKey.NET -> prefs.showNet
            }
        }
        val initialTab = if (prefs.defaultTab in visible) prefs.defaultTab else visible.firstOrNull() ?: TabKey.LOCAL
        state = state.copy(
            prefs = prefs,
            session = loadSession(),
            connections = loadConnections(),
            transfers = loadTransfers(),
            // 上次扫描得到的分类计数，先顶上，等这次全盘扫描完成再覆盖。
            categoryCounts = loadCounts(),
            tab = initialTab,
            localAccess = local.hasAccess(),
            localRoot = local.root().absolutePath
        )
        refreshLocal()
        // 冷启动自动恢复群晖会话与网络位置状态，用户不用每次重开都重新建连接。
        restoreConnectionsOnLaunch()
        if (prefs.downloadFolder.isBlank()) {
            setDownloadFolder(local.downloadsDir().absolutePath, silent = true)
        }
        // TLS: 默认始终校验系统证书。旧版本曾对「已有会话」自动放行自签名证书，
        // 等于静默关闭证书校验；现在必须由用户在登录页显式开启「允许自签名证书」。
        val allowSelfSigned = prefsStore.getBoolean("allow_self_signed", true)
        dsm.allowSelfSignedCertificates(allowSelfSigned)
        // "自动清理" really means automatic: purge expired items on every cold start.
        // days <= 0 means "永不" and must never delete anything.
        if (prefs.recycleDays > 0) launchCleanup(prefs.recycleDays)
        TransferCancellation.addListener(cancelListener)
        pruneTransferCache()
        maybeAutoCheckUpdate()
        registerNetworkCallback()
        startNetworkWatchdog()
    }

    /**
     * 监听网络切换（WiFi ↔ 移动数据）：
     * WiFi 登录后 session.endpoint 是局域网地址，切到移动数据后不可达。
     * 网络变化时清除 QC 端点缓存并自动重连，避免「切网后连接失败」。
     *
     * 注意：不能只依赖这个回调。实测 MIUI/HyperOS 上默认网络从 WiFi 换成蜂窝数据时，
     * registerDefaultNetworkCallback 有时**不会**回调 onAvailable（旧网络 onLost 之后
     * 新网络不补一次 onAvailable），于是「切网后必须重启 App 才恢复」。
     * 所以这里额外准备了两条兜底路径，见 [requestSynoRecovery] 的注释。
     */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
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

    // ---------------------------------------------------------------- 群晖自动恢复

    /**
     * 网络变化看门狗。
     *
     * 实测（HyperOS / Android 16）：「WiFi 在线 → 关掉 WiFi 走蜂窝」时，
     * registerDefaultNetworkCallback 既不给新网络 onAvailable、也不给旧网络 onLost，
     * 于是切网后群晖一直连不上，直到重启 App。既然系统不通知，就只能自己盯：
     * 每 1.5 秒读一次「当前默认网络的传输类型 + 接口名」，变了就当作切网处理。     * 只做几次系统 API 查询，开销可以忽略；同一传输类型下重播号（接口名不变）不会误判。
     */
    private var networkSignature = ""

    private fun startNetworkWatchdog() {
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
    private fun shouldRecoverOnNetworkChange(): Boolean {
        val endpoint = state.session?.endpoint ?: return false
        if (endpointLooksPrivate(endpoint)) return true
        return endpoint.contains("quickconnect", ignoreCase = true) && onLanTransport()
    }

    /** 当前是否接在 WiFi / 以太网上。 */
    private fun onLanTransport(): Boolean = runCatching {
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    /** 传输类型 + 接口名：WiFi→蜂窝、WiFi 换 AP、插拔网线都会变。 */
    private fun currentNetworkSignature(): String = runCatching {
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

    /** 正在进行的自动恢复任务：同一时刻只允许一个，避免几条路径同时重登。 */
    private var recoveryJob: kotlinx.coroutines.Job? = null
    private var lastRecoveryAt = 0L

    /**
     * 群晖「断线自愈」。
     *
     * 三条触发路径共用这里：
     * 1. 网络切换回调（[registerNetworkCallback]）；
     * 2. 任何一次群晖请求失败（[refreshSyno] / 打开共享 / 读取目录）；
     * 3. App 回到前台时发现「endpoint 是内网地址，但当前不在 WiFi / 以太网上」
     *    —— 这一条最关键：它不依赖系统回调，用户切网后一打开 App 就会被纠正。
     */
    fun requestSynoRecovery(reason: String, force: Boolean = false) {
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

    // ---------------------------------------------------------------- 连接超时提示

    /** 正在进行的目录请求：切网时取消，避免旧 socket 卡到超时才返回。 */
    private var browserLoadJob: kotlinx.coroutines.Job? = null

    /**
     * 当前这一轮下拉刷新的编号。
     *
     * 旧实现用 `state.refreshing` 同时当「防重入锁」和「转圈开关」，两个方向都会出问题：
     * 忘了收圈 → 后续下拉被 `if (state.refreshing) return` 全部吞掉（点了没反应）；
     * 别人擅自收圈 → 正在转的图标突然消失（看着像卡了一下）。
     * 改成令牌后：谁开的圈谁收，迟到的回调收不掉新一轮。
     */
    private var refreshToken = 0

    /** 下拉刷新的硬兜底：任何路径漏收圈，到点一定收，图标不会永久停住。 */
    private var refreshTimeoutJob: kotlinx.coroutines.Job? = null

    /** 本轮下拉的起始时刻，用来算「转圈最短显示时长」。 */
    private var pullStartedAt = 0L

    /**
     * 一轮下拉至少「转」这么久才允许收圈。
     *
     * 这不是审美问题，是那个「刷新完箭头卡在标题栏上方」的直接对策：本地目录刷新
     * （`quickSnapshot` 只 readdir 一层）实测 9ms 就结束，`beginPull()` 把
     * `refreshing` 置 true 和 `endPull()` 置回 false 全落在**同一帧**里，Compose 从头到尾
     * 只组合过 `false` 这一种值。Material3 的下拉指示器靠「观测到 isRefreshing 由 true
     * 变 false」去调 `animateToHidden()`，它没得可观测，于是位置永久停在阈值 1.0：
     * 圆圈加箭头就明明白白留在页面顶部（用户反复反馈的那个现象）。
     *
     * 只要让 true 这个状态在状态流里真实存在过 450ms，Compose 就一定会组合一次 true、
     * 再组合一次 false，M3 自己那条收起动画就会正常跑起来。网络慢的路径本来就超过
     * 450ms，等于不受影响；本地快刷则多了不到半秒的转圈，看起来反而更自然。
     */
    private val minPullVisibleMs = 450L

    /** 网络首页的探活任务：新一轮下拉先取消上一轮，避免几路并发互相覆盖结果。 */
    private var netProbeJob: kotlinx.coroutines.Job? = null

    /** 1 秒超时提示的定时器：每次发起网络位置读取重新计时。 */
    private var connectHintJob: kotlinx.coroutines.Job? = null

    /** 1 秒：网络位置超过这个时间还没结果就当作「连接超时」。 */
    private val connectHintMs = 1_000L

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
    private fun armNetTimeoutHint(location: String) {
        connectHintJob?.cancel()
        // 手机根本没网时不必等满 1 秒：结论在点下去那一刻就已经确定。
        // 让用户先看 1 秒转圈再读到「连接超时」，等于把一个已知答案又拖了一遍。
        if (offlineNotice() != null) {
            android.util.Log.w("MFB-NET", "offline at $location")
            state = state.copy(connectHint = true)
            return
        }
        connectHintJob = viewModelScope.launch {
            kotlinx.coroutines.delay(connectHintMs)
            if (!state.loading) return@launch
            android.util.Log.w("MFB-NET", "net slow: no result in ${connectHintMs}ms at $location")
            state = state.copy(connectHint = true)
        }
    }

    private fun cancelTimeoutHint() {
        connectHintJob?.cancel()
        connectHintJob = null
    }

    /** 切网后 20 秒内，失败基本都能归因于「旧 socket 失效 / 新通道未就绪」。 */
    private fun recentNetworkChange(windowMs: Long = 20_000L): Boolean =
        networkChangedAt > 0L && System.currentTimeMillis() - networkChangedAt < windowMs

    /**
     * 切网时把在途的目录请求撤掉。
     *
     * 旧 socket 指向已经离开的局域网，不撤会一直卡到超时。群晖页面随后由
     * [autoReloadAfterSwitch] 静默重读，真恢复不了才挂「网络已变化」提示。
     */
    private fun notifyNetworkChanged() {
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

    fun dismissNetworkHint() { state = state.copy(connectHint = false) }

    /**
     * 设备完全没联网时的一句话；有网返回 null。
     *
     * 界面用它把提示条从「连接超时，请检查网络」换成真正的原因——手机没网时
     * 那句「请检查网络」会把用户往错的方向带：网络本身没问题，是根本没连上。
     */
    fun offlineNotice(): String? = offlineNetworkMessage()

    /**
     * 页面常驻的设备状态说明，只用于「告诉用户现在什么情况」，**不参与是否发请求**。
     *
     * 飞行模式下把 WiFi 单独打开时 activeNetwork 还在，内网 NAS 依然可达；
     * 这时 [offlineNotice] 必须返回 null（否则会把合法的内网请求拦掉），
     * 但界面又必须显示「飞行模式已开启」，否则用户只看到过期的在线状态与超时。
     */
    fun networkNotice(): String? = networkStatusNotice()

    /** 当前是否完全没有网络（列表提示条、连接入口据此决定要不要提前拦下）。 */
    fun deviceHasNoNetwork(): Boolean = NetStatus.current() == DeviceNet.OFFLINE

    // ---------------------------------------------------------------- 名称筛选

    /** 设置当前页面的名称筛选词（空串 = 取消筛选）。 */
    fun setNameFilter(text: String) {
        val q = text.trim()
        if (q == state.nameFilter) return
        state = state.copy(nameFilter = q)
    }

    fun clearNameFilter() {
        if (state.nameFilter.isNotEmpty()) state = state.copy(nameFilter = "")
    }

    /** 当前筛选词；界面直接读 state.nameFilter 过滤，这里只供需要判定的逻辑用。 */
    fun matchesFilter(name: String): Boolean = nameMatchesFilter(name, state.nameFilter)

    /**
     * 用户操作时主动比对一次网络指纹，发现换网就地处理。
     *
     * 系统回调在 MIUI / HyperOS 上经常迟到，甚至 WiFi 切蜂窝时两条回调都不来；
     * 等回调意味着用户先对着空白页干等。点目录 / 点刷新这一刻就能查，成本只有两个系统 API。
     */
    private fun detectNetworkSwitchNow(): Boolean {
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
    private fun switchFailureMessage(switched: Boolean, other: String): String? = when {
        !switched -> other
        state.browser == null -> "网络已变化，请稍后重试"
        else -> null
    }

    /**
     * 群晖通道修不好时该说什么。
     * 「用内网 IP 登录、人已经离开该局域网」是最常见的一种，此时任何重试都不会成功，
     * 只能直接告诉用户两条可行路径，而不是让他反复点刷新。
     */
    private fun synoUnreachableMessage(): String {
        val endpoint = state.session?.endpoint.orEmpty()
        return if (hostLooksPrivate(endpoint) && !onLanTransport()) {
            "不在 NAS 所在局域网，请连同一 WiFi 或用 QuickConnect"
        } else {
            "群晖连接尚未恢复，请稍后重试"
        }
    }

    /** 超时提示条上的「重试」：按当前页面的上下文重新拉一次目录内容。 */
    fun reloadCurrentFolder() {
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

    /** 切网后自动重读当前目录的任务：同一时刻只留最后一次，避免连续切网叠加请求。 */
    private var autoReloadJob: kotlinx.coroutines.Job? = null

    /** 最近一次「真的读回列表」的目录位置。切网自动恢复用它判断是否已经成功。 */
    private var lastLoadedLocation: String = ""

    /**
     * 切网后自动把当前远端目录读回来（静默，不提示「网络已切换」）。
     *
     * 新网络刚起来时拨号 / IP 可能还没就绪，先等 0.6 秒试一次，失败再等 2 秒试一次。
     * 两次都失败才挂「网络已变化，请刷新后重试」；读回成功则提示自动撤掉，
     * 正常切网用户基本看不到任何东西。
     *
     * 只在远端页面（群晖 / 网络位置）触发：本地目录与网络无关，重读纯属浪费。
     */
    private fun autoReloadAfterSwitch(reason: String) {
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
    private fun willRepairChannel(): Boolean {
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
    private suspend fun repairChannelIfNeeded(): Boolean {
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
    private fun loadFolder(location: String, successMessage: String? = null) {
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
    private fun onNetFailure(connection: NetworkConnection, error: Throwable?) {
        markConnectionOffline(connection.id)
        state = state.copy(
            loading = false,
            connectHint = false,
            message = friendlyNetError(error, "无法连接该服务器", host = connection.host)
        )
    }

    /** 读目录成功说明这条连接当前可用：把卡片状态同步回在线。 */
    private fun markConnectionOnline(id: String) {
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
    private fun markConnectionOffline(id: String) {
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
    private fun loadNetworkFolder(
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
    fun healSynoChannelOnForeground() {
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
    private fun endpointLooksPrivate(endpoint: String): Boolean = hostLooksPrivate(endpoint)

    /** 当前是否「只有」蜂窝网络（没有 WiFi / 以太网可用）。 */
    private fun onCellularTransport(): Boolean = runCatching {
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
    private fun pruneTransferCache() {
        val days = state.prefs.cacheKeepDays
        if (days <= 0) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { pruneCacheBlocking(days, false) } }
    }

    /** 真正删缓存的实现：返回释放的字节数。all = true 表示不看到期时间、全清。 */
    private suspend fun pruneCacheBlocking(days: Int, all: Boolean): Long = withContext(Dispatchers.IO) {
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
    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
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

    fun setCacheKeepDays(days: Int) {
        state = state.copy(
            prefs = state.prefs.copy(cacheKeepDays = days.coerceAtLeast(0)),
            message = if (days <= 0) "缓存不再自动清理" else "缓存保留 $days 天，过期自动清理"
        )
        persist()
        pruneTransferCache()
    }

    /** 手动清理缓存：立刻清空，不受保留天数限制。 */
    fun cleanCacheNow() {
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
    fun refreshCacheSize() {
        viewModelScope.launch { state = state.copy(cacheBytes = cacheSizeBytes()) }
    }

    override fun onCleared() {
        TransferCancellation.removeListener(cancelListener)
        runCatching {
            val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        }
        super.onCleared()
    }

    // ---------------------------------------------------------------- prefs

    private fun loadPrefs(): AppPrefs {
        val base = AppPrefs()
        val mode = runCatching { ThemeMode.valueOf(prefsStore.getString("theme_mode", "SYSTEM")!!) }
            .getOrDefault(ThemeMode.SYSTEM)
        val accent = prefsStore.getLong("theme_accent", base.theme.accent)
        val dynamic = prefsStore.getBoolean("theme_dynamic", base.theme.dynamic)
        val iconFollowsTheme = prefsStore.getBoolean("theme_icon_follows", false)
        val order = prefsStore.getString("tab_order", null)
            ?.split(",")
            ?.mapNotNull { runCatching { TabKey.valueOf(it) }.getOrNull() }
            ?.filter { it in TabKey.entries }
            ?.distinct()
            ?.let { stored -> stored + TabKey.entries.filterNot { it in stored } }
            ?: base.tabOrder
        val modules = prefsStore.getString("module_order", null)
            ?.split(",")
            // local:recent / syno:recent 两个模块已下线（最近只留在文件分类里），
            // 老配置里可能还存着这两个键，加载时直接滤掉。
            ?.filter { it.isNotBlank() && it != "local:recent" && it != "syno:recent" }
            ?.let { stored -> stored + base.moduleOrder.filterNot { it in stored } }
            ?: base.moduleOrder
        val hidden = prefsStore.getStringSet("hidden_modules", emptySet()).orEmpty().toSet()
        val views = mutableMapOf<String, String>()
        runCatching {
            JSONObject(prefsStore.getString("section_views", "{}") ?: "{}").let { json ->
                json.keys().forEach { key -> views[key] = json.optString(key) }
            }
        }
        val tabViews = mutableMapOf("local" to "grid", "syno" to "grid", "net" to "grid")
        runCatching {
            JSONObject(prefsStore.getString("tab_views", "{}") ?: "{}").let { json ->
                json.keys().forEach { key ->
                    val value = json.optString(key)
                    if (value == "grid" || value == "list") tabViews[key] = value
                }
            }
        }
        return base.copy(
            defaultTab = runCatching { TabKey.valueOf(prefsStore.getString("default_tab", "LOCAL")!!) }
                .getOrDefault(base.defaultTab),
            showLocal = prefsStore.getBoolean("show_local", true),
            showSyno = prefsStore.getBoolean("show_syno", true),
            showNet = prefsStore.getBoolean("show_net", true),
            tabOrder = order,
            downloadFolder = prefsStore.getString("download_folder", "") ?: "",
            theme = ThemePrefs(mode, accent, dynamic, iconFollowsTheme),
            moduleOrder = modules,
            hiddenModules = hidden,
            sectionViews = base.sectionViews + views,
            viewMode = prefsStore.getString("view_mode", "grid") ?: "grid",
            tabViews = tabViews,
            favorites = prefsStore.getStringSet("favorites", emptySet()).orEmpty().toSet(),
            sortKey = prefsStore.getString("sort_key", "name") ?: "name",
            sortDir = prefsStore.getString("sort_dir", "asc") ?: "asc",
            tabSorts = buildMap {
                for (tab in listOf("local", "syno", "net")) {
                    prefsStore.getString("${tab}_sort_key", null)?.let { put("${tab}_key", it) }
                    prefsStore.getString("${tab}_sort_dir", null)?.let { put("${tab}_dir", it) }
                }
            },
            volumeId = prefsStore.getString("volume_id", "") ?: "",
            recycleDays = prefsStore.getInt("recycle_days", 30),
            cacheKeepDays = prefsStore.getInt("cache_keep_days", 7),
            categoryOrder = prefsStore.getString("category_order", null)
                ?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
            openDefaults = runCatching {
                val raw = prefsStore.getString("open_defaults", null) ?: return@runCatching emptyMap()
                val json = JSONObject(raw)
                buildMap {
                    json.keys().forEach { key ->
                        val pkg = json.optString(key)
                        if (pkg.isNotBlank()) put(key, pkg)
                    }
                }
            }.getOrDefault(emptyMap())
        )
    }

    private fun loadSession(): Session? {
        val server = prefsStore.getString("session_server", null) ?: return null
        return Session(
            server = server,
            account = SecretVault.decrypt(prefsStore.getString("session_account", "") ?: ""),
            name = prefsStore.getString("session_name", "") ?: "",
            nas = prefsStore.getString("session_nas", "") ?: "",
            mode = prefsStore.getString("session_mode", "") ?: "",
            sid = SecretVault.decrypt(prefsStore.getString("session_sid", "") ?: ""),
            synoToken = SecretVault.decrypt(prefsStore.getString("session_syno_token", "") ?: ""),
            https = prefsStore.getBoolean("session_https", true),
            endpoint = prefsStore.getString("session_endpoint", null)
        )
    }

    private fun loadConnections(): List<NetworkConnection> {
        val raw = SecretVault.decrypt(prefsStore.getString("connections", "[]") ?: "[]")
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                NetworkConnection(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    protocol = o.optString("protocol"),
                    host = o.optString("host"),
                    port = o.optInt("port"),
                    path = o.optString("path", "/"),
                    online = o.optBoolean("online", false),
                    latency = o.optInt("latency", 0),
                    username = o.optString("username", ""),
                    password = o.optString("password", ""),
                    secure = o.optBoolean("secure", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun loadTransfers(): List<TransferTask> {
        val raw = prefsStore.getString("transfer_history", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val status = runCatching { TransferStatus.valueOf(o.optString("status", "SUCCESS")) }
                    .getOrDefault(TransferStatus.SUCCESS)
                if (status == TransferStatus.RUNNING) return@mapNotNull null
                TransferTask(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    mode = runCatching { TransferMode.valueOf(o.optString("mode", "DOWNLOAD")) }
                        .getOrDefault(TransferMode.DOWNLOAD),
                    title = o.optString("title", ""),
                    detail = o.optString("detail", ""),
                    total = o.optInt("total", 0),
                    completed = o.optInt("completed", 0),
                    totalBytes = o.optLong("totalBytes", 0L),
                    completedBytes = o.optLong("completedBytes", 0L),
                    status = status,
                    startedAt = o.optLong("startedAt", 0L),
                    finishedAt = o.optLong("finishedAt", 0L).takeIf { it > 0L },
                    error = o.optString("error", "").ifBlank { null },
                    from = o.optString("from", ""),
                    to = o.optString("to", ""),
                    target = o.optString("target", ""),
                    targetCtx = o.optString("targetCtx", ""),
                    targetNet = o.optString("targetNet", ""),
                    sourceCtx = o.optString("sourceCtx", ""),
                    sourceNet = o.optString("sourceNet", ""),
                    sources = o.optJSONArray("sources").toStringList(),
                    uploadedTargets = o.optJSONArray("uploadedTargets").toStringList()
                )
            }
        }.getOrDefault(emptyList()).take(30)
    }

    private fun persistTransfers() {
        val arr = JSONArray()
        // 静默任务（同端复制移动、在线打开缓存）不写记录、重启后也不该出现。
        state.transfers.filterNot { it.quiet }.take(30).forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("mode", t.mode.name)
                    .put("title", t.title)
                    .put("detail", t.detail)
                    .put("total", t.total)
                    .put("completed", t.completed)
                    .put("totalBytes", t.totalBytes)
                    .put("completedBytes", t.completedBytes)
                    .put("status", t.status.name)
                    .put("startedAt", t.startedAt)
                    .put("finishedAt", t.finishedAt ?: 0L)
                    .put("error", t.error ?: "")
                    .put("from", t.from)
                    .put("to", t.to)
                    .put("target", t.target)
                    .put("targetCtx", t.targetCtx)
                    .put("targetNet", t.targetNet)
                    .put("sourceCtx", t.sourceCtx)
                    .put("sourceNet", t.sourceNet)
                    .put("sources", JSONArray(t.sources))
                    .put("uploadedTargets", JSONArray(t.uploadedTargets))
            )
        }
        prefsStore.edit().putString("transfer_history", arr.toString()).apply()
    }

    private fun updateTransfer(id: String, transform: (TransferTask) -> TransferTask) {
        state = state.copy(
            transfers = state.transfers.map { if (it.id == id) transform(it) else it }
        )
    }

    // 分类计数落盘：全盘扫描要十几二十秒，冷启动先用上次的数字，
    // 否则分类卡片会显示一串「0 项」，看起来像没读到文件。
    private fun loadCounts(): Map<FileKind, Int> = runCatching {
        val raw = prefsStore.getString("category_counts", null) ?: return emptyMap()
        val json = JSONObject(raw)
        FileKind.entries.mapNotNull { kind ->
            val value = json.optInt(kind.name, -1)
            if (value >= 0) kind to value else null
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun saveCounts(counts: Map<FileKind, Int>) {
        runCatching {
            val json = JSONObject()
            counts.forEach { (kind, value) -> json.put(kind.name, value) }
            prefsStore.edit().putString("category_counts", json.toString()).apply()
        }
    }

    private fun persist() {        val prefs = state.prefs
        val editor = prefsStore.edit()
            .putString("theme_mode", prefs.theme.mode.name)
            .putLong("theme_accent", prefs.theme.accent)
            .putBoolean("theme_dynamic", prefs.theme.dynamic)
            .putBoolean("theme_icon_follows", prefs.theme.iconFollowsTheme)
            .putString("tab_order", prefs.tabOrder.joinToString(",") { it.name })
            .putBoolean("show_local", prefs.showLocal)
            .putBoolean("show_syno", prefs.showSyno)
            .putBoolean("show_net", prefs.showNet)
            .putString("default_tab", prefs.defaultTab.name)
            .putString("download_folder", prefs.downloadFolder)
            .putString("module_order", prefs.moduleOrder.joinToString(","))
            .putStringSet("hidden_modules", prefs.hiddenModules)
            .putString("volume_id", prefs.volumeId)
            .putString("section_views", JSONObject().apply {
                prefs.sectionViews.forEach { (key, value) -> put(key, value) }
            }.toString())
            .putString("sort_key", prefs.sortKey)
            .putString("sort_dir", prefs.sortDir)
            .putString("local_sort_key", prefs.tabSorts["local_key"] ?: prefs.sortKey)
            .putString("local_sort_dir", prefs.tabSorts["local_dir"] ?: prefs.sortDir)
            .putString("syno_sort_key", prefs.tabSorts["syno_key"] ?: prefs.sortKey)
            .putString("syno_sort_dir", prefs.tabSorts["syno_dir"] ?: prefs.sortDir)
            .putString("net_sort_key", prefs.tabSorts["net_key"] ?: prefs.sortKey)
            .putString("net_sort_dir", prefs.tabSorts["net_dir"] ?: prefs.sortDir)
            .putString("view_mode", prefs.viewMode)
            .putString("tab_views", JSONObject().apply {
                prefs.tabViews.forEach { (key, value) -> put(key, value) }
            }.toString())
            .putStringSet("favorites", prefs.favorites)
            .putInt("recycle_days", prefs.recycleDays)
            .putInt("cache_keep_days", prefs.cacheKeepDays)
            .putString("category_order", prefs.categoryOrder.joinToString(","))
            .putString("open_defaults", JSONObject().apply {
                prefs.openDefaults.forEach { (key, value) -> put(key, value) }
            }.toString())
        // If encryption fails we keep the previous stored value instead of
        // writing plaintext or wiping the user's saved connections.
        val encryptedConnections = SecretVault.encrypt(connectionsJson())
        if (encryptedConnections.isNotBlank()) editor.putString("connections", encryptedConnections)
        val session = state.session
        if (rememberSession && session != null) {
            val encAccount = SecretVault.encrypt(session.account)
            val encSid = SecretVault.encrypt(session.sid)
            if (encAccount.isNotBlank() && encSid.isNotBlank()) {
                editor.putString("session_server", session.server)
                    .putString("session_account", encAccount)
                    .putString("session_name", session.name)
                    .putString("session_nas", session.nas)
                    .putString("session_mode", session.mode)
                    .putString("session_sid", encSid)
                    .putString("session_syno_token", SecretVault.encrypt(session.synoToken))
                    .putString("session_endpoint", session.endpoint)
                    .putBoolean("session_https", session.https)
            }
        } else {
            editor.remove("session_server").remove("session_account").remove("session_name")
                .remove("session_nas").remove("session_mode").remove("session_sid")
                .remove("session_syno_token")
                .remove("session_https").remove("session_endpoint")
        }
        editor.apply()
    }

    private fun connectionsJson(): String {
        val arr = JSONArray()
        state.connections.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id).put("name", c.name).put("protocol", c.protocol)
                    .put("host", c.host).put("port", c.port).put("path", c.path)
                    .put("online", c.online).put("latency", c.latency)
                    .put("username", c.username).put("password", c.password)
                    .put("secure", c.secure)
            )
        }
        return arr.toString()
    }

    // ---------------------------------------------------------------- tabs / screens

    /** Each tab keeps its own browsing stack, so "leave and come back" restores exactly what was open. */
    private val tabBrowsers = mutableMapOf<TabKey, Pair<BrowserState?, List<BrowserState>>>()

    fun selectTab(tab: TabKey) {
        if (tab !in visibleTabs() || tab == state.tab) return
        tabBrowsers[state.tab] = state.browser to state.browserStack
        val saved = tabBrowsers[tab]
        state = state.copy(
            tab = tab,
            browser = saved?.first,
            browserStack = saved?.second.orEmpty(),
            selected = emptySet(),
            selectionActive = false,
            homeSelected = emptySet(),
            homeSelecting = false,
            search = null,
            // 换底栏 = 换当前位置，筛选词不沿用。
            nameFilter = "",
            // 切换底栏也是进入新页面，统一动画方向不能沿用上一次的脏值。
            navDir = 1
        )
    }

    fun swipeTab(direction: Int) {
        val tabs = visibleTabs()
        if (tabs.size < 2) return
        val index = tabs.indexOf(state.tab).coerceAtLeast(0)
        val next = (index + direction).coerceIn(0, tabs.lastIndex)
        if (next != index) selectTab(tabs[next])
    }

    /**
     * 侧滑返回的底栏规则（清单 12）：第二、三栏首页返回第一栏首页（按设置里的底栏顺序），
     * 已在第一栏首页时返回 false，交还系统默认（退出应用）。
     */
    fun backToFirstTab(): Boolean {
        val first = visibleTabs().firstOrNull() ?: return false
        if (state.tab == first) return false
        selectTab(first)
        return true
    }

    fun visibleTabs(prefs: AppPrefs = state.prefs): List<TabKey> = prefs.tabOrder.filter {
        when (it) {
            TabKey.LOCAL -> prefs.showLocal
            TabKey.SYNO -> prefs.showSyno
            TabKey.NET -> prefs.showNet
        }
    }

    fun setDefaultTab(tab: TabKey) {
        val next = state.prefs.copy(defaultTab = tab)
        state = state.copy(prefs = next)
        persist()
    }

    fun toggleTab(tab: TabKey) {
        val visible = visibleTabs()
        if (tab in visible && visible.size == 1) {
            state = state.copy(message = "至少保留一个底栏入口")
            return
        }
        val prefs = state.prefs
        val next = when (tab) {
            TabKey.LOCAL -> prefs.copy(showLocal = !prefs.showLocal)
            TabKey.SYNO -> prefs.copy(showSyno = !prefs.showSyno)
            TabKey.NET -> prefs.copy(showNet = !prefs.showNet)
        }
        val nextVisible = visibleTabs(next)
        val fallback = if (next.defaultTab in nextVisible) next.defaultTab else nextVisible.firstOrNull() ?: TabKey.LOCAL
        state = state.copy(prefs = next, tab = if (state.tab in nextVisible) state.tab else fallback)
        persist()
    }

    fun moveTab(tab: TabKey, delta: Int) {
        val order = state.prefs.tabOrder.toMutableList()
        val index = order.indexOf(tab)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (target == index) return
        order.removeAt(index)
        order.add(target, tab)
        state = state.copy(prefs = state.prefs.copy(tabOrder = order))
        persist()
    }

    /** 本地文件分类排序（清单 4）：拖拽换位，按名字列表持久化。 */
    fun moveCategory(name: String, delta: Int) {
        val base = listOf("图片", "视频", "文档", "音频", "安装包", "压缩包", "最近", "收藏")
        val order = state.prefs.categoryOrder.ifEmpty { base }.toMutableList()
        val index = order.indexOf(name)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (target == index) return
        order.removeAt(index)
        order.add(target, name)
        state = state.copy(prefs = state.prefs.copy(categoryOrder = order))
        persist()
    }

    /** Navigation follows "how you got here, that's how you go back". */
    private fun openScreen(next: Screen) {
        if (next == state.screen) return
        val origin = state.screen
        state = state.copy(screen = next, screenStack = state.screenStack + origin, navDir = 1)
    }

    fun openSettings() { openScreen(Screen.SETTINGS) }
    fun openAbout() { openScreen(Screen.ABOUT) }

    /** 设置 → 主题与外观：外观模式、主题配色、动态取色都收到这一页。 */
    fun openAppearance() { openScreen(Screen.APPEARANCE) }

    fun closeScreen() {
        val stack = state.screenStack
        val target = stack.lastOrNull() ?: Screen.MAIN
        state = state.copy(screen = target, screenStack = stack.dropLast(1), navDir = -1)
    }

    fun setThemeMode(mode: ThemeMode) {
        state = state.copy(prefs = state.prefs.copy(theme = state.prefs.theme.copy(mode = mode)))
        persist()
    }

    /**
     * 选一个主题色。
     *
     * 顺手关掉动态取色：用户主动挑颜色，就是要这一套配色，不能还被壁纸色压着。
     */
    fun setAccent(color: Long) {
        state = state.copy(
            prefs = state.prefs.copy(
                theme = state.prefs.theme.copy(accent = color, dynamic = false)
            )
        )
        persist()
    }

    fun setDynamicColor(on: Boolean) {
        if (state.prefs.theme.dynamic == on) return
        state = state.copy(prefs = state.prefs.copy(theme = state.prefs.theme.copy(dynamic = on)))
        persist()
    }

    /**
     * 应用图标是否跟随主题色。关闭时用原始蓝色图标，开启后应用内图标按主色
     * 实时染色、桌面启动图标切到对应色相档；桌面组件的切换由 AppRoot 的
     * syncAppIcon 在拿到本次主色后统一执行，避免这里拿到的是旧的 accent。
     */
    fun setIconFollowsTheme(on: Boolean) {
        if (state.prefs.theme.iconFollowsTheme == on) return
        state = state.copy(prefs = state.prefs.copy(theme = state.prefs.theme.copy(iconFollowsTheme = on)))
        persist()
    }

    /**
     * 把桌面启动图标的目标档位同步到「当前真正在用的主色」——动态取色时主色
     * 来自壁纸，不是 prefs.accent，因此只能由主题确定后的 Compose 层回调进来。
     * 这里只登记目标，真正切换组件由 MainActivity.onStop 在退到后台时执行，
     * 避免禁用当前正在运行的启动组件把设置页直接踢回桌面。
     */
    fun syncAppIcon(primary: Int) {
        AppIconSwitcher.request(getApplication(), state.prefs.theme.iconFollowsTheme, primary)
    }

    // ---------------------------------------------------------------- login / session

    fun login(server: String, account: String, password: String, https: Boolean, remember: Boolean) {
        if (server.isBlank() || account.isBlank() || password.isBlank()) {
            state = state.copy(message = "请填写服务器、账号和密码")
            return
        }
        rememberSession = remember
        state = state.copy(loading = true, message = null, loginError = null)
        viewModelScope.launch {
            val parsed = dsm.parseServer(server, https)
            // QuickConnect 要分内网 / 外网通道：内网只走局域网直连，
            // 外网先试端口映射 / DDNS，全部不通才用官方中转。
            val candidates = if (parsed.quickConnect) {
                dsm.quickConnectCandidates(parsed.host)
            } else {
                listOf(QuickConnectEndpoint(parsed.baseUrl, parsed.display))
            }
            if (candidates.isEmpty()) {
                val reason = "未找到该 QuickConnect ID，请检查 ID 或网络"
                state = state.copy(loading = false, message = reason, loginError = reason)
                return@launch
            }
            var lastError = "登录失败"
            val errors = mutableListOf<String>()
            for (endpoint in candidates) {
                val provisional = Session(
                    server = parsed.display,
                    account = account,
                    name = account,
                    nas = "群晖 NAS",
                    mode = if (parsed.quickConnect) endpoint.label else "局域网直连",
                    https = parsed.https,
                    endpoint = endpoint.baseUrl
                )
                val result = dsm.login(provisional, password)
                if (result.success && result.sid != null) {
                    // 缓存到内存：切网后自动重连用。没勾「记住密码」也能无感恢复。
                    cachedPassword = password
                    val session = provisional.copy(
                        sid = result.sid,
                        synoToken = result.synoToken.orEmpty(),
                        nas = "DSM 已连接"
                    )
                    val saved = if (remember) saveLogin(server, account, password) else {
                        clearSavedLogin()
                        true
                    }
                    state = state.copy(
                        session = session,
                        connectionEditing = false,
                        loading = false,
                        tab = TabKey.SYNO,
                        editSynoOpen = false,
                        loginError = null,
                        message = (if (parsed.quickConnect) "已通过 QuickConnect 连接 ${parsed.display} · ${endpoint.label}"
                        else "已连接 ${parsed.display}") +
                            if (!saved) "（凭据加密失败，未保存密码）" else ""
                    )
                    rememberSession = remember
                    // 手动登录成功：解除「退出后不自动登录」的抑制。
                    autoLoginSuppressed = false
                    prefsStore.edit().putBoolean("auto_login_suppressed", false).apply()
                    persist()
                    refreshSyno()
                    return@launch
                }
                lastError = result.error ?: "登录失败"
                errors += lastError
                // 账号/密码/两步验证这类鉴权错误，换通道结果一样，立刻给结论，
                // 不再把剩下的候选挨个试一遍（数据网络下能省掉十几秒）。
                if (dsm.lastErrorCode in 400..410) break
            }
            val reason = summarizeLoginErrors(errors, candidates.size)
            state = state.copy(loading = false, message = reason, loginError = reason)
        }
    }

    fun describeServer(server: String, https: Boolean): String = dsm.describeServer(server, https)

    fun allowSelfSigned(): Boolean = prefsStore.getBoolean("allow_self_signed", true)

    fun setAllowSelfSigned(enabled: Boolean) {
        prefsStore.edit().putBoolean("allow_self_signed", enabled).apply()
        dsm.allowSelfSignedCertificates(enabled)
    }

    fun savedLogin(): Triple<String, String, String> = Triple(
        prefsStore.getString("login_server", "") ?: "",
        prefsStore.getString("login_account", "") ?: "",
        SecretVault.decrypt(prefsStore.getString("login_password", "") ?: "")
    )

    /** 返回是否安全保存成功。加密失败时绝不回退明文，也不留下空的密码项。 */
    private fun saveLogin(server: String, account: String, password: String): Boolean {
        val encrypted = SecretVault.encrypt(password)
        if (encrypted.isBlank()) {
            clearSavedLogin()
            return false
        }
        prefsStore.edit()
            .putString("login_server", server)
            .putString("login_account", account)
            .putString("login_password", encrypted)
            .apply()
        return true
    }

    private fun clearSavedLogin() {
        prefsStore.edit().remove("login_password").apply()
    }

    /** "去登录" from a secondary page: keep the origin so back returns there. */
    fun goToLogin() {
        val origin = state.screen
        state = state.copy(
            screen = Screen.MAIN,
            screenStack = if (origin == Screen.MAIN) state.screenStack else state.screenStack + origin,
            tab = TabKey.SYNO
        )
    }

    fun openEditSyno() {
        // 只切到登录页让用户改参数，不清 session：登录成功才替换，失败也不影响正在用的连接。
        state = state.copy(connectionEditing = true)
    }

    fun cancelConnectionEditing() { state = state.copy(connectionEditing = false) }

    fun closeEditSyno() { state = state.copy(editSynoOpen = false) }

    fun logout() {
        state = state.copy(
            session = null,
            shares = emptyList(),
            volumes = emptyList(),
            browser = null,
            browserStack = emptyList(),
            screen = Screen.MAIN,
            screenStack = emptyList(),
            tab = TabKey.SYNO,
            search = null,
            editSynoOpen = false,
            message = "已退出登录"
        )
        tabBrowsers.clear()
        // 退出登录只结束会话，密码保留：下次登录表单直接预填，不用重新输一遍。
        // 但要抑制冷启动自动登录，否则「退出」等于没退。
        autoLoginSuppressed = true
        prefsStore.edit().putBoolean("auto_login_suppressed", true).apply()
        rememberSession = true
        persist()
    }

    fun editConnection(server: String, account: String, password: String = "") {
        val current = state.session ?: return
        if (server.isBlank() || account.isBlank()) {
            state = state.copy(message = "服务器和账号不能为空")
            return
        }
        if (password.isBlank()) {
            state = state.copy(message = "编辑连接需要重新输入密码")
            return
        }
        rememberSession = true
        state = state.copy(loading = true)
        viewModelScope.launch {
            val parsed = dsm.parseServer(server, current.https)
            val candidates = if (parsed.quickConnect) {
                dsm.quickConnectCandidates(parsed.host)
            } else listOf(QuickConnectEndpoint(parsed.baseUrl, parsed.display))
            if (candidates.isEmpty()) {
                val reason = "未找到该 QuickConnect ID"
                state = state.copy(loading = false, message = reason, loginError = reason)
                return@launch
            }
            var lastError = "连接失败"
            val errors = mutableListOf<String>()
            for (endpoint in candidates) {
                val provisional = current.copy(
                    server = parsed.display,
                    account = account,
                    https = parsed.https,
                    endpoint = endpoint.baseUrl
                )
                val result = dsm.login(provisional, password)
                if (result.success && result.sid != null) {
                    cachedPassword = password
                    val saved = saveLogin(server, account, password)
                    state = state.copy(
                        session = provisional.copy(sid = result.sid, synoToken = result.synoToken.orEmpty()),
                        connectionEditing = false,
                        loading = false,
                        editSynoOpen = false,
                        loginError = null,
                        message = "连接信息已更新" + if (!saved) "（凭据加密失败，未保存密码）" else ""
                    )
                    persist()
                    refreshSyno()
                    return@launch
                }
                lastError = result.error ?: "连接失败"
                errors += lastError
                if (dsm.lastErrorCode in 400..410) break
            }
            val reason = summarizeLoginErrors(errors, candidates.size)
            state = state.copy(loading = false, message = reason, loginError = reason)
        }
    }

    /**
     * 多个通道全部失败时的总结提示：指出「被拦截 / 连不上」等真实原因，
     * 并给出下一步怎么办，而不是只丢一个底层错误。
     */
    private fun summarizeLoginErrors(errors: List<String>, tried: Int): String {
        if (errors.isEmpty()) return "登录失败"
        val joined = errors.joinToString("；")
        return when {
            // 先看鉴权类结果：能走到「账号或密码错误」说明通道已经通了。
            joined.contains("账号或密码") -> "账号或密码错误"
            joined.contains("两步验证") -> "该账号开启了两步验证，请先在 DSM 关闭或使用专用账号"
            joined.contains("网页") ->
                "已尝试 $tried 个通道，返回的都是网页而非 DSM 数据：请确认填的是地址或 QuickConnect ID"
            joined.contains("超时") || joined.contains("无法连接") || joined.contains("网络") ->
                "已尝试 $tried 个通道均无法连接，请确认 NAS 在线后重试"
            else -> errors.last()
        }
    }

    /** 只清除指定那条消息；传入 null 时无条件清空（兼容旧调用）。 */
    fun consumeMessage(message: String? = null) {
        if (message == null || state.message == message) state = state.copy(message = null)
    }

    fun showMessage(message: String) { state = state.copy(message = message) }

    /** 复制到剪贴板并给成功提示（清单 9：复制路径这类操作此前完全没有反馈）。 */
    fun copyText(text: String, label: String = "内容") {
        val clipboard = getApplication<Application>()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        showMessage("复制成功")
    }

    /** 退出选择模式：所有清空 selected 的场景都必须走这里，避免残留空的选择模式。 */
    private fun exitSelection() {
        state = state.copy(
            selected = emptySet(),
            selectionActive = false,
            homeSelected = emptySet(),
            homeSelecting = false
        )
    }

    // ---------------------------------------------------------------- local data

    fun hasLocalAccess(): Boolean = local.hasAccess()

    /** Re-check access when returning from the system permission screen. */
    fun checkLocalAccess() {
        val access = local.hasAccess()
        if (access != state.localAccess) {
            state = state.copy(localAccess = access)
            if (access) refreshLocal()
        }
    }

    fun requestLocalAccess() {
        val context = getApplication<Application>()
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { state = state.copy(message = "无法打开系统授权页面") }
    }

    /**
     * 启动权限流程：先弹系统的通知权限，再处理文件权限。
     * 文件权限没有系统弹窗，直接跳系统「所有文件访问」页（不再自绘引导框）；
     * 只在安装后第一次自动跳，之后靠首页的权限横幅手动触发，避免每次启动都弹设置页。
     */
    fun runStartupPermissionFlow(onNeedNotification: () -> Unit) {
        val needNotification = android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                getApplication(), android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needNotification) {
            onNeedNotification()
            return
        }
        afterNotificationPermission()
    }

    /** 通知权限弹完后：不再自动跳系统设置页，改为在首页显示权限横幅，由用户主动点「去授权」。 */
    fun afterNotificationPermission() {
        if (local.hasAccess()) return
        // 只提示一次横幅，不再自动跳转系统授权页（用户反馈体验不好）。
        state = state.copy(message = "需要文件访问权限才能浏览本机文件，请点击首页顶部的授权提示")
    }

    private var localScanInFlight = false
    private var resumedBefore = false
    private var lastScanAt = 0L

    /**
     * 从别的应用切回来时重新扫描本地存储：用户很可能刚在浏览器 / 微信里存了文件。
     * 之前只有 [checkLocalAccess] 在权限状态变化时才刷新，新文件要重启应用才看得到。
     * 首次 resume 紧跟 init 那次扫描，跳过以免重复全盘遍历。
     */
    fun refreshLocalOnResume() {
        // 缓存按天过期，回到前台顺手清一次即可，不必等下次冷启动。
        pruneTransferCache()
        if (!resumedBefore) {
            resumedBefore = true
            return
        }
        // 全盘扫描不便宜，刚扫过就没必要再来一次。
        if (System.currentTimeMillis() - lastScanAt < 8_000) return
        refreshLocal()
    }

    private var localScanPending = false

    /**
     * 全盘扫描并写回本地列表。
     *
     * @param pullToken 由下拉刷新传进来（[beginPull] 的令牌）：扫描结束、或发现已经在扫时，
     *   只收掉这一轮自己的转圈。不传的触发（删除、还原、权限变更、回前台）完全不碰转圈，
     *   结果从页面上的进度提示看。
     */
    fun refreshLocal(pullToken: Int? = null) {
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

    /** 按分类名从最新快照重算列表；「收藏」有独立来源，不走这里。 */
    private fun filesForLocalCategory(category: String, snapshot: com.yaoahos.myfilebox.android.data.LocalSnapshot): List<FileEntry>? =
        when (category) {
            "最近" -> snapshot.files
                .filter { it.modified >= System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }
                .sortedByDescending { it.modified }
            else -> FileKind.entries
                .firstOrNull { fileKindLabel(it) == category }
                ?.let { kind -> snapshot.files.filter { it.kind == kind } }
        }

    fun openLocalCategory(title: String, items: List<FileEntry>, groupByDate: Boolean = false) {
        val sorted = sortFiles(items)
        state = state.copy(
            browser = BrowserState(
                title, "内部存储 / $title", FileContext.LOCAL, sorted,
                groupByDate = groupByDate, localCategory = title
            ),
            browserStack = emptyList(),
            selected = emptySet(), selectionActive = false,
            // 分类页绕过 pushBrowser，必须显式标为「进入」，否则动画方向沿用上一次返回。
            navDir = 1
        )
    }

    fun openLocalFolder(name: String) {
        val folder = state.localFolders.firstOrNull { it.name == name }
        if (folder == null) {
            state = state.copy(message = "文件夹不存在")
            return
        }
        openLocalFolderPath(folder.path, folder.name)
    }

    fun openStorage(path: String, title: String) {
        if (path.isBlank()) {
            state = state.copy(message = "存储位置不可用")
            return
        }
        state = state.copy(tab = TabKey.LOCAL)
        openLocalFolderPath(path, title)
    }

    private fun openLocalFolderPath(path: String, title: String) {
        pushBrowser(
            BrowserState(
                title = title,
                path = "内部存储 / $title",
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

    // ---------------------------------------------------------------- DSM / network

    private var connectionsRestored = false

    /**
     * 冷启动恢复连接，避免「每次重开都要重新创建」：
     * 群晖用记住的密码静默换一个可用 SID，网络位置重新探一次在线状态。
     */
    fun restoreConnectionsOnLaunch() {
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
    private fun silentRelogin(server: String, account: String, password: String) {
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

    /** 启动时把每个网络位置探一次，卡片直接显示在离线状态，不用一个个手动测。 */
    private fun warmConnections() {
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
    fun refreshSyno(skipRecovery: Boolean = false, retried: Boolean = false) {
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
                    "refreshSyno failed: ${failure.javaClass.simpleName} ${failure.message} " +
                        "endpoint=${session.endpoint}"
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

    fun openShare(share: ShareFolder) {
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
                    path = "群晖文件 / ${share.title}",
                    ctx = FileContext.SYNO,
                    items = sortFiles(items),
                    location = path
                )
            )
        }
    }

    fun openConnection(connection: NetworkConnection) {
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
                    path = "网络文件 / ${connection.name} / ${connection.protocol}",
                    ctx = FileContext.NET,
                    items = sortFiles(items),
                    netId = connection.id,
                    location = connection.path
                )
            )
        }
    }

    fun testConnection(connection: NetworkConnection) {
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
    fun testConnectionDraft(
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

    fun openFolder(entry: FileEntry) {
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

    private fun pushBrowser(next: BrowserState) {
        val current = state.browser
        val tab = when (next.ctx) {
            FileContext.LOCAL -> TabKey.LOCAL
            FileContext.SYNO -> TabKey.SYNO
            FileContext.NET -> TabKey.NET
        }
        state = state.copy(
            browser = next.copy(grid = viewMode(tab) == "grid"),
            browserStack = if (current != null) state.browserStack + current else state.browserStack,
            selected = emptySet(), selectionActive = false,
            // 进入新目录意味着换了「当前位置」，上一处的筛选词不能跟过来。
            nameFilter = "",
            navDir = 1
        )
    }

    fun backFromBrowser() {
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
                navDir = -1
            )
        } else {
            state = state.copy(browser = null, selected = emptySet(), selectionActive = false, navDir = -1)
        }
    }

    // ---------------------------------------------------------------- browser sort / views

    fun jumpToCrumb(index: Int) {
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
            // 面包屑往根走 = 返回动画，往深走 = 前进动画。
            navDir = if (index >= trail.size - 1) 1 else -1
        )
    }

    /** 新建文件夹；parentOverride 为空时用当前浏览目录（或本地根）。 */
    fun createFolder(name: String, parentOverride: String? = null) {
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
    fun createFile(name: String, parentOverride: String? = null) {
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
                                    folder = created.parentFile?.name.orEmpty(),
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

    fun extractArchive(file: FileEntry, destinationPath: String? = null) {
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
        state = state.copy(selected = emptySet(), selectionActive = false, localTaskLabel = "正在解压 ${file.name}…", localTaskRunning = true)
        localTaskJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { local.extractArchive(source.absolutePath, destination) }
            }
            result.onSuccess {
                reloadBrowserItems()
                refreshLocal()
                state = state.copy(localTaskLabel = "", localTaskRunning = false, message = "已解压到「${destination.name}」")
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) {
                    state = state.copy(localTaskLabel = "", localTaskRunning = false, message = "已取消解压")
                } else {
                    state = state.copy(localTaskLabel = "", localTaskRunning = false, message = "解压失败：${it.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 取消当前压缩/解压任务。 */
    fun cancelLocalTask() {
        localTaskJob?.cancel()
        localTaskJob = null
        state = state.copy(localTaskLabel = "", localTaskRunning = false, message = "已取消操作")
    }

    /** 每个底栏独立排序：传入 tabKey 后只改该 Tab 的排序，不影响其它底栏。 */
    fun setSort(key: String, dir: String, tabKey: String = "local") {
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
    fun sortKeyFor(tabKey: String): String = state.prefs.tabSorts["${tabKey}_key"] ?: state.prefs.sortKey
    fun sortDirFor(tabKey: String): String = state.prefs.tabSorts["${tabKey}_dir"] ?: state.prefs.sortDir

    fun viewMode(tab: TabKey): String = state.prefs.tabViews[tab.name.lowercase()] ?: "grid"

    fun viewModeFor(ctx: FileContext): String = viewMode(
        when (ctx) {
            FileContext.LOCAL -> TabKey.LOCAL
            FileContext.SYNO -> TabKey.SYNO
            FileContext.NET -> TabKey.NET
        }
    )

    fun toggleViewFor(ctx: FileContext) {
        toggleView(
            when (ctx) {
                FileContext.LOCAL -> TabKey.LOCAL
                FileContext.SYNO -> TabKey.SYNO
                FileContext.NET -> TabKey.NET
            }
        )
    }

    fun toggleView(tab: TabKey) {
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

    fun toggleViewMode() {
        val current = state.browser?.ctx?.let {
            when (it) {
                FileContext.LOCAL -> TabKey.LOCAL
                FileContext.SYNO -> TabKey.SYNO
                FileContext.NET -> TabKey.NET
            }
        } ?: state.tab
        toggleView(current)
    }

    fun toggleSectionView(key: String) {
        val tab = when {
            key.startsWith("local") -> TabKey.LOCAL
            key.startsWith("syno") -> TabKey.SYNO
            else -> TabKey.NET
        }
        toggleView(tab)
    }

    fun setGrid(grid: Boolean) {
        val tab = state.browser?.ctx?.let {
            when (it) {
                FileContext.LOCAL -> TabKey.LOCAL
                FileContext.SYNO -> TabKey.SYNO
                FileContext.NET -> TabKey.NET
            }
        } ?: state.tab
        setViewMode(tab, if (grid) "grid" else "list")
    }

    fun setViewMode(tab: TabKey, mode: String) {
        val map = state.prefs.tabViews.toMutableMap()
        map[tab.name.lowercase()] = mode
        state = state.copy(
            prefs = state.prefs.copy(viewMode = mode, tabViews = map),
            browser = state.browser?.copy(grid = mode == "grid")
        )
        persist()
    }

    fun favoriteKey(file: FileEntry): String = if (file.path.isNotBlank()) file.path else file.id

    fun isFavorite(file: FileEntry): Boolean = favoriteKey(file) in state.prefs.favorites

    fun toggleFavorite(file: FileEntry) {
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

    fun favoriteFolderKey(folder: FolderCard): String =
        "folder:${folder.path.ifBlank { folder.name }}"

    fun isFavoriteFolder(folder: FolderCard): Boolean =
        favoriteFolderKey(folder) in state.prefs.favorites

    fun toggleFavoriteFolder(folder: FolderCard) {
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

    private fun folderEntry(folder: FolderCard): FileEntry = FileEntry(
        id = "folder:${folder.path.ifBlank { folder.name }}",
        name = folder.name,
        kind = FileKind.FOLDER,
        sizeLabel = "",
        timeLabel = folder.date,
        folder = "",
        childCount = folder.count,
        path = folder.path
    )

    fun favoriteFiles(ctx: FileContext = FileContext.LOCAL): List<FileEntry> {
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
                val resolved = state.synoFavorites.filter { favoriteKey(it) in keys }
                if (resolved.isNotEmpty()) resolved
                else state.shares.flatMap { it.items }.filter { favoriteKey(it) in keys }
            }
            FileContext.NET -> emptyList()
        }
    }

    /**
     * 解析群晖收藏：逐个 key 反查它的父目录拿到 FileEntry，
     * 这样深层路径和文件都能显示（shares.items 只覆盖已加载的浅层项）。
     */
    fun refreshSynoFavorites() {
        val session = state.session ?: return
        val root = state.localRoot.trimEnd('/')
        val keys = state.prefs.favorites.filter {
            !it.startsWith("folder:") && !it.startsWith("net:") && !it.startsWith(root)
        }
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                keys.mapNotNull { path ->
                    val parent = path.substringBeforeLast('/', "")
                    val name = path.substringAfterLast('/')
                    if (parent.isBlank() || name.isBlank()) return@mapNotNull null
                    runCatching { dsm.list(session, parent) }.getOrDefault(emptyList())
                        .firstOrNull { it.name == name }
                }
            }
            state = state.copy(
                synoFavorites = resolved,
                // 收藏页是打开时的快照：解析完成后同步刷新，否则新收藏看不到。
                browser = if (state.browser?.favorites == true) {
                    state.browser?.copy(items = resolved)
                } else state.browser
            )
        }
    }

    fun openFavorites(ctx: FileContext = FileContext.LOCAL) {
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
    private fun currentSortTab(): String = when (state.browser?.ctx) {
        FileContext.SYNO -> "syno"
        FileContext.NET -> "net"
        else -> "local"
    }

    fun sortFiles(items: List<FileEntry>, key: String = sortKeyFor(currentSortTab()), dir: String = sortDirFor(currentSortTab())): List<FileEntry> {
        val valueComparator: Comparator<FileEntry> = when (key) {
            "name" -> compareBy { it.name.lowercase() }
            "size" -> compareBy { it.sizeBytes }
            else -> compareBy { it.modified }
        }
        // Folders stay on top in BOTH directions; only their internal order flips.
        val ordered = if (dir == "asc") valueComparator else valueComparator.reversed()
        return items.sortedWith(compareBy<FileEntry> { !it.isFolder }.then(ordered))
    }

    /** 首页文件夹卡按当前 Tab 的排序偏好排。 */
    fun sortedFolders(items: List<FolderCard>, tabKey: String = currentSortTab()): List<FolderCard> {
        val key = sortKeyFor(tabKey)
        val dir = sortDirFor(tabKey)
        val comparator: Comparator<FolderCard> = when (key) {
            "name" -> compareBy { it.name.lowercase() }
            "size" -> compareBy { it.count.toLong() }
            else -> compareBy { it.date }
        }
        return if (dir == "asc") items.sortedWith(comparator) else items.sortedWith(comparator.reversed())
    }

    /** 网络连接卡按名称排序（大小 / 时间无意义时退化为名称）。 */
    fun sortedConnections(items: List<NetworkConnection>, tabKey: String = "net"): List<NetworkConnection> {
        val comparator: Comparator<NetworkConnection> = when (sortKeyFor(tabKey)) {
            "size" -> compareBy { it.host.lowercase() }
            "time" -> compareBy { it.protocol }
            else -> compareBy { it.name.lowercase() }
        }
        return if (sortDirFor(tabKey) == "asc") items.sortedWith(comparator) else items.sortedWith(comparator.reversed())
    }

    // ---------------------------------------------------------------- file sheet / open

    fun openFile(file: FileEntry) { state = state.copy(fileSheet = file) }

    // ---- 查看详情 / 安装权限 ----

    fun showDetail(file: FileEntry) {
        state = state.copy(detailFile = file, fileSheet = null)
    }

    fun closeDetail() { state = state.copy(detailFile = null) }

    fun dismissInstallPrompt() { state = state.copy(installFile = null) }

    fun canInstallPackages(): Boolean {
        val app = getApplication<Application>()
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            app.packageManager.canRequestPackageInstalls()
    }

    /** 系统设置里为本应用打开「安装未知应用」；装完回来点 APK 就能直接安装。 */
    fun requestInstallPermission() {
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
    fun consumePendingInstall() {
        val file = state.installFile ?: return
        state = state.copy(installFile = null)
        if (canInstallPackages()) requestOpen(file)
    }

    fun closeFileSheet() { state = state.copy(fileSheet = null) }

    /**
     * 预览 / 打开远程文件时的来源推断。搜索结果、首页文件没有 browser，
     * 旧实现只认 state.browser?.ctx，导致这些入口的远程文件一律「无法预览」。
     */
    private fun sourceContext(file: FileEntry): Pair<FileContext, String?> {
        state.browser?.let { return it.ctx to it.netId }
        val ctx = state.search?.ctx ?: contextOf(state.tab)
        val netId = state.connections.firstOrNull { conn ->
            val root = network.rootPath(conn)
            file.path.startsWith(root) || (conn.host.isNotBlank() && file.path.contains(conn.host))
        }?.id
        return ctx to netId
    }

    fun openPreview(file: FileEntry) {
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

    fun renameFile(file: FileEntry, newName: String) {
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
    private fun applyLocalRename(file: FileEntry, newName: String) {
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

    /** 单条重命名分发：本地走文件系统，群晖走 SYNO.FileStation.Rename，网络走对应协议。 */    private suspend fun renameEntry(file: FileEntry, newName: String): Boolean {
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

    fun compressFiles(files: List<FileEntry>, baseName: String, format: String = "zip") {
        if (files.isEmpty()) return
        if (files.any { !it.path.startsWith("/") }) {
            state = state.copy(fileSheet = null, message = "仅支持本机文件压缩")
            return
        }
        val targetDir = File(files.first().path).parentFile
        state = state.copy(fileSheet = null, localTaskLabel = "正在压缩 ${files.size} 项…", localTaskRunning = true)
        localTaskJob = viewModelScope.launch {
            val archive = withContext(Dispatchers.IO) {
                if (targetDir == null) null else local.createArchive(files, targetDir.absolutePath, baseName, format)
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
                            folder = archive.parentFile?.name.orEmpty(),
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

    fun downloadSubfolders(path: String): List<Pair<String, String>> {
        return runCatching {
            local.list(path)
                .filter { it.isFolder }
                .map { it.path to it.name }
        }.getOrDefault(emptyList())
    }

    fun createDownloadSubfolder(parent: String, name: String): Boolean {
        if (name.isBlank() || name.contains('/')) return false
        return runCatching { local.createFolder(parent, name) }.getOrDefault(false)
    }

    fun compressSelected(name: String, format: String) {
        val files = selectedItems()
        if (files.isEmpty()) return
        state = state.copy(selected = emptySet(), selectionActive = false)
        compressFiles(files, name, format)
    }

    fun requestOpen(file: FileEntry) {
        state = state.copy(fileSheet = null, preview = null, openWith = null)
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

    /** 防止连续快速点击触发多个系统选择器弹窗。 */
    private var lastRemoteOpenAt = 0L

    /**
     * 远端文件的「在线打开」：
     * - 视频/音频：不下载，经本机流式代理把地址交给外部播放器（可拖动进度，不受体积限制）；
     * - 图片：下载到缓存 → 系统相册，与本机图片一致；
     * - 其它（apk / 文档 / 压缩包）：下载到缓存 → 交给系统对应应用，apk 直接进安装器。
     */
    private fun openRemoteFile(file: FileEntry, gallery: Boolean, forceCtx: FileContext? = null) {
        // 防抖：800ms 内重复点击同一个文件不重复触发，避免弹出多个选择器。
        val now = System.currentTimeMillis()
        if (now - lastRemoteOpenAt < 800) return
        lastRemoteOpenAt = now

        val (autoCtx, autoNetId) = sourceContext(file)
        val ctx = forceCtx ?: autoCtx
        val netId = if (forceCtx == FileContext.NET) {
            state.connections.firstOrNull { file.path.startsWith(network.rootPath(it)) }?.id ?: autoNetId
        } else autoNetId
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
    private fun playRemoteStream(file: FileEntry, url: String, pkg: String = ""): Boolean = runCatching {
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
    private fun resolveActivityComponent(pkg: String, intent: Intent): android.content.ComponentName? = runCatching {
        val info = getApplication<Application>().packageManager
            .queryIntentActivities(intent, 0)
            .firstOrNull { it.activityInfo?.packageName == pkg }
            ?: return@runCatching null
        android.content.ComponentName(info.activityInfo.packageName, info.activityInfo.name)
    }.getOrNull()

    /** 下载到缓存后打开（图片走相册，其它走系统关联应用）。 */
    private fun cacheThenOpen(
        file: FileEntry,
        ctx: FileContext,
        netId: String?,
        gallery: Boolean,
        app: String = ""
    ) {
        val session = state.session
        val connection = state.connections.firstOrNull { it.id == netId }
        val target = File(
            getApplication<Application>().cacheDir,
            "open-${file.id.hashCode()}-${file.name}"
        )
        launchTransferTask(
            mode = TransferMode.DOWNLOAD,
            title = file.name,
            detail = "在线打开 · 缓存到本机",
            itemCount = 1,
            byteCount = file.sizeBytes,
            verb = "打开",
            quiet = true,
            onDone = { ok ->
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
                } else {
                    // quiet 任务不会写消息到 state.message，失败时必须手动提示，
                    // 否则用户看到的是「点了没反应」。
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
                        session?.let { dsm.download(it, file.path, target, onBytes = { b -> reporter.bytes(b) }) } ?: false
                    FileContext.NET -> connection?.let { network.download(it, file.path, target) } ?: false
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

    /**
     * 本地图片在媒体库里的 content URI。
     *
     * 相册只对「媒体库里的一张图」给出完整查看界面（分享 / 编辑 / 收藏 / 删除）；
     * 拿到别的应用 FileProvider 出来的地址时，它只当成一个外部文件，退化成
     * 「只有保存 / 更多」的日期页。所以打开图片前必须先把这张图对应到媒体库条目。
     *
     * 两种查法都试：Android 11+ 对 DATA 列有额外限制，RELATIVE_PATH + DISPLAY_NAME 更稳。
     */
    private fun mediaImageUri(path: String): android.net.Uri? {
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
    private fun ensureGalleryUri(target: File): android.net.Uri? {
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
    private fun isPending(uri: android.net.Uri): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf("is_pending"), null, null, null)
                ?.use { c -> c.moveToFirst() && c.getInt(0) == 1 } == true
        }.getOrDefault(false)
    }

    /** 把自家未发布条目转正。只有属主能改；失败返回 false，调用方退回 FileProvider。 */
    private fun markPublished(uri: android.net.Uri): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return true
        return runCatching {
            val values = android.content.ContentValues().apply { put("is_pending", 0) }
            getApplication<Application>().contentResolver.update(uri, values, null, null) > 0
        }.getOrDefault(false)
    }

    /** 用（媒体库）地址把图片交给相册；拿不到媒体库地址时退回 FileProvider，至少能打开。 */
    private fun openImageIntent(file: FileEntry, app: String, galleryUri: android.net.Uri?) {
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
                "img open app=${app.ifBlank { "chooser" }} uri=$uri " +
                    "media=${galleryUri != null} file=${file.name}"
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
    private fun openImageGallery(file: FileEntry, app: String = "") {
        openImageIntent(file, app, null)
    }

    /** 预览缓存：同样走传输中心，能在「进行中」看到下载进度，而不是只有一个转圈。 */
    private fun preparePreview(file: FileEntry, ctx: FileContext, netId: String?) {
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

    fun closePreview() { state = state.copy(preview = null) }
    fun closeOpenWith() { state = state.copy(openWith = null) }

    private val thumbSemaphore = Semaphore(3)

    /**
     * 远程图片列表缩略图：首次展示时按需下载到缓存目录，之后直接复用磁盘缓存。
     * 限制并发 3 个、单文件 8MB；远程视频不为列表下载整段视频（保留播放角标）。
     */
    suspend fun remoteThumbnail(file: FileEntry): String? {
        // 远端视频不需要预览图（用户要求），只显示类型图标即可。
        if (file.kind != FileKind.IMAGE) return null
        if (file.path.startsWith("/") && File(file.path).isFile) return file.path
        val dir = File(getApplication<Application>().cacheDir, "thumbs")
        val ext = file.name.substringAfterLast('.', "bin").take(8)
        val target = File(dir, "${(file.id + file.path).hashCode()}.$ext")
        if (target.isFile && target.length() > 0) return target.absolutePath
        val (ctx, netId) = sourceContext(file)
        val session = state.session
        val conn = state.connections.firstOrNull { it.id == netId }
        return thumbSemaphore.withPermit {
            if (target.isFile && target.length() > 0) return@withPermit target.absolutePath
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
            if (ok && target.isFile && target.length() > 0) target.absolutePath else null
        }
    }

    /**
     * 弹出「打开方式」：音乐 / 视频文件点一下先在这里选播放器。
     *
     * 之前的实现是写死一份假列表（系统音乐 / VLC / 网易云音乐…），真正点下去走的却是
     * 系统选择器，而系统选择器只认当前这次 Intent 的类型，很多已安装的音乐 App 因为
     * 只注册了 content:// 或 SEND 而被漏掉。现在改为用 PackageManager 真实枚举。
     */
    fun requestOpenWith(file: FileEntry) {
        // 用户之前点过「始终用这个应用打开」：直接开，不再每次都问。
        // 也不再每次弹「可在设置里修改」的提示 —— 那句话只在第一次设定时出现一次，
        // 之后每打开一次都提示纯属噪音（3.9.16 修）。
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
    fun launchWith(pkg: String, rememberForType: Boolean = false) {
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
    private fun rememberedOpenPackage(file: FileEntry): String? {
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
    fun openKeyOf(file: FileEntry): String = when (file.kind) {
        FileKind.AUDIO -> "audio"
        FileKind.VIDEO -> "video"
        FileKind.IMAGE -> "image"
        else -> {
            val mime = file.mime.substringBefore(';').trim().lowercase()
            if (isGenericMime(mime)) mimeFromName(file.name) else mime
        }
    }

    /** 这些 MIME 只说明「有点数据」，说不清是什么类型，等于没给。 */
    private fun isGenericMime(mime: String): Boolean = mime.isBlank() ||
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
    fun openTypeLabel(key: String): String {
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

    fun appLabelOf(pkg: String): String = runCatching {
        val pm = getApplication<Application>().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** 设置页展示用：已记住的默认打开方式（类型 → 应用名）。 */
    fun openDefaultEntries(): List<Pair<String, String>> =
        state.prefs.openDefaults.entries
            .sortedBy { it.key }
            .map { it.key to appLabelOf(it.value) }

    fun clearOpenDefault(key: String) {
        state = state.copy(
            prefs = state.prefs.copy(openDefaults = state.prefs.openDefaults - key),
            message = "已恢复为每次询问（${openTypeLabel(key)}）"
        )
        persist()
    }

    /** 真正的打开动作：本机文件走 FileProvider，远端文件按需缓存 / 流式播放。 */
    private fun openWithPackage(file: FileEntry, pkg: String) {
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
        val (ctx, netId) = sourceContext(file)
        if (pkg.isBlank() && (file.kind == FileKind.VIDEO || file.kind == FileKind.AUDIO)) {
            // 选「系统选择器」= 沿用原来的流式播放（不下载整个文件）。
            openRemoteFile(file, gallery = false)
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
     * 给流式源补上「这个文件」的信息：
     * - mime：上游（群晖下载接口）多半只回 application/octet-stream，
     *   播放器据此判断不了格式；
     * - name：代理地址结尾要带真实文件名与扩展名，否则小米视频这类播放器
     *   会因为 URL 没有后缀而直接判「格式不支持」；
     * - contentLength：SMB/FTP/SFTP 拿不到远端大小（旧实现恒为 -1），
     *   导致拖动进度时无法回 206，播放器会把半截数据当完整文件解码而失败。
     */
    private fun StreamProxy.Source.stampedWith(file: FileEntry): StreamProxy.Source = copy(
        mime = effectiveMime(file),
        name = file.name,
        contentLength = if (contentLength > 0) contentLength else file.sizeBytes
    )

    /**
     * 枚举「能打开这个文件」的已安装应用。
     *
     * 只查一种 Intent 一定会漏：不同 App 注册的入口差别很大（只注册 content://、
     * 只注册 file://、只接 ACTION_SEND、或只声明大类（audio、video 前缀）。这里把常见几种
     * 都查一遍再按包名去重，用户装了什么播放器就能看到什么。
     */
    private fun resolveOpenApps(file: FileEntry): List<OpenApp> {
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
        // ① 只带类型 / ② 带 content:// 或 file:// 假地址：这两类都是「打开」，命中率最高。
        val viewIntents = mutableListOf<Intent>()
        targets.forEach { type ->
            viewIntents += Intent(Intent.ACTION_VIEW).setType(type)
            viewIntents += Intent(Intent.ACTION_VIEW).setTypeAndNormalize(type)
            listOf("content://com.yaoahos.myfilebox.probe/", "file:///storage/emulated/0/").forEach { prefix ->
                viewIntents += Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(prefix + probeFile), type)
            }
        }
        val found = collectOpenApps(pm, viewIntents, targets, generic)
        android.util.Log.d("MFB-OPEN", "resolve ${file.name} kind=${file.kind} fileMime=${file.mime} mime=$mime family=$family targets=$targets generic=$generic viewFound=${found.map { it.pkg }}")
        if (found.isNotEmpty()) return found
        // ③ 兜底才查分享入口：只有「打开」类应用一个都没有时，才把只注册 SEND 的 App 列出来。
        // 分享类 App（浏览器 / 聊天工具）虽然能选中，但多半没有可导出的 View 入口，选了也打不开。
        val sendIntents = targets.map { Intent(Intent.ACTION_SEND).setType(it) }
        val sendFound = collectOpenApps(pm, sendIntents, targets, generic)
        android.util.Log.d("MFB-OPEN", "resolve ${file.name} sendFound=${sendFound.map { it.pkg }}")
        return sendFound
    }

    /**
     * 按包名去重地收集能处理这些 Intent 的应用。
     *
     * 只保留 intent-filter 里对该类型有「具体声明」的应用：只声明任意类型
     * 通配（`*`/`*`）的浏览器、聊天工具、文件管理器对什么文件都会应答，
     * 全塞进「打开方式」就是一堆完全用不到的应用（3.9.18 修）。
     * 系统选择器入口保留在弹窗底部，要看全量仍可以走它。
     */
    private fun collectOpenApps(
        pm: android.content.pm.PackageManager,
        probes: List<Intent>,
        targets: List<String>,
        generic: Boolean
    ): List<OpenApp> {
        val self = getApplication<Application>().packageName
        val found = LinkedHashMap<String, OpenApp>()
        probes.forEach { probe ->
            val resolved = runCatching {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, android.content.pm.PackageManager.GET_RESOLVED_FILTER)
            }.getOrDefault(emptyList())
            android.util.Log.d("MFB-OPEN", "probe [${probe.type}|${probe.data}] -> ${resolved.size}: ${resolved.map { it.activityInfo?.packageName }}")
            resolved.forEach { info ->
                val activity = info.activityInfo ?: return@forEach
                val pkg = activity.packageName ?: return@forEach
                if (pkg == self || found.containsKey(pkg)) return@forEach
                if (!declaresSpecificType(info.filter, targets, generic)) {
                    android.util.Log.d("MFB-OPEN", "  drop $pkg types=${runCatching { info.filter?.typesIterator()?.asSequence()?.toList() }.getOrNull()}")
                    return@forEach
                }
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                found[pkg] = OpenApp(pkg, label)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /**
     * 该应用命中我们的 filter 是否有具体类型声明。
     * 只声明任意类型通配的算通配、丢弃；声明 audio/mpeg 能命中音频大类、
     * 声明 text 大类能命中 text/plain，都算具体。目标本身就是通用 MIME 时
     * 只认精确的 application/octet-stream 声明。
     */
    private fun declaresSpecificType(
        filter: android.content.IntentFilter?,
        targets: List<String>,
        generic: Boolean
    ): Boolean {
        // 拿不到 filter 时不误伤（个别 ROM 不回填）。typesIterator() 是
        // IntentFilter 拿声明类型的标准入口（types() 并不存在）。
        val declared = filter?.let { f ->
            runCatching { f.typesIterator().asSequence().toList() }.getOrNull()
        } ?: return true
        if (declared.isEmpty()) return true
        for (raw in declared) {
            val t = normalizeDeclaredType(raw)
            if (t.equals("*/*", true)) continue
            for (target in targets) {
                if (t.equals(target, true)) return true
                // filter「audio/mpeg」命中目标「audio 大类」
                if (target.endsWith("/*") && t.startsWith(target.dropLast(1), true)) return true
                // filter「text 大类」命中目标「text/plain」；通用目标不收这种宽声明
                if (!generic && t.endsWith("/*") && target.startsWith(t.dropLast(1), true)) return true
            }
        }
        return false
    }

    /**
     * typesIterator() 吐出来的通配类型是「裸前缀」：audio 大类就是 audio、
     * 任意类型就是单个星号，只有精确类型才带斜杠（application/ogg 这类）。
     * 必须把裸前缀补成「前缀 + 斜杠 + 星号」再参与匹配，否则声明了
     * audio / text 大类的应用会被「以斜杠星号结尾」的判定整批误杀——
     * 表现就是打开方式里真播放器全部消失，只剩个别碰巧声明了具体类型
     * 的应用（3.9.18 修）。
     */
    private fun normalizeDeclaredType(raw: String): String {
        val t = raw.trim()
        return when {
            t.isEmpty() -> "*/*"
            '/' in t -> t
            else -> t + "/*"
        }
    }

    private fun openLocalIntent(file: FileEntry, app: String = "") {
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
                android.util.Log.i("MFB-OPEN", "launch $app type=${intent.type} data=${intent.data?.lastPathSegment} component=${intent.component?.flattenToShortString()}")
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
    private fun hasHandler(intent: Intent): Boolean = runCatching {
        getApplication<Application>().packageManager
            .queryIntentActivities(intent, 0)
            .isNotEmpty()
    }.getOrDefault(false)

    /** 这个包能否处理该 Intent（避免 setPackage 后直接 ActivityNotFound）。 */
    private fun canPackageHandle(pkg: String, intent: Intent): Boolean = runCatching {
        getApplication<Application>().packageManager
            .queryIntentActivities(intent, 0)
            .any { it.activityInfo?.packageName == pkg }
    }.getOrDefault(false)

    private fun mimeFromName(name: String): String {
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
    private fun effectiveMime(file: FileEntry): String {
        val mime = file.mime.substringBefore(';').trim().lowercase()
        return if (isGenericMime(mime)) mimeFromName(file.name) else mime
    }

    fun openWithSystem(file: FileEntry) {
        requestOpen(file)
    }

    // ---------------------------------------------------------------- selection

    fun toggleSelected(file: FileEntry) {
        val selected = state.selected.toMutableSet()
        if (!selected.add(file.id)) selected.remove(file.id)
        state = state.copy(selected = selected, selectionActive = true)
    }

    fun clearSelection() {
        state = state.copy(selected = emptySet(), selectionActive = false)
    }

    fun selectAll() {
        state = state.copy(
            selected = state.browser?.items?.map { it.id }?.toSet() ?: emptySet(),
            selectionActive = true
        )
    }

    /** 取消全选只清空勾选，不退出选择模式，方便继续逐个点选。 */
    fun toggleSelectAll() {
        val all = state.browser?.items?.map { it.id }?.toSet().orEmpty()
        state = state.copy(
            selected = if (all.isNotEmpty() && state.selected.containsAll(all)) emptySet() else all,
            selectionActive = true
        )
    }

    // ---- 首页「内部存储」的多选：行为与浏览页一致（长按进入多选 + 全选） ----

    fun homeFolderId(folder: FolderCard) = "home-folder:${folder.path}"
    fun homeFileId(file: FileEntry) = file.id

    private fun homeAllIds(): Set<String> {
        val rootPath = state.localRoot.trimEnd('/')
        val rootFiles = state.localFiles.filter { it.path.trimEnd('/').substringBeforeLast('/') == rootPath }
        return state.localFolders.map { homeFolderId(it) }.toSet() + rootFiles.map { homeFileId(it) }
    }

    fun toggleHomeSelected(id: String) {
        val next = state.homeSelected.toMutableSet()
        if (!next.add(id)) next.remove(id)
        state = state.copy(homeSelected = next, homeSelecting = true)
    }

    fun clearHomeSelection() {
        state = state.copy(homeSelected = emptySet(), homeSelecting = false)
    }

    fun toggleHomeSelectAll() {
        val all = homeAllIds()
        state = state.copy(
            homeSelected = if (all.isNotEmpty() && state.homeSelected.containsAll(all)) emptySet() else all,
            homeSelecting = true
        )
    }

    fun homeAllSelected(): Boolean {
        val all = homeAllIds()
        return all.isNotEmpty() && state.homeSelected.containsAll(all)
    }

    fun homeSelectedEntries(): List<FileEntry> {
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

    fun stageHomeClipboard(move: Boolean) {
        val entries = homeSelectedEntries()
        if (entries.isEmpty()) return
        stageClipboard(if (move) TransferMode.MOVE else TransferMode.COPY, entries)
        clearHomeSelection()
    }

    fun uploadHomeSelection() {
        val entries = homeSelectedEntries()
        if (entries.isEmpty()) return
        startTransfer(TransferMode.UPLOAD, entries)
        clearHomeSelection()
    }

    fun shareHomeSelection() {
        val entries = homeSelectedEntries().filterNot { it.isFolder }
        if (entries.isEmpty()) return
        shareFiles(entries)
        clearHomeSelection()
    }

    fun deleteHomeSelection() {
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
    fun compressHomeSelection(name: String, format: String) {
        val entries = homeSelectedEntries()
        if (entries.isEmpty()) return
        clearHomeSelection()
        compressFiles(entries, name, format)
    }

    /** 首页选中单个压缩包时解压；destinationPath 为空表示解压到当前文件夹。 */
    fun extractHomeSelection(destinationPath: String? = null) {
        val entries = homeSelectedEntries()
        if (entries.size != 1) return
        val archive = entries.first()
        clearHomeSelection()
        extractArchive(archive, destinationPath)
    }

    fun showHomeDetail() {
        val entries = homeSelectedEntries()
        if (entries.size != 1) return
        state = state.copy(detailFile = entries.first())
    }

    fun selectedItems(): List<FileEntry> = state.browser?.items?.filter { it.id in state.selected }.orEmpty()

    /** 是否已经配置了远端目标（群晖已登录或有网络位置），决定「上传」能不能点。 */
    fun hasRemoteTarget(): Boolean = state.session != null || state.connections.isNotEmpty()

    /**
     * 文件列表里的条目都是「共享文件夹内部的文件」，可以正常多选。
     * 真正不能操作的是根部的共享文件夹 / 连接本身，它们在首页只是卡片：
     * 长按不进入多选，点一下才会打开。
     */
    fun canSelectHere(): Boolean = true

    /**
     * 群晖收藏：文件夹走官方 SYNO.FileStation.Favorite 接口同步，
     * 文件仅记本机 key（官方收藏只收文件夹）；两者都进「群晖收藏」页。
     */
    fun favoriteSynoSelected() {
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
                if (allFavorited) next.remove(favoriteKey(file)) else next.add(favoriteKey(file))
            }
            state = state.copy(
                prefs = state.prefs.copy(favorites = next),
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
    fun favoriteSynoSingle(file: FileEntry) {
        state = state.copy(selected = setOf(file.id))
        favoriteSynoSelected()
    }

    /** 收藏选中项：全部已收藏时执行取消收藏。 */
    fun favoriteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        val allFavorited = items.all { isFavorite(it) }
        val next = state.prefs.favorites.toMutableSet()
        items.forEach { file -> if (allFavorited) next.remove(favoriteKey(file)) else next.add(favoriteKey(file)) }
        state = state.copy(
            prefs = state.prefs.copy(favorites = next),
            selected = emptySet(),
            selectionActive = false,
            message = if (allFavorited) "已取消收藏 ${items.size} 项" else "已收藏 ${items.size} 项"
        )
        persist()
        // 多选面板里的「收藏 / 取消收藏」也只改集合，不重算列表的话，
        // 在收藏页取消收藏后条目会赖着不走（3.9.17）。
        if (state.browser?.favorites == true) viewModelScope.launch { reloadBrowserItems() }
    }

    fun favoriteHomeSelection() {        val entries = homeSelectedEntries()
        if (entries.isEmpty()) return
        val allFavorited = entries.all { isFavorite(it) }
        val next = state.prefs.favorites.toMutableSet()
        entries.forEach { file -> if (allFavorited) next.remove(favoriteKey(file)) else next.add(favoriteKey(file)) }
        state = state.copy(
            prefs = state.prefs.copy(favorites = next),
            homeSelected = emptySet(),
            homeSelecting = false,
            message = if (allFavorited) "已取消收藏 ${entries.size} 项" else "已收藏 ${entries.size} 项"
        )
        persist()
    }

    /**
     * 群晖「共享」（清单 4）：共享设置确认后才生成链接并复制。
     * [password] 空 = 不设密码；[expireMinutes] 0 = 永不失效。
     */
    fun shareSynoLink(file: FileEntry, password: String = "", expireMinutes: Int = 0) {
        val session = state.session ?: run {
            state = state.copy(message = "请先登录群晖 NAS")
            return
        }
        viewModelScope.launch {
            state = state.copy(loading = true)
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
        }
    }

    // ---------------------------------------------------------------- 群晖共享管理（清单 4）

    fun openShareManager() {
        state = state.copy(shareManagerOpen = true, shareLinksLoading = true)
        refreshShareLinks()
    }

    fun closeShareManager() { state = state.copy(shareManagerOpen = false) }

    /** 共享管理下拉刷新：refreshing 驱动顶部刷新指示器，和文件列表页一致。 */
    fun refreshShareLinks(forceClean: Boolean = false) {
        val session = state.session ?: run {
            state = state.copy(shareLinksLoading = false, shareLinks = emptyList(), refreshing = false)
            return
        }
        state = state.copy(shareLinksLoading = true, refreshing = true)
        viewModelScope.launch {
            val links = withContext(Dispatchers.IO) { dsm.listShareLinks(session, forceClean) }
            state = state.copy(shareLinks = links, shareLinksLoading = false, refreshing = false)
        }
    }

    fun cancelShareLink(link: com.yaoahos.myfilebox.core.ShareLink) {
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
    fun clearInvalidShareLinks() {
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
    private suspend fun renewSynoSession(force: Boolean = false): Boolean {
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
    private suspend fun uploadSynoWithRetry(file: File, dest: String, name: String = file.name): Boolean {
        if (state.session?.let { dsm.upload(it, file, dest, name) } == true) return true
        if (!renewSynoSession()) return false
        return state.session?.let { dsm.upload(it, file, dest, name) } ?: false
    }

    /** 把本机文件上传到当前远程目录（群晖 / 网络位置），全程在传输中心展示进度。 */
    fun uploadLocalFilesToCurrent(files: List<File>) {
        val browser = state.browser ?: return
        if (browser.ctx == FileContext.LOCAL || files.isEmpty()) return
        val dest = browser.location
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
    fun uploadLocalFolderToCurrent(dirPath: String) {
        val browser = state.browser ?: return
        if (browser.ctx == FileContext.LOCAL) return
        val root = File(dirPath)
        if (!root.isDirectory || !root.canRead()) {
            showMessage("该文件夹不可读取，请换一个目录")
            return
        }
        val dest = browser.location.trimEnd('/')
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
                onDone = { ok -> if (ok) reloadBrowserItems() }
            ) { reporter ->
                val dirs = files.map { it.second }.filter { it.isNotBlank() }.distinct()
                    .sortedBy { rel -> rel.count { it == '/' } }
                var created = 0
                for (rel in dirs) {
                    if (reporter.cancelled()) return@launchTransferTask false
                    val full = "$dest/$rel"
                    runCatching { createRemoteFolder(browser, full.substringBeforeLast('/'), full.substringAfterLast('/')) }
                    created++
                    reporter.note("${browser.title} · 已建 $created/${dirs.size} 个目录")
                }
                reporter.note("${browser.title} · 上传中")
                var done = 0
                var sent = 0L
                var all = true
                for ((file, rel) in files) {
                    if (reporter.cancelled()) return@launchTransferTask false
                    val target = if (rel.isBlank()) dest else "$dest/$rel"
                    val base = sent
                    reporter.uploaded(joinRemote(target, file.name))
                    val ok = uploadOne(
                        browser, file, target, file.name,
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
    }

    fun unfavorite(file: FileEntry) {
        val keys = setOf(file.id, favoriteKey(file), "folder:${file.path}")
        state = state.copy(
            prefs = state.prefs.copy(favorites = state.prefs.favorites - keys),
            selected = state.selected - file.id,
            message = "已取消收藏"
        )
        persist()
        viewModelScope.launch { reloadBrowserItems() }
    }

    fun unfavoriteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        val removed = items.flatMap { setOf(it.id, favoriteKey(it), "folder:${it.path}") }.toSet()
        state = state.copy(
            prefs = state.prefs.copy(favorites = state.prefs.favorites - removed),
            selected = emptySet(),
            selectionActive = false,
            message = "已取消收藏 ${items.size} 项"
        )
        persist()
        viewModelScope.launch { reloadBrowserItems() }
    }

    // ---------------------------------------------------------------- transfer

    fun startTransfer(mode: TransferMode, items: List<FileEntry>) {
        val ctx = state.browser?.ctx ?: FileContext.LOCAL
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

    fun startSelectedTransfer(mode: TransferMode) {
        val items = selectedItems()
        if (items.isNotEmpty()) startTransfer(mode, items)
    }

    fun stageClipboard(mode: TransferMode, items: List<FileEntry>) {
        if (items.isEmpty()) return
        val browser = state.browser
        state = state.copy(
            clipboard = ClipboardState(mode, items, browser?.ctx ?: FileContext.LOCAL, browser?.netId),
            selected = emptySet(),
            selectionActive = false,
            fileSheet = null,
            // 首页根目录也能粘贴，所以提示不再限定「进入子文件夹」。
            message = if (mode == TransferMode.COPY) "已复制 ${items.size} 项，到目标位置点粘贴"
                      else "已剪切 ${items.size} 项，到目标位置点粘贴"
        )
    }

    /** 粘贴只在同一端内可用；跨端一律走上传 / 下载，不提供粘贴按钮。 */
    fun canPasteHere(): Boolean {
        val clip = state.clipboard ?: return false
        val browser = state.browser ?: return false
        if (clip.ctx != browser.ctx) return false
        if (clip.ctx == FileContext.NET && clip.netId != browser.netId) return false
        return true
    }

    fun clipboardLabel(): String {
        val clip = state.clipboard ?: return ""
        val kind = when (clip.ctx) {
            FileContext.LOCAL -> "内部存储"
            FileContext.SYNO -> "群晖 NAS"
            FileContext.NET -> "网络存储"
        }
        return "${if (clip.mode == TransferMode.COPY) "已复制" else "已剪切"} ${clip.items.size} 项 · $kind"
    }

    fun stageSelectedTransfer(mode: TransferMode) {
        stageClipboard(mode, selectedItems())
    }

    fun pasteClipboard() {
        val clip = state.clipboard ?: return
        val browser = state.browser
        if (browser == null) {
            state = state.copy(message = "请先进入目标文件夹")
            return
        }
        if (!canPasteHere()) {
            state = state.copy(
                message = "跨端文件只能用上传 / 下载传输，粘贴仅支持同一端内"
            )
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
        val destination = Destination(
            type = type,
            key = browser.location.ifBlank { browser.path },
            label = browser.title,
            meta = browser.title,
            path = browser.location.ifBlank { browser.path }
        )
        state = state.copy(
            transfer = TransferRequest(clip.mode, clip.items, clip.ctx, clip.netId, listOf(destination)),
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
    fun canPasteOnHome(): Boolean {
        val clip = state.clipboard ?: return false
        return clip.ctx == FileContext.LOCAL && state.localRoot.isNotBlank()
    }

    fun pasteToHome() {
        val clip = state.clipboard ?: return
        if (!canPasteOnHome()) {
            state = state.copy(message = "跨端文件只能用上传 / 下载传输，粘贴仅支持同一端内")
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

    fun closeTransfer() { state = state.copy(transfer = null) }

    /** 删除的群晖条目 → 回收站删除单元记录（trashPath 按 #recycle 保留原路径规则推算）。 */
    private fun synoRecycleRecordOf(entry: FileEntry): RecycleItem? {
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
    private suspend fun freshSynoRecycleItems(): List<RecycleItem> {
        val items = state.session?.let {
            dsm.listRecycleBin(it, state.shares.map { s -> s.path }, tracked = synoRecycleIndex.all())
        }.orEmpty()
        pruneSynoRecycleIndex(items)
        return items
    }

    /** 列表为空且带错误 = 拉取失败，绝不能拿空列表把索引清光。 */
    private fun pruneSynoRecycleIndex(items: List<RecycleItem>) {
        if (items.isEmpty() && dsm.lastError != null) return
        synoRecycleIndex.retain(items.map { it.trashPath })
    }

    fun openRecycle(ctx: FileContext = FileContext.LOCAL) {
        if (ctx == FileContext.SYNO) {
            val session = state.session
            state = state.copy(
                recycleOpen = true,
                recycleSelected = emptySet(),
                recycleSelecting = false,
                recycleContext = FileContext.SYNO,
                recycleItems = emptyList(),
                loading = true
            )
            viewModelScope.launch {
                val items = withContext(Dispatchers.IO) {
                    session?.let {
                        dsm.listRecycleBin(it, state.shares.map { s -> s.path }, tracked = synoRecycleIndex.all())
                    }.orEmpty()
                }
                pruneSynoRecycleIndex(items)
                state = state.copy(recycleItems = items, loading = false)
            }
        } else {
            state = state.copy(
                recycleOpen = true,
                recycleSelected = emptySet(),
                recycleSelecting = false,
                recycleContext = FileContext.LOCAL,
                recycleItems = runCatching { local.listRecycle() }.getOrDefault(emptyList())
            )
        }
    }

    fun closeRecycle() { state = state.copy(recycleOpen = false, recycleSelected = emptySet(), recycleSelecting = false) }

    /** 回收站下拉刷新：与其它文件列表页保持一致的手势。 */
    fun refreshRecycle() {
        if (state.refreshing) return
        val ctx = state.recycleContext
        if (ctx == FileContext.SYNO) {
            val session = state.session ?: return
            viewModelScope.launch {
                state = state.copy(refreshing = true)
                val items = withContext(Dispatchers.IO) {
                    session.let {
                        dsm.listRecycleBin(it, state.shares.map { s -> s.path }, tracked = synoRecycleIndex.all())
                    }
                }
                pruneSynoRecycleIndex(items)
                state = state.copy(recycleItems = items, refreshing = false)
            }
        } else {
            val items = runCatching { local.listRecycle() }.getOrDefault(emptyList())
            state = state.copy(recycleItems = items, refreshing = false)
        }
    }

    fun restoreRecycle(item: RecycleItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                when (state.recycleContext) {
                    FileContext.SYNO -> state.session?.let { dsm.restoreRecycleItem(it, item) } ?: false
                    else -> local.restoreRecycle(item)
                }
            }
            val destParent = item.originalPath.substringBeforeLast('/').ifBlank { "根目录" }
            state = state.copy(
                message = if (ok) "已还原「${item.name}」到 $destParent" else
                    if (state.recycleContext == FileContext.SYNO) dsm.lastError ?: "恢复失败" else "恢复失败"
            )
            if (state.recycleContext == FileContext.SYNO) {
                if (ok) synoRecycleIndex.remove(listOf(item.trashPath))
                val items = withContext(Dispatchers.IO) { freshSynoRecycleItems() }
                state = state.copy(recycleItems = items)
                // 群晖恢复后要让原目录重新拉一次列表，否则回到原位置还是旧内容。
                if (ok) {
                    refreshSyno()
                    reloadBrowserItems()
                    return@launch
                }
            } else {
                state = state.copy(
                    recycleItems = runCatching { local.listRecycle() }.getOrDefault(emptyList())
                )
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
    private fun localEntryOf(item: RecycleItem): FileEntry? {
        if (item.isFolder) return null
        val now = System.currentTimeMillis()
        return FileEntry(
            id = item.originalPath,
            name = item.name,
            kind = FileKinds.of(item.name),
            sizeLabel = LocalRepository.formatSize(item.sizeBytes),
            timeLabel = LocalRepository.formatStamp(now),
            folder = item.originalPath.substringBeforeLast('/', "").substringAfterLast('/'),
            path = item.originalPath,
            sizeBytes = item.sizeBytes,
            modified = now
        )
    }

    /** 刚还原的本地文件夹 → 缓存条目。 */
    private fun localFolderOf(item: RecycleItem): FolderCard? {
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
    fun toggleRecycleSelected(item: RecycleItem) {
        val next = if (item.id in state.recycleSelected) state.recycleSelected - item.id
        else state.recycleSelected + item.id
        state = state.copy(recycleSelected = next, recycleSelecting = true)
    }

    fun toggleRecycleSelectAll() {
        val all = state.recycleItems.map { it.id }.toSet()
        state = state.copy(
            recycleSelected = if (state.recycleSelected.size == all.size) emptySet() else all,
            recycleSelecting = true
        )
    }

    fun clearRecycleSelection() {
        state = state.copy(recycleSelected = emptySet(), recycleSelecting = false)
    }

    fun recycleSelectedItems(): List<RecycleItem> =
        state.recycleItems.filter { it.id in state.recycleSelected }

    /** 多选还原（清单 6）：选中的回收条目逐个还原到原始位置。 */    fun restoreRecycleMany(items: List<RecycleItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            state = state.copy(loading = true, recycleSelected = emptySet(), recycleSelecting = false)
            var ok = 0
            var failed = 0
            val failedIds = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                when (state.recycleContext) {
                    FileContext.SYNO -> {
                        val session = state.session
                        items.forEach {
                            if (session != null && dsm.restoreRecycleItem(session, it)) ok++ else {
                                failed++; failedIds += it.id
                            }
                        }
                    }
                    else -> items.forEach {
                        if (local.restoreRecycle(it)) ok++ else {
                            failed++; failedIds += it.id
                        }
                    }
                }
            }
            val restored = items.filter { it.id !in failedIds }
            if (state.recycleContext == FileContext.SYNO && restored.isNotEmpty()) {
                synoRecycleIndex.remove(restored.map { it.trashPath })
            }
            val fresh = withContext(Dispatchers.IO) {
                when (state.recycleContext) {
                    FileContext.SYNO -> freshSynoRecycleItems()
                    else -> runCatching { local.listRecycle() }.getOrDefault(emptyList())
                }
            }
            state = state.copy(
                loading = false,
                recycleItems = fresh,
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
    fun deleteRecycleItems(items: List<RecycleItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            state = state.copy(loading = true, recycleSelected = emptySet(), recycleSelecting = false)
            val ok = withContext(Dispatchers.IO) {
                when (state.recycleContext) {
                    FileContext.SYNO -> {
                        val session = state.session
                        session != null && dsm.deleteRecycleItems(session, items.map { it.trashPath })
                    }
                    else -> {
                        var done = true
                        items.forEach { item ->
                            val target = java.io.File(item.trashPath)
                            runCatching { if (target.exists()) target.deleteRecursively() }.onFailure { done = false }
                        }
                        done
                    }
                }
            }
            if (state.recycleContext == FileContext.SYNO && ok) {
                synoRecycleIndex.remove(items.map { it.trashPath })
            }
            val fresh = withContext(Dispatchers.IO) {
                when (state.recycleContext) {
                    FileContext.SYNO -> freshSynoRecycleItems()
                    else -> runCatching { local.listRecycle() }.getOrDefault(emptyList())
                }
            }
            state = state.copy(
                loading = false,
                recycleItems = fresh,
                message = if (ok) "已彻底删除 ${items.size} 项" else "删除失败：${dsm.lastError ?: "请稍后重试"}"
            )
        }
    }

    fun emptyRecycle() {
        viewModelScope.launch {
            if (state.recycleContext == FileContext.SYNO) {
                val session = state.session
                val paths = state.recycleItems.map { it.trashPath }
                val ok = withContext(Dispatchers.IO) {
                    if (session != null && paths.isNotEmpty()) dsm.deleteRecycleItems(session, paths) else true
                }
                if (ok) synoRecycleIndex.clear()
                val fresh = withContext(Dispatchers.IO) {
                    session?.let {
                        dsm.listRecycleBin(it, state.shares.map { s -> s.path }, tracked = synoRecycleIndex.all())
                    }.orEmpty()
                }
                state = state.copy(
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
        }
    }

    fun setRecycleDays(days: Int) {
        state = state.copy(prefs = state.prefs.copy(recycleDays = days))
        persist()
        // 0 = 永不，绝不触发清理；只有正数天数才按过期时间清理。
        if (days > 0) launchCleanup(days)
        else state = state.copy(message = "已设为永不自动清理")
    }

    private fun launchCleanup(days: Int) {
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

    fun openTransferCenter() {
        detectMissingFiles()
        state = state.copy(transferCenterOpen = true)
    }

    fun closeTransferCenter() {
        state = state.copy(transferCenterOpen = false)
    }

    fun clearTransferHistory() {
        state = state.copy(transfers = state.transfers.filter { it.status == TransferStatus.RUNNING || it.status == TransferStatus.PAUSED })
        persistTransfers()
    }

    /** 暂停传输：取消协程但保留记录与已传文件，之后可恢复。 */
    fun pauseTransfer(id: String) {
        TransferCancellation.request(id)
        transferJobs.remove(id)?.cancel()
        updateTransfer(id) { task ->
            if (task.status == TransferStatus.RUNNING) {
                task.copy(status = TransferStatus.PAUSED, error = "已暂停")
            } else task
        }
        persistTransfers()
        val app = getApplication<Application>()
        // 真正停下来：更新通知 + 没有其它任务就摘掉前台服务。
        state.transfers.firstOrNull { it.id == id }?.takeIf { !it.quiet }?.let {
            TransferNotifications.notifyFinal(app, it, useForegroundSlot = !hasOtherRunning(id))
        }
        stopTransferServiceIfIdle(app)
        val pausedMode = state.transfers.firstOrNull { it.id == id }?.mode
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
    private fun rebuildTransferContext(task: TransferTask): Pair<TransferRequest, Destination>? {
        val sources = task.sources.filter { it.isNotBlank() }
        if (sources.isEmpty()) return null
        val items = sources.map { path ->
            val name = path.trimEnd('/').substringAfterLast('/').ifBlank { task.title }
            FileEntry(
                id = path, name = name, kind = FileKinds.of(name),
                sizeLabel = "", timeLabel = "", folder = "", path = path,
                sizeBytes = 0L, modified = 0L
            )
        }
        return when (task.mode) {
            TransferMode.DOWNLOAD -> {
                val ctx = runCatching { FileContext.valueOf(task.sourceCtx) }.getOrNull() ?: return null
                if (ctx == FileContext.LOCAL) return null
                val destPath = task.target.takeIf { it.startsWith("/") }
                    ?.substringBeforeLast('/')?.takeIf { it.isNotBlank() }
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
    fun resumeTransfer(task: TransferTask) {
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
        val job = viewModelScope.launch {
            var reportedTotalBytes = state.transfers.firstOrNull { it.id == task.id }?.totalBytes ?: 0L
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
                            downloadToLocal(request.items, destination, request.ctx, request.netId, progress, bytes, task.id, total, totalBytesUpdate, onTarget = {}, resume = true)
                        request.mode == TransferMode.UPLOAD ->
                            uploadToRemote(
                                request.items, destination, progress, total, bytes, totalBytesUpdate,
                                onTarget = {}, taskId = task.id,
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

    /** 只删除传输记录，不动已下载的文件。 */
    fun deleteTransferRecord(id: String) {
        state = state.copy(transfers = state.transfers.filterNot { it.id == id })
        persistTransfers()
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
    fun deleteTransferFile(task: TransferTask) {
        val job = transferJobs[task.id]
        val wasRunning = task.status == TransferStatus.RUNNING || task.status == TransferStatus.PAUSED
        TransferCancellation.request(task.id)
        transferJobs.remove(task.id)?.cancel()
        val app = getApplication<Application>()
        TransferNotifications.dismiss(app, task.id)
        // 记录先消失，用户不用等网络删除完成。
        state = state.copy(transfers = state.transfers.filterNot { it.id == task.id })
        persistTransfers()
        stopTransferServiceIfIdle(app)
        if (!wasRunning) {
            // 已完成的任务没有协程在写文件，直接清理即可。
            viewModelScope.launch {
                val note = withContext(Dispatchers.IO) { deleteTransferPayload(task) }
                state = state.copy(message = note)
                refreshBrowserAfterDelete(task)
            }
            return
        }
        viewModelScope.launch {
            // 等传输协程真正退出（最多 10 秒），再去删文件。
            withTimeoutOrNull(10_000) { job?.join() }
            var note = withContext(Dispatchers.IO) { deleteTransferPayload(task) }
            // 阻塞式网络 IO 有时会晚一拍才把最后一块写进 .part，
            // 删完再核一次，避免「删了任务又冒出一个半截文件」。
            if (task.mode == TransferMode.DOWNLOAD) {
                for (round in 0 until 3) {
                    delay(700)
                    val gone = withContext(Dispatchers.IO) {
                        !File(task.target).exists() && !File("${task.target}.part").exists()
                    }
                    if (gone) break
                    note = withContext(Dispatchers.IO) { deleteTransferPayload(task) }
                }
            }
            state = state.copy(message = note)
            refreshBrowserAfterDelete(task)
        }
    }

    /**
     * 删掉文件后同步刷新正在看的那个目录。
     *
     * 之前远端文件已经删了，但列表还是进入时的快照，用户必须退出再进来才能看到变化，
     * 看起来就像「没删掉」。这里只刷与任务同源的目录，避免误刷到别的连接。
     */
    private suspend fun refreshBrowserAfterDelete(task: TransferTask) {
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

    /** 真正落地删除，返回给用户的提示文案。 */
    private suspend fun deleteTransferPayload(task: TransferTask): String = withContext(Dispatchers.IO) {
        when (task.mode) {
            TransferMode.UPLOAD -> {
                // 远端已上传（含写了一半）的文件。单文件任务再兜一层 target。
                val paths = (task.uploadedTargets +
                    listOfNotNull(task.target.takeIf { it.isNotBlank() && task.uploadedTargets.isEmpty() }))
                    .distinct()
                if (paths.isEmpty()) return@withContext "已删除记录（没有可删除的远端文件）"
                val ok = when (task.targetCtx) {
                    FileContext.SYNO.name -> state.session?.let { dsm.delete(it, paths) } ?: false
                    FileContext.NET.name -> {
                        state.connections.firstOrNull { it.id == task.targetNet }?.let { conn ->
                            network.delete(conn, paths)
                        } ?: false
                    }
                    else -> false
                }
                if (ok) "已删除记录和远端文件 · ${paths.size} 个" else "记录已删除，远端文件删除失败"
            }
            TransferMode.DOWNLOAD -> {
                if (task.target.isBlank()) return@withContext "已删除记录"
                val f = File(task.target)
                runCatching {
                    if (f.isDirectory) {
                        // 多个文件 / 文件夹下载时 target 是「下载目录」本身，
                        // 直接 deleteRecursively 会把整个 Download 目录清空。
                        // 只删这次任务真的下载下来的那些文件（按源文件名反推）。
                        task.sources
                            .mapNotNull { it.trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank) }
                            .forEach { name ->
                                val single = File(f, name)
                                if (single.isFile) single.delete()
                            }
                    } else if (f.exists()) {
                        f.delete()
                    }
                }
                // .part 临时文件：暂停中的下载会留一份，名字永远是「目标名.part」。
                runCatching {
                    val part = File(task.target + ".part")
                    if (part.exists()) part.delete()
                }
                // 兜底：同目录下所有与目标同名的 .part（包括 uniqueTarget 生成的「(1)」变体）。
                runCatching {
                    f.parentFile?.listFiles()?.forEach { sibling ->
                        val name = sibling.name
                        if (name.endsWith(".part") && name.removeSuffix(".part").startsWith(f.nameWithoutExtension)) {
                            sibling.delete()
                        }
                    }
                }
                "已删除记录和文件"
            }
            else -> "已删除记录"
        }
    }

    /** 重新下载（用于文件丢失的记录）：从原位置重新拉一份。 */
    fun redownloadTransfer(task: TransferTask) {
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
            ?: task.target.takeIf { it.startsWith("/") }?.substringBeforeLast('/')?.takeIf { it.isNotBlank() }
            ?: state.prefs.downloadFolder.ifBlank { "/sdcard/Download" }
        val dest = Destination(
            type = DestType.LOCAL, key = "", label = "本地",
            meta = "下载位置", path = destPath
        )
        // 旧记录（文件丢失）先移除，新任务以「进行中」出现在传输中心，进度可见。
        state = state.copy(
            transfers = state.transfers.filterNot { it.id == task.id },
            message = "重新下载 · ${task.title}"
        )
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
            sourceCtx = ctx.name,
            sourceNet = netId ?: ""
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

    /** 检测已下载文件是否被外部删除，更新记录状态为 MISSING。 */
    fun detectMissingFiles() {
        var changed = false
        val updated = state.transfers.map { task ->
            if (task.status == TransferStatus.SUCCESS && task.mode == TransferMode.DOWNLOAD && task.target.isNotBlank()) {
                val exists = File(task.target).exists()
                if (!exists && task.status != TransferStatus.MISSING) {
                    changed = true
                    task.copy(status = TransferStatus.MISSING, error = "文件已丢失或被删除")
                } else task
            } else task
        }
        if (changed) {
            state = state.copy(transfers = updated)
            persistTransfers()
        }
    }

    /**
     * 点击传输记录 / 通知：进行中的跳到传输页，已完成的直接打开那个文件——
     * 与在文件列表里点一下完全一样（图片进相册、安装包直接安装、视频交给播放器）。
     */
    fun openTransferTask(task: TransferTask) {
        if (task.status != TransferStatus.SUCCESS) {
            state = state.copy(
                transferCenterOpen = true,
                message = when (task.status) {
                    TransferStatus.RUNNING -> "该传输仍在进行中"
                    TransferStatus.PAUSED -> "该传输已暂停"
                    else -> "该传输未完成，可在记录里重试"
                }
            )
            return
        }
        val path = task.target.ifBlank { "" }
        if (path.isBlank()) {
            state = state.copy(message = "${task.title} · ${task.detail}")
            return
        }
        val entry = fileEntryAt(path)
        // 远端目标（上传记录）按记录的端来打开：不能拿当前浏览页的端去猜，
        // 否则在本地文件页点开一条群晖上传记录会变成「打开失败」。
        val remoteCtx = when (task.targetCtx) {
            FileContext.SYNO.name -> FileContext.SYNO
            FileContext.NET.name -> FileContext.NET
            else -> null
        }
        // 本机目标被用户手动删掉 / 移走后，记录还在；这时要给一句明确提示，
        // 不能当成远端文件去「下载」一个本机路径。
        if (remoteCtx == null && !File(path).exists()) {
            state = state.copy(message = "「${task.title}」已被移动或删除，无法打开")
            return
        }
        if (remoteCtx != null && !File(path).exists()) {
            state = state.copy(fileSheet = null, preview = null, openWith = null)
            openRemoteFile(entry, gallery = entry.kind == FileKind.IMAGE, forceCtx = remoteCtx)
            return
        }
        if (entry.isFolder) {
            // 多文件 / 文件夹下载：直接进到目标目录，效果和在文件列表里点进去一致。
            val dir = path.trimEnd('/')
            state = state.copy(tab = TabKey.LOCAL)
            openLocalFolderPath(dir, dir.substringAfterLast('/').ifBlank { "内部存储" })
            return
        }
        requestOpen(entry)
    }

    /** 通知栏点击入口：由 MainActivity 把任务 id 传进来。 */
    fun handleTransferNotification(taskId: String) {
        val task = state.transfers.firstOrNull { it.id == taskId }
        if (task == null) {
            state = state.copy(transferCenterOpen = true)
            return
        }
        openTransferTask(task)
    }

    private fun fileEntryAt(path: String): FileEntry {
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
            folder = path.substringBeforeLast('/').substringAfterLast('/'),
            path = path,
            sizeBytes = if (local) file.length() else 0L,
            modified = if (local) file.lastModified() else 0L
        )
    }

    fun performTransfer(destination: Destination) {
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
        // 去重：连点两次「上传 / 下载」不应该建两条任务把同一个文件传两遍。
        val firstTitle = request.items.firstOrNull()?.name ?: ""
        val duplicated = state.transfers.firstOrNull {
            it.status == TransferStatus.RUNNING && !it.quiet && it.mode == request.mode &&
                it.to == destination.path && it.title == firstTitle &&
                System.currentTimeMillis() - it.startedAt < 3_000L
        }
        if (duplicated != null) {
            state = state.copy(transfer = null, message = "该传输已经在进行中，不用重复提交")
            return
        }
        val totalBytes = request.items.filterNot { it.isFolder }.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        // 同端复制 / 移动与在线打开缓存一样，不占用传输中心，只留一条顶部提示。
        val quiet = request.mode == TransferMode.COPY || request.mode == TransferMode.MOVE
        val sourceLabel = sourceLabelOf(request)
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            mode = request.mode,
            title = request.items.firstOrNull()?.name ?: formatTransferTitle(request.mode),
            detail = "${destination.label} · ${request.items.size} 项",
            total = request.items.size,
            totalBytes = totalBytes,
            completed = 0,
            completedBytes = 0,
            status = TransferStatus.RUNNING,
            from = sourceLabel,
            to = destination.path.ifBlank { destination.label },
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
            quiet = quiet
        )
        transferRequests[task.id] = request
        transferDestinations[task.id] = destination
        val startVerb = when (request.mode) {
            TransferMode.DOWNLOAD -> "下载"
            TransferMode.UPLOAD -> "上传"
            TransferMode.COPY -> "复制"
            TransferMode.MOVE -> "移动"
        }
        state = state.copy(
            transfer = null,
            loading = false,
            transferCenterOpen = false,
            selected = emptySet(),
            selectionActive = false,
            transfers = if (quiet) state.transfers else (listOf(task) + state.transfers).take(30),
            // 顶部提示用户操作已开始（清单 9）
            message = if (quiet) state.message else "${startVerb}中 · ${task.title}"
        )
        if (!quiet) {
            persistTransfers()
            TransferNotifications.ensureChannel(app)
            startTransferService(app, task)
        }
        var reportedTarget = ""
        var reportedTotalBytes = totalBytes
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
                    // 只能靠它找到半截文件与 .part 临时文件（任务还没成功，旧代码里 target 是空的）。
                    val target: (String) -> Unit = { path ->
                        reportedTarget = path
                        if (path.isNotBlank()) {
                            viewModelScope.launch {
                                updateTransfer(task.id) { if (it.target != path) it.copy(target = path) else it }
                            }
                        }
                    }
                    when {
                        crossEnd && destination.type == DestType.LOCAL ->
                            downloadToLocal(request.items, destination, request.ctx, request.netId, progress, bytes, task.id, total, totalBytesUpdate, target)
                        crossEnd -> uploadToRemote(
                            request.items, destination, progress, total, bytes, totalBytesUpdate, target,
                            taskId = task.id,
                            onUploaded = { path ->
                                updateTransfer(task.id) {
                                    if (it.uploadedTargets.contains(path)) it
                                    else it.copy(uploadedTargets = it.uploadedTargets + path)
                                }
                            }
                        )
                        request.mode == TransferMode.DOWNLOAD ->
                            downloadToLocal(request.items, destination, request.ctx, request.netId, progress, bytes, task.id, total, totalBytesUpdate, target)
                        request.mode == TransferMode.UPLOAD ->
                            uploadToRemote(
                                request.items, destination, progress, total, bytes, totalBytesUpdate, target,
                                taskId = task.id,
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
            val current = state.transfers.firstOrNull { it.id == task.id }
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
                target = if (status == TransferStatus.SUCCESS) reportedTarget else it.target,
                error = when {
                    status == TransferStatus.PAUSED -> "已暂停"
                    status == TransferStatus.SUCCESS -> null
                    else -> lastTransferError ?: "目标位置不可写、连接中断或登录已过期"
                }
            ) }
            val verb = when (request.mode) {
                TransferMode.DOWNLOAD -> "下载"
                TransferMode.UPLOAD -> "上传"
                TransferMode.COPY -> "复制"
                TransferMode.MOVE -> "移动"
            }
            state = state.copy(
                loading = false,
                selected = emptySet(),
                selectionActive = false,
                message = when (status) {
                    TransferStatus.SUCCESS -> "${verb}完成 · ${request.items.size} 项"
                    TransferStatus.PAUSED -> "已暂停$verb"
                    else -> "${verb}失败：${cleanError(lastTransferError, verb)}"
                }
            )
            if (!quiet) {
                persistTransfers()
                val finished = state.transfers.firstOrNull { it.id == task.id }
                val othersRunning = hasOtherRunning(task.id)
                if (finished != null) {
                    TransferNotifications.notifyFinal(app, finished, useForegroundSlot = !othersRunning)
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
    }

    /** 底层错误文案常常已经自带「下载失败：」，这里去掉重复前缀再拼接。 */
    private fun cleanError(error: String?, verb: String): String {
        val raw = error?.takeIf { it.isNotBlank() } ?: return "请检查连接、权限与目标位置"
        return raw.removePrefix("${verb}失败：").removePrefix("$verb 失败：").trim()
    }

    /** 记录的「从哪来」：优先文件所在目录，多选时给出上级目录 + 数量。 */
    private fun sourceLabelOf(request: TransferRequest): String {
        val root = when (request.ctx) {
            FileContext.LOCAL -> "内部存储"
            FileContext.SYNO -> "群晖 NAS"
            FileContext.NET -> state.connections.firstOrNull { it.id == request.netId }?.name ?: "网络存储"
        }
        val first = request.items.firstOrNull() ?: return root
        val dir = first.path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }
        return if (request.items.size > 1) "$root · $dir（${request.items.size} 项）" else "$root · ${first.path}"
    }

    private fun pushTransferNotification(id: String) {
        state.transfers.firstOrNull { it.id == id }?.let {
            TransferNotifications.update(getApplication(), it)
        }
    }

    fun cancelTransfer(id: String, fromRegistry: Boolean = false) {
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
        val cancelledMode = state.transfers.firstOrNull { it.id == id }?.mode
        val cancelVerb = when (cancelledMode) {
            TransferMode.UPLOAD -> "上传"
            TransferMode.COPY -> "复制"
            TransferMode.MOVE -> "移动"
            else -> "下载"
        }
        state = state.copy(message = "已暂停$cancelVerb")
        val app = getApplication<Application>()
        val othersRunning = hasOtherRunning(id)
        state.transfers.firstOrNull { it.id == id }?.takeIf { !it.quiet }?.let {
            TransferNotifications.notifyFinal(app, it, useForegroundSlot = !othersRunning)
        }
        stopTransferServiceIfIdle(app)
    }

    fun retryTransfer(task: TransferTask) {
        if (task.status != TransferStatus.FAILED && task.status != TransferStatus.PAUSED) return
        val request = transferRequests[task.id]
        val destination = transferDestinations[task.id]
        if (request == null || destination == null) {
            state = state.copy(message = "应用重启后无法重试该任务，请重新发起")
            return
        }
        state = state.copy(
            transfers = state.transfers.filterNot { it.id == task.id },
            transfer = request
        )
        performTransfer(destination)
    }

    /**
     * 统一的后台任务进度上报器。上传 / 下载 / 缓存打开都通过它把
     * 「已经处理了几项、已经传了多少字节」实时写回传输中心。
     */
    private inner class TaskReporter(val id: String) {
        fun progress(count: Int) {
            viewModelScope.launch {
                updateTransfer(id) { it.copy(completed = count.coerceIn(0, it.total)) }
                pushTransferNotification(id)
            }
        }

        fun total(count: Int) {
            viewModelScope.launch {
                updateTransfer(id) { it.copy(total = count.coerceAtLeast(1)) }
                pushTransferNotification(id)
            }
        }

        fun bytes(done: Long) {
            viewModelScope.launch {
                updateTransfer(id) {
                    it.copy(
                        completedBytes = if (it.totalBytes > 0L)
                            done.coerceIn(0L, it.totalBytes) else done.coerceAtLeast(0L)
                    )
                }
                pushTransferNotification(id)
            }
        }

        fun note(text: String) {
            viewModelScope.launch {
                updateTransfer(id) { it.copy(detail = text) }
                pushTransferNotification(id)
            }
        }

        /** 文件夹展开后的真实字节总数（进度分母）。 */
        fun totalBytes(sum: Long) {
            if (sum <= 0L) return
            viewModelScope.launch {
                updateTransfer(id) { if (it.totalBytes == sum) it else it.copy(totalBytes = sum) }
            }
        }

        /** 记录当前真实落地路径：删除进行中的任务时靠它清理半截文件。 */
        fun target(path: String) {
            if (path.isBlank()) return
            viewModelScope.launch {
                updateTransfer(id) { if (it.target == path) it else it.copy(target = path) }
            }
        }

        /**
         * 记录一个已经写到远端的文件路径。
         * 暂停上传时当前那个文件也已写入一部分，所以「开始传」就先登记，
         * 删除任务时能把这半截文件一起清掉。
         */
        fun uploaded(path: String) {
            if (path.isBlank()) return
            viewModelScope.launch {
                updateTransfer(id) {
                    if (it.uploadedTargets.contains(path)) it
                    else it.copy(uploadedTargets = it.uploadedTargets + path)
                }
                persistTransfers()
            }
        }

        fun cancelled(): Boolean = TransferCancellation.isCancelled(id)
    }

    /**
     * 统一的后台传输入口。
     *
     * 点击即建任务并立刻出现在「传输 → 进行中」，进度实时更新；结束写入记录并给顶部提示。
     * 页面只负责发起，不再设置全局 loading（旧实现用 loading 转圈，什么都看不到）。
     */
    private fun launchTransferTask(
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
        sourceCtx: String = "",
        sourceNet: String = "",
        quiet: Boolean = false,
        onDone: suspend (Boolean) -> Unit = {},
        work: suspend (TaskReporter) -> Boolean
    ): String {
        val app = getApplication<Application>()
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
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
            quiet = quiet
        )
        lastTransferError = null
        // 静默任务（在线打开缓存等）不进传输中心，也不落盘。
        if (!quiet) {
            state = state.copy(
                transfers = (listOf(task) + state.transfers).take(30),
                // 上传/下载一开始就给顶部提示。之前只更新了任务列表，
                // 从文件页发起的上传没有任何反馈，看起来像点了没反应。
                message = "${verb}中 · ${title}"
            )
            persistTransfers()
            TransferNotifications.ensureChannel(app)
            startTransferService(app, task)
        }
        val reporter = TaskReporter(task.id)
        val job = viewModelScope.launch {
            val ok = runCatching {
                withTimeoutOrNull(6 * 60 * 60 * 1000L) {
                    withContext(Dispatchers.IO) { work(reporter) }
                } ?: false
            }.getOrElse { false }
            val current = state.transfers.firstOrNull { it.id == task.id }
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
                val finished = state.transfers.firstOrNull { it.id == task.id }
                val othersRunning = hasOtherRunning(task.id)
                if (finished != null) {
                    TransferNotifications.notifyFinal(app, finished, useForegroundSlot = !othersRunning)
                }
                stopTransferServiceIfIdle(app)
            }
            TransferCancellation.clear(task.id)
            runCatching { onDone(status == TransferStatus.SUCCESS) }
        }
        transferJobs[task.id] = job
        job.invokeOnCompletion { transferJobs.remove(task.id) }
        return task.id
    }

    /** 上传 / 下载都要有通知栏进度：同一个前台服务，多个任务共用一条进行中通知。 */
    private fun startTransferService(app: Application, task: TransferTask) {
        val intent = Intent(app, TransferService::class.java)
            .putExtra(TransferNotifications.EXTRA_TASK_ID, task.id)
            .putExtra(TransferNotifications.EXTRA_TASK_TITLE, task.title)
            .putExtra(TransferNotifications.EXTRA_TASK_TOTAL, task.totalBytes)
            .putExtra(TransferNotifications.EXTRA_TASK_MODE, task.mode.name)
        runCatching { ContextCompat.startForegroundService(app, intent) }
    }

    /** 还有正在进行的任务就不要停前台服务，否则其余任务的进度条会突然消失。 */
    private fun stopTransferServiceIfIdle(app: Application) {
        if (state.transfers.any { it.status == TransferStatus.RUNNING && !it.quiet }) return
        // 不用 stopService：那样会把刚更新成「已完成」的那条通知一起撤掉。
        // 让服务自己 stopForeground(DETACH) + stopSelf，通知继续留在通知栏可点。
        runCatching {
            app.startService(
                Intent(app, TransferService::class.java).setAction(TransferService.ACTION_DETACH)
            )
        }
    }

    /** 是否还有别的任务在跑（决定完成通知能不能复用前台通知那一条）。 */
    private fun hasOtherRunning(excludeTaskId: String): Boolean =
        state.transfers.any { it.id != excludeTaskId && it.status == TransferStatus.RUNNING && !it.quiet }

    /** 单个文件上传到当前远程位置（含群晖会话续期）。 */
    private suspend fun uploadOne(
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
    private suspend fun createRemoteFolder(browser: BrowserState, parent: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            when (browser.ctx) {
                FileContext.SYNO -> state.session?.let { dsm.createFolder(it, parent, name) } ?: false
                FileContext.NET -> state.connections.firstOrNull { it.id == browser.netId }
                    ?.let { network.createFolder(it, parent, name) } ?: false
                FileContext.LOCAL -> false
            }
        }

    private suspend fun downloadToLocal(
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
        var all = true
        var completed = 0
        var completedBytes = 0L

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
        items.forEach { item ->
            if (item.isFolder) collect(item, item.name, 0) else pending += item.name to item
        }
        onTotal(pending.count { it.second != null }.coerceAtLeast(1))
        // 文件夹展开后的真实字节数才是进度分母，否则 totalBytes=0 会让进度条永远 0%。
        val realBytes = pending.sumOf { (_, e) -> e?.sizeBytes?.coerceAtLeast(0L) ?: 0L }
        if (realBytes > 0L) onTotalBytes(realBytes)

        // 记录「完成后能直接打开哪个文件」：单文件就是它本身，多文件 / 文件夹是目标目录。
        val fileCount = pending.count { it.second != null }
        onTarget(dir)

        for ((rel, item) in pending) {
            if (TransferCancellation.isCancelled(taskId)) return false
            val target = File(dir, rel)
            if (item == null) {
                runCatching { target.mkdirs() }
                continue
            }
            // 同名去重：本地已有同名且同大小的文件，直接视为已完成，不重复下载。
            if (target.exists() && target.isFile && item.sizeBytes > 0L && target.length() == item.sizeBytes) {
                completedBytes += item.sizeBytes
                completed++
                onProgress(completed)
                onBytes(completedBytes)
                continue
            }
            val unique = uniqueTarget(target)
            unique.parentFile?.mkdirs()
            if (fileCount == 1) onTarget(unique.absolutePath)
            val fileBytes: (Long) -> Unit = { written -> onBytes(completedBytes + written) }
            val abortCheck = { TransferCancellation.isCancelled(taskId) }
            var ticker: kotlinx.coroutines.Job? = null
            val ok = try {
                when (ctx) {
                    FileContext.SYNO -> state.session?.let {
                        // 只有「恢复任务」才断点续传；全新下载若残留旧 .part 视为脏数据，
                        // 直接从头下载（远端文件可能已变化，续传旧数据会拼出损坏文件）。
                        val partFile = File(unique.parentFile, "${unique.name}.part")
                        if (!resume && partFile.exists()) runCatching { partFile.delete() }
                        dsm.download(it, item.path, unique, fileBytes, abortCheck, resume = resume && partFile.exists())
                    } ?: false
                    FileContext.NET -> {
                        // 直接写最终文件，不再用 .part 中转：
                        // ① SMB / FTP / SFTP / WebDAV 都不支持从中间续传，.part 没有续传收益；
                        // ② MIUI 的 FUSE 对「刚写完的临时文件」stat 会返回 0 长度，此时
                        //    rename 必失败（3.9.15 真机实证：partLen=0、copy 兜底也失败）。
                        ticker = viewModelScope.launch {
                            while (isActive) {
                                onBytes(completedBytes + unique.length().coerceAtLeast(0L))
                                delay(350)
                            }
                        }
                        val conn = state.connections.firstOrNull { it.id == netId }
                        // 连接对象找不到时不能只回一句「目录不可写」——把真实原因留给用户，
                        // 否则表现为「点开始下载就失败、远端却收到的请求为 0」。
                        if (conn == null) {
                            lastTransferError = "连接已失效，请返回后重新进入"
                            android.util.Log.w(
                                "MyFileBox",
                                "download: NET connection missing netId=$netId conns=${state.connections.map { it.id }}"
                            )
                        }
                        val okNet = if (conn == null) false else {
                            runCatching { if (unique.exists()) unique.delete() }
                            network.download(conn, item.path, unique, abortCheck, item.sizeBytes)
                        }
                        // 半截文件不能留：占住目标名会让下次下载变成 (1) 重复文件。
                        if (!okNet) runCatching { if (unique.exists()) unique.delete() }
                        okNet
                    }
                    else -> false
                }
            } catch (ce: CancellationException) {
                // 暂停/取消时保留 .part 临时文件：恢复下载时可以复用已下载数据。
                // 只删除已完成的目标文件（可能是空文件或不完整的目标）。
                runCatching { if (unique.exists() && unique.length() == 0L) unique.delete() }
                throw ce
            } finally {
                ticker?.cancel()
            }
            if (TransferCancellation.isCancelled(taskId)) {
                // 暂停：保留 .part 和目标文件，不清进度。
                return false
            }
            if (!ok) {
                all = false
                // 优先用底层给的真实原因（超时 / 权限 / 会话失效），否则只能给出笼统文案。
                if (lastTransferError == null) {
                    lastTransferError = dsm.lastError ?: network.lastError
                        ?: "源文件不可读、连接已断开或目录不可写"
                }
                android.util.Log.w(
                    "MyFileBox",
                    "download failed path=${item.path} dir=$dir size=${item.sizeBytes} err=${lastTransferError}"
                )
                runCatching { if (unique.exists() && unique.length() == 0L) unique.delete() }
                // 真实失败清理 .part（与暂停区分：暂停保留、失败清理）。
                runCatching { File(unique.parentFile, "${unique.name}.part").delete() }
            } else {
                completedBytes += maxOf(item.sizeBytes, unique.length())
            }
            completed++
            onProgress(completed)
            onBytes(completedBytes)
        }
        return all
    }

    private fun uniqueTarget(target: File): File {
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

    private suspend fun uploadToRemote(
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
        val root = destination.path.trimEnd('/').ifBlank { "/" }
        network.clearError()
        var all = true
        var completed = 0
        var sentBytes = 0L
        for ((file, rel) in pending) {
            // 暂停/取消要真的停下来：每个文件开始前先看标记。
            if (TransferCancellation.isCancelled(taskId)) return false
            val dir = when {
                rel.isBlank() -> root
                root == "/" -> "/$rel"
                else -> "$root/$rel"
            }
            val remotePath = if (dir == "/") "/${file.name}" else "${dir.trimEnd('/')}/${file.name}"
            // 先登记远端目标：万一传到一半被暂停，远端已经留下半截文件，
            // 删除任务时要能把它一起清掉。
            onUploaded(remotePath)
            val abortCheck = { TransferCancellation.isCancelled(taskId) }
            val base = sentBytes
            val ok = when (destination.type) {
                DestType.SYNO -> {
                    val s = state.session
                    if (s == null) {
                        lastTransferError = "群晖登录已失效，请重新登录"
                        false
                    } else {
                        val result = dsm.upload(s, file, dir, file.name, { bytes -> onBytes(base + bytes) }, abortCheck)
                        if (!result) {
                            if (TransferCancellation.isCancelled(taskId)) return false
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
                            if (TransferCancellation.isCancelled(taskId)) return false
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
            sentBytes = base + runCatching { file.length() }.getOrDefault(0L)
            onBytes(sentBytes)
            // 单文件上传记录远端目标路径，完成后可直接在线打开 / 安装。
            if (pending.size == 1) onTarget(remotePath)
            if (!ok) all = false
            completed++
            onProgress(completed)
        }
        return all
    }

    private suspend fun copyOrMove(request: TransferRequest, destination: Destination): Boolean {
        return when (request.ctx) {
            FileContext.LOCAL -> request.items.all { local.copy(it, destination.path, request.mode == TransferMode.MOVE) }
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

    private fun destinationsFor(mode: TransferMode, ctx: FileContext): List<Destination> {
        val locals = state.localFolders.map { Destination(DestType.LOCAL, it.path, it.name, "内部存储 / ${it.name}", it.path) }
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

    private fun joinRemote(base: String, name: String): String {
        val left = base.trimEnd('/')
        return "$left/$name"
    }

    /** Directory tree used by the upload / download / copy target picker. */
    suspend fun childDirectories(dest: Destination): List<Destination> = withContext(Dispatchers.IO) {
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

    fun parentDestination(dest: Destination): Destination? {
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
        else File(parent).name
        val label = name.ifBlank { "上级目录" }
        return dest.copy(
            label = label,
            meta = dest.meta.substringBefore(" / ") + " / " + label,
            path = parent
        )
    }

    /** Create a sub-folder inside a picker destination (local storage only). */
    fun createDirectoryAt(dest: Destination, name: String): Boolean {
        if (name.isBlank() || dest.type != DestType.LOCAL) return false
        return runCatching { File(dest.path, name).mkdirs() }.getOrDefault(false)
    }

    fun setDownloadFolder(path: String, silent: Boolean = false) {
        if (path.isBlank()) return
        local.setDownloadFolder(path)
        state = state.copy(
            prefs = state.prefs.copy(downloadFolder = path),
            message = if (silent) null else "默认下载位置已更新"
        )
        persist()
    }

    fun downloadFolderCandidates(): List<Pair<String, String>> {
        val base = state.localFolders.map { it.path to it.name }.toMutableList()
        val downloads = local.downloadsDir()
        if (base.none { it.first == downloads.absolutePath }) {
            base += downloads.absolutePath to downloads.name
        }
        return base.distinctBy { it.first }
    }

    // ---------------------------------------------------------------- delete / share

    fun deleteFile(file: FileEntry) {
        state = state.copy(
            pendingDelete = listOf(file),
            pendingDeleteLabel = file.name
        )
    }

    fun deleteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        state = state.copy(
            pendingDelete = items,
            pendingDeleteLabel = if (items.size == 1) items.first().name else "${items.size} 个项目"
        )
    }

    fun cancelDelete() {
        state = state.copy(
            pendingDelete = emptyList(),
            pendingDeleteLabel = "",
            pendingDeleteConnection = null,
            pendingDeleteHome = false
        )
    }

    fun confirmDelete() {
        val items = state.pendingDelete
        if (items.isEmpty()) return
        val fromHome = state.pendingDeleteHome
        state = state.copy(
            pendingDelete = emptyList(),
            pendingDeleteLabel = "",
            pendingDeleteHome = false,
            selected = emptySet(),
            selectionActive = false,
            homeSelected = if (fromHome) emptySet() else state.homeSelected,
            homeSelecting = if (fromHome) false else state.homeSelecting
        )
        deleteFiles(items)
    }

    fun requestDeleteConnection(connection: NetworkConnection) {
        state = state.copy(pendingDeleteConnection = connection.id)
    }

    fun confirmDeleteConnection() {
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

    fun stageFolderClipboard(mode: TransferMode, folder: FolderCard) {
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
            clipboard = ClipboardState(mode, listOf(entry), FileContext.LOCAL, null),
            selected = emptySet(),
            selectionActive = false,
            message = if (mode == TransferMode.COPY) "已复制，进入目标文件夹后点粘贴" else "已剪切，进入目标文件夹后点粘贴"
        )
    }

    fun startHomeFolderTransfer(mode: TransferMode, folder: FolderCard) {
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

    fun deleteHomeFolder(folder: FolderCard) {
        val entry = FileEntry(
            id = "home-folder:${folder.path}",
            name = folder.name,
            kind = FileKind.FOLDER,
            path = folder.path
        )
        state = state.copy(pendingDelete = listOf(entry), pendingDeleteLabel = folder.name)
    }

    private fun deleteFiles(items: List<FileEntry>) {
        if (items.isEmpty()) return
        val ctx = state.browser?.ctx ?: FileContext.LOCAL
        state = state.copy(loading = true)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                when (ctx) {
                    FileContext.LOCAL -> local.recycle(items) > 0
                    FileContext.SYNO -> state.session?.let { dsm.delete(it, items.map { f -> f.path }) } ?: false
                    FileContext.NET -> {
                        val conn = state.connections.firstOrNull { it.id == state.browser?.netId }
                        if (conn == null) false else network.delete(conn, items.map { it.path })
                    }
                }
            }
            state = state.copy(
                loading = false,
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

    private suspend fun reloadBrowserItems() {
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
    private fun localSnapshotOf(): com.yaoahos.myfilebox.android.data.LocalSnapshot =
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
    private fun applyLocalDelta(
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

    fun shareSelected() { shareFiles(selectedItems()) }

    fun shareFile(file: FileEntry) { shareFiles(listOf(file)) }

    fun shareFiles(files: List<FileEntry>) {
        val localFiles = files.filter { it.path.startsWith("/") && File(it.path).exists() }
        if (localFiles.isEmpty()) {
            state = state.copy(message = "远程文件请先下载后再分享")
            return
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

    // ---------------------------------------------------------------- settings / modules / connections

    fun moduleVisible(key: String): Boolean = key !in state.prefs.hiddenModules

    fun toggleModule(key: String) {
        val hidden = state.prefs.hiddenModules.toMutableSet()
        if (!hidden.add(key)) hidden.remove(key)
        state = state.copy(prefs = state.prefs.copy(hiddenModules = hidden))
        persist()
    }

    fun moveModule(key: String, delta: Int) {
        val group = key.substringBefore(":")
        val order = state.prefs.moduleOrder.toMutableList()
        val indices = order.indices.filter { order[it].startsWith("$group:") }
        if (indices.isEmpty()) return
        val localOrder = indices.map { order[it] }.toMutableList()
        val index = localOrder.indexOf(key)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, localOrder.lastIndex)
        if (target == index) return
        localOrder.removeAt(index)
        localOrder.add(target, key)
        indices.forEachIndexed { i, position -> order[position] = localOrder[i] }
        state = state.copy(prefs = state.prefs.copy(moduleOrder = order))
        persist()
    }

    fun volume(id: String = state.prefs.volumeId): Volume? =
        state.volumes.firstOrNull { it.id == id } ?: state.volumes.firstOrNull()

    fun setVolume(id: String) {
        state = state.copy(prefs = state.prefs.copy(volumeId = id), message = "已切换存储空间")
        persist()
    }

    fun openCleaner() {
        val context = getApplication<Application>()
        val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { state = state.copy(message = "已打开系统存储设置") }
            .onFailure { state = state.copy(message = "无法打开系统存储设置") }
    }

    /** 返回是否保存成功；失败时对话框保持打开，用户不用重新输入。 */
    /**
     * 解析用户输入的服务器地址。支持三种写法：
     * ① 纯 IP/域名（192.168.1.100）② 带端口（192.168.1.100:5255）③ 完整 URL（http://192.168.1.100:5255/dav）
     * 返回 (host, port, path)，port/path 为空串表示用户没填、用表单里的值。
     */
    private fun parseServerInput(raw: String): Triple<String, String, String> {
        var s = raw.trim()
        var scheme = ""
        if (s.startsWith("https://", true)) { scheme = "https"; s = s.removePrefix("https://").removePrefix("HTTPS://") }
        else if (s.startsWith("http://", true)) { scheme = "http"; s = s.removePrefix("http://").removePrefix("HTTP://") }
        if (scheme.isEmpty()) return Triple(raw.trim(), "", "")
        // 完整 URL：拆出 host[:port]/path]
        val slashIdx = s.indexOf('/')
        val hostPort = if (slashIdx >= 0) s.substring(0, slashIdx) else s
        val path = if (slashIdx >= 0) s.substring(slashIdx) else ""
        val colonIdx = hostPort.lastIndexOf(':')
        val host = if (colonIdx > 0) hostPort.substring(0, colonIdx) else hostPort
        val port = if (colonIdx > 0) hostPort.substring(colonIdx + 1) else if (scheme == "https") "443" else "80"
        return Triple(host, port, path)
    }

    fun saveConnection(id: String?, name: String, protocol: String, host: String, port: String, username: String, password: String, path: String, secure: Boolean): Boolean {
        // 连接配置变更，网络搜索索引作废（清单 11）。
        if (state.searchIndexNet.isNotEmpty()) state = state.copy(searchIndexNet = emptyMap())
        // 用户可能把完整 URL 填进「服务器地址」，自动拆出 host/port/path。
        val (parsedHost, parsedPort, parsedPath) = parseServerInput(host)
        val effectivePort = parsedPort.ifBlank { port }
        val effectivePath = parsedPath.ifBlank { path }
        val effectiveSecure = secure || parsedPort == "443"
        val portNumber = effectivePort.toIntOrNull()
        if (parsedHost.isBlank()) {
            state = state.copy(message = "保存失败：请填写服务器地址")
            return false
        }
        if (portNumber == null || portNumber !in 1..65535) {
            state = state.copy(message = "保存失败：端口需在 1-65535 之间")
            return false
        }
        // 名称不再是必填项：留空时用主机地址当名字，卡片上仍能分辨。
        val displayName = name.trim().ifBlank { parsedHost.trim() }
        val connection = NetworkConnection(
            id = id ?: "nc-${UUID.randomUUID().toString().take(8)}",
            name = displayName,
            protocol = protocol,
            host = parsedHost.trim(),
            port = portNumber,
            path = if (protocol == "SMB") effectivePath.trim() else effectivePath.ifBlank { "/" },
            online = false,
            latency = 0,
            username = username,
            password = password,
            secure = effectiveSecure
        )
        val next = if (id == null) state.connections + connection else state.connections.map { if (it.id == id) connection else it }
        state = state.copy(connections = next, message = if (id == null) "已添加「$displayName」" else "连接已更新")
        persist()
        testConnection(connection)
        return true
    }

    fun addConnection(
        name: String,
        protocol: String,
        host: String,
        port: String,
        path: String = "/",
        username: String = "",
        password: String = "",
        secure: Boolean = true
    ): Boolean = saveConnection(null, name, protocol, host, port, username, password, path, secure)

    fun updateConnection(
        id: String,
        name: String,
        protocol: String,
        host: String,
        port: String,
        path: String = "/",
        username: String = "",
        password: String = "",
        secure: Boolean = true
    ): Boolean {
        val old = state.connections.firstOrNull { it.id == id }
        return saveConnection(
            id, name, protocol, host, port,
            username.ifBlank { old?.username.orEmpty() },
            password.ifBlank { old?.password.orEmpty() },
            path.ifBlank { old?.path ?: "/" },
            secure
        )
    }

    fun contextOf(tab: TabKey): FileContext = when (tab) {
        TabKey.LOCAL -> FileContext.LOCAL
        TabKey.SYNO -> FileContext.SYNO
        TabKey.NET -> FileContext.NET
    }

    // ---------------------------------------------------------------- search

    fun openSearch(ctx: FileContext = contextOf(state.tab)) {
        state = state.copy(
            search = SearchState(ctx),
            selected = emptySet(),
            selectionActive = false,
            searchIndexing = ctx != FileContext.LOCAL
        )
        ensureSearchIndex()
    }

    fun closeSearch() {
        searchIndexJob?.cancel()
        state = state.copy(search = null, searchIndexing = false)
    }

    fun setSearchQuery(query: String) {
        state = state.copy(search = state.search?.copy(query = query))
        ensureSearchIndex()
    }

    fun setSearchFolder(folder: String, label: String = "") {
        state = state.copy(
            search = state.search?.copy(
                folder = folder,
                folderLabel = when {
                    folder == "all" -> "所有位置"
                    label.isNotBlank() -> label
                    else -> state.search?.folderLabel ?: "所有位置"
                }
            )
        )
    }
    fun setSearchExt(ext: String) { state = state.copy(search = state.search?.copy(ext = ext)) }

    /**
     * 搜索位置选择器的第一层：本地直接列出存储根目录下的文件夹（不再提供
     * 与「所有位置」语义冲突的「内部存储」条目），群晖列共享文件夹，网络列连接。
     */
    fun searchRootDestinations(ctx: FileContext): List<Destination> = when (ctx) {
        FileContext.LOCAL -> runCatching {
            File(state.localRoot.ifBlank { Environment.getExternalStorageDirectory().absolutePath })
                .listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith('.') && it.canRead() }
                .sortedBy { it.name.lowercase() }
                .map { Destination(DestType.LOCAL, it.absolutePath, it.name, "内部存储 / ${it.name}", it.absolutePath) }
        }.getOrDefault(emptyList())
        FileContext.SYNO -> state.shares.map {
            Destination(DestType.SYNO, it.path, it.title, "群晖 NAS / ${it.title}", it.path)
        }
        FileContext.NET -> state.connections.map {
            Destination(DestType.NET, it.id, it.name, "${it.protocol} / ${it.host}", network.rootPath(it))
        }
    }

    /** 搜索位置选择器的唯一键：网络端要带上连接 id，否则不同连接的同一路径会串。 */
    fun searchFolderKey(dest: Destination): String =
        if (dest.type == DestType.NET) "net:${dest.key}|${dest.path}" else dest.path

    fun searchFolderValueOf(dest: Destination, isRoot: Boolean): String = when (dest.type) {
        DestType.NET -> if (isRoot) dest.key else "net:${dest.key}|${dest.path}"
        else -> dest.path
    }


    private var searchIndexJob: Job? = null

    /** Load the top level of Synology shares / network roots so local search works there too. */
    private fun ensureSearchIndex() {
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
    fun searchFolders(search: SearchState): List<Pair<String, String>> {
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

    private fun matchesFolder(file: FileEntry, value: String, ctx: FileContext): Boolean {
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

    fun searchResults(search: SearchState): List<FileEntry> {
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
                    val name = dir.substringAfterLast('/')
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
                    folder = "群晖 NAS",
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

    fun openSearchResult(file: FileEntry) {
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
    fun refresh() {
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

    // ---------------------------------------------------------------- 下拉刷新（按页面拆分）

    /**
     * 下拉刷新的硬兜底上限。
     *
     * 正常路径都会提前 [endPull]（本地目录一两百毫秒、群晖目录一秒内、网络目录最多
     * 4 秒连接超时），这个值只覆盖最慢的一种：切网后先修通道再读目录，自愈重登可能
     * 拖到十几秒。定这么长是为了不误杀真实请求，同时保证「无论发生什么，25 秒后
     * 图标一定停下」——用户不会对着一个永远转的圈等。
     */
    private val pullWatchdogMs = 25_000L

    /** 下拉刷新打点：日志里能看到「转圈多久」，排查「刷新慢」不用靠手感描述。 */
    private fun logPull(page: String, started: Long) {
        android.util.Log.i("MFB-REFRESH", "$page ${System.currentTimeMillis() - started}ms")
    }

    /**
     * 开始一轮下拉刷新，返回这一轮的令牌。
     *
     * 这里刻意**不做**「已经在刷就忽略」：用户又拉一下，正说明他判断的是「没反应」，
     * 此刻最不该做的就是什么都不做。重复请求由 [loadFolder] 内部先 cancel 旧 job 收口。
     */
    private fun beginPull(): Int {
        refreshToken++
        val token = refreshToken
        refreshTimeoutJob?.cancel()
        pullStartedAt = System.currentTimeMillis()
        state = state.copy(refreshing = true, message = null)
        refreshTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(pullWatchdogMs)
            if (refreshToken != token) return@launch
            android.util.Log.w("MFB-REFRESH", "watchdog token=$token：迟迟没有结果，强制收圈")
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
    private fun endPull(token: Int, message: String? = null) {
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
    fun refreshLocalTab() {
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
    fun refreshSynoTab() {
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
    fun refreshNetTab() {
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
    fun refreshBrowser() {
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
    private fun watchBrowserLoadJob(token: Int, page: String) {
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
    private suspend fun pullLocalSnapshot() {
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
    private suspend fun pullSynoSummary() {
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
    private suspend fun pullNetStatus() {
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
    fun updateCheckOnStartup(): Boolean = prefsStore.getBoolean("update_check_startup", true)

    fun setUpdateCheckStartup(enabled: Boolean) {
        prefsStore.edit().putBoolean("update_check_startup", enabled).apply()
        state = state.copy(message = if (enabled) "已开启：每次冷启动检查一次更新" else "已关闭：启动时不再检查更新")
    }

    /** 手动检查：有新版弹 updateInfo；没有/失败都给明确提示。自动检查静默。 */
    fun checkForUpdate(manual: Boolean) {
        viewModelScope.launch {
            if (manual) state = state.copy(loading = true)
            val result = runCatching {
                withContext(Dispatchers.IO) { com.yaoahos.myfilebox.android.update.GitHubUpdater.fetchLatest() }
            }
            if (manual) state = state.copy(loading = false)
            result.onSuccess { info ->
                val current = com.yaoahos.myfilebox.android.BuildConfig.VERSION_NAME
                if (com.yaoahos.myfilebox.android.update.GitHubUpdater.isNewer(info.version, current)) {
                    state = state.copy(updateInfo = info, message = if (manual) "发现新版本 ${info.version}" else null)
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
    private fun maybeAutoCheckUpdate() {
        if (!updateCheckOnStartup()) return
        checkForUpdate(manual = false)
    }

    fun dismissUpdateDialog() { state = state.copy(updateInfo = null) }

    /** 下载安装包到 cache/updates/ 后走应用既有的 APK 安装流。 */
    fun downloadUpdate() {
        val info = state.updateInfo ?: return
        if (info.apkUrl.isBlank()) {
            state = state.copy(message = "该版本没有安装包，请到发布页下载")
            return
        }
        viewModelScope.launch {
            state = state.copy(updateDownloading = true, updateProgress = 0)
            val target = java.io.File(
                getApplication<Application>().cacheDir,
                "updates/MyFileBox-${info.version}.apk"
            )
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    com.yaoahos.myfilebox.android.update.GitHubUpdater.download(info.apkUrl, target) { percent ->
                        state = state.copy(updateProgress = percent)
                    }
                }
            }
            state = state.copy(updateDownloading = false)
            result.onSuccess {
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
                val msg = (e as? com.yaoahos.myfilebox.android.update.UpdateException)?.message
                    ?: "下载失败：${e.message ?: "网络异常"}"
                state = state.copy(message = msg)
            }
        }
    }

    /** 更新日志：优先内置 assets/updateLog.md，联网失败也有的看。 */
    fun loadUpdateLog() {
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

    fun closeUpdateLog() { state = state.copy(updateLogOpen = false) }
}

fun formatTransferTitle(mode: TransferMode): String = when (mode) {
    TransferMode.DOWNLOAD -> "下载到本地"
    TransferMode.UPLOAD -> "上传到"
    TransferMode.COPY -> "复制"
    TransferMode.MOVE -> "移动"
}

fun fileKindLabel(kind: FileKind): String = when (kind) {
    FileKind.IMAGE -> "图片"
    FileKind.VIDEO -> "视频"
    FileKind.DOCUMENT -> "文档"
    FileKind.PDF -> "PDF"
    FileKind.SHEET -> "表格"
    FileKind.AUDIO -> "音频"
    FileKind.ARCHIVE -> "压缩包"
    FileKind.APK -> "安装包"
    FileKind.FOLDER -> "文件夹"
    FileKind.UNKNOWN -> "文件"
}

/**
 * 在线预览 / 在线打开的体积上限。
 *
 * 这些文件都要先落到本机缓存才能交给系统组件，文件越大等待越久、缓存越吃空间。
 * 超过上限时直接提示「下载后查看」，而不是让用户对着转圈等一个几百兆的文档。
 * 视频不在此列：视频走外部播放器流式播放，边下边播、可拖进度，不需要整份缓存。
 */
private const val PREVIEW_TEXT_LIMIT = 2L * 1024 * 1024
private const val PREVIEW_AUDIO_LIMIT = 64L * 1024 * 1024
private const val PREVIEW_IMAGE_LIMIT = 256L * 1024 * 1024

/** 其它文件「缓存后交给系统应用打开」的上限（apk、文档、压缩包等）。 */
private const val OPEN_CACHE_LIMIT = 1024L * 1024 * 1024

/**
 * 音视频流式播放失败后，允许退回「先缓存再打开」的体积上限。
 *
 * 视频本来就是为了不落地才走流式；一旦流式不可用（播放器不认 http、协议不支持），
 * 旧实现会不分大小地开始缓存整份文件：点一个 20GB 的片子等于静默开了一个 20GB 下载，
 * 手机存不下、用户也不知道在等什么。超过这个体积就直接告诉用户「先下载再看」。
 */
private const val STREAM_FALLBACK_LIMIT = 512L * 1024 * 1024

/**
 * org.json 没有把 JSONArray 转成 List 的便捷方法，
 * 而传输记录里「源路径」「已上传目标」都是字符串数组，统一在这里转换。
 */
private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optString(index, "").takeIf { it.isNotBlank() }
    }
}
