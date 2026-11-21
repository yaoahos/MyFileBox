package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.MODULE_LABEL_LOCAL
import com.yaoahos.myfilebox.core.displayLeaf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.BackHandler
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 目录选择里的 produceState 首帧就是 `value = null` 后返回，lint 判成「从不赋值」。
@Suppress("ProduceStateDoesNotAssignValue")
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(state: MyFileBoxState, vm: AppViewModel) {
    val search = state.search ?: return
    var folderDialog by remember { mutableStateOf(false) }
    var extDialog by remember { mutableStateOf(false) }
    var extText by remember(search.ext) { mutableStateOf(search.ext) }
    // 位置选择器：从第一层开始逐级进入，直到任意深层文件夹。
    var pickerCurrent by remember { mutableStateOf<Destination?>(null) }
    var pickerStack by remember { mutableStateOf<List<Destination>>(emptyList()) }
    // 统一文件树的「先选中、后确认」交互：圆圈只标记，底部「选择」才写入。
    var pendingFolder by remember { mutableStateOf("") }
    var pendingLabel by remember { mutableStateOf("") }

    // Keep typing at 60fps: wait for a short pause before filtering the pool.
    var debouncedQuery by remember { mutableStateOf(search.query) }
    LaunchedEffect(search.query) {
        kotlinx.coroutines.delay(160)
        debouncedQuery = search.query
    }
    val effectiveSearch = search.copy(query = debouncedQuery)
    val results = remember(
        debouncedQuery,
        search.folder,
        search.ext,
        search.ctx,
        state.localFiles,
        state.shares,
        state.netFiles,
        state.connections
    ) {
        vm.searchResults(effectiveSearch)
    }
    val searching = search.query != debouncedQuery
    val folderLabel = search.folderLabel.ifBlank { "所有位置" }
    // 搜索结果排序：默认按修改时间倒序（最近动过的先看到），可切名称 / 大小。
    var sortKey by remember { mutableStateOf("time") }
    var sortDir by remember { mutableStateOf("desc") }
    val ordered = remember(results, sortKey, sortDir) { vm.sortFiles(results, sortKey, sortDir) }
    // 多选：结果不在 browser.items 里，勾选单独存在 state.searchSelected（键 = 路径 + 名称）。
    val selecting = state.searchSelecting
    val allSelected = ordered.isNotEmpty() &&
        state.searchSelected.containsAll(ordered.map { vm.searchKeyOf(it) })
    var showMore by remember { mutableStateOf(false) }
    BackHandler(enabled = selecting) { vm.clearSearchSelection() }

    // 与其它文件列表页统一：整页下拉刷新（重算搜索结果）。
    AppPullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize()
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 稳定的状态栏高度：点文件启动外部应用时 insets 会抖小，整页会跳一下。
            .padding(top = rememberStableStatusBarTop())
            .imePadding()
            .pullRefreshArea()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleBackButton(
                onClick = { if (selecting) vm.clearSearchSelection() else vm.closeSearch() },
                label = if (selecting) "退出选择" else "返回",
                icon = if (selecting) Icons.Outlined.Close else Icons.Outlined.ArrowBack
            )
            if (selecting) {
                // 选择态：顶栏换成标题 + 全选，搜索框先让位（继续输入没有意义）。
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(
                        "已选择 ${state.searchSelected.size} 项",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        SELECTING_HINT,
                        fontSize = 12.sp,
                        color = MutedText,
                        maxLines = 1
                    )
                }
                SelectionSelectAllButton(
                    allSelected = allSelected,
                    onClick = {
                        vm.setSearchSelection(ordered.map { vm.searchKeyOf(it) }.toSet())
                    }
                )
            } else {
                OutlinedTextField(
                    value = search.query,
                    onValueChange = vm::setSearchQuery,
                    modifier = Modifier.weight(1f).padding(start = 8.dp).imePadding(),
                    placeholder = {
                        Text(
                            when (search.ctx) {
                                FileContext.LOCAL -> "搜索本地文件"
                                FileContext.SYNO -> "搜索群晖文件"
                                FileContext.NET -> "搜索网络文件"
                            },
                            fontSize = 12.sp
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = dialogFieldColors(),
                    leadingIcon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(17.dp)) },
                    trailingIcon = {
                        if (search.query.isNotEmpty()) {
                            IconButton(onClick = { vm.setSearchQuery("") }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Outlined.Close, "清除", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                )
                TextButton(onClick = vm::closeSearch) { Text("取消", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                HeaderOverflow(vm, search.ctx)
            }
        }

        // 选择态不显示位置 / 后缀两个筛选：改它们会清空勾选，用户会莫名其妙丢选择。
        if (!selecting) Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            FilterChip(
                selected = search.folder != "all",
                onClick = {
                    pickerCurrent = null
                    pickerStack = emptyList()
                    pendingFolder = search.folder
                    pendingLabel = folderLabel
                    folderDialog = true
                },
                label = { Text(folderLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(14.dp)) }
            )
            FilterChip(
                selected = search.ext.isNotBlank(),
                onClick = { extText = search.ext; extDialog = true },
                label = {
                    val extChips = search.ext.split(',', '，', ' ')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString(" / ") { "." + it.removePrefix(".") }
                    Text(
                        if (search.ext.isBlank()) "后缀" else "后缀 $extChips",
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
            Spacer(Modifier.weight(1f))
            // 排序放在这一行：顶栏已经有搜索框 + 取消 + 更多，再塞一个图标会把输入框挤没。
            SortMenuButton(
                sortKey = sortKey,
                sortDir = sortDir,
                onPick = { k, d ->
                    // 换字段时用该字段自然的初始方向：名称从小到大、时间 / 大小从新（大）到旧（小）。
                    // 直接沿用上一个字段的方向，会出现「按名称」却是从 Z 到 A 的反直觉结果。
                    if (k == sortKey) {
                        sortDir = d
                    } else {
                        sortKey = k
                        sortDir = if (k == "name") "asc" else "desc"
                    }
                },
                showSize = true
            )
        }

        if (state.searchIndexing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                // 一次性建立索引，完成后一次出全部结果（清单 3）：
                // 不再写「结果会陆续出现」，避免看着像网络卡、一直在搜。
                Text(
                    "正在建立索引…",
                    fontSize = 12.sp,
                    color = MutedText,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        } else {
            Text(
                (if (searching) "搜索中…" else "找到 ${results.size} 个项目") +
                    " · 位置「$folderLabel」" + if (search.ext.isNotBlank()) " · 后缀「${search.ext}」" else "",
                fontSize = 12.sp,
                color = MutedText,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)
            )
        }

        if (results.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
                Text(
                    when {
                        state.searchIndexing -> "正在建立索引…"
                        search.query.isBlank() -> "输入关键词开始搜索"
                        else -> "没有找到匹配的文件或文件夹"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text("试试更换关键词、文件夹或后缀", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 4.dp))
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp,
                    // 选择态底栏是浮层，列表底部要留出高度，否则最后一行被压住。
                    bottom = if (selecting) 96.dp else 24.dp
                )
            ) {
                itemsIndexed(
                    ordered,
                    key = { _, file -> vm.searchKeyOf(file) }
                ) { index, file ->
                    val shape = when {
                        ordered.size == 1 -> RoundedCornerShape(20.dp)
                        index == 0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                        index == ordered.lastIndex -> RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                        else -> RectangleShape
                    }
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, shape)
                    ) {
                        FileRow(
                            file,
                            selected = vm.searchKeyOf(file) in state.searchSelected,
                            selecting = selecting,
                            onLongClick = { vm.toggleSearchSelected(file) },
                            subtitle = listOf(
                                friendlyFolder(file.folder, state.localRoot),
                                file.timeLabel,
                                file.sizeLabel
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            onMore = null
                        ) {
                            // 选择态下点一行 = 继续勾选；否则才是打开。
                            if (selecting) vm.toggleSearchSelected(file) else vm.openSearchResult(file)
                        }
                    }
                }
            }
        }
    }
    }

    if (selecting) {
        val chosen = ordered.filter { vm.searchKeyOf(it) in state.searchSelected }
        val hasChosen = chosen.isNotEmpty()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SelectionActionBar(
                actions = buildList {
                    // 搜索结果的「复制 / 移动」走暂存剪贴板：用户接着退出去选目标文件夹粘贴。
                    add(SelectionAction(Icons.Outlined.ContentCopy, "复制", enabled = hasChosen) {
                        vm.stageClipboard(TransferMode.COPY, chosen, search.ctx)
                    })
                    add(SelectionAction(Icons.Outlined.DriveFileMove, "移动", enabled = hasChosen) {
                        vm.stageClipboard(TransferMode.MOVE, chosen, search.ctx)
                    })
                    add(SelectionAction(Icons.Outlined.Delete, "删除", enabled = hasChosen, danger = true) {
                        vm.deleteSearchEntries(chosen)
                    })
                    add(SelectionAction(Icons.Outlined.MoreHoriz, "更多", enabled = hasChosen) { showMore = true })
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showMore) {
        val chosen = ordered.filter { vm.searchKeyOf(it) in state.searchSelected }
        val actions = buildList {
            when (search.ctx) {
                FileContext.LOCAL -> {
                    add(SelectionAction(Icons.Outlined.Upload, "上传", enabled = vm.hasRemoteTarget()) {
                        showMore = false
                        vm.startTransfer(TransferMode.UPLOAD, chosen, FileContext.LOCAL)
                    })
                    add(SelectionAction(Icons.Outlined.Share, "分享", enabled = chosen.any { !it.isFolder }) {
                        showMore = false
                        vm.shareFiles(chosen)
                    })
                    add(SelectionAction(Icons.Outlined.StarBorder, "收藏") {
                        showMore = false
                        vm.favoriteEntries(chosen, fromSearch = true)
                    })
                }
                FileContext.SYNO -> {
                    add(SelectionAction(Icons.Outlined.Download, "下载") {
                        showMore = false
                        vm.startTransfer(TransferMode.DOWNLOAD, chosen, FileContext.SYNO)
                    })
                    add(SelectionAction(Icons.Outlined.StarBorder, "收藏") {
                        showMore = false
                        vm.favoriteEntries(chosen, fromSearch = true)
                    })
                }
                // 网络位置的搜索结果横跨多个连接，下载要先知道具体连哪个，
                // 这里不提供（否则只会得到一句「下载失败」）；删除走 deleteSearchEntries 自己判连接。
                FileContext.NET -> Unit
            }
            add(SelectionAction(Icons.Outlined.Info, "查看详情", enabled = chosen.size == 1) {
                showMore = false
                chosen.singleOrNull()?.let(vm::showDetail)
            })
        }
        SelectionMoreSheet(actions = actions, onDismiss = { showMore = false })
    }

    if (folderDialog) {
        val current = pickerCurrent
        val children by produceState<List<Destination>?>(initialValue = null, current) {
            value = null
            value = current?.let { vm.childDirectories(it) }
        }
        val atTop = pickerStack.isEmpty()
        AlertDialog(
            onDismissRequest = { folderDialog = false },
            title = { Text("选择搜索位置", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    // 面包屑（清单 12/19）：点哪层回哪层；无「上一级」。
                    val crumbs = buildList {
                        // 第一层是模块名，不是「内部存储」：存储根只是本模块的根目录，
                        // 单独占一层会让面包屑和浏览页对不上。
                        add(null to MODULE_LABEL_LOCAL)
                        pickerStack.forEach { add(it to it.label) }
                        current?.let { add(it to it.label) }
                    }
                    PathBreadcrumbs(
                        labels = crumbs.map { it.second },
                        modifier = Modifier.padding(bottom = 6.dp),
                        onCrumbClick = { index ->
                            pickerStack = if (index == 0) emptyList() else pickerStack.take(index - 1)
                            pickerCurrent = crumbs[index].first
                        }
                    )
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        if (current == null) {
                            // 「所有位置」只在第一层出现；本地端不再单列「内部存储」，
                            // 它的语义和「所有位置」重复，用户也分不清选的是哪个。
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { pendingFolder = "all"; pendingLabel = "所有位置" }
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text("所有位置", fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                                RadioButton(
                                    selected = pendingFolder == "all",
                                    onClick = { pendingFolder = "all"; pendingLabel = "所有位置" },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        // 根目录候选要真去列一次 /sdcard：放在组合里会被每次重组（改选中项、
                        // 滚动、主题变化）重复触发，等于反复读盘。按输入记住结果即可。
                        val rows = if (current == null) {
                            remember(search.ctx, state.localRoot, state.shares, state.connections) {
                                vm.searchRootDestinations(search.ctx)
                            }
                        } else (children ?: emptyList())
                        if (children == null && current != null) {
                            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        } else if (rows.isEmpty()) {
                            Text(
                                // 进入某个文件夹后没子文件夹就是空目录（清单 2），
                                // 不能提示「未读取到共享文件夹」，那会被误以为连接失败。
                                when {
                                    current != null -> "这个文件夹里没有子文件夹"
                                    search.ctx == FileContext.SYNO -> "尚未读取到群晖共享文件夹，请先登录 NAS 或下拉刷新后再试。"
                                    search.ctx == FileContext.NET -> "还没有添加网络位置，请先在「网络文件」里添加连接。"
                                    else -> "这个文件夹里没有子文件夹"
                                },
                                fontSize = 12.sp,
                                color = MutedText,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                            )
                        } else {
                            rows.forEach { dest ->
                                val value = vm.searchFolderValueOf(dest, atTop)
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            pickerStack = pickerStack + listOfNotNull(current)
                                            pickerCurrent = dest
                                            // 进入目录时自动勾选该目录，未手动勾选时默认就是当前目录。
                                            pendingFolder = value
                                            pendingLabel = dest.label
                                        }
                                        .padding(vertical = 6.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Folder, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(dest.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(dest.meta, fontSize = 12.sp, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    RadioButton(
                                        selected = pendingFolder == value,
                                        onClick = { pendingFolder = value; pendingLabel = dest.label },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "点文件夹进入下一层，点圆圈选中后点「选择」确认",
                        fontSize = 12.sp,
                        color = MutedText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 没勾选任何行时，默认选面包屑中的当前目录。
                        val pick = pendingFolder.ifBlank { pickerCurrent?.let { vm.searchFolderValueOf(it, pickerStack.isEmpty()) } ?: "all" }
                        val pickLabel = pendingLabel.ifBlank { pickerCurrent?.label ?: "所有位置" }
                        vm.setSearchFolder(pick, pickLabel)
                        folderDialog = false
                    }
                ) { Text("选择") }
            },
            dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("取消") } }
        )
    }

    if (extDialog) {
        FormDialog(
            title = "按后缀筛选",
            onDismiss = { extDialog = false },
            spacing = 6.dp,
            confirmButton = {
                Button(
                    onClick = { vm.setSearchExt(extText); extDialog = false },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("应用后缀") }
            },
            dismissButton = {
                TextButton(onClick = { vm.setSearchExt(""); extDialog = false }) { Text("清除") }
            }
        ) {
            Text("输入文件后缀，例如 jpg, png 或 .pdf。", fontSize = 12.sp, color = MutedText)
            OutlinedTextField(
                value = extText,
                onValueChange = { extText = it },
                label = { Text("文件后缀") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = dialogFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Text("支持逗号或空格分隔多个后缀；留空表示不限。", fontSize = 12.sp, color = MutedText)
        }
    }
}

private fun friendlyFolder(name: String, localRoot: String): String =
    displayLeaf(name, localRoot, "内部存储")
