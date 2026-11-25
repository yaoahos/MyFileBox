package com.yaoahos.myfilebox.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.android.data.filterSummary
import com.yaoahos.myfilebox.android.data.itemMatchesFilter
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.MODULE_LABEL_LOCAL
import com.yaoahos.myfilebox.core.MODULE_LABEL_NET
import com.yaoahos.myfilebox.core.MODULE_LABEL_SYNO
import java.io.File

/**
 * 是否是 Android 11+ 的「其它应用不可读」区域（`Android/data`、`Android/obb`）。
 *
 * 这两个目录按包隔离：即便给了「所有文件访问权限」，也只能读自己那一份，
 * 其它应用的目录 `listFiles()` 直接是空的。系统「文件管理」是特权应用，所以那边看得到——
 * 空列表不是本应用漏读，别再用「这个文件夹是空的」误导用户。
 *
 * 自己包名下的子目录排除掉：那里读得到，真空就是真空。
 */
internal fun isRestrictedSystemDir(path: String, ownPackage: String): Boolean {
    if (path.isBlank()) return false
    val p = path.replace('\\', '/').trimEnd('/')
    // 自己的包名必须按路径段比较：`com.x` 是 `com.x.debug` 的前缀，
    // 用 contains 会把调试包的目录当成「自己的」而不给提示。
    if (p.endsWith("/Android/data/$ownPackage") || p.contains("/Android/data/$ownPackage/") ||
        p.endsWith("/Android/obb/$ownPackage") || p.contains("/Android/obb/$ownPackage/")
    ) {
        return false
    }
    return p.contains("/Android/data") || p.contains("/Android/obb")
}

/**
 * 面包屑要显示的页面下标（相对返回栈）。
 *
 * 收藏、图片、视频、最近这些是集合快照，不是目录：留在栈里是为了「返回」能回到它们，
 * 但不该出现在面包屑上——否则从收藏点进 /photo/2026/旅行 会显示成「收藏 › 2026 › 旅行」。
 * 当前页本身是虚拟页时仍要显示，不然停在收藏页就只剩一个模块名。
 */
internal fun breadcrumbIndices(trail: List<BrowserState>): List<Int> =
    trail.indices.filter { index ->
        index == trail.lastIndex || !trail[index].isVirtualPage()
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(state: MyFileBoxState, vm: AppViewModel) {
    val browser = state.browser ?: return
    // 跨端剪贴板提示默认只出现一次，用户可主动关掉；换一次剪贴板才重新出现。
    var clipboardHintDismissed by remember(state.clipboard) { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    // 群晖 / 网络位置没有「新建文件夹」按钮，用 FAB 收拢「新建 / 上传」两个动作。
    var showAddMenu by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showCompress by remember { mutableStateOf(false) }
    var showExtract by remember { mutableStateOf(false) }
    var compressDefaultName by remember { mutableStateOf("压缩包") }
    var showNewFile by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    // 单个重命名（清单 8：批量重命名已移除）。
    var showRename by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    // 群晖「共享」：先出共享设置（有效期/密码），确认后才生成并复制（清单 4）。
    var shareTarget by remember { mutableStateOf<FileEntry?>(null) }
    val canSelect = vm.canSelectHere()
    // 上传文件 / 上传文件夹统一走应用内同一套文件树（清单 12）：风格一致、都能进目录。
    var showUploadFiles by remember { mutableStateOf(false) }
    var showUploadFolder by remember { mutableStateOf(false) }
    val selecting = state.selectionActive || state.selected.isNotEmpty()
    val favoritesPage = browser.title == "收藏"

    // 收藏页的条目来自不同目录，只显示名字会分不清「Temp」到底是哪个共享下的。
    // 群晖显示完整父路径（/Backup），本地 / 网络只取末段，避免整条存储路径把行撑满。
    fun favoriteSubtitle(file: FileEntry): String {
        val parent = file.path.substringBeforeLast('/', "")
        val label = when (browser.ctx) {
            FileContext.SYNO -> parent.ifBlank { file.folder }
            else -> parent.substringAfterLast('/').ifBlank { file.folder }
        }
        return listOf(label, file.timeLabel).filter { it.isNotBlank() }.joinToString(" · ")
    }
    val crumbTrail = state.browserStack + listOfNotNull(browser)
    // 清单 1（3.1.0）：所有文件不再给三点按钮，FileActionSheet 已删除；操作走长按多选。
    val rowShowMore = false
    // 名称 / 后缀筛选作用在「这一屏」：展示、勾选、全选都只认筛出来的条目。
    val filtering = state.nameFilter.isNotBlank() || state.extensionFilter.isNotBlank()
    val shownItems = remember(browser.items, state.nameFilter, state.extensionFilter) {
        if (!filtering) browser.items
        else browser.items.filter {
            itemMatchesFilter(it.name, it.isFolder, state.nameFilter, state.extensionFilter)
        }
    }
    // 全选按钮的状态按可见条目算：筛选生效时，勾选筛出来的那些就算「已全选」。
    val allSelected = shownItems.isNotEmpty() && state.selected.containsAll(shownItems.map { it.id })
    // 「最近」分类按具体日期分区、按分组懒加载：默认最近 3 组，滚到底再加载更早（清单 13）。
    val dateGroups = remember(shownItems) { recentFileGroups(shownItems) }
    var dateGroupsVisible by remember(shownItems) { mutableStateOf(3) }

    BackHandler(enabled = selecting) { vm.clearSelection() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 稳定的状态栏高度：点文件启动外部应用时 statusBars insets 会瞬时抖小，
            // 直接读 insets 会让整页上移约 40px 再跳回（真机录屏实测）。
            .padding(top = rememberStableStatusBarTop())
            .pointerInput(selecting) {
                if (selecting) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        vm.clearSelection()
                    }
                }
            }
    ) {
        // 下拉刷新包住整个页面（含标题 / 面包屑）：从屏幕任意位置下拉都能刷新，
        // 不再出现「只有列表那半屏能下拉」。
        AppPullToRefreshBox(
            isRefreshing = state.refreshing,
            // 停在目录里下拉 = 重读这一层，不再顺带全盘扫描 / 拉共享 / 测连所有连接。
            onRefresh = vm::refreshBrowser,
            modifier = Modifier.fillMaxSize()
        ) {
        Column(Modifier.fillMaxSize().pullRefreshArea()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleBackButton(
                    onClick = { if (selecting) vm.clearSelection() else vm.backFromBrowser() },
                    label = if (selecting) "退出选择" else "返回",
                    icon = if (selecting) Icons.Outlined.Close else Icons.Outlined.ArrowBack
                )
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(
                        if (selecting) "已选择 ${state.selected.size} 项" else browser.title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selecting) {
                        Text(
                            SELECTING_HINT,
                            fontSize = 12.sp,
                            color = MutedText,
                            maxLines = 1
                        )
                    } else {
                        val moduleLabel = when (browser.ctx) {
                            FileContext.LOCAL -> MODULE_LABEL_LOCAL
                            FileContext.SYNO -> MODULE_LABEL_SYNO
                            FileContext.NET -> MODULE_LABEL_NET
                        }
                        // 第一层固定是模块名（本地文件 / 群晖文件 / 网络文件），第二层起就是
                        // 用户真正走过的页面：本地是首页里的文件夹，网络是连接名，群晖是共享文件夹。
                        //
                        // 不再从 browser.path 拆段：path 里塞过「内部存储」「网络文件」「协议名」，
                        // 拆出来就成了「网络存储 › 网络文件 › 我的群晖 › WebDAV」——结构层、模块名、
                        // 协议全混在一条线上，而且中间那几段点了也回不去。
                        //
                        // 收藏页与分类页是集合快照，不是目录：它们只作为「返回目标」留在栈里，
                        // 不进面包屑，否则从收藏点进 /photo/2026/旅行 会显示成「收藏 › 2026 › 旅行」。
                        // 当前页本身是虚拟页时仍要显示，不然停在收藏页会只剩一个模块名。
                        val visibleIndices = breadcrumbIndices(crumbTrail)
                        val trail = visibleIndices.map { crumbTrail[it].title }
                            // 单层页面且标题与模块名相同时只留一层，避免「本地文件 › 本地文件」。
                            .let { if (it.size == 1 && it.first() == moduleLabel) emptyList() else it }
                        PathBreadcrumbs(
                            labels = listOf(moduleLabel) + trail,
                            emphasizeCurrent = false,
                            chevronSize = 11.dp,
                            // 第 0 段是模块名，不对应任何页面；第 i 段对应可见页面里的第 i-1 个。
                            clickableFrom = 1,
                            onCrumbClick = if (visibleIndices.size > 1) { index ->
                                // 跳层要按「在栈里的真实下标」算，跳过虚拟页后两者不再相等。
                                vm.jumpToCrumb(visibleIndices[index - 1])
                            } else null
                        )
                    }
                }
                if (selecting) {
                    // 全选只保留右上角一个入口，底栏不再重复。
                    SelectionSelectAllButton(allSelected = allSelected, onClick = vm::toggleSelectAll)
                } else {
                    if (vm.canPasteHere()) {
                        // 长按取消复制 / 移动：剪贴板不该一直占着顶栏，
                        // 用户临时改主意也能就地丢掉，不必先去目标目录贴掉。
                        val pasteInteraction = remember { MutableInteractionSource() }
                        Box(
                            Modifier
                                .size(40.dp)
                                .pressScale(pasteInteraction)
                                .combinedClickable(
                                    interactionSource = pasteInteraction,
                                    indication = null,
                                    onClick = vm::pasteClipboard,
                                    onLongClick = vm::cancelClipboard
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.ContentPaste, "粘贴（长按取消复制 / 移动）",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    val sortTabKey = when (browser.ctx) {
                        FileContext.LOCAL -> "local"
                        FileContext.SYNO -> "syno"
                        FileContext.NET -> "net"
                    }
                    // 「最近」是按日期分区的时间流，排序与它无关，这里不提供排序入口。
                    if (!browser.groupByDate) {
                        SortMenuButton(
                            sortKey = vm.sortKeyFor(sortTabKey),
                            sortDir = vm.sortDirFor(sortTabKey),
                            onPick = { key, dir -> vm.setSort(key, dir, sortTabKey) },
                            showSize = true
                        )
                    }
                    IconButton(onClick = { vm.toggleViewFor(browser.ctx) }) {
                        Icon(
                            if (vm.viewModeFor(browser.ctx) == "grid") Icons.Outlined.ViewList else Icons.Outlined.GridView,
                            if (vm.viewModeFor(browser.ctx) == "grid") "列表" else "宫格",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    // 收藏页里不再提供「收藏/回收/设置/关于」互跳入口，防止套娃；
                    // 但筛选必须留着——收藏多了同样需要按名称 / 后缀找。
                    HeaderOverflow(
                        vm = vm,
                        favoritesContext = browser.ctx,
                        hideNavigation = favoritesPage,
                        showFilter = true
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (!filtering) "${shownItems.size} 个项目"
                    else "${shownItems.size} / ${browser.items.size} 个项目",
                    fontSize = 12.sp, color = MutedText, modifier = Modifier.weight(1f)
                )
                // 「最近」固定按时间倒序（按日期分区），排序偏好对它不生效，
                // 显示「名称 · 升序」会自相矛盾。
                if (!browser.groupByDate) {
                    Text(sortLabel(state.prefs.sortKey, state.prefs.sortDir), fontSize = 12.sp, color = MutedText)
                }
            }
            if (filtering) ActiveFilterBar(vm)
            // 手机完全没网时，页内提示条与空态都换成真正的原因：
            // 「请检查网络」在没网时是句废话——网络本身没问题，是根本没连上；
            // 用户照着它去怀疑地址、端口、服务器，全都白费。
            // connectHint 与 netEpoch 一起当 key：提示条没变、但设备联网状态变了该重算。
            val deviceNotice = remember(state.connectHint, state.netEpoch) { vm.networkNotice() }
            // 页面级连接提示（两种页面两套原因，文案不能混）：
            // - 群晖：重建通道期间页面中央已经有转圈，不再额外挂一条「正在重建」——
            //   挂上又立刻消失只会闪一下，观感比没有更差；重建完仍读不回来，
            //   才切成「网络已变化，请刷新后重试」；
            // - 网络文件：连不上就是连不上 → 「连接超时，请检查网络」，不谈网络切换。
            if (state.connectHint && !selecting && browser.ctx != FileContext.LOCAL) {
                val net = browser.ctx == FileContext.NET
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        // 整条都能点：提示条插在列表上方会把首行的点击区顶掉，
                        // 用户点文件夹落到这里时至少能重试一次，不会「点了没反应」。
                        // 网络文件的提示条是「1 秒没结果」挂上的，此刻请求往往还在途，
                        // 所以不按 loading 禁用——点重试会撤掉在途请求重新发起。
                        .pressClickable { if (net || !state.loading) vm.reloadCurrentFolder() }
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Wifi, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        when {
                            // 没网压过一切：先让用户去解决唯一能解决的那件事。
                            deviceNotice != null -> deviceNotice
                            // 挂上这条就说明已经等够了：再显示「正在连接…」等于把结论又咽回去。
                            net -> "连接超时"
                            state.loading -> "正在重新连接…"
                            else -> "网络已变化，请刷新后重试"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    TextButton(onClick = vm::reloadCurrentFolder, enabled = net || !state.loading) {
                        Text(
                            if (net) "重试"
                            else if (state.loading) "连接中"
                            else "刷新",
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = vm::dismissNetworkHint, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Outlined.Close, "关闭提示", tint = MutedText, modifier = Modifier.size(14.dp))
                    }
                }
            }
            // 「最近 / 收藏」是展示视图，本来就不能粘贴，别再挂「只能同一端内粘贴」——
            // 那句话会让用户以为换个端就能贴进来。
            if (!selecting && state.clipboard != null && !vm.canPasteHere() &&
                !vm.browserIsVirtual() && !clipboardHintDismissed
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (vm.isPasteSourceHere()) "${vm.clipboardLabel()} · 已在来源目录，换个位置再粘贴"
                        else "${vm.clipboardLabel()} · 只能在同一端内粘贴，跨端请用上传 / 下载",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "取消",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable {
                                vm.cancelClipboard()
                                clipboardHintDismissed = true
                            }
                            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    )
                    IconButton(
                        onClick = { clipboardHintDismissed = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close, "关闭提示",
                            tint = MutedText,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                if (shownItems.isEmpty() && !state.loading) {
                    val readFailed = !state.localAccess && browser.ctx == FileContext.LOCAL
                    val restrictedDir = browser.ctx == FileContext.LOCAL &&
                        isRestrictedSystemDir(browser.location, LocalContext.current.packageName)
                    when {
                        filtering -> EmptyState(
                            icon = Icons.Outlined.Folder,
                            title = "没有匹配的项目",
                            message = filterSummary(state.nameFilter, state.extensionFilter),
                            actionLabel = "清除筛选",
                            onAction = vm::clearNameFilter
                        )
                        // 系统限制不是「空文件夹」：给错文案用户会以为文件丢了或应用读不了盘。
                        restrictedDir -> EmptyState(
                            icon = Icons.Outlined.Lock,
                            title = "系统限制，无法读取",
                            message = "Android 11 起，第三方应用只能读取自己的 Android/data、Android/obb 目录。" +
                                "其它应用的数据请用系统「文件管理」查看。"
                        )
                        favoritesPage -> EmptyState(
                            icon = Icons.Outlined.StarBorder,
                            title = "还没有收藏",
                            message = "长按文件即可收藏"
                        )
                        // 目录还没读到：说明原因并给出重试入口，
                        // 不要显示「这个文件夹是空的」——那会让用户以为文件没了。
                        state.connectHint && browser.ctx != FileContext.LOCAL -> EmptyState(
                            icon = Icons.Outlined.Wifi,
                            title = when {
                                deviceNotice != null -> "当前网络不可用"
                                browser.ctx == FileContext.NET -> "连接超时"
                                else -> "网络已变化"
                            },
                            message = deviceNotice ?: if (browser.ctx == FileContext.NET) "请检查网络"
                            else "刷新后重试",
                            actionLabel = if (browser.ctx == FileContext.NET) "重试" else "刷新",
                            onAction = vm::reloadCurrentFolder
                        )
                        readFailed -> EmptyState(
                            icon = Icons.Outlined.Folder,
                            title = "暂时无法读取",
                            message = "下拉重试",
                            actionLabel = "重试",
                            onAction = vm::refresh
                        )
                        else -> EmptyState(
                            icon = Icons.Outlined.Folder,
                            title = "这个文件夹是空的",
                            message = "这里还没有任何文件"
                        )
                    }
                } else if (vm.viewModeFor(browser.ctx) == "grid") {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (selecting) 96.dp else 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (browser.groupByDate) {
                            // 日期分组整组渲染、按组分页：同屏不会出现一半新一半旧。
                            dateGroups.take(dateGroupsVisible).forEach { (label, files) ->
                                item(span = { GridItemSpan(maxLineSpan) }, key = "header-$label") {
                                    RecentGroupHeader(label)
                                }
                                items(files, key = { it.id }) { file ->
                                    FileGridCard(
                                        file = file,
                                        selected = file.id in state.selected, selecting = selecting,
                                        onClick = {
                                            if (selecting) { if (canSelect) vm.toggleSelected(file) } else vm.requestOpen(file)
                                        },
                                        onLongClick = { if (canSelect) vm.toggleSelected(file) },
                                        onMore = null
                                    )
                                }
                            }
                            if (dateGroupsVisible < dateGroups.size) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "recent-more") {
                                    LaunchedEffect(Unit) { dateGroupsVisible += 2 }
                                    Text(
                                        "上滑加载更早的文件…",
                                        fontSize = 11.sp,
                                        color = MutedText,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                                    )
                                }
                            }
                        } else {
                            items(shownItems, key = { it.id }) { file ->
                                if (file.isFolder) {
                                    BrowserFolderTile(
                                        name = file.name,
                                        date = if (favoritesPage) favoriteSubtitle(file) else file.timeLabel,
                                        count = file.childCount,
                                        selected = file.id in state.selected, selecting = selecting,
                                        showCount = browser.ctx == FileContext.LOCAL,
                                        onClick = {
                                            if (selecting) { if (canSelect) vm.toggleSelected(file) } else vm.openFolder(file)
                                        },
                                        onLongClick = { if (canSelect) vm.toggleSelected(file) },
                                        // 收藏页里文件夹也要能一键取消，与文件行保持同一套操作。
                                        onUnfavorite = if (favoritesPage && !selecting) {
                                            { vm.unfavorite(file) }
                                        } else null
                                    )
                                } else {
                                    FileGridCard(
                                        file = file,
                                        selected = file.id in state.selected, selecting = selecting,
                                        onClick = {
                                            if (selecting) { if (canSelect) vm.toggleSelected(file) } else vm.requestOpen(file)
                                        },
                                        onLongClick = { if (canSelect) vm.toggleSelected(file) },
                                        onMore = null
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (selecting) 96.dp else 24.dp)) {
                        if (browser.groupByDate) {
                            dateGroups.take(dateGroupsVisible).forEach { (label, files) ->
                                item(key = "header-$label") { RecentGroupHeader(label) }
                                items(files, key = { it.id }) { file ->
                                    Column(Modifier.animateItem()) {
                                        FileRow(
                                            file = file,
                                            selected = file.id in state.selected, selecting = selecting,
                                            onClick = {
                                                if (selecting) { if (canSelect) vm.toggleSelected(file) } else vm.requestOpen(file)
                                            },
                                            onLongClick = { if (canSelect) vm.toggleSelected(file) },
                                            onMore = null,
                                            showMore = false
                                        )
                                        FileListDivider()
                                    }
                                }
                            }
                            if (dateGroupsVisible < dateGroups.size) {
                                item(key = "recent-more") {
                                    LaunchedEffect(Unit) { dateGroupsVisible += 2 }
                                    Text(
                                        "上滑加载更早的文件…",
                                        fontSize = 11.sp,
                                        color = MutedText,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(shownItems, key = { _, file -> file.id }) { index, file ->
                                Column(Modifier.animateItem()) {
                                if (file.isFolder) {
                                    FolderListRow(
                                        name = file.name,
                                        date = if (favoritesPage) favoriteSubtitle(file) else file.timeLabel,
                                        count = file.childCount,
                                        fallbackMeta = file.folder,
                                        showCount = browser.ctx == FileContext.LOCAL,
                                        selected = file.id in state.selected, selecting = selecting,
                                        onLongClick = { if (canSelect) vm.toggleSelected(file) },
                                        onUnfavorite = if (favoritesPage && !selecting) {
                                            { vm.unfavorite(file) }
                                        } else null,
                                        onClick = {
                                            if (selecting) { if (canSelect) vm.toggleSelected(file) } else vm.openFolder(file)
                                        }
                                    )
                                } else {
                                    FileRow(
                                        file = file,
                                        selected = file.id in state.selected, selecting = selecting,
                                        onClick = {
                                            if (selecting) { if (canSelect) vm.toggleSelected(file) } else vm.requestOpen(file)
                                        },
                                        onLongClick = { if (canSelect) vm.toggleSelected(file) },
                                        onMore = null,
                                        showMore = false,
                                        onUnfavorite = if (favoritesPage && !selecting) {
                                            { vm.unfavorite(file) }
                                        } else null
                                    )
                                }
                                if (index != shownItems.lastIndex) FileListDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        if (!selecting && canSelect && !browser.groupByDate && browser.title != "收藏" && browser.location.isNotBlank()) {
            SmallFloatingActionButton(
                onClick = { showAddMenu = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 18.dp)
                    .navigationBarsPadding()
            ) {
                Icon(
                    Icons.Outlined.Add,
                    "新建或上传",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 只有「页面本身还没内容」时才显示整屏加载。
        // 上传 / 下载 / 重命名这类操作不再走 loading，进度统一在传输中心看，
        // 否则每次点上传都是整屏转圈、什么都看不到。
        if (state.loading && shownItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        }

        if (selecting) {
            // 底栏只放四个动作；全选在右上角，上传 / 分享 / 收藏等都收进「更多」。
            // 取消全选后仍停在这个模式里（selectionActive），此时没有动作可做，整栏置灰。
            val hasSelection = state.selected.isNotEmpty()
            SelectionActionBar(
                actions = listOf(
                    SelectionAction(Icons.Outlined.ContentCopy, "复制", enabled = hasSelection) { vm.stageSelectedTransfer(TransferMode.COPY) },
                    SelectionAction(Icons.Outlined.DriveFileMove, "移动", enabled = hasSelection) { vm.stageSelectedTransfer(TransferMode.MOVE) },
                    SelectionAction(Icons.Outlined.Delete, "删除", enabled = hasSelection, danger = true) { vm.deleteSelected() },
                    SelectionAction(Icons.Outlined.MoreHoriz, "更多", enabled = hasSelection) { showMore = true }
                ),
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        }

    if (showMore) {
        val chosen = browser.items.filter { it.id in state.selected }
        val single = chosen.singleOrNull()
        val canExtract = single != null && !single.isFolder && isArchiveName(single.name)
        val allFavorited = chosen.isNotEmpty() && chosen.all { vm.isFavorite(it) }
        val actions = buildList {
            when (browser.ctx) {
                FileContext.LOCAL -> {
                    add(SelectionAction(Icons.Outlined.Upload, "上传", enabled = vm.hasRemoteTarget()) {
                        showMore = false
                        vm.startSelectedTransfer(TransferMode.UPLOAD)
                    })
                    add(SelectionAction(Icons.Outlined.Share, "分享", enabled = chosen.any { !it.isFolder }) {
                        showMore = false
                        vm.shareSelected()
                    })
                    add(SelectionAction(
                        if (allFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        if (allFavorited) "取消收藏" else "收藏"
                    ) { showMore = false; vm.favoriteSelected() })
                    add(SelectionAction(Icons.Outlined.FolderZip, "压缩") {
                        showMore = false
                        compressDefaultName = chosen.firstOrNull()?.name ?: "压缩包"
                        showCompress = true
                    })
                    add(SelectionAction(Icons.Outlined.Unarchive, "解压", enabled = canExtract) {
                        showMore = false
                        showExtract = true
                    })
                }
                FileContext.SYNO -> {
                    add(SelectionAction(Icons.Outlined.Download, "下载") {
                        showMore = false
                        vm.startSelectedTransfer(TransferMode.DOWNLOAD)
                    })
                    // 「共享」先弹共享设置（有效期 / 密码），确定后才生成并复制（清单 4）。
                    add(SelectionAction(Icons.Outlined.Share, "共享", enabled = single != null) {
                        showMore = false
                        shareTarget = single
                    })
                    if (vm.state.synoFavoriteSupported) {
                        // 文件与文件夹均可收藏（文件记本机 key，文件夹同步官方接口）——清单 8。
                        add(SelectionAction(
                            if (allFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            if (allFavorited) "取消收藏" else "收藏",
                            enabled = chosen.isNotEmpty()
                        ) { showMore = false; vm.favoriteSynoSelected() })
                    }
                }
                FileContext.NET -> {
                    add(SelectionAction(Icons.Outlined.Download, "下载") {
                        showMore = false
                        vm.startSelectedTransfer(TransferMode.DOWNLOAD)
                    })
                }
            }
            // 重命名只支持单个（清单 8），固定排在「查看详情」上方（清单 8）。
            add(SelectionAction(Icons.Outlined.Edit, "重命名", enabled = single != null) {
                showMore = false
                renameTarget = single
                showRename = true
            })
            add(SelectionAction(Icons.Outlined.Info, "查看详情", enabled = single != null) {
                showMore = false
                single?.let(vm::showDetail)
            })
        }
        SelectionMoreSheet(actions = actions, onDismiss = { showMore = false })
    }
    if (showCompress) {
        CompressDialog(
            defaultName = compressDefaultName,
            onConfirm = { name, format, password ->
                showCompress = false
                vm.compressSelected(name, format, password)
            },
            onDismiss = { showCompress = false }
        )
    }
    if (showExtract) {
        val target = browser.items.filter { it.id in state.selected }.singleOrNull()
        ExtractDialog(
            rootPath = vm.state.localRoot,
            listSubfolders = vm::downloadSubfolders,
            onConfirm = { dest ->
                showExtract = false
                target?.let { vm.extractArchive(it, dest) }
            },
            onDismiss = { showExtract = false }
        )
    }
    if (showRename) {
        renameTarget?.let { target ->
            RenameDialog(
                currentName = target.name,
                onConfirm = { next ->
                    showRename = false
                    vm.renameFile(target, next)
                },
                onDismiss = { showRename = false }
            )
        }
    }
    if (shareTarget != null) {
        ShareLinkDialog(
            onConfirm = { password, expireMinutes ->
                val target = shareTarget
                shareTarget = null
                target?.let { vm.shareSynoLink(it, password, expireMinutes) }
            },
            onDismiss = { shareTarget = null }
        )
    }
    if (showNewFolder) {
        FormDialog(
            title = "新建文件夹",
            onDismiss = { showNewFolder = false },
            confirmButton = {
                TextButton(
                    enabled = newFolderName.isNotBlank(),
                    onClick = {
                        vm.createFolder(newFolderName)
                        showNewFolder = false
                    }
                ) { Text("创建") }
            }
        ) {
            OutlinedTextField(
                value = newFolderName,
                onValueChange = { newFolderName = it },
                singleLine = true,
                label = { Text("文件夹名称") },
                shape = RoundedCornerShape(12.dp),
                colors = dialogFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (showNewFile) {
        FormDialog(
            title = "新建文件",
            onDismiss = { showNewFile = false },
            confirmButton = {
                TextButton(
                    enabled = newFileName.isNotBlank(),
                    onClick = {
                        vm.createFile(newFileName)
                        showNewFile = false
                    }
                ) { Text("创建") }
            }
        ) {
            OutlinedTextField(
                value = newFileName,
                onValueChange = { newFileName = it },
                singleLine = true,
                label = { Text("文件名（含扩展名）") },
                shape = RoundedCornerShape(12.dp),
                colors = dialogFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (showAddMenu) {
        val actions = buildList {
            // 清单 12（3.2.0）：菜单依次是 新建文件→新建文件夹→上传文件→上传文件夹。
            add(SelectionAction(Icons.Outlined.NoteAdd, "新建文件") {
                showAddMenu = false
                newFileName = ""
                showNewFile = true
            })
            add(SelectionAction(Icons.Outlined.CreateNewFolder, "新建文件夹") {
                showAddMenu = false
                newFolderName = ""
                showNewFolder = true
            })
            if (browser.ctx != FileContext.LOCAL) {
                add(SelectionAction(Icons.Outlined.Upload, "上传文件") {
                    showAddMenu = false
                    showUploadFiles = true
                })
                add(SelectionAction(Icons.Outlined.DriveFolderUpload, "上传文件夹") {
                    showAddMenu = false
                    showUploadFolder = true
                })
            }
        }
        SelectionMoreSheet(
            actions = actions,
            onDismiss = { showAddMenu = false },
            title = "添加到当前文件夹"
        )
    }
    if (showUploadFiles) {
        LocalTreePickerDialog(
            title = "选择要上传的文件",
            rootPath = vm.state.localRoot,
            mode = TreePickerMode.FILE,
            multiSelect = true,
            onDismiss = { showUploadFiles = false },
            onConfirm = { paths ->
                showUploadFiles = false
                if (paths.isNotEmpty()) vm.uploadLocalFilesToCurrent(paths.map { File(it) })
            }
        )
    }
    if (showUploadFolder) {
        LocalTreePickerDialog(
            title = "选择要上传的文件夹",
            rootPath = vm.state.localRoot,
            mode = TreePickerMode.FOLDER,
            onDismiss = { showUploadFolder = false },
            onConfirm = { paths ->
                showUploadFolder = false
                paths.firstOrNull()?.let(vm::uploadLocalFolderToCurrent)
            }
        )
    }
}

private fun sortLabel(key: String, dir: String): String {
    val name = when (key) {
        "name" -> "名称"
        "size" -> "大小"
        else -> "修改时间"
    }
    return "$name · ${if (dir == "asc") "升序" else "降序"}"
}

/**
 * 统一排序按钮：点击后在按钮下方展开下拉菜单（不弹窗），与首页排序按钮同款样式。
 * 选中项高亮 + 方向箭头，点选中项反转方向，点其它项切换字段。
 */
@Composable
fun SortMenuButton(
    sortKey: String,
    sortDir: String,
    onPick: (String, String) -> Unit,
    showSize: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Sort, "排序", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val items = buildList {
                add("name" to "按名称")
                add("time" to "按修改时间")
                if (showSize) add("size" to "按文件大小")
            }
            items.forEach { (value, label) ->
                val active = sortKey == value
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (active) {
                                Icon(
                                    if (sortDir == "asc") Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                                    if (sortDir == "asc") "升序" else "降序",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        if (active) {
                            onPick(sortKey, if (sortDir == "asc") "desc" else "asc")
                        } else {
                            onPick(value, sortDir)
                        }
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCard(
    file: FileEntry,
    selected: Boolean,
    selecting: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
    val useDark = isSystemInDarkTheme() ||
        MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box {
        Column(
            Modifier
                .clip(shape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else Color.Transparent)
                .then(
                    if (selected || selecting) Modifier.border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) primary else MaterialTheme.colorScheme.outline.copy(alpha = .5f),
                        shape = shape
                    ) else Modifier
                )
                .pressScale(interaction)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = 2.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 清单 6：文件与文件夹尺寸统一——固定 46dp 图标/缩略图区，与 BrowserFolderTile 同规格。
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                FileThumb(file = file, size = 42.dp, dark = useDark)
            }
            // 清单 2：文件名居中 + 行高与文件夹一致（14sp）。
            Text(
                file.name,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )
            FileMetaLine(file, Modifier.fillMaxWidth(), fontSize = 9.sp, grid = true)
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                "已选择",
                tint = primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(17.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(
                message,
                fontSize = 12.sp,
                color = MutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) {
                    Text(actionLabel, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RecentGroupHeader(label: String) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
// 内部 produceState 在 null 分支里直接 return@produceState，lint 因此判定「从不赋值」。
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
fun TransferSheet(state: MyFileBoxState, vm: AppViewModel) {
    val request = state.transfer ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 目录树当前所在目录：null = 显示根位置列表；stack 用于「上一级」回退。
    val preferred = request.preferredKey?.let { key ->
        request.destinations.firstOrNull { it.key == key }
    }
    var current by remember(request) { mutableStateOf<Destination?>(preferred) }
    var stack by remember(request) { mutableStateOf<List<Destination>>(emptyList()) }
    // 下载带 preferredKey 时预选默认下载目录作为目标，主按钮立即可用。
    var selectedTarget by remember(request) { mutableStateOf<Destination?>(preferred) }
    var reloadToken by remember(request) { mutableIntStateOf(0) }
    var showNewFolder by remember(request) { mutableStateOf(false) }
    var newFolderName by remember(request) { mutableStateOf("") }

    val currentVal = current
    // current 变化（或新建文件夹后 reloadToken++）时异步加载子目录。
    val children by produceState<List<Destination>?>(
        initialValue = null,
        key1 = currentVal,
        key2 = reloadToken
    ) {
        // 切换目录时立刻清空，否则会短暂显示上一个目录的子项，
        // 看起来像「选择的位置和实际目录不一致」。
        value = null
        value = currentVal?.let { vm.childDirectories(it) }
    }
    val loading = currentVal != null && children == null
    val rows: List<Destination> = if (currentVal == null) request.destinations else (children ?: emptyList())
    val primary = MaterialTheme.colorScheme.primary

    fun goUp() {
        if (stack.isNotEmpty()) {
            current = stack.last()
            stack = stack.dropLast(1)
        } else {
            // 上一层正好是存储根时回到「全部位置」：根没有 Destination，
            // 硬造一个会让面包屑显示成「0」（File("/storage/emulated/0").name）。
            val up = current?.let { vm.parentDestination(it) }
            current = if (up == null || up.path.trimEnd('/') == vm.state.localRoot.trimEnd('/')) null else up
        }
    }

    ModalBottomSheet(
        onDismissRequest = vm::closeTransfer,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 640.dp)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 20.dp)
        ) {
            Text(formatTransferTitle(request.mode), fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "${request.items.size} 个项目 · 选择目标位置" +
                    if (request.mode == TransferMode.UPLOAD) " · 上传不会移动原文件" else "",
                fontSize = 12.sp,
                color = MutedText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )

            // 面包屑（清单 12/19）：全部位置 > 层1 > 层2 …，点哪层回哪层；无「上一级」。
            val crumbs = buildList {
                add(null to "全部位置")
                stack.forEach { add(it to it.label) }
                currentVal?.let { add(it to it.label) }
            }
            // 与浏览页 / 搜索位置选择共用同一套取舍：空间不够只丢外层，不截半个名字。
            PathBreadcrumbs(
                labels = crumbs.map { it.second },
                modifier = Modifier.padding(top = 12.dp),
                onCrumbClick = { index ->
                    // crumbs[0]=全部位置；crumbs[k](k>=1) 对应 stack[k-1]。
                    stack = if (index == 0) emptyList() else stack.take(index - 1)
                    current = crumbs[index].first
                    selectedTarget = current
                    showNewFolder = false
                }
            )

            // 目录列表 / 加载中 / 空态
            if (loading) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text("没有子文件夹", fontSize = 12.sp, color = MutedText)
                }
            } else {
                // 子目录可能有几百个。原来 Column + forEach 会把它们一次全部组合，
                // 打开选择器的那一帧就全压在主线上了。改成定高 LazyColumn，只组合可见行；
                // 定高之后它在外层 scrolling Column 里测得的约束是有限的，不会触发
                // 「Lazy 组件被无限高度测量」的崩溃。
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .padding(top = 6.dp)
                ) {
                    // 不挂 key：目标列表里理论上可能出现两条同 identity 的条目，
                    // 重复 key 会让 Lazy 直接抛异常；这些行按位置复用即可。
                    items(rows) { dest ->
                        val isRoot = currentVal == null
                        TransferDestRow(
                            icon = if (isRoot) destIcon(dest.type) else Icons.Outlined.Folder,
                            label = dest.label,
                            meta = dest.meta,
                            typeLabel = if (isRoot) destTypeLabel(dest.type) else null,
                            selected = selectedTarget?.identity() == dest.identity(),
                            onSelect = { selectedTarget = dest },
                            onEnter = {
                                stack = stack + listOfNotNull(current)
                                current = dest
                                selectedTarget = dest
                                showNewFolder = false
                            }
                        )
                    }
                }
            }

            // 仅本地目录时可新建文件夹
            if (currentVal?.type == DestType.LOCAL) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "在当前文件夹下新建子文件夹",
                        fontSize = 12.sp,
                        color = MutedText,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { showNewFolder = !showNewFolder },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, null, modifier = Modifier.size(17.dp))
                        Text(if (showNewFolder) "收起" else "新建", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                if (showNewFolder) {
                    // 输入框和按钮同高、同一基线，之前输入框比按钮高导致看起来错位。
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            singleLine = true,
                            placeholder = { Text("子文件夹名称", fontSize = 13.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            shape = RoundedCornerShape(12.dp),
                            colors = dialogFieldColors(),
                            modifier = Modifier.weight(1f).height(52.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val c = currentVal
                                if (c != null && newFolderName.isNotBlank() &&
                                    vm.createDirectoryAt(c, newFolderName)
                                ) {
                                    newFolderName = ""
                                    showNewFolder = false
                                    reloadToken++
                                }
                            },
                            enabled = newFolderName.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(52.dp)
                        ) { Text("新建", fontSize = 13.sp) }
                    }
                }
            }

            // 目标位置摘要：单选高亮 + 当前目标一目了然
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("目标位置", fontSize = 12.sp, color = MutedText)
                Text(
                    selectedTarget?.label ?: "未选择",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selectedTarget != null) primary else MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                if (selectedTarget != null) {
                    Icon(Icons.Filled.CheckCircle, "已选择", tint = primary, modifier = Modifier.size(18.dp))
                }
            }

            Button(
                onClick = { selectedTarget?.let(vm::performTransfer) },
                enabled = selectedTarget != null,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    when (request.mode) {
                        TransferMode.DOWNLOAD -> "开始下载"
                        TransferMode.UPLOAD -> "开始上传"
                        TransferMode.COPY -> "开始复制"
                        TransferMode.MOVE -> "开始移动"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            TextButton(
                onClick = vm::closeTransfer,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            ) { Text("取消", fontSize = 12.sp) }
        }
    }
}

private fun Destination.identity(): String = "${type.name}|$key|$path"

// 目标选择器里的类型标签：与面包屑第一层同一口径。
private fun destTypeLabel(type: DestType): String = when (type) {
    DestType.LOCAL -> MODULE_LABEL_LOCAL
    DestType.SYNO -> MODULE_LABEL_SYNO
    DestType.NET -> MODULE_LABEL_NET
}

private fun destIcon(type: DestType): ImageVector = when (type) {
    DestType.LOCAL -> Icons.Outlined.Folder
    DestType.SYNO -> Icons.Outlined.Cloud
    DestType.NET -> Icons.Outlined.Wifi
}

@Composable
private fun TransferDestRow(
    icon: ImageVector,
    label: String,
    meta: String,
    typeLabel: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onEnter: (() -> Unit)?
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .then(
                Modifier.border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    shape = shape
                )
            )
            // 与其他位置选择保持一致：点整行 = 进入该目录，右侧按钮 = 选中它。
            .clickable(onClick = { if (onEnter != null) onEnter() else onSelect() })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = primary, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, fontSize = 13.sp, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (typeLabel != null) {
            Text(
                typeLabel,
                fontSize = 12.sp,
                color = primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(primary.copy(alpha = 0.1f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
        // 右侧只放一个勾选圆圈：选中 ≠ 确认，确认由底部「开始…」按钮完成（清单 13）。
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onSelect),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                if (selected) "已选中" else "选中",
                tint = if (selected) primary else MutedText.copy(alpha = 0.45f),
                modifier = Modifier.size(21.dp)
            )
        }
    }
}
