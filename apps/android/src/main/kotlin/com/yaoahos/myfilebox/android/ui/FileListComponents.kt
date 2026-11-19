package com.yaoahos.myfilebox.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FolderCard
import com.yaoahos.myfilebox.core.TabKey
import com.yaoahos.myfilebox.android.R
import com.yaoahos.myfilebox.android.data.ExtractConflict
import com.yaoahos.myfilebox.android.data.LocalRepository
import kotlinx.coroutines.launch
import com.yaoahos.myfilebox.android.ui.theme.semanticColors

@Composable
fun StorageCard(
    used: String,
    total: String,
    percent: Int,
    chips: List<Pair<Color, String>>,
    onClean: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(primary.copy(alpha = 0.82f), primary, primary.copy(alpha = 0.72f))
                )
            )
            .padding(18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(17.dp))
                Text(
                    "内部存储",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 7.dp).weight(1f)
                )
                Surface(
                    onClick = onClean,
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.18f)
                ) {
                    Text(
                        "清理",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(used, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                Text(
                    " GB  /  $total",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 7.dp, start = 2.dp)
                )
            }
            Spacer(Modifier.height(11.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(percent.coerceAtLeast(1).toFloat()).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
                Box(Modifier.weight(22f).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.55f)))
                Box(Modifier.weight(18f).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.25f)))
            }
            Spacer(Modifier.height(11.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { (dotColor, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(4.dp).clip(CircleShape).background(dotColor))
                        Text(
                            label,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("可用 41.6 GB", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CategoryTile(name: String, count: String, icon: ImageVector, tint: Color, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).pressClickable(onClick).padding(vertical = 2.dp)
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Text(count, fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 1.dp))
    }
}

/**
 * 文件夹图标。旧实现直接画固定的蓝色 PNG（ic_folder_blue），所以「主题与外观」
 * 换成红 / 绿之后，首页和列表里满屏仍是品牌蓝——改代码里的 tint 完全无效，
 * 颜色本来就烧在图片像素里。
 * 现在改矢量绘制：主体跟随主题色，顶部一条白色内页保留原图「文件夹里有纸」的层次。
 */
@Composable
fun FolderGlyph(size: Int = 32) {
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.size(size.dp)) {
        // 整体放大 12%：Material 的 folder 图形在 24dp 画布里只占约 20×17，
        // 直接按 size 摆放会比同尺寸的其它图标小一圈。graphicsLayer 只改绘制、
        // 不改测量尺寸，所以放大后不会挤压右侧文字、也不会撑高列表行。
        Box(Modifier.matchParentSize().graphicsLayer { scaleX = 1.12f; scaleY = 1.12f }) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.matchParentSize()
            )
            // 白色内页：照原 ic_folder_blue.png 量出来的比例 —— 宽 66%、高 4.4%、
            // 紧贴顶盖下沿（y≈0.29）。它本来是一条「盖子与主体的分界线」，
            // 之前做成短而粗的杠（0.58×0.085）才显得突兀。
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = (size * 0.29f).dp)
                    .fillMaxWidth(0.66f)
                    .height((size * 0.044f).dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White)
            )
        }
    }
}

@Composable
fun FolderCard(item: FolderCard, onClick: () -> Unit) {
    Column(
        Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pressClickable(onClick)
            .padding(13.dp)
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            FolderGlyph(27)
        }
        Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(item.meta, fontSize = 12.sp, color = MutedText, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * 列表行之间的分隔线：整行贯通 + 中性灰。
 *
 * 之前是从图标右侧缩进着画的（文件夹 74dp、文件 60dp），两套缩进混排时线头
 * 长短不一；颜色又是 outline（#E2E9F5）再乘 0.55 透明度，落在 #F1F5FC 的
 * 页面底色上对比不到 10 级，看着像断成几截、没画全。
 *
 * 现在统一：列表本身已有 16dp 内容内边距，这里不再额外缩进；颜色取
 * onSurface 的 11%（浅色下是浅灰蓝，深色下自动变浅），线清楚但不抢内容。
 */
@Composable
fun FileListDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f))
}

@Composable
fun FolderList(
    items: List<FolderCard>,
    files: List<FileEntry> = emptyList(),
    onClick: (FolderCard) -> Unit,
    onMore: (FolderCard) -> Unit = {},
    onFileClick: (FileEntry) -> Unit = {},
    onFileMore: (FileEntry) -> Unit = {},
    showCount: Boolean = true,
    selectedIds: Set<String> = emptySet(),
    selecting: Boolean = false,
    onToggle: (String) -> Unit = {},
    onLongClick: (String) -> Unit = {},
    showMore: Boolean = false
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        items.forEach { item ->
            val id = "home-folder:${item.path}"
            FolderListRow(
                name = item.name,
                date = item.date,
                count = item.count,
                fallbackMeta = item.meta,
                showCount = showCount,
                selected = id in selectedIds,
                selecting = selecting,
                onLongClick = { onLongClick(id) },
                onClick = { if (selecting) onToggle(id) else onClick(item) },
                onMore = { onMore(item) },
                showMore = showMore
            )
            FileListDivider()
        }
        files.forEachIndexed { index, file ->
            val id = file.id
            FileRow(
                file = file,
                selected = id in selectedIds,
                selecting = selecting,
                onLongClick = { onLongClick(id) },
                onClick = { if (selecting) onToggle(id) else onFileClick(file) },
                onMore = { onFileMore(file) },
                showMore = showMore
            )
            if (index < files.lastIndex) {
                FileListDivider()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderListRow(
    name: String,
    date: String,
    count: Int,
    fallbackMeta: String = "",
    showCount: Boolean = true,
    selected: Boolean = false,
    selecting: Boolean = false,
    onLongClick: () -> Unit = {},
    onUnfavorite: (() -> Unit)? = null,
    onClick: () -> Unit,
    // 文件夹不再带三点按钮（清单 17）：默认无 onMore。
    onMore: (() -> Unit)? = null,
    showMore: Boolean = false
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else Color.Transparent)
            .then(
                if (selected || selecting) Modifier.border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .5f),
                    shape = shape
                ) else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FolderGlyph(38)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = if (date.isNotBlank() || (showCount && count > 0)) {
                listOf(date, if (showCount) "$count 项" else "").filter { it.isNotBlank() }.joinToString("  |  ")
            } else fallbackMeta
            Text(meta, fontSize = 12.sp, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        when {
            selected -> Icon(
                Icons.Filled.CheckCircle, "已选择",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            onUnfavorite != null -> IconButton(onClick = onUnfavorite, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Star, "取消收藏", tint = semanticColors().star, modifier = Modifier.size(18.dp))
            }
            else -> if (showMore && onMore != null) Box(
                Modifier.size(30.dp).clip(CircleShape).pressClickable(onMore),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.MoreVert, "更多操作", tint = MutedText, modifier = Modifier.size(17.dp)) }
        }
    }
}

@Composable
fun FolderStrip(items: List<FolderCard>, onClick: (FolderCard) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        items.forEach { FolderCard(it) { onClick(it) } }
    }
}

@Composable
fun FolderGrid(
    items: List<FolderCard>,
    files: List<FileEntry> = emptyList(),
    onClick: (FolderCard) -> Unit,
    onMore: (FolderCard) -> Unit = {},
    onFileClick: (FileEntry) -> Unit = {},
    onFileMore: (FileEntry) -> Unit = {},
    showCount: Boolean = true,
    selectedIds: Set<String> = emptySet(),
    selecting: Boolean = false,
    onToggle: (String) -> Unit = {},
    onLongClick: (String) -> Unit = {},
    showMore: Boolean = false
) {
    if (items.isEmpty() && files.isEmpty()) return
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        val columns = if (maxWidth >= 600.dp) 6 else 4
        val cells: List<Any> = items + files
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.chunked(columns).forEach { row ->
                FolderGridRow(
                    cells = row,
                    columns = columns,
                    onClick = onClick,
                    onMore = onMore,
                    onFileClick = onFileClick,
                    onFileMore = onFileMore,
                    showCount = showCount,
                    selectedIds = selectedIds,
                    selecting = selecting,
                    onToggle = onToggle,
                    onLongClick = onLongClick,
                    showMore = showMore
                )
            }
        }
    }
}

/**
 * 网格里的一行。
 *
 * 首页要把这行交给 `LazyColumn` 的 `item` 逐行组合：根目录动辄一两百个条目，
 * 整块塞进一个 `Column` 会在首帧把上百个卡片全部组合一遍（实测切到本地页
 * 单帧 1.2 秒）。按行进入懒加载后，只有可见的几行参与组合。
 */
@Composable
fun FolderGridRow(
    cells: List<Any>,
    columns: Int,
    onClick: (FolderCard) -> Unit,
    onMore: (FolderCard) -> Unit = {},
    onFileClick: (FileEntry) -> Unit = {},
    onFileMore: (FileEntry) -> Unit = {},
    showCount: Boolean = true,
    selectedIds: Set<String> = emptySet(),
    selecting: Boolean = false,
    onToggle: (String) -> Unit = {},
    onLongClick: (String) -> Unit = {},
    showMore: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        cells.forEach { cell ->
            when (cell) {
                is FolderCard -> {
                    val id = "home-folder:${cell.path}"
                    FolderTile(
                        item = cell,
                        modifier = Modifier.weight(1f),
                        showCount = showCount,
                        selected = id in selectedIds,
                        selecting = selecting,
                        onClick = { if (selecting) onToggle(id) else onClick(cell) },
                        onLongClick = { onLongClick(id) },
                        onMore = { onMore(cell) },
                        showMore = showMore
                    )
                }
                is FileEntry -> {
                    val id = cell.id
                    HomeFileTile(
                        file = cell,
                        modifier = Modifier.weight(1f),
                        selected = id in selectedIds,
                        selecting = selecting,
                        onClick = { if (selecting) onToggle(id) else onFileClick(cell) },
                        onLongClick = { onLongClick(id) },
                        onMore = { onFileMore(cell) },
                        showMore = showMore
                    )
                }
            }
        }
        repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeFileTile(
    file: FileEntry,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selecting: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onMore: () -> Unit = {},
    showMore: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else Color.Transparent)
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
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            IconTile(file.kind, 36, file = file)
            if (selected) Icon(
                Icons.Filled.CheckCircle, "已选择", tint = primary,
                modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
            )
        }
        // 清单 2/6：名称字号行高与文件夹统一（11sp / 14sp）。
        Text(
            text = file.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 20.dp)
                .padding(top = 2.dp)
        )
        FileMetaLine(file, Modifier.fillMaxWidth(), fontSize = 9.sp, grid = true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    item: FolderCard,
    modifier: Modifier = Modifier,
    showCount: Boolean = true,
    selected: Boolean = false,
    selecting: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onMore: () -> Unit = {},
    showMore: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else Color.Transparent)
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
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            FolderGlyph(36)
            if (selected) Icon(
                Icons.Filled.CheckCircle, "已选择", tint = primary,
                modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
            )
        }
        Text(
            text = item.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 20.dp)
                .padding(top = 2.dp)
        )
        // 日期一行、数量一行（清单 15）；9sp 免得宫格里截断（清单 16）。
        // 宫格口径：今天给时间、更早给日期（与文件格一致）。
        // 清单 3（3.1.0）：群晖/网络不显示数量，showCount 控制。
        Text(
            LocalRepository.formatGridText(item.date).ifBlank { " " },
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = MutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (showCount) {
            Text(
                "${item.count} 项",
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = MutedText,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserFolderTile(
    name: String,
    date: String,
    count: Int,
    selected: Boolean = false,
    selecting: Boolean = false,
    showCount: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onUnfavorite: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
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
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            FolderGlyph(36)
            when {
                selected -> Icon(
                    Icons.Filled.CheckCircle, "已选择", tint = primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
                )
                onUnfavorite != null -> IconButton(
                    onClick = onUnfavorite,
                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp)
                ) {
                    Icon(Icons.Filled.Star, "取消收藏", tint = Color(0xFFF2A51A), modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            text = name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 20.dp)
                .padding(top = 2.dp)
        )
        // 日期一行、数量一行（清单 15）；字号 9sp 让宫格里不再截断（清单 16）。
        // 宫格口径：今天给时间、更早给日期（与文件格一致）。
        // 清单 3（3.1.0）：群晖/网络不显示数量。
        Text(
            LocalRepository.formatGridText(date).ifBlank { " " },
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = MutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (showCount) {
            Text(
                "$count 项",
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = MutedText,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun SharesList(items: List<com.yaoahos.myfilebox.core.ShareFolder>, onClick: (com.yaoahos.myfilebox.core.ShareFolder) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { share ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .pressClickable { onClick(share) }
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) { FolderGlyph(26) }
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(share.title, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(share.subtitle, fontSize = 12.sp, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MutedText, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun SharesGrid(items: List<com.yaoahos.myfilebox.core.ShareFolder>, onClick: (com.yaoahos.myfilebox.core.ShareFolder) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { share ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(17.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .pressClickable { onClick(share) }
                            .padding(13.dp)
                    ) {
                        Box(
                            Modifier.size(33.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            FolderGlyph(26)
                        }
                        Text(share.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                        Text(share.subtitle, fontSize = 12.sp, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 统一的密码输入框：默认掩码，眼睛按钮在框内右侧（trailingIcon）。
 *
 * 眼睛放进 trailingIcon 而不是 Box 叠放：Material3 里这是唯一能保证图标与输入
 * 文字行垂直对齐的位置——叠放对齐的是整个组件几何中心，带浮动 label 的单行框
 * 文字行中心比组件中心低，图标会偏高。IconButton 自己消费点击，不会当成点输入框
 * 而抢焦点 / 拉起输入法（均已真机实测）。
 * 点眼睛只切掩码、不改行数与字号，输入框高度恒定，多个密码框始终等高。
 */
