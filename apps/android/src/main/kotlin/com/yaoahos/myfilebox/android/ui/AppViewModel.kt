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
import com.yaoahos.myfilebox.core.effectiveDefaultTab
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


enum class Screen { MAIN, SETTINGS, APPEARANCE, ABOUT, BACKUP_SYNC }
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

/**
 * 这一页是不是「集合快照」——收藏、图片 / 视频 / 最近这些分类页。
 *
 * 它们不是目录：没有真实的上级路径，也不该出现在面包屑里当某一层目录。
 */
internal fun BrowserState.isVirtualPage(): Boolean = favorites || localCategory != null

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

/**
 * 传输任务状态。
 *
 * [QUEUED] = 同一次多选传输被拆成多条记录后，还没轮到自己执行的那些：
 * 记录先建出来（用户选了几项就看到几条），执行按顺序一条条来。
 */
enum class TransferStatus { QUEUED, RUNNING, PAUSED, SUCCESS, FAILED, CANCELLED, MISSING }

/** 文件夹传输里单个子文件的状态。 */
enum class TransferChildStatus { QUEUED, RUNNING, SUCCESS, FAILED, PAUSED }

/**
 * 文件夹展开后的单个子文件进度，供传输中心逐条展示。
 * 只有文件数 > 1（多选 / 文件夹）时才填充，单文件传输保持 children 为空。
 */
data class TransferChild(
    /** 相对路径，作为稳定 key（同名不同目录不会串）。 */
    val key: String,
    /** 展示名：相对路径。 */
    val name: String,
    /** 源文件真实路径，单项重试要用。 */
    val path: String,
    val sizeBytes: Long = 0L,
    val completedBytes: Long = 0L,
    val status: TransferChildStatus = TransferChildStatus.QUEUED,
    val error: String? = null,
    /** 本地落地路径（下载）或远端目标路径（上传）。 */
    val target: String = "",
    /** 同名同大小被跳过（未真正写入）——删除任务时不能把它当成下载产物删掉。 */
    val preExisting: Boolean = false,
    /** 因目标已存在而另存为「(n)」时，记录被避开的原有文件；删任务绝不能碰它。 */
    val conflictOriginal: String? = null
)

data class TransferTask(
    val id: String,
    val mode: TransferMode,
    val title: String,
    val detail: String,
    val total: Int,
    /**
     * 本次传输的条目里是否包含文件夹（多选也算）。
     *
     * 必须记在记录里，不能事后靠 title / target 去猜：上传 1 个文件夹时记录里只有
     * 「1 项」、target 指向文件夹里第一个文件，看起来和单文件传输一模一样，
     * 于是点击记录会去「打开那个文件」，而不是目标文件夹。下载侧同理
     * （目标文件被删掉后就再也判断不出它原本是文件还是文件夹）。
     */
    val folderTask: Boolean = false,
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
    /** 单文件下载因同名冲突另存为「(n)」时，被避开的原有文件；删任务绝不能碰它。 */
    val conflictOriginal: String? = null,
    /** 单文件因同名同大小被跳过：target 是用户原有文件，删任务只清 .part。 */
    val targetPreExisting: Boolean = false,
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
     * 这次传输**用户实际选中的条目**（顶层条目）。
     *
     * 与 [sources] 的区别：上传一个文件夹时 [sources] 记的是夹内的每个文件（重试、
     * 恢复需要逐文件路径），而详情里的「来源 / 目标」要显示的是那个文件夹本身。
     * 不分开存，详情就会把文件夹里的文件一条条列出来（一个文件夹应当只有一条）。
     */
    val sourceItems: List<String> = emptyList(),
    /**
     * 文件夹 / 多选展开后的逐文件进度。空表示单文件传输，UI 不显示子项列表。
     */
    val children: List<TransferChild> = emptyList(),
    /**
     * 本次下载新建的本地目录（相对目标目录的路径）。删除任务时自底向上回收这些
     * 目录里已经空掉的层级；只记原本不存在的，避免误删用户预先建好的目录。
     */
    val createdDirs: Set<String> = emptySet(),
    /**
     * 上传任务已经写到远端的文件路径。
     * 删除上传任务时要按它删远端文件；上传中途暂停也会留一条（半截文件），
     * 一并清掉，避免远端出现打不开的残缺文件。
     */
    val uploadedTargets: Set<String> = emptySet(),
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
    val netId: String?,
    /**
     * 来源目录（与目标的 location/path 同一口径）。用于挡「粘贴到同一目录」：
     * 复制完原地粘贴既没有意义又白跑一遍 IO，按钮直接置灰、给一句说明。
     * 搜索页 / 无明确目录的来源留空，此时不做同目录判断。
     */
    val sourceDir: String = ""
)

/**
 * 解压前发现目标目录已有同名文件，等用户选处理方式。
 *
 * 非空即显示对话框。存路径字符串而不是 File：状态会被整份 copy、也可能进日志，
 * 路径字符串更干净。冲突条目最多列前几条，够用户判断「是不是同一个包」就行。
 */
data class ExtractConflictPrompt(
    val archiveName: String,
    val sourcePath: String,
    val destinationPath: String,
    val destinationName: String,
    val conflicts: List<String>
)

/** 加密压缩包等待输入密码；message 在密码错误时改成可重试的提示。 */
data class ExtractPasswordPrompt(
    val archiveName: String,
    val sourcePath: String,
    val destinationPath: String,
    val message: String = "该压缩包已加密，请输入密码",
    /** 每次校验失败自增：让弹窗里的输入框清空重输，同时弹窗本身不卸载（避免闪一下又弹出）。 */
    val revision: Int = 0
)

/**
 * 「在线打开」大文件时的可见进度。
 *
 * 过去在线打开走 quiet 传输：整个下载在后台静默进行，界面没有任何反馈，
 * 用户以为没点上就反复点同一个文件，或者直接退出 App —— 既浪费流量，
 * 又会在缓存目录留下互相覆盖的半截文件。这里把它提升为页面级状态，
 * 顶部配一条可取消的进度条；来源支持字节回调（群晖）时显示百分比，
 * 不支持的网络协议只显示不确定进度。
 */
data class OpeningFile(
    val taskId: String,
    val name: String,
    val targetPath: String,
    val totalBytes: Long,
    val completedBytes: Long = 0L,
    val hasByteProgress: Boolean = false
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
    /**
     * 首页「展开全部分类」是视图样式的一部分：切到别的页面再回来应保持展开，
     * 只有冷启动（进程重建、state 重新初始化）才回到默认收起。
     */
    val categoryExpanded: Boolean = false,
    /** 「最近」：媒体库快路径 + 全盘遍历合并，并已按 RecentPolicy 过滤。 */
    val recentFiles: List<FileEntry> = emptyList(),
    /** 「最近」里是否隐藏备份副本（.bak/.orig/~ 等），默认隐藏。 */
    val hideRecentBackups: Boolean = true,
    /** 「最近」里是否隐藏应用缓存目录（tencent/MicroMsg、Android/media 等），默认隐藏。 */
    val hideRecentCache: Boolean = true,
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
    // transfers 不放在这个 data class 里：传输进度每 200~350ms 更新一次，
    // 若写 state.copy() 会让所有以 state 为参数的页面整屏重组。见下方独立 State。
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
    /**
     * 回收站自己的筛选条件。
     *
     * 不能复用浏览页的 `nameFilter`：回收站是独立页面，从目录里带着筛选条件点进回收站
     * 会让「回收站是空的」变成假象。两个页面各管一份，互不影响。
     */
    val recycleNameFilter: String = "",
    val recycleExtFilter: String = "",
    /**
     * 回收站列表是否正在读盘 / 拉取。
     *
     * 不能复用通用 `loading`：那个字段是浏览页的加载态，回收站读写它会让两页互相干扰。
     * 本地条目多时 `listRecycle()` 要几百毫秒（实测 1635 项 ≈ 250ms），必须离开 UI 线程，
     * 加载期间用这个标记显示「正在加载回收站」，而不是先闪一次「回收站是空的」。
     */
    val recycleLoading: Boolean = false,
    /** 回收站归属（清单 5）：本地回收只管本地，群晖回收走 #recycle。 */
    val recycleContext: FileContext = FileContext.LOCAL,
    /**
     * 群晖回收站当前钻进去的 `#recycle` 子目录；空串 = 汇总视图（各共享一层 + 删除单元）。
     *
     * DSM 的回收站保留原路径结构，删 `/photo/trip/a.jpg` 后落到
     * `/photo/#recycle/trip/a.jpg`。以前把整棵树 BFS 打平成一张文件清单，
     * 用户只看到一堆同名文件、看不出归属；现在文件夹点得进去，逐层展开。
     * 本地回收站没有这个概念（每个条目就是一个独立删除单元），永远为空。
     */
    val recycleDir: String = "",
    /**
     * 群晖回收站每一层的最近一次结果：dir（空串 = 汇总视图）→ 条目。
     *
     * 只为一个目的：进出 `#recycle` 子目录时页面是要做转场的，如果返回上一层
     * 还要等一次网络请求，滑进来的就是「正在加载回收站…」的空白页——与浏览页
     * 返回时直接显示已有列表的观感对不上。有缓存就先顶上，随后静默刷新。
     */
    val recycleLevels: Map<String, List<RecycleItem>> = emptyMap(),
    /** 群晖共享管理（清单 4）：统一查看 / 取消共享链接。 */
    val shareManagerOpen: Boolean = false,
    val shareLinks: List<com.yaoahos.myfilebox.core.ShareLink> = emptyList(),
    val shareLinksLoading: Boolean = false,
    /** 群晖收藏解析结果（收藏页展示用，文件与文件夹都要能看）。 */
    val synoFavorites: List<FileEntry> = emptyList(),
    /**
     * 群晖服务器上真实存在的收藏路径（SYNO.FileStation.Favorite list 的结果）。
     *
     * 本机 `prefs.favorites` 只记录「在这台设备上点过收藏」；DSM 网页端或另一台设备
     * 加的收藏它不知道。不同步的后果很直接：对已收藏的文件夹再点收藏会被服务器
     * 回 800（该路径已在收藏里），界面却显示成失败。这里只放运行时结果，不落盘。
     */
    val synoFavoritePaths: Set<String> = emptySet(),
    val search: SearchState? = null,
    val searchIndexing: Boolean = false,
    /**
     * 搜索结果的多选。
     *
     * 搜索页不是目录，结果也不在 `browser.items` 里，所以勾选必须单独存一份：
     * 沿用 `selected` 会让「搜索页选中的文件」跟目录页的勾选互相污染。
     */
    val searchSelected: Set<String> = emptySet(),
    val searchSelecting: Boolean = false,
    /** 待删除项来自搜索页：确认后要一并退出搜索页选择态。 */
    val pendingDeleteSearch: Boolean = false,
    /** 待删除项的网络连接 id（搜索页没有浏览上下文，删除时必须显式带上）。 */
    val pendingDeleteNet: String? = null,
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
    /**
     * 后缀名筛选词（`jpg,png` 这种，逗号或空格分隔）。
     *
     * 与名称筛选同生共死：同样只作用于当前位置、同样在换目录时清空。
     * 文件夹不受它影响——否则一筛后缀，目录就全消失，连返回下一级都做不到。
     */
    val extensionFilter: String = "",
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
    /** 正在缓存并打开远端文件；非空时根组合显示可取消的进度条。 */
    val openingFile: OpeningFile? = null,
    val openWith: FileEntry? = null,
    /**
     * 「打开方式」这一次要用的端。从传输记录打开文件时记录已经说明它在哪一端，
     * 不能靠当前浏览页去猜（在群晖栏点开一条 SMB 下载记录会打开错端）。
     * 只在 requestOpen 到用户点应用之间有效，用完即清。
     */
    val openForceCtx: String = "",
    val openForceNetId: String = "",
    /** 能打开 openWith 那个文件的已安装应用（含真实图标名称，替代写死的假列表）。 */
    val openWithApps: List<OpenApp> = emptyList(),
    /** 「打开方式」还在枚举安装包（包管理器查询最多几百毫秒）。 */
    val openWithLoading: Boolean = false,
    /**
     * 已记住「始终用自带解压」时，点压缩包不再问打开方式，直接把文件交给解压对话框。
     * 根组合消费后调用 [AppViewModel.consumeExtractRequest] 清空，避免重组时重弹。
     */
    val extractRequest: FileEntry? = null,
    /** 解压前发现同名文件：非空则弹窗，用户选覆盖 / 跳过 / 两份都留。 */
    val extractConflict: ExtractConflictPrompt? = null,
    /** 加密 7z：非空则弹密码框，密码只留到这次解压结束。 */
    val extractPassword: ExtractPasswordPrompt? = null,
    val pendingDelete: List<FileEntry> = emptyList(),
    val pendingDeleteLabel: String = "",
    /** 待删除项来自首页多选；确认后要一并退出首页选择态。 */
    val pendingDeleteHome: Boolean = false,
    val pendingDeleteConnection: String? = null,
    val refreshing: Boolean = false,
    /** 最近一次页面导航方向：1=前进（右往左入），-1=返回（左往右入）。动画据此决定滑入方向。 */
    val navDir: Int = 1,

    /**
     * 一次性「页面层级链」：传输记录跳转这种直接落到深页的导航，把
     * 首页/父目录/目标的 pageKey 整条写进来，转场宿主据此重建页面栈，
     * 保证进入是前进、逐级返回都是返回，方向不会因为目标 key 以前访问过而颠倒。
     * 宿主消费后立即清空（[AppViewModel.consumePageChain]）。
     */
    val pendingPageChain: List<String> = emptyList(),
    /** 发现新版本时非空，AppRoot 弹更新框。 */
    val updateInfo: com.yaoahos.myfilebox.android.update.UpdateInfo? = null,
    val updateDownloading: Boolean = false,
    val updateProgress: Int = 0,
    /** 下载失败原因留在更新弹窗内；弹窗是独立窗口，顶部提示会被挡住。 */
    val updateError: String? = null,
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
    val loginError: String? = null,
    /** 备份与同步页：云同步配置（WebDAV 连接 + 周期 + 上次结果）。 */
    val sync: SyncSettings = SyncSettings(),
    /** 正在导出 / 导入配置（本地备份与云端恢复共用，页面按它锁按钮、显示进度）。 */
    val backupBusy: Boolean = false,
    /** 正在上传到云端 / 从云端拉取。 */
    val syncBusy: Boolean = false,
    /** 云端各设备的配置文件（恢复时供选择；本机那份排最前）。 */
    val cloudFiles: List<CloudConfigFile> = emptyList(),
    /** 正在列云端目录。 */
    val cloudFilesLoading: Boolean = false,
    /** 列目录失败的原因（云端没配好、目录不存在、网络不通）。 */
    val cloudFilesError: String? = null
)

/** MIME → 给普通用户看的类型名（设置页「打开方式」、各类提示文案共用）。 */
internal val mimeTypeLabels = mapOf(
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
    internal val prefsStore = app.getSharedPreferences(PREFS_NAME, 0)

    internal val syncStore = ConfigSyncStore(prefsStore)

    internal val local = LocalRepository(app)

    internal val dsm = DsmRepository()

    /** 群晖删除单元索引：#recycle 里「整只文件夹」与「中间层目录」只能靠删时记录区分。 */
    internal val synoRecycleIndex = SynoRecycleIndex(File(app.filesDir, "syno_recycle_index.json"))

    internal val network = NetworkRepository(
        sftp = SftpRepository(File(app.filesDir, "known_hosts"))
    )

    internal var rememberSession = true

    /** 内存密码缓存：切网后静默重连用。不落盘，进程结束即失效。 */
    @Volatile
    internal var cachedPassword: String = ""

    /** 最近一次网络切换时刻：切换后短时间内网络类错误也允许自动重连。 */
    @Volatile
    internal var networkChangedAt: Long = 0L

    internal val startedAt = System.currentTimeMillis()

    /** 用户主动退出登录后置真：密码保留，但不允许冷启动自动登录，否则「退出」等于没退。 */
    internal var autoLoginSuppressed = prefsStore.getBoolean("auto_login_suppressed", false)

    internal val transferJobs = mutableMapOf<String, Job>()

    internal val transferRequests = mutableMapOf<String, TransferRequest>()

    internal val transferDestinations = mutableMapOf<String, Destination>()

    /** 文件夹下载的子项调度器：taskId -> 控制器。任务结束即移除。 */
    internal val childControls = mutableMapOf<String, ChildDownloadControl>()

    /**
     * 本地首页派生数据缓存（根目录文件 / 排序结果 / 分类分组 / 收藏）。
     *
     * 放在 ViewModel 而不是页面内：切底栏时页面会被销毁重建，页面内的 remember 全部
     * 失效，数万项要重算一遍，首帧就卡。挂在这里可以跨页面复用。
     */
    internal val homeDerivedCache = HomeDerivedCache()

    /** 当前压缩/解压任务的协程句柄，用于取消。 */
    internal var localTaskJob: Job? = null

    /** 加密 7z 的本次密码：只在内存里保留到解压结束，不进 State、不落盘。 */
    internal var pendingExtractPassword: CharArray? = null

    internal val cancelListener: (String) -> Unit = { id -> cancelTransfer(id, fromRegistry = true) }

    @Volatile internal var lastTransferError: String? = null

    var state by mutableStateOf(MyFileBoxState())
        internal set

    /**
     * 传输列表独立成 State：进度回调只改这里，不碰 [state]，
     * 因此只有传输中心重组，文件列表 / 首页 / 设置都不会跟着刷。
     */
    var transfers by mutableStateOf(emptyList<TransferTask>())
        internal set


    init {
        val prefs = loadPrefs()
        val initialTab = effectiveDefaultTab(prefs)
        // 传输记录可能含上千个子项（整份 JSON 到 MB 级），解析放后台：首帧用不到它，
        // 传输中心打开时再读也已经就绪，不会出现「先空一下再冒出来」。
        // 若解析回来前用户已经新建了任务（transfers 非空），保留新列表，别覆盖。
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { loadTransfers() }.getOrDefault(emptyList())
            }
            if (transfers.isEmpty()) transfers = loaded
        }
        state = state.copy(
            prefs = prefs,
            session = loadSession(),
            connections = loadConnections(),
            // 上次扫描得到的分类计数，先顶上，等这次全盘扫描完成再覆盖。
            categoryCounts = loadCounts(),
            tab = initialTab,
            localAccess = local.hasAccess(),
            localRoot = local.root().absolutePath,
            hideRecentBackups = prefsStore.getBoolean("recent_hide_backups", true),
            hideRecentCache = prefsStore.getBoolean("recent_hide_cache", true)
        )
        refreshLocal()
        // 「最近」先走媒体库索引，几百毫秒内就能出列表，不必等全盘扫描。
        refreshRecent()
        // 冷启动自动恢复群晖会话与网络位置状态，用户不用每次重开都重新建连接。
        restoreConnectionsOnLaunch()
        if (prefs.downloadFolder.isBlank()) {
            setDownloadFolder(local.downloadsDir().absolutePath, silent = true)
        }
        // TLS: 默认始终校验系统证书。旧版本曾对「已有会话」自动放行自签名证书，
        // 等于静默关闭证书校验；现在必须由用户在登录页显式开启「允许自签名证书」。
        val allowSelfSigned = prefsStore.getBoolean("allow_self_signed", true)
        syncTlsSetting(allowSelfSigned)
        // "自动清理" really means automatic: purge expired items on every cold start.
        // days <= 0 means "永不" and must never delete anything.
        if (prefs.recycleDays > 0) launchCleanup(prefs.recycleDays)
        // 关掉「自动清理」时不会走 purge，这里补一次：把老版本留在存储根的回收站搬进私有目录，
        // 否则它既不会被清理，卸载后也不会被系统删掉。
        else viewModelScope.launch(Dispatchers.IO) { runCatching { local.warmRecycle() } }
        TransferCancellation.addListener(cancelListener)
        pruneTransferCache()
        maybeAutoCheckUpdate()
        // 自动同步的周期由 WorkManager 托管：冷启动重放一次，系统清理过任务也能恢复。
        // 是否开启只看「自动同步」那一项（周期为 0 即关闭），没有第二个总开关。
        //
        // 这两件事都挪到后台：同步设置要解一次 Keystore（IPC），WorkManager.getInstance
        // 还会触发它自己的 DB 初始化——首帧根本用不到，放在 init 里只会拖慢冷启动。
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { syncStore.load() }.getOrDefault(state.sync) }
            state = state.copy(sync = loaded)
            withContext(Dispatchers.IO) {
                runCatching {
                    ConfigSyncScheduler.apply(
                        getApplication(),
                        if (loaded.configured) loaded.autoHours else 0
                    )
                }
            }
        }
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
    internal var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null


    // ---------------------------------------------------------------- 群晖自动恢复

    /**
     * 网络变化看门狗。
     *
     * 实测（HyperOS / Android 16）：「WiFi 在线 → 关掉 WiFi 走蜂窝」时，
     * registerDefaultNetworkCallback 既不给新网络 onAvailable、也不给旧网络 onLost，
     * 于是切网后群晖一直连不上，直到重启 App。既然系统不通知，就只能自己盯：
     * 每 1.5 秒读一次「当前默认网络的传输类型 + 接口名」，变了就当作切网处理。     * 只做几次系统 API 查询，开销可以忽略；同一传输类型下重播号（接口名不变）不会误判。
     */
    internal var networkSignature = ""


    /** 正在进行的自动恢复任务：同一时刻只允许一个，避免几条路径同时重登。 */
    internal var recoveryJob: kotlinx.coroutines.Job? = null

    internal var lastRecoveryAt = 0L


    // ---------------------------------------------------------------- 连接超时提示

    /** 正在进行的目录请求：切网时取消，避免旧 socket 卡到超时才返回。 */
    internal var browserLoadJob: kotlinx.coroutines.Job? = null


    /**
     * 当前这一轮下拉刷新的编号。
     *
     * 旧实现用 `state.refreshing` 同时当「防重入锁」和「转圈开关」，两个方向都会出问题：
     * 忘了收圈 → 后续下拉被 `if (state.refreshing) return` 全部吞掉（点了没反应）；
     * 别人擅自收圈 → 正在转的图标突然消失（看着像卡了一下）。
     * 改成令牌后：谁开的圈谁收，迟到的回调收不掉新一轮。
     */
    internal var refreshToken = 0


    /** 下拉刷新的硬兜底：任何路径漏收圈，到点一定收，图标不会永久停住。 */
    internal var refreshTimeoutJob: kotlinx.coroutines.Job? = null


    /** 本轮下拉的起始时刻，用来算「转圈最短显示时长」。 */
    internal var pullStartedAt = 0L


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
    internal val minPullVisibleMs = 450L


    /** 网络首页的探活任务：新一轮下拉先取消上一轮，避免几路并发互相覆盖结果。 */
    internal var netProbeJob: kotlinx.coroutines.Job? = null


    /** 1 秒超时提示的定时器：每次发起网络位置读取重新计时。 */
    internal var connectHintJob: kotlinx.coroutines.Job? = null


    /** 1 秒：网络位置超过这个时间还没结果就当作「连接超时」。 */
    internal val connectHintMs = 1_000L


    /** 切网后自动重读当前目录的任务：同一时刻只留最后一次，避免连续切网叠加请求。 */
    internal var autoReloadJob: kotlinx.coroutines.Job? = null


    /** 最近一次「真的读回列表」的目录位置。切网自动恢复用它判断是否已经成功。 */
    internal var lastLoadedLocation: String = ""


    override fun onCleared() {
        TransferCancellation.removeListener(cancelListener)
        runCatching {
            val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        }
        super.onCleared()
    }


    // ---------------------------------------------------------------- tabs / screens

    internal var localScanInFlight = false

    internal var resumedBefore = false

    internal var lastScanAt = 0L


    internal var localScanPending = false


    /** 媒体库快路径的最近结果；全盘快照到达后与它合并。 */
    internal var recentFast: List<FileEntry> = emptyList()


    // ---------------------------------------------------------------- DSM / network

    internal var connectionsRestored = false


    /** 防止连续快速点击触发多个系统选择器弹窗。 */
    internal var lastRemoteOpenAt = 0L

    /** 打开进度写回 state 的节流时间戳（IO 线程读写，仅进度上报用）。 */
    @Volatile
    internal var lastOpeningProgressAt = 0L


    internal val thumbSemaphore = Semaphore(3)


    /**
     * 给流式源补上「这个文件」的信息：
     * - mime：上游（群晖下载接口）多半只回 application/octet-stream，
     *   播放器据此判断不了格式；
     * - name：代理地址结尾要带真实文件名与扩展名，否则小米视频这类播放器
     *   会因为 URL 没有后缀而直接判「格式不支持」；
     * - contentLength：SMB/FTP/SFTP 拿不到远端大小（旧实现恒为 -1），
     *   导致拖动进度时无法回 206，播放器会把半截数据当完整文件解码而失败。
     */
    internal fun StreamProxy.Source.stampedWith(file: FileEntry): StreamProxy.Source = copy(
        mime = effectiveMime(file),
        name = file.name,
        contentLength = if (contentLength > 0) contentLength else file.sizeBytes
    )


    /** 通知栏点击入口：由 MainActivity 把任务 id 传进来。 */
    fun handleTransferNotification(taskId: String) {
        val task = transfers.firstOrNull { it.id == taskId }
        if (task == null) {
            state = state.copy(transferCenterOpen = true)
            return
        }
        openTransferTask(task)
    }


    /**
     * 统一的后台任务进度上报器。上传 / 下载 / 缓存打开都通过它把
     * 「已经处理了几项、已经传了多少字节」实时写回传输中心。
     */
    internal inner class TaskReporter(val id: String) {
        // 字节进度节流：下载路径每读 64KB 回调一次（约每秒数千次），每次都 launch +
        // 整份 state.copy + 通知会拖垮主线程。上传侧 ProgressStream 已在源头 200ms 节流，
        // 这里再兜一层，双路径统一；done>=totalBytes 的收尾一律放行，避免停在 99%。
        @Volatile
        private var lastBytesAt = 0L

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
            val now = System.currentTimeMillis()
            val totalBytes = transfers.firstOrNull { it.id == id }?.totalBytes ?: 0L
            val isFinal = totalBytes > 0L && done >= totalBytes
            if (!isFinal && now - lastBytesAt < 200L) return
            lastBytesAt = now
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
                // 节流：一个文件夹任务可能几十上百个文件，逐个全量写盘没有必要。
                persistTransfersThrottled()
            }
        }

        fun cancelled(): Boolean = TransferCancellation.isCancelled(id)
    }



    internal var searchIndexJob: Job? = null


    // ---------------------------------------------------------------- 下拉刷新（按页面拆分）

    /**
     * 下拉刷新的硬兜底上限。
     *
     * 正常路径都会提前 [endPull]（本地目录一两百毫秒、群晖目录一秒内、网络目录最多
     * 4 秒连接超时），这个值只覆盖最慢的一种：切网后先修通道再读目录，自愈重登可能
     * 拖到十几秒。定这么长是为了不误杀真实请求，同时保证「无论发生什么，25 秒后
     * 图标一定停下」——用户不会对着一个永远转的圈等。
     */
    internal val pullWatchdogMs = 25_000L
}

fun formatTransferTitle(mode: TransferMode): String = when (mode) {
    TransferMode.DOWNLOAD -> "下载到本地"
    TransferMode.UPLOAD -> "上传到"
    TransferMode.COPY -> "复制"
    TransferMode.MOVE -> "移动"
}

/** 备份与同步页统一的时间写法：同一天只给时分，跨年补年份。 */
fun formatSyncTime(at: Long): String {
    if (at <= 0L) return "—"
    val now = System.currentTimeMillis()
    val pattern = if (now - at < 365L * 24 * 3600 * 1000) "MM/dd HH:mm" else "yyyy/MM/dd HH:mm"
    return LocalRepository.formatWith(pattern, at)
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
internal const val PREVIEW_TEXT_LIMIT = 2L * 1024 * 1024
internal const val PREVIEW_AUDIO_LIMIT = 64L * 1024 * 1024
internal const val PREVIEW_IMAGE_LIMIT = 256L * 1024 * 1024

/** 其它文件「缓存后交给系统应用打开」的上限（apk、文档、压缩包等）。 */
internal const val OPEN_CACHE_LIMIT = 1024L * 1024 * 1024

/**
 * 音视频流式播放失败后，允许退回「先缓存再打开」的体积上限。
 *
 * 视频本来就是为了不落地才走流式；一旦流式不可用（播放器不认 http、协议不支持），
 * 旧实现会不分大小地开始缓存整份文件：点一个 20GB 的片子等于静默开了一个 20GB 下载，
 * 手机存不下、用户也不知道在等什么。超过这个体积就直接告诉用户「先下载再看」。
 */
internal const val STREAM_FALLBACK_LIMIT = 512L * 1024 * 1024

/**
 * 「始终用 MyFileBox 解压」在 openDefaults 里存的占位值。
 *
 * 它不是真实包名，所以既不会被 PackageManager 认成已装应用，也不可能和别的应用撞车；
 * 请求打开时先比对这个值，再走「直接弹解压」的分支。
 */
internal const val EXTRACT_TARGET = "@myfilebox/extract"

/**
 * org.json 没有把 JSONArray 转成 List 的便捷方法，
 * 而传输记录里「源路径」「已上传目标」都是字符串数组，统一在这里转换。
 */
internal fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optString(index, "").takeIf { it.isNotBlank() }
    }
}
