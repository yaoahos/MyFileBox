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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
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
import com.yaoahos.myfilebox.core.FileKinds
import com.yaoahos.myfilebox.core.RecycleItem

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
    var sortKey by remember { mutableStateOf("time") }
    var sortDir by remember { mutableStateOf("desc") }
    // 清单 14（3.2.0）：回收站视图与外部浏览同步（外面宫格→回收站宫格）。
    val grid = vm.viewModeFor(state.recycleContext) == "grid"
    val selecting = state.recycleSelecting
    val sortedItems = remember(state.recycleItems, sortKey, sortDir) {
        val list = state.recycleItems
        val sorted = when (sortKey) {
            "name" -> list.sortedBy { it.name.lowercase() }
            "size" -> list.sortedBy { it.sizeBytes }
            else -> list.sortedBy { it.deletedAt }
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
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (selecting) vm.clearRecycleSelection() else vm.closeRecycle()
                        }) {
                            Icon(Icons.Outlined.ArrowBack, if (selecting) "退出选择" else "返回")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (selecting) "已选择 ${state.recycleSelected.size} 项" else "回收",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                // 回收按底栏分域（清单 5）：本地回收只管本地，群晖回收看 #recycle。
                                when {
                                    selecting -> "长按可多选 · 点按继续选择"
                                    state.recycleContext == FileContext.SYNO -> "群晖删除的文件 · 长按可多选"
                                    else -> "删除的本地文件会先放到这里"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (selecting) {
                            SelectionSelectAllButton(
                                allSelected = state.recycleSelected.size == state.recycleItems.size &&
                                    state.recycleItems.isNotEmpty(),
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
                            if (state.recycleItems.isNotEmpty()) {
                                IconButton(onClick = { confirmEmpty = true }) {
                                    Icon(
                                        Icons.Outlined.DeleteSweep, "清空回收站",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                            }
                        }
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
                sortedItems.isEmpty() && state.loading -> {
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
                                    Icons.Outlined.DeleteOutline, null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    "回收站是空的",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                                Text(
                                    "删除的文件会先放到这里",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                                    if (selecting) vm.toggleRecycleSelected(item) else detailItem = item
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
                                    selected = item.id in state.recycleSelected,
                                    selecting = selecting,
                                    onClick = {
                                        if (selecting) vm.toggleRecycleSelected(item) else detailItem = item
                                    },
                                    onLongClick = { vm.toggleRecycleSelected(item) },
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
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
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
                (item.originalPath.substringBeforeLast('/', "").ifBlank { "/" }) +
                    " · " + LocalRepository.formatStamp(item.deletedAt),
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
        if (!selecting) {
            // 行内只留「还原」；彻底删除是这一页唯一不可逆的操作，
            // 必须走「长按选择 → 彻底删除 → 二次确认」，不做单点即毁。
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
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("回收详情", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailLine("名称", item.name)
                DetailLine("类型", if (item.isFolder) "文件夹" else "文件")
                DetailLine("大小", if (item.isFolder) "—" else formatBytes(item.sizeBytes))
                DetailLine("删除时间", LocalRepository.formatFull(item.deletedAt))
                DetailLine("原位置", item.originalPath)
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
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(value, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B" else String.format("%.1f %s", value, units[unit])
}
