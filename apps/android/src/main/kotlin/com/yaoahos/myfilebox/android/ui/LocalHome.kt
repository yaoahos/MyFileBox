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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LocalHome(state: MyFileBoxState, vm: AppViewModel) {
    val modules = state.prefs.moduleOrder
        .filter { it.startsWith("local:") && it != "local:storage" && vm.moduleVisible(it) }
    val rootPath = state.localRoot.trimEnd('/')
    // 首页派生数据（根目录文件 / 排序 / 筛选 / 分类分组 / 收藏）全部从 ViewModel 的
    // 跨页面缓存取：切底栏时页面会重建，页面内 remember 会失效，数万项重算就是那一顿。
    val derived = vm.homeDerived()
    val rootFiles = derived.rootFiles
    val sortedLocalFolders = derived.folders
    val sortedRootFiles = derived.rootItems
    // 筛选把所有东西都筛掉了：界面要讲清楚「是筛没了」而不是「什么都没有」。
    val filterOn = state.nameFilter.isNotBlank() || state.extensionFilter.isNotBlank()
    val filteringHome = filterOn && sortedLocalFolders.isEmpty() && sortedRootFiles.isEmpty()
    // 网格列数在这里读一次，下面的 LazyColumn 内容 lambda 里好按行切块。
    val gridColumns = if (LocalConfiguration.current.screenWidthDp >= 600) 6 else 4
    Box(Modifier.fillMaxSize()) {
    AppPullToRefreshBox(
        isRefreshing = state.refreshing,
        // 只重扫本地存储：群晖共享与网络连接探活跟这一屏无关，不该一起等。
        onRefresh = vm::refreshLocalTab,
        modifier = Modifier.fillMaxSize()
    ) {
        // 顶部用稳定的状态栏高度：点文件启动外部应用时 insets 会抖小，整页会跳一下。
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = rememberStableStatusBarTop())
                .pullRefreshArea()
        ) {
        // 顶栏固定（3.9.16）：文件多、已经滑到底部时，不必先滑回顶部才能点搜索 / 更多。
        PageTitle(
            title = if (state.homeSelecting) "已选择 ${state.homeSelected.size} 项" else "本地文件",
            subtitle = if (state.homeSelecting) SELECTING_HINT else null,
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
                            // 长按取消：与浏览页同一约定，剪贴板不再一直占着顶栏。
                            val pasteInteraction = remember { MutableInteractionSource() }
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .pressScale(pasteInteraction)
                                    .combinedClickable(
                                        interactionSource = pasteInteraction,
                                        indication = null,
                                        onClick = vm::pasteToHome,
                                        onLongClick = vm::cancelClipboard
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.ContentPaste, "粘贴到内部存储（长按取消复制 / 移动）",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
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
            contentPadding = PaddingValues(bottom = BottomBarClearance)
        ) {
            if (!state.localAccess) {
                item { LocalAccessBanner(vm) }
            }
            if (state.nameFilter.isNotBlank() || state.extensionFilter.isNotBlank()) {
                item {
                    ActiveFilterBar(
                        vm,
                        matched = sortedLocalFolders.size + sortedRootFiles.size,
                        total = state.localFolders.size + rootFiles.size
                    )
                }
            }
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
                        // 筛后缀后文件夹会被隐藏，根目录可能一个都不剩。
                        // 这里必须给出「没有匹配」而不是一块空白——空白会被当成加载失败。
                        if (filteringHome) {
                            item { FilterEmptyHint(vm) }
                        } else if (vm.viewMode(TabKey.LOCAL) == "grid") {
                            // 根目录常常一两百个条目。整块塞进一个 item 会让首帧把
                            // 全部卡片组合一遍（实测单帧 1.2 秒）；按行拆成独立 item，
                            // 交给 LazyColumn 只组合可见的几行。
                            val cells: List<Any> = sortedLocalFolders + sortedRootFiles
                            cells.chunked(gridColumns).forEachIndexed { index, row ->
                                item(key = "home-grid-$index") {
                                    FolderGridRow(
                                        cells = row,
                                        columns = gridColumns,
                                        onClick = { vm.openLocalFolder(it.name) },
                                        onFileClick = vm::requestOpen,
                                        selectedIds = state.homeSelected,
                                        selecting = state.homeSelecting,
                                        onToggle = vm::toggleHomeSelected,
                                        onLongClick = vm::toggleHomeSelected,
                                        // 首页不再挂每项的三点：长按即多选，操作都收在底栏。
                                        showMore = false
                                    )
                                }
                            }
                        } else {
                            item {
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
internal fun HomeSelectionBar(state: MyFileBoxState, vm: AppViewModel, modifier: Modifier = Modifier) {
    var showMore by remember { mutableStateOf(false) }
    var showCompress by remember { mutableStateOf(false) }
    var showExtract by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var compressDefaultName by remember { mutableStateOf("压缩包") }
    // 与浏览页完全同一套动作：复制 / 移动 / 删除 / 更多。
    // 取消全选后仍停在这个模式里，没有选中项时整栏置灰、不可点。
    val hasSelection = state.homeSelected.isNotEmpty()
    SelectionActionBar(
        actions = listOf(
            SelectionAction(Icons.Outlined.ContentCopy, "复制", enabled = hasSelection) { vm.stageHomeClipboard(false) },
            SelectionAction(Icons.Outlined.DriveFileMove, "移动", enabled = hasSelection) { vm.stageHomeClipboard(true) },
            SelectionAction(Icons.Outlined.Delete, "删除", enabled = hasSelection, danger = true) { vm.deleteHomeSelection() },
            SelectionAction(Icons.Outlined.MoreHoriz, "更多", enabled = hasSelection) { showMore = true }
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
            onConfirm = { name, format, password ->
                showCompress = false
                vm.compressHomeSelection(name, format, password)
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

@Composable
internal fun FolderSheetAction(
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
        Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
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
internal fun SectionViewToggle(state: MyFileBoxState, vm: AppViewModel, key: String) {
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
internal fun HomeSortButton(vm: AppViewModel, tabKey: String = "local") {
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
    // 展开状态放在 ViewModel：切到别的页面再回来保持样式，冷启动才重置。
    val expanded = state.categoryExpanded
    // 分类排序（清单 4）：按设置里拖出来的顺序展示，默认原顺序。
    val ordered = remember(state.prefs.categoryOrder) {
        val order = state.prefs.categoryOrder
        if (order.isEmpty()) categories
        else categories.sortedBy { order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }
    val shown = if (expanded) ordered else ordered.take(4)
    // 分类分组与收藏同样走 ViewModel 的跨页面缓存：切底栏不再对全盘文件重跑 groupBy。
    val buckets = vm.homeDerived()
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
                    val recentItems = state.recentFiles
                    val items = when {
                        isFavorite -> buckets.favorites
                        isRecent -> recentItems
                        item.kind == FileKind.IMAGE -> state.photos
                        item.kind == FileKind.UNKNOWN -> state.localFiles
                        else -> buckets.byKind[item.kind].orEmpty()
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
                onClick = vm::toggleCategoryExpanded,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (expanded) "收起分类" else "展开全部分类", fontSize = 12.sp)
            }
        }
    }
}

