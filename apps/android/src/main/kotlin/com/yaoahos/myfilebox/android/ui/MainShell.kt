package com.yaoahos.myfilebox.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.android.data.LocalRepository
import com.yaoahos.myfilebox.android.data.filterSummary
import com.yaoahos.myfilebox.android.data.itemMatchesFilter
import com.yaoahos.myfilebox.android.data.nameMatchesFilter
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FolderCard
import com.yaoahos.myfilebox.core.NetworkConnection
import com.yaoahos.myfilebox.core.Session
import com.yaoahos.myfilebox.core.TabKey
import com.yaoahos.myfilebox.core.Volume
import kotlin.math.abs
import com.yaoahos.myfilebox.android.ui.theme.semanticColors

internal data class CategoryItem(
    val name: String,
    val count: String,
    val icon: ImageVector,
    val tint: Color,
    val kind: FileKind,
    val special: String = ""
)

/**
 * 全局固定底栏：由 AppRoot 在页面宿主之外绘制，永远不参与页面水平转场。
 * 切换底栏时只有选中态瞬时更新，这一块区域本身保持不动。
 */
/**
 * 悬浮底栏压住的视觉高度：62dp 胶囊 + 12dp 底部留白 + 系统手势区。
 *
 * 底栏是浮在页面之上的（不参与 Scaffold 的 bottomBar 占位），三个首页的列表
 * 末尾必须自己留出这段空间，否则最后一行会停在胶囊底下，看着像「滚不到底」。
 */
internal val BottomBarClearance = 96.dp

@Composable
fun FixedBottomBar(state: MyFileBoxState, vm: AppViewModel, activeTab: TabKey) {
    // 底栏统一收敛为居中胶囊：手机上贴近全宽，平板 / 横屏最多 520dp 居中，
    // 不再铺满屏幕形成一条横贯的异色横条。底部安全区与四周留白在这里统一处理，
    // Scaffold 的透明占位复用同一个组件，占位高度天然一致。
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            if (activeTab == TabKey.LOCAL && state.homeSelecting) {
                HomeSelectionBar(state = state, vm = vm)
            } else {
                FloatingTabBar(
                    tabs = vm.visibleTabs(),
                    active = activeTab,
                    onSelect = vm::selectTab
                )
            }
        }
    }
}

/** 首页 8 个分类的图标与配色；设置里的排序预览复用同一份，避免两处颜色对不上。 */
internal val categories = listOf(
    CategoryItem("图片", "0 项", Icons.Outlined.Image, Color(0xFF7C5CFF), FileKind.IMAGE),
    CategoryItem("视频", "0 项", Icons.Outlined.Videocam, Color(0xFF5B8DEF), FileKind.VIDEO),
    CategoryItem("文档", "0 项", Icons.Outlined.Description, Color(0xFFF5A623), FileKind.DOCUMENT),
    CategoryItem("音频", "0 项", Icons.Outlined.LibraryMusic, Color(0xFFFF5A6B), FileKind.AUDIO),
    CategoryItem("安装包", "0 项", Icons.Outlined.Android, Color(0xFF16B364), FileKind.APK),
    CategoryItem("压缩包", "0 项", Icons.Outlined.FolderZip, Color(0xFF2F80ED), FileKind.ARCHIVE),
    CategoryItem("最近", "0 项", Icons.Outlined.Schedule, Color(0xFF12B5CB), FileKind.UNKNOWN, "recent"),
    CategoryItem("收藏", "0 项", Icons.Outlined.StarBorder, Color(0xFFFF5A7A), FileKind.UNKNOWN, "favorite")
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MainShell(state: MyFileBoxState, vm: AppViewModel, tabOverride: TabKey? = null) {
    val tabs = vm.visibleTabs()
    // 页面宿主按 key 决定显示哪个底栏页：底栏切换也是一次「页面转场」，
    // 因此这里不再自带 AnimatedContent，避免出现两套不一致的动画。
    val activeTab = tabOverride ?: state.tab
    val backEdgePx = with(LocalDensity.current) { PageMotion.EDGE_DP.dp.toPx() }
    var showVolume by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        bottomBar = {
            // 真实底栏画在页面宿主之外（AppRoot 固定层），这里只保留一个等高、
            // 全透明的占位，让 Scaffold 继续自动为页面内容留出底部空间。
            // 用同一个组件占位，可保证多选操作栏 / 标签栏两种高度都精确一致。
            Box(Modifier.alpha(0f)) {
                FixedBottomBar(state, vm, activeTab)
            }
        }
    ) { padding ->
        // 键盘弹出后底栏被键盘整个盖住，Scaffold 为透明底栏占位预留的底部空间
        // 必须一起释放，否则键盘上方会空出一条「底栏高度」的空白带，
        // 表单卡片还会被视口硬裁一刀（3.9.18 修，群晖登录页 / 网络连接表单同款问题）。
        val imeVisible = WindowInsets.isImeVisible
        val bottomReserve = if (imeVisible) 0.dp else padding.calculateBottomPadding()
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = bottomReserve)
                .pointerInput(tabs, activeTab, backEdgePx) {
                    var total = 0f
                    // 左边缘 20dp 是页面返回手势区，切底栏手势必须让行，
                    // 否则一次右滑会同时触发「返回页面」和「切换底栏」。
                    var fromBackEdge = false
                    detectHorizontalDragGestures(
                        onDragStart = { pos ->
                            total = 0f
                            fromBackEdge = pos.x <= backEdgePx
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (fromBackEdge) return@detectHorizontalDragGestures
                            total += dragAmount
                            if (abs(total) > 8f) change.consume()
                        },
                        onDragEnd = {
                            if (!fromBackEdge && abs(total) > 64f) {
                                vm.swipeTab(if (total < 0) 1 else -1)
                            }
                            total = 0f
                            fromBackEdge = false
                        },
                        onDragCancel = {
                            total = 0f
                            fromBackEdge = false
                        }
                    )
                }
        ) {
            // 底栏切换由外层统一页面宿主驱动（300ms 严格对称转场），此处只负责内容。
            when (activeTab) {
                TabKey.LOCAL -> LocalHome(state, vm)
                TabKey.SYNO -> if (state.session == null || state.connectionEditing) {
                    LoginGate(state, vm)
                } else {
                    SynoHome(state, vm) { showVolume = true }
                }
                TabKey.NET -> NetHome(state, vm)
            }
        }
    }

    if (showVolume) {
        VolumeDialog(state, vm) { showVolume = false }
    }
}


@Composable
fun HeaderOverflow(
    vm: AppViewModel,
    favoritesContext: FileContext = FileContext.LOCAL,
    hideNavigation: Boolean = false,
    showFilter: Boolean = false,
    // 设置独立于「互跳导航」：收藏 / 回收页不跳来跳去，但仍要有设置入口；
    // 只有设置自己的「关于」子页才关掉它，防止套娃。
    showSettings: Boolean = true,
    // 底部固定一项「首页」，按当前端回到各自根首页。
    showHome: Boolean = true,
    // 回收站等独立筛选页自带对话框：传了就由它打开，不用内置的全局筛选对话框。
    onFilter: (() -> Unit)? = null,
    // 非空时显示红色的破坏性操作（回收站「清空」）。
    clearAction: (() -> Unit)? = null,
    clearLabel: String = "清空",
    // 非空时用这对筛选值算摘要（回收站的独立筛选字段）。
    filterPair: Pair<String, String>? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val filterForSummary = filterPair ?: (vm.state.nameFilter to vm.state.extensionFilter)
    val activeFilter = filterSummary(filterForSummary.first, filterForSummary.second)
    Box {
        HeaderIcon(Icons.Outlined.MoreVert, "更多") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // 破坏性的「清空」放最顶：回收站里最该一眼看到、也最容易误触的操作，
            // 红色 + 置顶比埋在菜单底部更难漏看。
            if (clearAction != null) {
                DropdownMenuItem(
                    text = { Text(clearLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.DeleteSweep, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = { expanded = false; clearAction() }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
            // 收藏 / 回收 / 设置 这些二级页面里不再提供互相跳转的入口，
            // 避免「更多 → 设置 → 更多 → …」层层套娃、返回键要按很久。
            // 各底栏的收藏 / 回收只管各自的文件（清单 5）：本地只含本地，
            // 群晖看群晖的收藏与 #recycle 回收站，网络文件不提供这两项。
            if (!hideNavigation && favoritesContext != FileContext.NET) {
                DropdownMenuItem(
                    text = { Text("收藏", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.StarBorder, null, modifier = Modifier.size(18.dp)) },
                    onClick = { expanded = false; vm.openFavorites(favoritesContext) }
                )
                DropdownMenuItem(
                    text = { Text("回收", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(18.dp)) },
                    onClick = { expanded = false; vm.openRecycle(favoritesContext) }
                )
                if (favoritesContext == FileContext.SYNO) {
                    // 群晖共享管理紧跟回收下方（清单 5）：统一查看 / 取消共享链接。
                    DropdownMenuItem(
                        text = { Text("共享", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Share, null, modifier = Modifier.size(18.dp)) },
                        onClick = { expanded = false; vm.openShareManager() }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
            DropdownMenuItem(
                text = {
                    // 传输只此一处入口；有任务时在后面标出数量，不用再额外放一个按钮。
                    val running = vm.transfers.count { it.status == TransferStatus.RUNNING && !it.quiet }
                    Text(if (running > 0) "传输 · $running 个进行中" else "传输", fontSize = 13.sp)
                },
                leadingIcon = { Icon(Icons.Outlined.SwapVert, null, modifier = Modifier.size(18.dp)) },
                onClick = { expanded = false; vm.openTransferCenter() }
            )
            if (showFilter) {
                // 筛选紧跟「设置」上方：两个都是整页范围的开关，放一起更好找。
                DropdownMenuItem(
                    text = {
                        Text(
                            if (activeFilter.isBlank()) "筛选" else "筛选 · $activeFilter",
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.FilterList, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        expanded = false
                        if (onFilter != null) onFilter() else filterOpen = true
                    }
                )
            }
            if (showSettings) {
                DropdownMenuItem(
                    text = { Text("设置", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(18.dp)) },
                    onClick = { expanded = false; vm.openSettings() }
                )
            }
            if (showHome) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                DropdownMenuItem(
                    text = { Text("首页", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Home, null, modifier = Modifier.size(18.dp)) },
                    onClick = { expanded = false; vm.goHome(favoritesContext) }
                )
            }
        }
    }
    if (filterOpen) {
        FilterDialog(
            initialName = vm.state.nameFilter,
            initialExt = vm.state.extensionFilter,
            onDismiss = { filterOpen = false },
            onApply = { n, e -> vm.setFilters(n, e) },
            onClear = { vm.clearNameFilter() }
        )
    }
}

/**
 * 筛选对话框：名称 + 后缀名两个条件（同时满足才显示）。
 *
 * 抽成公共组件是因为浏览页、首页、收藏页、回收站都要用同一套交互与文案；
 * 各页面只负责把「读哪两个字段、写回哪两个字段」传进来。
 * 不做类型 / 大小 / 日期条件——那些都能用后缀近似表达，再多几行输入框只会让人犹豫。
 */
@Composable
fun FilterDialog(
    initialName: String,
    initialExt: String,
    note: String = "两个条件同时生效，只筛当前这一页；不区分大小写。只填后缀时只列文件、" +
        "文件夹会一并隐藏，需要进目录先点「清除」。换目录或返回上一页后自动清除。",
    onDismiss: () -> Unit,
    onApply: (String, String) -> Unit,
    onClear: () -> Unit
) {
    // key 用初始值：从别处改过筛选再打开，输入框要显示当前值而不是上次残留。
    var text by remember(initialName) { mutableStateOf(initialName) }
    var ext by remember(initialExt) { mutableStateOf(initialExt) }
    // 常用后缀：点一下直接填，省得手打。逗号分隔，和手动输入走同一套解析。
    val presets = listOf(
        "图片" to "jpg,jpeg,png,gif,webp",
        "视频" to "mp4,mkv,mov,avi",
        "文档" to "pdf,doc,docx,xls,xlsx,ppt,pptx,txt",
        "音频" to "mp3,flac,wav,m4a",
        "压缩包" to "zip,7z,rar,tar,gz",
        "安装包" to "apk"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("筛选", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    label = { Text("名称", fontSize = 12.sp) },
                    placeholder = { Text("名称关键字，可留空", fontSize = 13.sp) },
                    // 跟其它对话框里的输入框同一套配色：不聚焦时也留一道看得见的描边。
                    // 只靠 M3 默认的 outline，在浅色底上几乎等于没有框，用户找不到输入区。
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ext,
                    onValueChange = { ext = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    label = { Text("后缀名", fontSize = 12.sp) },
                    placeholder = { Text("jpg,png（多个用逗号分隔）", fontSize = 13.sp) },
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { (label, value) ->
                        val on = ext.trim() == value
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = if (on) MaterialTheme.colorScheme.primary else MutedText,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
                                )
                                .clickable { ext = if (on) "" else value }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
                Text(
                    note,
                    fontSize = 11.sp,
                    color = MutedText,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(text, ext); onDismiss() }) { Text("筛选") }
        },
        dismissButton = {
            TextButton(onClick = { onClear(); onDismiss() }) { Text("清除") }
        }
    )
}

/** 筛选生效时的常驻条：让用户知道「东西没丢，是被筛掉了」，并给一键清除。 */
@Composable
fun ActiveFilterBar(summary: String, onClear: () -> Unit, matched: Int? = null, total: Int? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.FilterList,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            // 计数口径跟浏览页一致：筛出来的 / 原本的「X / Y 个项目」，
            // 不另造「命中 N 项」这种和别处对不上的说法。
            if (matched == null || total == null) "筛选 $summary"
            else "$matched / $total 个项目 · 筛选 $summary",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 6.dp)
        )
        Text(
            "清除",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onClear)
                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
        )
    }
}

/** 浏览页 / 首页共用的常驻筛选条：直接读全局的两条筛选条件。 */
@Composable
fun ActiveFilterBar(vm: AppViewModel, matched: Int? = null, total: Int? = null) {
    ActiveFilterBar(
        summary = filterSummary(vm.state.nameFilter, vm.state.extensionFilter),
        onClear = vm::clearNameFilter,
        matched = matched,
        total = total
    )
}

@Composable
fun HeaderIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .pressScale(interaction),
        interactionSource = interaction
    ) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
    }
}

@Composable
internal fun FilterEmptyHint(vm: AppViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FolderGlyph(56)
        Text("没有匹配的项目", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(
            filterSummary(vm.state.nameFilter, vm.state.extensionFilter) +
                " · 筛后缀时文件夹也会隐藏",
            fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 4.dp)
        )
        TextButton(onClick = vm::clearNameFilter, modifier = Modifier.padding(top = 2.dp)) {
            Text("清除筛选", fontSize = 12.sp)
        }
    }
}

@Composable
internal fun EmptyModules(text: String, hint: String = "可在设置的「首页显示」中重新开启") {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FolderGlyph(56)
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(hint, fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun VolumeDialog(state: MyFileBoxState, vm: AppViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存储空间", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                Text("NAS 上检测到 ${state.volumes.size} 个存储空间，点击切换查看。", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(bottom = 8.dp))
                state.volumes.forEach { volume ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setVolume(volume.id); onDismiss() }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(volume.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("%.2f / %.0f TB · %d%% 已用 · %s".format(volume.usedTb, volume.totalTb, volume.percent, volume.type), fontSize = 12.sp, color = MutedText)
                        }
                        if (volume.id == state.prefs.volumeId) {
                            Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

