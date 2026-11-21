package com.yaoahos.myfilebox.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.android.data.LocalRepository
import com.yaoahos.myfilebox.core.displayParent
import com.yaoahos.myfilebox.core.FileKinds
import com.yaoahos.myfilebox.core.RecycleItem
import kotlinx.coroutines.delay

/**
 * 回收站（清单 6）：本地 / 群晖统一风格。
 * - 按钮全图标；支持长按多选 + 全选；
 * - 选择态提供 还原 / 彻底删除 / 查看详情；
 * - 不再提供一键恢复。
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RecycleScreen(state: MyFileBoxState, vm: AppViewModel) {
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingDays by remember { mutableStateOf<Int?>(null) }
    var detailItem by remember { mutableStateOf<RecycleItem?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    var sortKey by remember { mutableStateOf("time") }
    var sortDir by remember { mutableStateOf("desc") }
    // 清单 14（3.2.0）：回收站视图与外部浏览同步（外面宫格→回收站宫格）。
    val grid = vm.viewModeFor(state.recycleContext) == "grid"
    val selecting = state.recycleSelecting
    // 只有群晖回收站里的文件夹能进：`#recycle` 保留原路径，文件夹是真实可浏览的目录；
    // 本地回收站的文件夹是一个独立删除单元，内部没有可列的目录结构。
    val canEnter: (RecycleItem) -> Boolean = { item ->
        state.recycleContext == FileContext.SYNO && item.isFolder
    }
    // 回收站有自己的筛选条件（不复用浏览页那份）：这两个字段只在本页生效。
    val filterOn = state.recycleNameFilter.isNotBlank() || state.recycleExtFilter.isNotBlank()
    val visibleItems = remember(state.recycleItems, state.recycleNameFilter, state.recycleExtFilter) {
        vm.recycleVisibleItems()
    }
    val sortedItems = remember(visibleItems, sortKey, sortDir) {
        val sorted = when (sortKey) {
            "name" -> visibleItems.sortedBy { it.name.lowercase() }
            "size" -> visibleItems.sortedBy { it.sizeBytes }
            else -> visibleItems.sortedBy { it.deletedAt }
        }
        if (sortDir == "desc") sorted.reversed() else sorted
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        // 标题、自动清理筛选和列表放进同一个滚动容器：
        // 整页（包括标题区）都能下拉刷新，不再只有下半屏列表能拉。
        AppPullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refreshRecycle() },
            modifier = Modifier.fillMaxSize()
        ) {
            val header: @Composable () -> Unit = {
                // 稳定状态栏高度：避免启动外部应用时 insets 抖动导致整页跳一下。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = rememberStableStatusBarTop())
                        .padding(horizontal = 14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircleBackButton(
                            onClick = {
                                when {
                                    selecting -> vm.clearRecycleSelection()
                                    // 钻在 #recycle 子目录里时，返回先回上一层，而不是直接关掉回收站。
                                    state.recycleDir.isNotBlank() -> vm.backRecycleFolder()
                                    else -> vm.closeRecycle()
                                }
                            },
                            label = if (selecting) "退出选择" else "返回"
                        )
                        // 与传输 / 设置二级页一致：标题离箭头 12dp，别贴着按钮。
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                if (selecting) "已选择 ${state.recycleSelected.size} 项" else "回收",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (selecting) {
                            SelectionSelectAllButton(
                                // 全选只认筛出来的条目，否则筛选在回收站里等于没用。
                                allSelected = visibleItems.isNotEmpty() &&
                                    state.recycleSelected.containsAll(visibleItems.map { it.id }),
                                onClick = vm::toggleRecycleSelectAll
                            )
                        } else {
                            SortMenuButton(
                                sortKey = sortKey,
                                sortDir = sortDir,
                                onPick = { k, d -> sortKey = k; sortDir = d },
                                showSize = true
                            )
                            IconButton(onClick = { vm.toggleViewFor(state.recycleContext) }) {
                                Icon(
                                    if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                                    if (grid) "切换为列表" else "切换为宫格",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                            // 筛选 / 传输 / 设置 / 首页 / 清空统一收进「更多」：
                            // 标题行只留排序 + 视图 + 更多，副标题「删除的本地文件会先放到这里」
                            // 才不会被四个图标挤到省略号。
                            HeaderOverflow(
                                vm,
                                favoritesContext = state.recycleContext,
                                hideNavigation = true,
                                showFilter = true,
                                onFilter = { filterOpen = true },
                                filterPair = state.recycleNameFilter to state.recycleExtFilter,
                                // 清空是「整个回收站」的破坏性操作：群晖钻进 #recycle 子目录后，
                                // 这一屏只是某一层，清空它既删不干净、又会误清删除单元索引。
                                // 只在汇总层（dir 为空）提供，子目录里靠长按逐项删。
                                clearAction = if (state.recycleItems.isNotEmpty() &&
                                    !(state.recycleContext == FileContext.SYNO && state.recycleDir.isNotBlank())
                                ) ({ confirmEmpty = true }) else null
                            )
                        }
                    }
                    // 说明独占整行，不再和右上角三个图标抢宽度。
                    // 原来它排在标题那一行里，窄屏上被挤成「…· 长按」+「可多选」两行，
                    // 断在词中间，看着很突兀。整行后 18 个字一行放得下。
                    Text(
                        // 一句话说清这一屏放的是什么：不再附带「长按可多选」——
                        // 长按本来就是列表的通用手势，写在说明里只是噪声。
                        when {
                            selecting -> SELECTING_HINT
                            // 钻进子目录后，这一屏放的是「原目录里的东西」，
                            // 直接把对应的原路径写在标题下，归属一眼可见。
                            state.recycleDir.isNotBlank() ->
                                "原位置 · " + originalPathOfTrash(state.recycleDir).trimStart('/')
                            state.recycleContext == FileContext.SYNO -> "群晖删除的文件"
                            else -> "本地删除的文件"
                        },
                        // 与传输页副标题同款：单独一行、左起 20dp，不再缩进到标题下方。
                        fontSize = 13.sp,
                        color = MutedText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 2.dp)
                    )
                    if (filterOn) {
                        ActiveFilterBar(
                            summary = com.yaoahos.myfilebox.android.data.filterSummary(
                                state.recycleNameFilter, state.recycleExtFilter
                            ),
                            onClear = vm::clearRecycleFilters
                        )
                    }
                    if (state.recycleContext != FileContext.SYNO && !selecting) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("自动清理", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf(0, 7, 30, 90).forEach { days ->
                                val active = state.prefs.recycleDays == days
                                FilterChip(
                                    selected = active,
                                    onClick = {
                                        if (days == 0) vm.setRecycleDays(0) else pendingDays = days
                                    },
                                    label = { Text(if (days == 0) "永不" else "${days}天", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }
                }
            }
            // 标题区固定（3.9.16）：回收站文件多、已经滑到底部时也能直接返回或改排序。
            Column(Modifier.fillMaxSize()) {
            header()
            Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                sortedItems.isEmpty() && state.recycleLoading -> {
                    // 清单 8（3.2.0）：群晖回收站加载时不再先闪「空」状态。
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                Text(
                                    "正在加载回收站…",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                            }
                        }
                }
                sortedItems.isEmpty() -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Column(
                                Modifier
                                    .fillParentMaxHeight(0.7f)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    if (filterOn) Icons.Outlined.FilterList else Icons.Outlined.DeleteOutline, null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    // 有东西但被筛没了，不能写「回收站是空的」——那会让用户以为文件被彻底删了。
                                    // 钻在子目录里时同理：空的是这一层，不是整个回收站。
                                    when {
                                        filterOn -> "没有匹配的项目"
                                        state.recycleDir.isNotBlank() -> "这个文件夹是空的"
                                        else -> "回收站是空的"
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                                Text(
                                    when {
                                        filterOn -> "筛后缀时文件夹也会隐藏"
                                        state.recycleDir.isNotBlank() -> "点左上角返回上一层"
                                        else -> "删除的文件会先放到这里"
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (filterOn) {
                                    TextButton(onClick = vm::clearRecycleFilters) {
                                        Text("清除筛选", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                grid -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(bottom = if (selecting) 96.dp else 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(sortedItems, key = { it.id }) { item ->
                            RecycleGridCard(
                                item = item,
                                selected = item.id in state.recycleSelected,
                                selecting = selecting,
                                onClick = {
                                    when {
                                        selecting -> vm.toggleRecycleSelected(item)
                                        canEnter(item) -> vm.openRecycleFolder(item)
                                        else -> detailItem = item
                                    }
                                },
                                onLongClick = { vm.toggleRecycleSelected(item) }
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = if (selecting) 96.dp else 24.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(sortedItems, key = { it.id }) { item ->
                            Column(Modifier.animateItem()) {
                                RecycleRow(
                                    item = item,
                                    localRoot = state.localRoot,
                                    selected = item.id in state.recycleSelected,
                                    selecting = selecting,
                                    onClick = {
                                        when {
                                            selecting -> vm.toggleRecycleSelected(item)
                                            canEnter(item) -> vm.openRecycleFolder(item)
                                            else -> detailItem = item
                                        }
                                    },
                                    onLongClick = { vm.toggleRecycleSelected(item) },
                                    showRestore = state.recycleContext != FileContext.SYNO,
                                    showFolderHint = state.recycleDir.isBlank(),
                                    onRestore = { vm.restoreRecycle(item) },
                                    onDelete = {
                                        detailItem = null
                                        vm.deleteRecycleItems(listOf(item))
                                    }
                                )
                                HorizontalDivider(
                                    Modifier.padding(start = 52.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = .5f)
                                )
                            }
                        }
                    }
                }
            }
            }
            }
        }
    }

    if (selecting) {
        val chosen = vm.recycleSelectedItems()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SelectionActionBar(
                actions = listOf(
                    SelectionAction(Icons.Outlined.Restore, "还原") {
                        vm.restoreRecycleMany(chosen)
                    },
                    SelectionAction(Icons.Outlined.Delete, "彻底删除", danger = true) {
                        confirmDelete = true
                    },
                    SelectionAction(Icons.Outlined.Info, "查看详情", enabled = chosen.size == 1) {
                        detailItem = chosen.singleOrNull()
                    }
                )
            )
        }
    }

    if (filterOpen) {
        FilterDialog(
            initialName = state.recycleNameFilter,
            initialExt = state.recycleExtFilter,
            note = "两个条件同时生效，只筛回收站这一页；不区分大小写。只填后缀时只列文件、" +
                "文件夹会一并隐藏，想看文件夹先点「清除」。退出回收站后自动清除。",
            onDismiss = { filterOpen = false },
            onApply = { n, e -> vm.setRecycleFilters(n, e) },
            onClear = { vm.clearRecycleFilters() }
        )
    }

    pendingDays?.let { days ->
        AlertDialog(
            onDismissRequest = { pendingDays = null },
            title = { Text("自动清理") },
            text = { Text("将自动彻底删除已在回收站中停留超过 $days 天的文件。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.setRecycleDays(days)
                    pendingDays = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pendingDays = null }) { Text("取消") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("彻底删除") },
            text = { Text("选中的 ${state.recycleSelected.size} 项将被彻底删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteRecycleItems(vm.recycleSelectedItems())
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空回收站") },
            text = { Text("回收站中的 ${state.recycleItems.size} 项将被彻底删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    vm.emptyRecycle()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("取消") } }
        )
    }

    detailItem?.let { item ->
        RecycleDetailDialog(
            item = item,
            // 原路径整行点一下即复制；「已复制」在弹窗内部显示（顶部提示会被弹窗挡住）。
            onCopyPath = { vm.copyToClipboard(item.originalPath, "路径") },
            onRestore = {
                detailItem = null
                vm.restoreRecycle(item)
            },
            onDismiss = { detailItem = null }
        )
    }
}

/** 回收条目列表行：图标 + 名称/日期/大小 + 还原/删除图标按钮。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleRow(
    item: RecycleItem,
    localRoot: String,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    /**
     * 折叠后的副标题是否带「父目录 · 时间」。
     *
     * 顶层要带：同名文件散在不同原目录里，只能靠这一行区分归属。
     * 钻进 `#recycle` 子目录后就不用带了——这一层是哪个目录，标题下面已经写明，
     * 每行再重复一遍当前目录名只是噪声。
     */
    showFolderHint: Boolean = true,
    /**
     * 行内是否给「还原」按钮。
     *
     * 群晖回收站一律不给：那里的内容是各共享 `#recycle` 并列出来的，
     * 行内再塞按钮既挤又容易误触，统一走「长按 → 底部操作栏」。
     */
    showRestore: Boolean = true
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else Color.Transparent)
            .then(
                if (selected) Modifier.border(
                    width = 2.dp,
                    color = primary,
                    shape = RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (item.isFolder) {
                FolderGlyph(32)
            } else {
                IconTile(FileKinds.of(item.name), 32)
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, "已选择",
                    tint = primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                item.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // 同名文件只看名字分不清，补一行原位置（回收站里最常被问的就是「这是从哪删的」）。
                if (showFolderHint) {
                    displayParent(item.originalPath, localRoot) + " · " + LocalRepository.formatStamp(item.deletedAt)
                } else {
                    LocalRepository.formatStamp(item.deletedAt)
                },
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (item.isFolder) {
                    if (item.childCount > 0) "${item.childCount} 项" else "文件夹"
                } else formatBytes(item.sizeBytes),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!selecting && showRestore) {
            // 本地回收站保留行内「还原」（条目就是删除单元，一键还原最省事）；
            // 群晖回收站不给——见 [showRestore]。
            IconButton(onClick = onRestore, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.Restore, "还原", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 回收条目宫格卡。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleGridCard(
    item: RecycleItem,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else Color.Transparent)
            .then(
                if (selected) Modifier.border(2.dp, primary, shape) else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            if (item.isFolder) {
                FolderGlyph(36)
            } else {
                IconTile(FileKinds.of(item.name), 36)
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, "已选择", tint = primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                )
            }
        }
        Text(
            item.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp)
        )
        Text(
            LocalRepository.formatStamp(item.deletedAt),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (item.isFolder) {
                // 清单 14a：本地回收站文件夹显示数量，群晖不显示。
                if (item.childCount > 0) "${item.childCount} 项" else "文件夹"
            } else formatBytes(item.sizeBytes),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 回收条目详情（清单 6：查看详情）：信息 + 还原按钮。 */
@Composable
private fun RecycleDetailDialog(
    item: RecycleItem,
    onCopyPath: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    // 复制反馈留在弹窗内：AlertDialog 是独立窗口，页面顶部提示条会在弹窗下方看不见。
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("回收详情", fontWeight = FontWeight.Bold)
                if (copied) {
                    Text(
                        "已复制",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailLine("名称", item.name)
                DetailLine("类型", if (item.isFolder) "文件夹" else "文件")
                DetailLine("大小", if (item.isFolder) "—" else formatBytes(item.sizeBytes))
                DetailLine("删除时间", LocalRepository.formatFull(item.deletedAt))
                DetailLine("原位置", item.originalPath, onClick = {
                    onCopyPath()
                    copied = true
                })
            }
        },
        confirmButton = {
            TextButton(onClick = onRestore) {
                Icon(Icons.Outlined.Restore, null, modifier = Modifier.size(16.dp))
                Text("还原", modifier = Modifier.padding(start = 4.dp))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun DetailLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick) else Modifier)
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(value, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatBytes(bytes: Long): String =
    com.yaoahos.myfilebox.core.FileSizeFormat.format(bytes)
