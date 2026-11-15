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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.android.data.LocalRepository
import com.yaoahos.myfilebox.android.data.nameMatchesFilter
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FolderCard
import com.yaoahos.myfilebox.core.NetworkConnection
import com.yaoahos.myfilebox.core.Session
import com.yaoahos.myfilebox.core.TabKey
import com.yaoahos.myfilebox.core.Volume
import kotlin.math.abs

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
@Composable
fun FixedBottomBar(state: MyFileBoxState, vm: AppViewModel, activeTab: TabKey) {
    if (activeTab == TabKey.LOCAL && state.homeSelecting) {
        HomeSelectionBar(state = state, vm = vm, modifier = Modifier)
    } else {
        FloatingTabBar(
            tabs = vm.visibleTabs(),
            active = activeTab,
            onSelect = vm::selectTab
        )
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
            // 压缩/解压等本地任务的底部进度条（不进传输中心、不发通知）。
            if (state.localTaskRunning) {
                LocalTaskBar(state.localTaskLabel, Modifier.align(Alignment.BottomCenter)) {
                    vm.cancelLocalTask()
                }
            }
        }
    }

    if (showVolume) {
        VolumeDialog(state, vm) { showVolume = false }
    }
}

@Composable
internal fun LocalTaskBar(label: String, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
        shadowElevation = 6.dp
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 10.dp).weight(1f)
            )
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("取消", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalHome(state: MyFileBoxState, vm: AppViewModel) {
    val modules = state.prefs.moduleOrder
        .filter { it.startsWith("local:") && it != "local:storage" && vm.moduleVisible(it) }
    val rootPath = state.localRoot.trimEnd('/')
    val rootFiles = state.localFiles.filter { it.path.trimEnd('/').substringBeforeLast('/') == rootPath }
    // 首页文件夹 / 根目录文件跟随统一排序偏好。
    // remember 键必须包含 tabSorts：setSort 按 Tab 隔离写入 tabSorts，
    // 仅看全局 sortKey 可能不变化，导致排序偶尔不生效。
    val sortedLocalFolders = remember(state.localFolders, state.prefs.tabSorts, state.nameFilter) {
        vm.sortedFolders(state.localFolders).filter { nameMatchesFilter(it.name, state.nameFilter) }
    }
    val sortedRootFiles = remember(rootFiles, state.prefs.tabSorts, state.nameFilter) {
        vm.sortFiles(rootFiles).filter { nameMatchesFilter(it.name, state.nameFilter) }
    }
    Box(Modifier.fillMaxSize()) {
    AppPullToRefreshBox(
        isRefreshing = state.refreshing,
        // 只重扫本地存储：群晖共享与网络连接探活跟这一屏无关，不该一起等。
        onRefresh = vm::refreshLocalTab,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().pullRefreshArea()) {
        // 顶栏固定（3.9.16）：文件多、已经滑到底部时，不必先滑回顶部才能点搜索 / 更多。
        PageTitle(
            title = if (state.homeSelecting) "已选择 ${state.homeSelected.size} 项" else "本地文件",
            subtitle = if (state.homeSelecting) "轻点继续选择 · 长按进入多选" else null,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.homeSelecting) {
                        // 只保留右上角一个「全选 / 取消全选」；不再有对勾按钮，
                        // 退出选择态用系统返回键或左上角返回。
                        SelectionSelectAllButton(
                            allSelected = vm.homeAllSelected(),
                            onClick = vm::toggleHomeSelectAll
                        )
                    } else {
                        // 首页根目录同样可以粘贴；复制后不必先钻进某个子目录。
                        if (vm.canPasteOnHome()) {
                            HeaderIcon(Icons.Outlined.ContentPaste, "粘贴到内部存储") { vm.pasteToHome() }
                        }
                        // 传输只在「更多」里出现一次（清单 3），标题栏不再重复放按钮。
                        HeaderIcon(Icons.Outlined.Search, "搜索") { vm.openSearch(FileContext.LOCAL) }
                        HeaderOverflow(vm, showFilter = true)
                    }
                }
            }
        )
        LazyColumn(
            state = LocalPageListState.current,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (!state.localAccess) {
                item { LocalAccessBanner(vm) }
            }
            if (state.nameFilter.isNotBlank()) item { ActiveFilterBar(vm) }
            modules.forEach { key ->
                when (key) {
                    "local:categories" -> item {
                        CategoryCard(state, vm)
                    }
                    "local:folders" -> {
                        item {
                            SectionTitle("内部存储", trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HomeSortButton(vm, "local")
                                    SectionViewToggle(state, vm, "localFolders")
                                }
                            })
                        }
                        item {
                            if (vm.viewMode(TabKey.LOCAL) == "grid") {
                                FolderGrid(
                                    items = sortedLocalFolders,
                                    files = sortedRootFiles,
                                    onClick = { vm.openLocalFolder(it.name) },
                                    onFileClick = vm::requestOpen,
                                    selectedIds = state.homeSelected,
                                    selecting = state.homeSelecting,
                                    onToggle = vm::toggleHomeSelected,
                                    onLongClick = vm::toggleHomeSelected,
                                    // 首页不再挂每项的三点：长按即多选，操作都收在底栏。
                                    showMore = false
                                )
                            } else {
                                FolderList(
                                    items = sortedLocalFolders,
                                    files = sortedRootFiles,
                                    onClick = { vm.openLocalFolder(it.name) },
                                    onFileClick = vm::requestOpen,
                                    selectedIds = state.homeSelected,
                                    selecting = state.homeSelecting,
                                    onToggle = vm::toggleHomeSelected,
                                    onLongClick = vm::toggleHomeSelected,
                                    showMore = false
                                )
                            }
                        }
                    }
                }
            }
            if (modules.isEmpty()) {
                item { EmptyModules("首页显示模块已全部隐藏") }
            }
        }
        }
    }
        // 新建按钮统一为右下角悬浮（清单 5）：与文件夹页 FAB 同款同位。
        if (!state.homeSelecting) {
            HomeCreateFab(
                vm,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 18.dp)
                    .navigationBarsPadding()
            )
        }
    }
}

@Composable
private fun HomeSelectionBar(state: MyFileBoxState, vm: AppViewModel, modifier: Modifier = Modifier) {
    var showMore by remember { mutableStateOf(false) }
    var showCompress by remember { mutableStateOf(false) }
    var showExtract by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var compressDefaultName by remember { mutableStateOf("压缩包") }
    // 与浏览页完全同一套动作：复制 / 移动 / 删除 / 更多。
    SelectionActionBar(
        actions = listOf(
            SelectionAction(Icons.Outlined.ContentCopy, "复制") { vm.stageHomeClipboard(false) },
            SelectionAction(Icons.Outlined.DriveFileMove, "移动") { vm.stageHomeClipboard(true) },
            SelectionAction(Icons.Outlined.Delete, "删除", danger = true) { vm.deleteHomeSelection() },
            SelectionAction(Icons.Outlined.MoreHoriz, "更多") { showMore = true }
        ),
        modifier = modifier
    )
    HomeSelectionMoreSheet(
        state = state, vm = vm, show = showMore, onDismiss = { showMore = false },
        onCompress = { name ->
            compressDefaultName = name
            showCompress = true
        },
        onExtract = { showExtract = true },
        onRename = { showRename = true }
    )
    if (showRename) {
        // 单个重命名（清单 8：批量重命名已移除）。
        vm.homeSelectedEntries().singleOrNull()?.let { target ->
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
    if (showCompress) {
        CompressDialog(
            defaultName = compressDefaultName,
            onConfirm = { name, format ->
                showCompress = false
                vm.compressHomeSelection(name, format)
            },
            onDismiss = { showCompress = false }
        )
    }
    if (showExtract) {
        ExtractDialog(
            rootPath = vm.state.localRoot,
            listSubfolders = vm::downloadSubfolders,
            onConfirm = { dest ->
                showExtract = false
                vm.extractHomeSelection(dest)
            },
            onDismiss = { showExtract = false }
        )
    }
}

/**
 * 首页多选的「更多」面板：上传 / 分享 / 收藏 / 压缩 / 解压 / 重命名 / 查看详情，
 * 和内部文件夹里的同一套（本地端的更多面板）。
 */
@Composable
private fun HomeSelectionMoreSheet(
    state: MyFileBoxState,
    vm: AppViewModel,
    show: Boolean,
    onDismiss: () -> Unit,
    onCompress: (defaultName: String) -> Unit,
    onExtract: () -> Unit,
    onRename: () -> Unit = {}
) {
    if (!show) return
    val entries = vm.homeSelectedEntries()
    val single = entries.singleOrNull()
    val canExtract = single != null && !single.isFolder && isArchiveName(single.name)
    val allFavorited = entries.isNotEmpty() && entries.all { vm.isFavorite(it) }
    val actions = listOf(
        SelectionAction(Icons.Outlined.Upload, "上传", enabled = vm.hasRemoteTarget()) {
            onDismiss()
            vm.uploadHomeSelection()
        },
        SelectionAction(Icons.Outlined.Share, "分享", enabled = entries.any { !it.isFolder }) {
            onDismiss()
            vm.shareHomeSelection()
        },
        SelectionAction(
            if (allFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
            if (allFavorited) "取消收藏" else "收藏"
        ) { onDismiss(); vm.favoriteHomeSelection() },
        SelectionAction(Icons.Outlined.FolderZip, "压缩") {
            onDismiss()
            onCompress(entries.firstOrNull()?.name ?: "压缩包")
        },
        SelectionAction(Icons.Outlined.Unarchive, "解压", enabled = canExtract) {
            onDismiss()
            onExtract()
        },
        // 重命名只支持单个（清单 8），固定排在「查看详情」上方。
        SelectionAction(Icons.Outlined.Edit, "重命名", enabled = single != null) {
            onDismiss()
            onRename()
        },
        SelectionAction(Icons.Outlined.Info, "查看详情", enabled = single != null) {
            onDismiss()
            vm.showHomeDetail()
        }
    )
    SelectionMoreSheet(actions = actions, onDismiss = onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderActionSheet(
    folder: FolderCard,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
            Text(folder.name, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(folder.meta, fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(16.dp))
            FolderSheetAction(Icons.Outlined.FolderOpen, "打开", onOpen)
            FolderSheetAction(Icons.Outlined.StarBorder, if (favorite) "取消收藏" else "收藏", onFavorite)
            FolderSheetAction(Icons.Outlined.ContentCopy, "复制", onCopy)
            FolderSheetAction(Icons.Outlined.DriveFileMove, "移动", onMove)
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            FolderSheetAction(Icons.Outlined.DeleteOutline, "删除文件夹", { confirmDelete = true }, danger = true)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除文件夹", fontWeight = FontWeight.Bold) },
            text = { Text("确定删除「${folder.name}」及其中所有内容？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("删除", color = Color(0xFFE5484D))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun FolderSheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pressClickable(onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (danger) Color(0xFFE5484D) else MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (danger) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 14.dp)
        )
    }
}

@Composable
private fun LocalAccessBanner(vm: AppViewModel) {
    var showPermission by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFFF4E5))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("需要文件访问权限", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF7A4A00))
            Text("授权后可浏览本机下载、相机、文档等真实文件", fontSize = 12.sp, color = Color(0xFF9A6A20), modifier = Modifier.padding(top = 2.dp))
        }
        TextButton(onClick = { showPermission = true }) { Text("去授权", fontSize = 12.sp) }
    }
    if (showPermission) {
        AlertDialog(
            onDismissRequest = { showPermission = false },
            title = { Text("需要文件访问权限") },
            text = { Text("MyFileBox 需要“所有文件访问权限”才能显示本机文件夹和文件。下一步会打开系统授权页，请选择 MyFileBox 并允许访问。") },
            confirmButton = {
                TextButton(onClick = { showPermission = false; vm.requestLocalAccess() }) { Text("去授权") }
            },
            dismissButton = { TextButton(onClick = { showPermission = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionViewToggle(state: MyFileBoxState, vm: AppViewModel, key: String) {
    val tab = when {
        key.startsWith("local") -> TabKey.LOCAL
        key.startsWith("syno") -> TabKey.SYNO
        else -> TabKey.NET
    }
    val grid = vm.viewMode(tab) == "grid"
    // 与内部层级的切换按钮保持同一套样式：onSurface + 21dp。
    IconButton(onClick = { vm.toggleView(tab) }) {
        Icon(
            if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
            contentDescription = if (grid) "切换为列表" else "切换为宫格",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(21.dp)
        )
    }
}

/** 首页排序按钮：点击在按钮下方展开排序菜单（不弹窗），可实时看排序变化。 */
@Composable
private fun HomeSortButton(vm: AppViewModel, tabKey: String = "local") {
    var show by remember { mutableStateOf(false) }
    val sortKey = vm.sortKeyFor(tabKey)
    val sortDir = vm.sortDirFor(tabKey)
    Box {
        IconButton(onClick = { show = true }) {
            Icon(
                Icons.Outlined.Sort,
                "排序",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(21.dp)
            )
        }
        DropdownMenu(expanded = show, onDismissRequest = { show = false }) {
            val items = buildList {
                add("name" to "按名称")
                add("time" to "按修改时间")
                if (tabKey == "local") add("size" to "按文件大小")
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
                            vm.setSort(value, if (sortDir == "asc") "desc" else "asc", tabKey)
                        } else {
                            vm.setSort(value, sortDir, tabKey)
                        }
                        show = false
                    }
                )
            }
        }
    }
}

/** 首页新建 FAB（清单 5）：右下角悬浮，与文件夹页「新建或上传」同款风格。 */
@Composable
private fun HomeCreateFab(vm: AppViewModel, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    SmallFloatingActionButton(
        onClick = { expanded = true },
        modifier = modifier
    ) {
        Icon(Icons.Outlined.Add, "新建文件或文件夹", modifier = Modifier.size(20.dp))
    }
    if (expanded) {
        SelectionMoreSheet(
            actions = listOf(
                SelectionAction(Icons.Outlined.CreateNewFolder, "新建文件夹") {
                    expanded = false
                    folderName = ""
                    showNewFolder = true
                },
                SelectionAction(Icons.Outlined.NoteAdd, "新建文件") {
                    expanded = false
                    fileName = ""
                    showNewFile = true
                }
            ),
            onDismiss = { expanded = false },
            title = "添加到内部存储"
        )
    }
    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    singleLine = true,
                    label = { Text("文件夹名称") },
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = folderName.isNotBlank(),
                    onClick = {
                        vm.createFolder(folderName, vm.state.localRoot)
                        showNewFolder = false
                    }
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("取消") } }
        )
    }
    if (showNewFile) {
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            title = { Text("新建文件") },
            text = {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    singleLine = true,
                    label = { Text("文件名（含扩展名）") },
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = fileName.isNotBlank(),
                    onClick = {
                        vm.createFile(fileName, vm.state.localRoot)
                        showNewFile = false
                    }
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFile = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun MiniFileCard(file: FileEntry, onClick: () -> Unit) {
    val tint = fileTint(file.kind)
    Column(
        Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tint.first),
            contentAlignment = Alignment.Center
        ) {
            Icon(fileIcon(file.kind), null, tint = tint.second, modifier = Modifier.size(21.dp))
        }
        Text(
            file.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 4.dp, start = 1.dp, end = 1.dp)
        )
        FileMetaLine(file, Modifier.padding(start = 1.dp, top = 1.dp), grid = true)
    }
}

/**
 * 最近文件按「具体日期」分区。
 *
 * timeLabel 现在带时分秒（列表要求一行看全），分区只能按**日期**取键，
 * 否则每个文件都成了独立一组。本年度分区标题省略年份，跨年才带年份。
 */
fun recentFileGroups(files: List<FileEntry>): List<Pair<String, List<FileEntry>>> {
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
    return files
        .map { it to recentDateKey(it) }
        .filter { (_, key) -> key.isNotBlank() }
        .groupBy({ it.second }, { it.first })
        .map { (date, group) ->
            val label = if (date.startsWith("$year/")) date.removePrefix("$year/") else date
            label to group.sortedByDescending { it.modified }
        }
        .sortedByDescending { it.first }
}

/** 分区键：一律 `yyyy/MM/dd`（不带时间）。拿不到真实时间戳时退回 timeLabel 的日期段。 */
private fun recentDateKey(file: FileEntry): String =
    if (file.modified > 0) LocalRepository.formatDate(file.modified)
    else file.timeLabel.substringBefore(" ").trim()

/**
 * 最近文件分区渲染为 LazyColumn 的独立条目：每个日期分组一个 item（整组一起出现，
 * 不会出现同屏半加载），滑到底部的「加载更早」条目进入视野时才拉下一批。
 */
fun LazyListScope.recentGroupsItems(
    groups: List<Pair<String, List<FileEntry>>>,
    grid: Boolean,
    onClick: (FileEntry) -> Unit,
    onMore: ((FileEntry) -> Unit)? = null,
    onNeedMore: (() -> Unit)? = null
) {
    groups.forEach { (label, group) ->
        item(key = "recent-$label") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MutedText,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
                )
                if (grid) {
                    Column(
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        group.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { file ->
                                    Box(Modifier.weight(1f)) { MiniFileCard(file) { onClick(file) } }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                } else {
                    group.forEach { file ->
                        FileRow(file, onMore = onMore?.let { more -> { more(file) } }) { onClick(file) }
                    }
                }
            }
        }
    }
    if (onNeedMore != null) {
        item(key = "recent-more") {
            LaunchedEffect(Unit) { onNeedMore() }
            Text(
                "上滑加载更早的文件…",
                fontSize = 11.sp,
                color = MutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun CategoryCard(state: MyFileBoxState, vm: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    // 分类排序（清单 4）：按设置里拖出来的顺序展示，默认原顺序。
    val ordered = remember(state.prefs.categoryOrder) {
        val order = state.prefs.categoryOrder
        if (order.isEmpty()) categories
        else categories.sortedBy { order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }
    val shown = if (expanded) ordered else ordered.take(4)
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 10.dp)
    ) {
        shown.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { item ->
                    val isFavorite = item.special == "favorite"
                    val isRecent = item.special == "recent"
                    val recentItems = state.localFiles.filter {
                        it.modified >= System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                    }.sortedByDescending { it.modified }
                    val items = when {
                        isFavorite -> vm.favoriteFiles(FileContext.LOCAL)
                        isRecent -> recentItems
                        item.kind == FileKind.IMAGE -> state.photos
                        item.kind == FileKind.UNKNOWN -> state.localFiles
                        else -> state.localFiles.filter { it.kind == item.kind }
                    }
                    val countLabel = when {
                        isFavorite -> "${items.size} 项"
                        isRecent -> "${recentItems.size} 项"
                        else -> "${state.categoryCounts[item.kind] ?: 0} 项"
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        CategoryTile(item.name, countLabel, item.icon, item.tint) {
                            if (isFavorite) vm.openFavorites(FileContext.LOCAL)
                            else vm.openLocalCategory(item.name, items, groupByDate = isRecent)
                        }
                    }
                }
            }
        }
        if (categories.size > 4) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (expanded) "收起分类" else "展开全部分类", fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginGate(state: MyFileBoxState, vm: AppViewModel) {
    val saved = remember { vm.savedLogin() }
    // 编辑连接时用当前会话的地址 / 账号预填，登录成功才替换，失败也不影响正在用的连接。
    var server by remember(state.session, state.connectionEditing) {
        mutableStateOf(state.session?.server ?: saved.first)
    }
    var account by remember(state.session, state.connectionEditing) {
        mutableStateOf(state.session?.account ?: saved.second)
    }
    var password by remember { mutableStateOf(saved.third) }
    var https by remember { mutableStateOf(true) }
    var remember by remember { mutableStateOf(true) }
    var allowSelfSigned by remember { mutableStateOf(true) }
    val loginInteraction = remember { MutableInteractionSource() }

    AppPullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().imePadding(),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            item {
                PageTitle(
                    title = "群晖文件",
                    trailing = { HeaderOverflow(vm) }
                )
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    AppBrandIcon(
                        modifier = Modifier.size(54.dp),
                        shape = RoundedCornerShape(17.dp),
                        contentDescription = "MyFileBox"
                    )
                    Text(
                        if (state.connectionEditing) "编辑连接" else "登录群晖 NAS",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = 7.dp)
                    )
                    Text(
                        if (state.connectionEditing) "修改后需要重新登录，取消则保持当前连接" else "安全访问你的私有云文件",
                        fontSize = 12.sp,
                        color = MutedText,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("服务器") },
                        // 提示固定成一行，不再随输入内容变成解析结果，避免用户以为提示会变。
                        supportingText = { Text("地址或QuickConnectID", fontSize = 12.sp) },
                        shape = RoundedCornerShape(14.dp),
                        colors = dialogFieldColors()
                    )
                    // 明文只在用户关掉 HTTPS 时提示一次，不作为常驻说明。
                    if (!https) {
                        Text(
                            "关闭 HTTPS 后账号与密码将以明文传输，仅建议在可信内网使用",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("账号") },
                        shape = RoundedCornerShape(14.dp),
                        colors = dialogFieldColors()
                    )
                    Spacer(Modifier.height(7.dp))
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = "密码",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("HTTPS", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        Switch(checked = https, onCheckedChange = { https = it })
                        Text("记住登录", fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
                        Switch(checked = remember, onCheckedChange = { remember = it })
                    }
                    // 清单 8（3.1.0）：恢复「允许自签名证书」选项，默认选中。
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("允许自签名证书", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        Switch(checked = allowSelfSigned, onCheckedChange = {
                            allowSelfSigned = it
                            vm.setAllowSelfSigned(it)
                        })
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.connectionEditing) {
                            // 编辑连接只是回到登录页改参数，取消后原来的会话还在。
                            OutlinedButton(
                                onClick = vm::cancelConnectionEditing,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(0.4f).height(46.dp)
                            ) { Text("取消", fontSize = 13.sp) }
                        }
                        Button(
                            // 没网时不发请求：群晖要依次试直连与中继，没网时每个通道都要
                            // 耗满超时，最后才给一句「已尝试 N 个通道均无法连接」——
                            // 用户真正要做的只是打开 Wi-Fi。
                            onClick = {
                                // 表单没填全时先让校验说话——那些提示与有没有网无关，
                                // 用「没联网」盖掉它，用户会去开 Wi-Fi 再回来，仍然登录不了。
                                val filled = server.isNotBlank() && account.isNotBlank() && password.isNotBlank()
                                val offline = if (filled) vm.offlineNotice() else null
                                if (offline != null) vm.showMessage(offline)
                                else vm.login(server, account, password, https, remember)
                            },
                            enabled = !state.loading,
                            interactionSource = loginInteraction,
                            modifier = Modifier.weight(1f).height(46.dp).pressScale(loginInteraction),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                when {
                                    state.loading -> "正在连接…"
                                    state.connectionEditing -> "保存并登录"
                                    else -> "登录"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // 登录失败原因常驻显示：Snackbar 一闪而过，用户根本看不清为什么连不上。
                    state.loginError?.takeIf { !state.loading }?.let { reason ->
                        Text(
                            reason,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    Text(
                        "登录信息仅保存在本机，不会上传到第三方服务。",
                        fontSize = 12.sp,
                        color = MutedText,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SynoHome(state: MyFileBoxState, vm: AppViewModel, onVolume: () -> Unit) {
    var showAccount by remember { mutableStateOf(false) }
    val sharesByKey = state.shares.associateBy { it.key }
    val modules = state.prefs.moduleOrder
        .filter { it.startsWith("syno:") && vm.moduleVisible(it) }
    // 冷启动/静默登录后共享列表可能还没拉到：进到群晖首页发现是空的就自动刷一次，
    // 不用用户手动下拉刷新才看到文件夹。
    LaunchedEffect(state.session?.sid) {
        if (state.session != null && state.shares.isEmpty()) vm.refreshSyno()
    }
    AppPullToRefreshBox(
        isRefreshing = state.refreshing,
        // 只拉共享与卷（并行），不再连带全盘扫描和连接探活。
        onRefresh = vm::refreshSynoTab,
        modifier = Modifier.fillMaxSize()
    ) {
        val shareCards = vm.sortedFolders(state.shares.map {
            FolderCard(
                name = it.title,
                meta = "",
                kind = FileKind.FOLDER,
                path = it.key,
                date = it.subtitle,
                count = 0
            )
        }).filter { nameMatchesFilter(it.name, state.nameFilter) }
        Column(Modifier.fillMaxSize().statusBarsPadding().pullRefreshArea()) {
        // 顶栏固定（3.9.16）：共享文件夹多时也能随时点搜索 / 账号 / 更多。
        PageTitle(
            title = "群晖文件",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 传输只在「更多」里出现一次（清单 3）。
                    HeaderIcon(Icons.Outlined.Search, "搜索") { vm.openSearch(FileContext.SYNO) }
                    HeaderIcon(Icons.Outlined.Person, "账号") { showAccount = true }
                    HeaderOverflow(vm, FileContext.SYNO, showFilter = true)
                }
            }
        )
        // 群晖首页原来也没有设备状态说明：飞行模式下 WiFi 单独打开时，用户只看到
        // 过期的共享列表，连不上还要等满超时。这一行只说状态，不拦内网请求。
        DeviceNoticeRow(vm.networkNotice())
        // 进共享文件夹要先重新解析通道再登录，切网后实测 7~8 秒；这段时间首页原本
        // 毫无变化，用户只能反复点。openShare 会在 1 秒没结果时挂上 connectHint，
        // 这里把它说出来，并给一个能点的重试。
        if (state.connectHint) {
            ConnectHintBar(
                state, vm,
                waitingText = "正在连接群晖…",
                failedText = "网络已变化，请下拉刷新",
                onRetry = vm::refreshSynoTab
            )
        }
        LazyColumn(
            state = LocalPageListState.current,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (state.nameFilter.isNotBlank()) item { ActiveFilterBar(vm) }
            modules.forEach { key ->
                when (key) {
                    "syno:storage" -> item { NasCard(state.session, vm.volume(), onVolume) }
                    "syno:shares" -> {
                        item {
                            SectionTitle("文件夹", trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HomeSortButton(vm, "syno")
                                    SectionViewToggle(state, vm, "synoShares")
                                }
                            })
                        }
                        item {
                            val openShare: (FolderCard) -> Unit = { card ->
                                sharesByKey[card.path]?.let(vm::openShare)
                            }
                            // 群晖首页共享文件夹不带三点按钮（清单 17）。
                            if (vm.viewMode(TabKey.SYNO) == "grid") {
                                FolderGrid(shareCards, onClick = openShare, showCount = false)
                            } else {
                                FolderList(shareCards, onClick = openShare, showCount = false)
                            }
                        }
                    }
                }
            }
            if (modules.isEmpty()) {
                item { EmptyModules("群晖首页显示已全部隐藏") }
            }
        }
        }
    }
    if (showAccount && state.session != null) {
        ModalBottomSheet(
            onDismissRequest = { showAccount = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp).imePadding()) {
                Text("群晖账号", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "${state.session.account}@${state.session.server}",
                    fontSize = 12.sp,
                    color = MutedText,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(16.dp))
                FolderSheetAction(Icons.Outlined.Edit, "编辑连接", onClick = {
                    showAccount = false
                    vm.openEditSyno()
                })
                FolderSheetAction(Icons.Outlined.Logout, "退出登录", onClick = {
                    showAccount = false
                    vm.logout()
                }, danger = true)
            }
        }
    }
}

@Composable
private fun NasCard(session: Session?, volume: Volume?, onVolume: () -> Unit) {
    // 整块渐变都由主题色派生：写死深蓝起笔会让用户选了红 / 绿之后，
    // 卡片变成「深蓝 → 蓝 → 红」的三段杂色。
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(accent.shade(0.72f), accent.shade(0.34f), accent)
                )
            )
            .padding(18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Cloud, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text("PRIVATE CLOUD  ·  在线", color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp, letterSpacing = 1.2.sp)
                    Text("${session?.name ?: "Leo"} 的私有云", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    onClick = onVolume,
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.14f)
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(volume?.name ?: "存储空间 1", color = Color.White, fontSize = 12.sp)
                        Icon(Icons.Outlined.ChevronRight, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
                    }
                }
            }
            Text("${session?.server ?: "192.168.1.100:5001"} · HTTPS", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.Bottom) {
                Text("%.2f TB".format(volume?.usedTb ?: 2.42), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
                Text(" / %.0f TB".format(volume?.totalTb ?: 4.0), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
                Spacer(Modifier.weight(1f))
                Text("${volume?.percent ?: 60}% 已用", color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth((volume?.percent ?: 60) / 100f).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.92f)))
            }
            Text("DSM 7.2   ·   12 ms   ·   ${volume?.type ?: "SHR"}   ·   ${stateCountLabel(volume)}", color = Color.White.copy(alpha = 0.52f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun stateCountLabel(volume: Volume?): String = if (volume?.id == "v2") "4 个共享文件夹" else "6 个共享文件夹"

/**
 * 设备联网状态说明行：网络首页与群晖首页共用。
 *
 * 纯展示、不可点击，不会吞掉列表首行的点击。飞行模式开着但把 WiFi 单独打开、
 * 或 WiFi 连着却上不了外网时也要显示——这两种情况 activeNetwork 还在，
 * 原来的 offlineNotice 返回 null，用户只看到过期的「● 在线」卡片和超时。
 *
 * 状态查询必须由调用方在 item lambda 之外算好传进来：LazyColumn 的 item 有自己的
 * 重组作用域，参数只有不变的 vm 时会被跳过，网络回调后就一直停在首次的 null。
 */
@Composable
private fun DeviceNoticeRow(notice: String?) {
    val text = notice ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Wifi, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * 列表页上的连接等待提示：请求超过 1 秒还没有结果时挂出，可点重试、可关闭。
 *
 * 网络位置与群晖首页共用一个实现。两边都出现过「点了没反应、用户反复点」，而卡片和
 * 文件夹的状态不能改（在线就是在线，进目录只是读一次），所以只能另起一条说明在等什么。
 */
@Composable
private fun ConnectHintBar(
    state: MyFileBoxState,
    vm: AppViewModel,
    waitingText: String,
    failedText: String,
    onRetry: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .pressClickable(onRetry)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Wifi, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            // 设备状态压过一切：先让用户去解决唯一能解决的那件事。
            vm.networkNotice() ?: if (state.loading) waitingText else failedText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
        IconButton(onClick = vm::dismissNetworkHint, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Outlined.Close, "关闭提示", tint = MutedText, modifier = Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetHome(state: MyFileBoxState, vm: AppViewModel) {
    var editing by remember { mutableStateOf<NetworkConnection?>(null) }
    var adding by remember { mutableStateOf(false) }
    // 记住最近一次点开的连接：等待提示条上的「重试」要重连它，而不是泛泛刷新整页。
    var pending by remember { mutableStateOf<NetworkConnection?>(null) }
    val online = state.connections.count { it.online }
    AppPullToRefreshBox(
        isRefreshing = state.refreshing,
        // 只探活已有连接（并行），不再连带全盘扫描与群晖请求。
        onRefresh = vm::refreshNetTab,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().pullRefreshArea()) {
        // 顶栏固定（3.9.16）：位置多、已经滑到底部时也能直接点搜索 / 添加 / 更多。
        PageTitle(
            title = "网络文件",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 网络端也要能搜索；否则搜索位置里的网络连接永远用不上。
                    // 传输只在「更多」里出现一次（清单 3）。
                    HeaderIcon(Icons.Outlined.Search, "搜索") { vm.openSearch(FileContext.NET) }
                    HeaderIcon(Icons.Outlined.Add, "添加") { adding = true }
                    HeaderOverflow(vm, FileContext.NET, showFilter = true)
                }
            }
        )
        // 点卡片进目录期间卡片状态不动（在线就是在线的读操作），等待感全交给这条：
        // 1 秒没结果才挂，正常几十毫秒的连接根本不会出现。
        if (state.connectHint) {
            ConnectHintBar(
                state, vm,
                waitingText = "正在打开网络位置…",
                failedText = "连接超时，请检查网络",
                onRetry = { (pending ?: state.connections.firstOrNull())?.let(vm::openConnection) }
            )
        }
        LazyColumn(
            state = LocalPageListState.current,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (state.nameFilter.isNotBlank()) item { ActiveFilterBar(vm) }
            item {
                SectionTitle("已连接的位置", trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HomeSortButton(vm, "net")
                        SectionViewToggle(state, vm, "netConnections")
                    }
                })
            }
            // 在 item 之外现查：LazyColumn 的 item 是独立重组作用域，把查询放进去
            // 会因参数只有 vm 而被跳过，网络回调后提示永远停在首次的 null。
            val deviceNotice = vm.networkNotice()
            if (deviceNotice != null) item { DeviceNoticeRow(deviceNotice) }
            val netGrid = vm.viewMode(TabKey.NET) == "grid"
            val sortedConnections = vm.sortedConnections(state.connections)
                .filter { nameMatchesFilter(it.name, state.nameFilter) }
            if (sortedConnections.isEmpty()) {
                item { EmptyModules("还没有网络位置，点击右上角 + 添加", "支持 WebDAV / SMB / FTP / SFTP，凭证仅保存在本机") }
            } else if (netGrid) {
                item {
                    Column(
                        Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        sortedConnections.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                row.forEach { connection ->
                                    NetworkTile(connection, Modifier.weight(1f), onClick = {
                                        // 「离线」只是上次探测的结果，点击时始终重试连接；
                                        // openConnection 失败时会给出明确的错误提示。
                                        pending = connection
                                        vm.openConnection(connection)
                                    }, onEdit = { editing = connection })
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                item {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        sortedConnections.forEach { connection ->
                            NetworkCard(connection, onClick = {
                                pending = connection
                                vm.openConnection(connection)
                            }, onEdit = { editing = connection })
                        }
                    }
                }
            }
        }
        }
    }
    if (adding || editing != null) {
        AddNetworkDialog(
            initial = editing,
            vm = vm,
            onDismiss = { adding = false; editing = null },
            onSave = { id, name, protocol, host, port, path, username, password, secure ->
                val ok = if (id == null) {
                    vm.addConnection(name, protocol, host, port, path, username, password, secure)
                } else {
                    vm.updateConnection(id, name, protocol, host, port, path, username, password, secure)
                }
                // 只有保存成功才关窗；失败时保留用户填的内容，只弹提示。
                if (ok) {
                    adding = false
                    editing = null
                }
                ok
            },
            onDelete = editing?.let { connection ->
                {
                    editing = null
                    vm.requestDeleteConnection(connection)
                }
            }
        )
    }
}

@Composable
private fun NetworkHero(count: Int, online: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))))
            .padding(18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Wifi, null, tint = Color.White, modifier = Modifier.size(15.dp))
                Text("NETWORK STORAGE", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(start = 7.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                Text("$count 个网络位置", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                Text(" $online 在线", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
                listOf("WebDAV", "SMB", "FTP").forEach {
                    Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.18f)) {
                        Text("· $it", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
            }
        }
    }
}

/** 统一的连接地址显示：每种协议有固定格式，末尾不带多余斜杠。 */
private fun connectionAddressLabel(c: NetworkConnection): String {
    val p = c.path.trim().let { if (it.startsWith("/")) it else if (it.isBlank()) "" else "/$it" }.trimEnd('/')
    return when (c.protocol.uppercase()) {
        "SMB" -> "\\\\${c.host}\\${p.trimStart('/')}".trimEnd('\\')
        "SFTP" -> "sftp://${c.host}:${c.port}$p"
        "FTP" -> "ftp://${c.host}:${c.port}$p"
        else -> {
            val scheme = if (c.secure || c.port == 443) "https" else "http"
            "$scheme://${c.host}:${c.port}$p"
        }
    }
}

/** 协议图标底色：宫格与列表共用，保证两种视图完全一致。 */
private fun protocolIconBackground(protocol: String): Color = when (protocol) {
    "SMB" -> Color(0xFFEAF1FF)
    // FTP 用紫色系，和 WebDAV 的珊瑚橙拉开距离（原来两者都是橙，很难分辨）。
    "FTP" -> Color(0xFFF1ECFF)
    "SFTP" -> Color(0xFFE8F8EE)
    else -> Color(0xFFFFEEE6)
}

/**
 * 协议图标：宫格与列表共用。
 * 光靠颜色区分不够可靠（色弱 / 深色模式下橙色系几乎一样），
 * 所以每种协议同时用不同的图形 + 不同色相：
 * SMB 蓝·机架 / FTP 紫·双向箭头 / SFTP 绿·盾牌 / WebDAV 珊瑚橙·云。
 */
@Composable
private fun ProtocolIcon(protocol: String, size: androidx.compose.ui.unit.Dp = 22.dp) {
    val tint = when (protocol) {
        "SMB" -> Color(0xFF3B7BFF)
        "FTP" -> Color(0xFF7C5CFF)
        "SFTP" -> Color(0xFF2ECC71)
        else -> Color(0xFFFF7043)
    }
    val icon = when (protocol) {
        "SMB" -> Icons.Outlined.Dns
        "FTP" -> Icons.Outlined.SwapVert
        "SFTP" -> Icons.Outlined.VerifiedUser
        else -> Icons.Outlined.Cloud
    }
    Icon(icon, null, tint = tint, modifier = Modifier.size(size))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkCard(
    connection: NetworkConnection,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onEdit)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(
                protocolIconBackground(connection.protocol)
            ),
            contentAlignment = Alignment.Center
        ) {
            ProtocolIcon(connection.protocol)
        }
        Column(Modifier.weight(1f).padding(start = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(connection.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    connection.protocol,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Text(
                connectionAddressLabel(connection),
                fontSize = 12.sp,
                color = MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // 这里只反映「上一次探测的结论」：点进去读目录不再改写成「正在连接…」，
                // 那个中间态一闪而过，反而让人以为连接掉线了。
                if (connection.online) "● 在线 · ${connection.latency} ms"
                else "● 离线 · 请稍后重试",
                fontSize = 12.sp,
                color = if (connection.online) Color(0xFF1AA874) else MutedText,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Outlined.Settings, "编辑连接", tint = MutedText, modifier = Modifier.size(17.dp))
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MutedText, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkTile(
    connection: NetworkConnection,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onEdit)
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(
                    protocolIconBackground(connection.protocol)
                ),
                contentAlignment = Alignment.Center
            ) {
                ProtocolIcon(connection.protocol)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Settings, "编辑连接", tint = MutedText, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            connection.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            connection.protocol,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            connectionAddressLabel(connection),
            fontSize = 12.sp,
            color = MutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            if (connection.online) "● 在线 · ${connection.latency} ms" else "● 离线",
            fontSize = 12.sp,
            color = if (connection.online) Color(0xFF1AA874) else MutedText,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

/**
 * 表单类弹窗的次要按钮：统一高度 40dp、圆角 12dp、1dp 描边。
 * 描边用文字色 28% 透明度而不是 outline：浅色背景上也能看出按钮边界，
 * 同时又不会像实心按钮那样跟「保存」抢视觉重心。
 */
private enum class DialogActionTone { ACCENT, DANGER, NEUTRAL }

/**
 * 弹窗底部操作按钮：统一 40dp 高、12dp 圆角、等宽排布。
 *
 * 三档视觉层级，避免「测试」和「取消」长得一模一样（都是灰描边、灰字）：
 * - ACCENT：主色淡底 + 主色描边，用于「测试」这类有实际动作但不写入的次要操作；
 * - DANGER：红字红描边，用于删除；
 * - NEUTRAL：中性灰描边，用于取消/关闭，视觉最弱。
 * 保存是主色实心按钮，一眼即可区分主次。
 */
@Composable
private fun DialogOutlineAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: DialogActionTone = DialogActionTone.NEUTRAL
) {
    val accent = when (tone) {
        DialogActionTone.ACCENT -> MaterialTheme.colorScheme.primary
        DialogActionTone.DANGER -> Color(0xFFE25C5C)
        DialogActionTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fill = when (tone) {
        DialogActionTone.ACCENT -> accent.copy(alpha = if (enabled) 0.10f else 0.04f)
        else -> Color.Transparent
    }
    val borderAlpha = when (tone) {
        DialogActionTone.ACCENT -> 0.38f
        DialogActionTone.DANGER -> 0.28f
        DialogActionTone.NEUTRAL -> 0.20f
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accent.copy(alpha = if (enabled) borderAlpha else borderAlpha * 0.45f)
        ),
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = fill,
            contentColor = accent,
            disabledContainerColor = fill,
            disabledContentColor = accent.copy(alpha = 0.38f)
        )
    ) {
        Text(text, fontSize = 13.5.sp, maxLines = 1, softWrap = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddNetworkDialog(
    initial: NetworkConnection?,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onSave: (String?, String, String, String, String, String, String, String, Boolean) -> Boolean,
    onDelete: (() -> Unit)? = null
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var port by remember(initial) { mutableStateOf(initial?.port?.toString() ?: "5006") }
    var protocol by remember(initial) { mutableStateOf(initial?.protocol ?: "WebDAV") }
    var path by remember(initial) { mutableStateOf(initial?.path ?: "") }
    var username by remember(initial) { mutableStateOf(initial?.username ?: "") }
    var password by remember(initial) { mutableStateOf(initial?.password ?: "") }
    var secure by remember(initial) { mutableStateOf(initial?.secure ?: true) }
    var testing by remember(initial) { mutableStateOf(false) }
    var testResult by remember(initial) { mutableStateOf<String?>(null) }
    // 保存失败时的行内提示：对话框是独立窗口，页面顶部的浮层会被它挡住。
    var formError by remember(initial) { mutableStateOf<String?>(null) }
    // 键盘弹出时对话框只剩半屏：表单区交给 weight + verticalScroll 自适应，
    // 有键盘就自动变矮并滚动，没键盘就按内容高度展开。
    // 整个弹窗也要有高度上限：之前只钳了表单区，标题 + 按钮行 + 提示行照样能把
    // 对话框顶出屏幕，长一点的报错直接被屏幕边缘裁掉。
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val maxDialogHeight = (screenHeight * 0.92f).coerceAtLeast(260.dp)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            // 底色跟「新建文件夹」「筛选」这些 AlertDialog 一致（surfaceContainerHigh），
            // 且不随主题色变化。旧实现用 surface + tonalElevation = 8dp：
            // Material3 会按高度把 surfaceTint（= 主题色）混进底色，红色主题下这张
            // 对话框就整体泛红，和别的输入界面明显不是一套。层级改由阴影表达。
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .heightIn(max = maxDialogHeight)
        ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).imePadding()) {
            Text(
                if (initial == null) "添加网络位置" else "编辑网络连接",
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp
            )
            Column(
                Modifier
                    // weight(fill = false)：内容不多时按内容高度，内容过多时才占满剩余空间并滚动。
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (formError != null) {
                    InlineNotice(formError.orEmpty(), androidx.compose.ui.graphics.Color(0xFFE25C5C))
                }
                // 四个协议必须在一行里排开；FlowRow 在窄屏上会把 SFTP 挤到下一行。
                // 也不用 Material 的 FilterChip：它自带左右内边距，等分宽度后
                // WebDAV 会被裁成「WebD」。这里用自定义紧凑胶囊，宽度可精确控制。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("WebDAV", "SMB", "FTP", "SFTP").forEach { option ->
                        ProtocolChip(
                            label = option,
                            selected = protocol == option,
                            // 「WebDAV」比其它三个多两到三个字符，等分宽度时只够放下
                            // 「WebD」（12sp 下约 168px，等分后仅 175px 还含边框）。
                            // 按内容给权重，四枚仍在同一行。
                            modifier = Modifier.weight(if (option == "WebDAV") 1.7f else 1f),
                            onClick = {
                                if (protocol != option) {
                                    protocol = option
                                    // 新建连接时切换协议清空表单；编辑已有连接时保留 host/用户/密码，
                                    // 只调整端口默认值（不同协议默认端口不同）。
                                    if (initial == null) {
                                        host = ""
                                        port = when (option) {
                                            "SMB" -> "445"
                                            "FTP" -> "21"
                                            "SFTP" -> "22"
                                            else -> "5006"
                                        }
                                        path = ""
                                        username = ""
                                        password = ""
                                    } else {
                                        port = when (option) {
                                            "SMB" -> "445"
                                            "FTP" -> "21"
                                            "SFTP" -> "22"
                                            else -> port.ifBlank { "5006" }
                                        }
                                    }
                                    if (option == "SFTP") secure = true
                                }
                                testResult = null
                            }
                        )
                    }
                }
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("显示名称（可选）") },
                    placeholder = { Text("留空则使用地址", fontSize = 13.sp) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                // 服务器地址独占整行并允许折行：以前和端口挤一行，`https://dav.jianguoyun.com/dav`
                // 这类长域名只能看到开头，必须拖动才能看完。整行 + 最多三行折行后一目了然。
                OutlinedTextField(
                    host,
                    // 折行只用于显示，不允许真的输入换行符，否则地址会被写坏。
                    { host = it.replace("\n", "").replace("\r", "") },
                    label = { Text("服务器地址") },
                    placeholder = { Text("192.168.1.10 或 example.com", fontSize = 13.sp) },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                // 端口 + 路径同一行（3.9.19 端口在前、路径在后，与连接测试 / 排错习惯一致）：
                // 端口固定 96dp 刚好放下 5 位数字，路径吃剩余宽度。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        port, { port = it }, label = { Text("端口") }, singleLine = true,
                        shape = RoundedCornerShape(12.dp), colors = dialogFieldColors(),
                        modifier = Modifier.width(96.dp)
                    )
                    OutlinedTextField(
                        path, { path = it }, label = { Text("路径") }, singleLine = true,
                        placeholder = { Text("可留空", fontSize = 13.sp) },
                        shape = RoundedCornerShape(12.dp), colors = dialogFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }
                // 用户名、密码各自独占一行：并排时各自只有半屏宽，查看密码（明文）时
                // 稍长一点的密码就被截断，只能横向拖动。整行后 20 位以内的密码完整可见。
                OutlinedTextField(
                    username, { username = it }, label = { Text("用户名") }, singleLine = true,
                    shape = RoundedCornerShape(12.dp), colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码",
                    modifier = Modifier.fillMaxWidth()
                )
                // 安全连接与说明合到一行：右侧开关，左侧标题 + 一行小字说明。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (protocol != "SMB") {
                            Text("使用安全连接", fontSize = 13.sp)
                        }
                        Text(
                            "凭证仅保存在本机；连接时会读取真实目录。",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MutedText,
                            modifier = Modifier.padding(top = if (protocol == "SMB") 0.dp else 2.dp)
                        )
                    }
                    if (protocol != "SMB") {
                        Switch(checked = secure, onCheckedChange = { secure = it })
                    }
                }
            }
            // 固定按钮区：测试 / 删除 / 取消 / 保存 同一行、等宽、同高。
            // 文案压到两个字（原「测试连接」「保存并连接」「保存修改」），四个按钮才排得开
            // 又不会被裁字；主操作实心、危险操作红色描边、其余弱化描边，一眼能分主次。
            HorizontalDivider(
                Modifier.padding(top = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DialogOutlineAction(
                    text = if (testing) "测试中" else "测试",
                    enabled = !testing,
                    tone = DialogActionTone.ACCENT,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        testing = true
                        testResult = null
                        formError = null
                        vm.testConnectionDraft(protocol, host, port, path, username, password, secure) { _, message ->
                            testing = false
                            testResult = message
                        }
                    }
                )
                if (onDelete != null) {
                    DialogOutlineAction(
                        text = "删除",
                        tone = DialogActionTone.DANGER,
                        modifier = Modifier.weight(1f),
                        onClick = onDelete
                    )
                }
                DialogOutlineAction(
                    text = "取消",
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                val saveInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        formError = null
                        val ok = onSave(initial?.id, name, protocol, host, port, path, username, password, secure)
                        if (!ok) {
                            // 文字已经复制到对话框内的行内提示，把页面浮层那条消费掉，
                            // 否则同一句话会在屏幕上出现两次。
                            formError = vm.state.message ?: "保存失败，请检查填写内容"
                            vm.consumeMessage()
                        }
                    },
                    interactionSource = saveInteraction,
                    modifier = Modifier.weight(1f).height(40.dp).pressScale(saveInteraction),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("保存", fontSize = 13.5.sp, maxLines = 1, softWrap = false)
                }
            }
            // 测试结果：高度按内容走（上限约 5 行，超出可滚动），不再固定 40dp 把长报错裁掉。
            val resultText = if (testing) "正在测试…" else testResult.orEmpty()
            if (resultText.isNotBlank()) {
                val accent = when {
                    testing -> MutedText
                    testResult?.startsWith("连接可用") == true -> Color(0xFF1AA874)
                    else -> Color(0xFFE25C5C)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        resultText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = accent,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = 90.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun ProtocolChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                color = if (selected) primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedText, modifier = Modifier.padding(start = 4.dp, bottom = 5.dp))
}

@Composable
fun HeaderOverflow(
    vm: AppViewModel,
    favoritesContext: FileContext = FileContext.LOCAL,
    hideNavigation: Boolean = false,
    showFilter: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val activeFilter = vm.state.nameFilter
    Box {
        HeaderIcon(Icons.Outlined.MoreVert, "更多") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
                    val running = vm.state.transfers.count { it.status == TransferStatus.RUNNING && !it.quiet }
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
                    onClick = { expanded = false; filterOpen = true }
                )
            }
            if (!hideNavigation) {
                DropdownMenuItem(
                    text = { Text("设置", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(18.dp)) },
                    onClick = { expanded = false; vm.openSettings() }
                )
            }
        }
    }
    if (filterOpen) NameFilterDialog(vm) { filterOpen = false }
}

/** 按名称筛选当前所在位置：只有输入框，不做类型 / 大小 / 日期条件（见需求）。 */
@Composable
private fun NameFilterDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(vm.state.nameFilter) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按名称筛选", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    placeholder = { Text("输入名称关键字", fontSize = 13.sp) },
                    // 跟其它对话框里的输入框同一套配色：不聚焦时也留一道看得见的描边。
                    // 只靠 M3 默认的 outline，在浅色底上几乎等于没有框，用户找不到输入区。
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "只筛选当前所在位置的文件、文件夹或连接名，不区分大小写；进入下一级目录后自动清除。",
                    fontSize = 11.sp,
                    color = MutedText,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.setNameFilter(text); onDismiss() }) { Text("筛选") }
        },
        dismissButton = {
            TextButton(onClick = { vm.setNameFilter(""); onDismiss() }) { Text("清除") }
        }
    )
}

/** 筛选生效时的常驻条：让用户知道「东西没丢，是被筛掉了」，并给一键清除。 */
@Composable
fun ActiveFilterBar(vm: AppViewModel) {
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
            "筛选「${vm.state.nameFilter}」",
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
                .clickable { vm.clearNameFilter() }
                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
        )
    }
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
private fun EmptyModules(text: String, hint: String = "可在设置的「首页显示」中重新开启") {
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
private fun VolumeDialog(state: MyFileBoxState, vm: AppViewModel, onDismiss: () -> Unit) {
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

