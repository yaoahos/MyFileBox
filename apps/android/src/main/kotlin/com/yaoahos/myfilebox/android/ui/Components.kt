package com.yaoahos.myfilebox.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FolderCard
import com.yaoahos.myfilebox.core.TabKey
import com.yaoahos.myfilebox.android.R
import com.yaoahos.myfilebox.android.data.LocalRepository
import kotlinx.coroutines.launch

val SoftBlue = Color(0xFFEAF1FF)
val BlueDeep = Color(0xFF1453D6)
val MutedText = Color(0xFF7B879C)
val CardShape = RoundedCornerShape(20.dp)

@Composable
fun PageTitle(title: String, subtitle: String? = null, leading: @Composable (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp)
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionTitle(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

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
        Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text(item.meta, fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 2.dp))
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
                Icon(Icons.Filled.Star, "取消收藏", tint = Color(0xFFFFB020), modifier = Modifier.size(18.dp))
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
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        val columns = if (maxWidth >= 600.dp) 6 else 4
        val cells: List<Any> = items + files
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    row.forEach { cell ->
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
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
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
                    Text(share.title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.replace("\n", "").replace("\r", "")) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        // 明文状态允许折行：整行宽度只放得下 20 来个字符，稍长的密码会被横向
        // 挤到末尾、开头看不见（用户反馈「查看密码时显示不全」）。折行 + 12.5sp
        // 之后 40 位以内都能完整显示，且不改变遮挡状态的单行外观。
        singleLine = !visible,
        minLines = 1,
        maxLines = if (visible) 3 else 1,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = if (visible) 12.5.sp else 14.sp
        ),
        shape = RoundedCornerShape(12.dp),
        colors = dialogFieldColors(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏密码" else "显示密码",
                    modifier = Modifier.size(19.dp)
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * 第二行统一小字号；清单 15：日期一行，数量/大小一行（文件夹显示 N 项、文件显示大小）。
 *
 * [grid] 区分两种展示口径（同一份数据，两种场景要求不同）：
 * - 列表（默认）：时间给全 `yyyy/MM/dd HH:mm:ss`，左对齐——列表一行放得下，
 *   而且用户要在一行里看全「哪天几点」；
 * - 宫格：当天只给 `HH:mm:ss`、更早只给 `yyyy/MM/dd`，居中——一格只有 4 列宽，
 *   完整时间会截断；同一天靠时间分、跨天靠日期分，信息不丢。
 */
@Composable
fun FileMetaLine(
    file: FileEntry,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 10.sp,
    grid: Boolean = false
) {
    val date = when {
        file.modified > 0 ->
            if (grid) LocalRepository.formatGridStamp(file.modified)
            else LocalRepository.formatStamp(file.modified)
        grid -> LocalRepository.formatGridText(file.timeLabel)
        else -> file.timeLabel
    }
    val info = when {
        file.isFolder -> "${file.childCount} 项"
        file.sizeLabel.isNotBlank() -> file.sizeLabel
        else -> ""
    }
    Column(
        modifier,
        horizontalAlignment = if (grid) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(
            date,
            fontSize = fontSize,
            lineHeight = 12.sp,
            color = MutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (grid) TextAlign.Center else TextAlign.Start
        )
        if (info.isNotBlank()) {
            Text(
                info,
                fontSize = fontSize,
                lineHeight = 12.sp,
                color = MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (grid) TextAlign.Center else TextAlign.Start
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    file: FileEntry,
    selected: Boolean = false,
    selecting: Boolean = false,
    onLongClick: () -> Unit = {},
    onMore: (() -> Unit)? = null,
    onUnfavorite: (() -> Unit)? = null,
    subtitle: String? = null,
    /** 首页不挂每项的三点：长按即进入多选，操作都收在底栏。 */
    showMore: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .then(
                if (selected || selecting) Modifier.border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) primary else MaterialTheme.colorScheme.outline.copy(alpha = .5f),
                    shape = shape
                ) else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(file.kind, 36, file = file)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, fontSize = 10.sp, color = MutedText, maxLines = 1)
            } else {
                FileMetaLine(file)
            }
        }
        if (selected) {
            Icon(Icons.Filled.CheckCircle, "已选择", tint = primary, modifier = Modifier.size(19.dp))
        } else if (onUnfavorite != null) {
            IconButton(onClick = onUnfavorite, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Star,
                    "取消收藏",
                    tint = Color(0xFFFFB020),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
fun IconTile(
    kind: FileKind,
    size: Int = 40,
    file: FileEntry? = null,
    useThumb: Boolean = true
) {
    // 图片 / 视频且持有真实 FileEntry 时显示缩略图；FileThumb 的回退使用
    // useThumb = false，避免再次回到这里造成递归。
    if (useThumb && file != null && (kind == FileKind.IMAGE || kind == FileKind.VIDEO)) {
        FileThumb(
            file = file,
            size = size.dp,
            dark = isSystemInDarkTheme() || MaterialTheme.colorScheme.background.luminance() < 0.5f
        )
        return
    }
    val dark = isSystemInDarkTheme() || MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (lightBg, lightTint) = fileTint(kind)
    // 清单 2：深色模式同样用完整浅彩底板 + 彩色图标，不再降透明度变成灰白。
    val bg = lightBg
    val tint = lightTint
    val shape = RoundedCornerShape((size / 3.4).dp)
    Box(
        Modifier.size(size.dp).clip(shape),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.matchParentSize().background(bg, shape))
        if (kind == FileKind.FOLDER) {
            FolderGlyph((size * 0.68f).toInt())
        } else {
            Icon(fileIcon(kind), null, tint = tint, modifier = Modifier.size((size * 0.48f).dp))
        }
    }
}

/** 与 LocalRepository.extractArchive 支持的格式保持一致。 */
fun isArchiveName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".tar") ||
        lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
}

fun fileIcon(kind: FileKind): ImageVector = when (kind) {    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.VIDEO -> Icons.Outlined.VideoFile
    FileKind.DOCUMENT -> Icons.Outlined.Description
    FileKind.PDF -> Icons.Outlined.PictureAsPdf
    FileKind.SHEET -> Icons.Outlined.TableChart
    FileKind.AUDIO -> Icons.Outlined.Audiotrack
    FileKind.ARCHIVE -> Icons.Outlined.FolderZip
    FileKind.APK -> Icons.Outlined.Android
    FileKind.FOLDER -> Icons.Outlined.Folder
    FileKind.UNKNOWN -> Icons.Outlined.InsertDriveFile
}

/**
 * 文件类型配色：底板是明确的浅彩、图标是饱和彩色。
 * 清单 2：禁止任何灰白 / 类白灰色文件图标——旧的 UNKNOWN 用 0xFF697891 灰蓝，
 * 看起来像没加载出来的占位图；底板也不能近白，否则整体观感偏「损坏」。
 *
 * 文件夹单独处理：它是出现频率最高的条目（首页文件夹区、目录列表、面包屑），
 * 必须跟随主题色，否则用户换了配色后满屏仍是品牌蓝。其余类型保留各自的
 * 专属色——它们承担的是「区分文件类型」的信息，跟着主题走会全部变成同一个色调。
 */
@Composable
fun fileTint(kind: FileKind): Pair<Color, Color> {
    if (kind == FileKind.FOLDER) {
        val primary = MaterialTheme.colorScheme.primary
        // 底板用主题色的极浅叠加，保证「浅彩底 + 饱和图标」的层次不被拉平。
        val plate = primary.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface)
        return plate to primary
    }
    return fileTintByKind(kind)
}

private fun fileTintByKind(kind: FileKind): Pair<Color, Color> = when (kind) {
    FileKind.IMAGE -> Color(0xFFD6E4FF) to Color(0xFF2B6BEF)
    FileKind.VIDEO -> Color(0xFFE4DBFF) to Color(0xFF7146E0)
    FileKind.DOCUMENT -> Color(0xFFD2E9FF) to Color(0xFF1878C4)
    FileKind.PDF -> Color(0xFFFFD9D9) to Color(0xFFD94848)
    FileKind.SHEET -> Color(0xFFD2F1DF) to Color(0xFF0E945E)
    FileKind.AUDIO -> Color(0xFFFFD9E8) to Color(0xFFD63468)
    FileKind.ARCHIVE -> Color(0xFFFFE7BF) to Color(0xFFD98C1F)
    FileKind.APK -> Color(0xFFD2EFDD) to Color(0xFF0E9668)
    // 兜底值：正常路径不会走到这里，[fileTint] 已经把 FOLDER 交给主题色。
    FileKind.FOLDER -> Color(0xFFD6E4FF) to Color(0xFF1F6BFF)
    FileKind.UNKNOWN -> Color(0xFFD9E6FA) to Color(0xFF2F6FE0)
}

/**
 * 把颜色按比例压暗。主题色需要一条「深 → 亮」的渐变时用它派生起笔色，
 * 不能把某个固定深蓝写死——否则换配色后渐变会从深蓝跳到用户选的颜色。
 */
fun Color.shade(amount: Float): Color = Color(
    red = (red * (1f - amount)).coerceIn(0f, 1f),
    green = (green * (1f - amount)).coerceIn(0f, 1f),
    blue = (blue * (1f - amount)).coerceIn(0f, 1f),
    alpha = alpha
)

@Composable
fun FloatingTabBar(
    tabs: List<TabKey>,
    active: TabKey,
    onSelect: (TabKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val labels = mapOf(TabKey.LOCAL to "本地", TabKey.SYNO to "群晖", TabKey.NET to "网络")
    val icons = mapOf(TabKey.LOCAL to Icons.Outlined.Folder, TabKey.SYNO to Icons.Outlined.Cloud, TabKey.NET to Icons.Outlined.Wifi)
    // 选中态跟随主题色：底栏是全局最显眼的一处品牌色，写死品牌蓝会让
    // 「主题与外观」里选的配色在切换标签后立刻破功。
    val selectedAccent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp
    ) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(66.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val selected = tab == active
                    val interaction = remember { MutableInteractionSource() }
                    val foreground by animateColorAsState(
                        targetValue = if (selected) selectedAccent
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        label = "tab-fg"
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = interaction,
                                indication = null
                            ) { onSelect(tab) }
                            .pressScale(interaction, pressedScale = 0.96f)
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            icons.getValue(tab),
                            null,
                            tint = foreground,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            labels.getValue(tab),
                            color = foreground,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(
            title.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MutedText,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(19.dp))
                .padding(vertical = 2.dp)
        ) { content() }
    }
}

@Composable
fun SmallCategoryIcon(kind: FileKind) {
    Icon(fileIcon(kind), null, tint = fileTint(kind).second, modifier = Modifier.size(18.dp))
}

/**
 * 一个「选择操作」条目。底部操作栏和「更多」面板共用同一种结构，
 * 这样首页根目录、内部文件夹、群晖、网络四个位置的操作才不会各写一套。
 * enabled=false 时整项变灰且不可点。
 */
data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/** 底部选择操作栏：复制 / 移动 / 删除 / 更多，四个位置完全一致。 */
@Composable
fun SelectionActionBar(actions: List<SelectionAction>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            actions.forEach { action ->
                val tint = when {
                    !action.enabled -> MutedText.copy(alpha = 0.55f)
                    action.danger -> Color(0xFFE25C5C)
                    else -> MaterialTheme.colorScheme.primary
                }
                Column(
                    Modifier
                        .then(if (action.enabled) Modifier.pressClickable(onClick = action.onClick) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(action.icon, action.label, tint = tint, modifier = Modifier.size(21.dp))
                    Text(
                        action.label,
                        fontSize = 12.sp,
                        color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MutedText.copy(alpha = 0.6f),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/** 选择态右上角只需要一个「全选 / 取消全选」按钮。 */
@Composable
fun SelectionSelectAllButton(allSelected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Outlined.SelectAll,
            if (allSelected) "取消全选" else "全选",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 「更多」面板：条目按端不同，禁用项仅变灰并不可点击。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionMoreSheet(actions: List<SelectionAction>, onDismiss: () -> Unit, title: String = "更多操作") {
    // 清单 6：Material3 默认停在「部分展开」半屏锚点，7 个动作会被截掉、要上滑才看得全。
    // 强制跳过部分展开，并给内容加滚动兜底，任何数量的动作都能一次看全。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            actions.forEach { action ->
                val tint = when {
                    !action.enabled -> MutedText.copy(alpha = 0.55f)
                    action.danger -> Color(0xFFE25C5C)
                    else -> MaterialTheme.colorScheme.primary
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (action.enabled) Modifier.clickable(onClick = action.onClick) else Modifier)
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(action.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                    Text(
                        action.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MutedText.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 输入框的统一配色（对话框、设置页、搜索框共用）。
 *
 * 浅色主题的 `outline` 是 #E2E9F5，几乎是白的；描边落在同样偏白的卡片上等于没有边，
 * 用户一眼分不清「标题文字」和「可以输入的框」。这里改用 onSurfaceVariant 的半透明色
 * 压出清晰但柔和的边界：没选中就能看见框，聚焦时再换成主色，形成两级对比。
 */
@Composable
fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = Color.Transparent,
    focusedContainerColor = Color.Transparent,
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary
)

/**
 * 对话框内的行内提示。对话框是独立窗口，页面顶部浮层会被它遮住，
 * 所以同一套配色在这里以行内条的形式出现。
 */
@Composable
fun InlineNotice(text: String, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            null,
            tint = accent,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text,
            fontSize = 12.sp,
            color = accent,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 7.dp)
        )
    }
}

/**
 * 压缩对话框：名称默认为所选文件（夹）名，可改；格式 ZIP / 7Z。
 * 选中态与排序方向一致——无框，只用强调色文字 + 对勾。
 */
@Composable
fun CompressDialog(
    defaultName: String,
    onConfirm: (name: String, format: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    var format by remember { mutableStateOf("zip") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.imePadding()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("压缩包名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("格式", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("zip" to "ZIP", "7z" to "7Z").forEach { (value, label) ->
                        val active = format == value
                        Row(
                            Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clickable { format = value },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            if (active) {
                                Icon(
                                    Icons.Outlined.Check,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp).size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(name.trim().ifBlank { defaultName }, format)
            }) { Text("开始压缩") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 解压对话框：解压到当前文件夹，或挑一个指定文件夹。
 * 指定文件夹用轻量目录浏览器（listSubfolders 返回 path→(path,name)）。
 */
@Composable
fun ExtractDialog(
    rootPath: String,
    listSubfolders: (String) -> List<Pair<String, String>>,
    onConfirm: (destinationPath: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var pickMode by remember { mutableStateOf("here") } // here | pick
    var pickPath by remember { mutableStateOf(rootPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解压", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.imePadding()) {
                Row(
                    Modifier.fillMaxWidth().clickable { pickMode = "here" }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = pickMode == "here", onClick = { pickMode = "here" })
                    Text("解压到当前文件夹", fontSize = 12.sp)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { pickMode = "pick" }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = pickMode == "pick", onClick = { pickMode = "pick" })
                    Text("解压到指定文件夹", fontSize = 12.sp)
                }
                if (pickMode == "pick") {
                    // 面包屑（清单 12/19）：点哪层回哪层；无「上一级」。
                    val segments = buildList {
                        add("根目录" to rootPath.trimEnd('/'))
                        val rel = pickPath.trimEnd('/').removePrefix(rootPath.trimEnd('/')).trim('/')
                        if (rel.isNotBlank()) {
                            var acc = rootPath.trimEnd('/')
                            rel.split('/').forEach { seg ->
                                acc = "$acc/$seg"
                                add(seg to acc)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        segments.forEachIndexed { index, (label, path) ->
                            val isLast = index == segments.lastIndex
                            Text(
                                label,
                                fontSize = 11.sp,
                                fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
                                color = if (isLast) MaterialTheme.colorScheme.primary else MutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { if (!isLast) pickPath = path }
                                    .padding(horizontal = 2.dp, vertical = 2.dp)
                            )
                            if (!isLast) {
                                Icon(Icons.Outlined.ChevronRight, null, tint = MutedText, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                    val subfolders = remember(pickPath) { listSubfolders(pickPath) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        subfolders.forEach { (path, name) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { pickPath = path }.padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(name, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(if (pickMode == "here") null else pickPath)
            }) { Text("开始解压") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 单文件 / 文件夹重命名对话框（清单 8：移除批量模板，只保留单个重命名）。
 * 预填原名，改完点保存。
 */
@Composable
fun RenameDialog(
    currentName: String,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.imePadding()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name != currentName,
                onClick = { onConfirm(name.trim()) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 群晖共享设置（清单 4）：先选有效期 / 访问密码，点「生成并复制链接」才真正生成。
 * 返回 (password, expireMinutes)；expireMinutes 0 = 永不失效。
 */
@Composable
fun ShareLinkDialog(
    onConfirm: (password: String, expireMinutes: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var expireChoice by remember { mutableStateOf(0) } // minutes; 0 = never
    val expiryOptions = listOf(
        0 to "永不",
        1440 to "1 天",
        10080 to "7 天",
        43200 to "30 天"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("共享设置", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState())) {
                Text("有效期", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedText)
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    expiryOptions.forEach { (minutes, label) ->
                        FilterChip(
                            selected = expireChoice == minutes,
                            onClick = { expireChoice = minutes },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("访问密码（可选）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                Text(
                    "留空表示不设密码；设置后访客需输入密码才能打开链接。",
                    fontSize = 12.sp,
                    color = MutedText,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password.trim(), expireChoice) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("生成并复制链接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 文件树选择器的两种模式：只选文件夹（上传文件夹 / 下载位置）、选文件（上传文件）。 */
enum class TreePickerMode { FOLDER, FILE }

/**
 * 全软件统一的「本机文件树」选择器（清单 12 / 13）。
 *
 * 交互统一为三步，所有入口一模一样：
 *  1 点一行 = 进入该文件夹（文件行则切换勾选）；
 *  2 点右侧圆圈 = 选中它（单选，选中另一个会自动取消上一个；文件模式可多选）；
 *  3 点底部「选择」= 确认。
 *
 * 之前上传文件走系统选择器、上传文件夹走应用内选择器，两套风格并存；
 * 现在统一成这一套，避免同一件事出现两种操作习惯。
 */
@Composable
fun LocalTreePickerDialog(
    title: String,
    rootPath: String,
    mode: TreePickerMode = TreePickerMode.FOLDER,
    multiSelect: Boolean = false,
    initialPath: String = rootPath,
    /** 允许在当前目录下新建子文件夹：下载位置、上传文件夹都需要，缺了就得来回切页面。 */
    allowCreateFolder: Boolean = mode == TreePickerMode.FOLDER,
    hint: String = if (mode == TreePickerMode.FOLDER) {
        "点文件夹进入下一层，勾选右侧圆圈选中，再点「选择」"
    } else {
        "点文件夹进入下一层，勾选文件后点「选择」"
    },
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val root = rootPath.trimEnd('/')
    var path by remember(root, initialPath) { mutableStateOf(initialPath.ifBlank { root }) }
    var selected by remember(root) { mutableStateOf<Set<String>>(emptySet()) }
    var showNewFolder by remember(path) { mutableStateOf(false) }
    var newFolderName by remember(path) { mutableStateOf("") }
    var createError by remember(path) { mutableStateOf("") }
    var reloadToken by remember(path) { mutableIntStateOf(0) }

    // 目录扫描在 IO 线程执行：目录文件多时 listFiles() 在主线程会 ANR（等待超时没响应）。
    // 用 loading 标记区分「加载中」和「真的是空目录」。
    var loading by remember(path) { mutableStateOf(true) }
    val entries by produceState<List<java.io.File>>(initialValue = emptyList(), path, reloadToken) {
        loading = true
        value = emptyList() // 切目录时先清掉上一屏残留
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(path)
                if (!dir.isDirectory) emptyList() else dir.listFiles().orEmpty()
                    .filter { !it.name.startsWith(".") }
                    .filter { it.isDirectory || mode == TreePickerMode.FILE }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }.getOrDefault(emptyList())
        }
        loading = false
    }
    val segments = buildList {
        add("内部存储" to root)
        val rel = path.removePrefix(root).trim('/')
        if (rel.isNotBlank()) {
            var acc = root
            rel.split('/').forEach { seg ->
                acc = "$acc/$seg"
                add(seg to acc)
            }
        }
    }

    fun pick(target: String) {
        selected = if (multiSelect) {
            if (target in selected) selected - target else selected + target
        } else {
            if (target in selected) emptySet() else setOf(target)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 面包屑：每一层都能点回去。
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    segments.forEachIndexed { index, (label, target) ->
                        val current = index == segments.lastIndex
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            color = if (current) MaterialTheme.colorScheme.primary else MutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { path = target }
                                .padding(horizontal = 3.dp, vertical = 2.dp)
                        )
                        if (!current) {
                            Icon(
                                Icons.Outlined.ChevronRight, null,
                                tint = MutedText, modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
                // LazyColumn 懒加载：文件夹里成千上万个文件时只组合可见行，
                // 旧实现 Column+forEach 一次性组合全部行，必然卡顿。
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    if (loading) {
                        item {
                            Text(
                                "正在读取文件夹…",
                                fontSize = 12.sp,
                                color = MutedText,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                            )
                        }
                    } else if (entries.isEmpty()) {
                        item {
                            Text(
                                if (mode == TreePickerMode.FILE) "这个文件夹里没有可上传的文件" else "这个文件夹里没有子文件夹",
                                fontSize = 12.sp,
                                color = MutedText,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                            )
                        }
                    }
                    items(entries, key = { it.absolutePath }) { entry ->
                        val checked = entry.absolutePath in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    if (entry.isDirectory) path = entry.absolutePath
                                    else pick(entry.absolutePath)
                                }
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                                null,
                                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MutedText,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(
                                    entry.name,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (entry.isFile) {
                                    Text(
                                        com.yaoahos.myfilebox.android.data.LocalRepository.formatSize(entry.length()),
                                        fontSize = 11.sp,
                                        color = MutedText,
                                        maxLines = 1
                                    )
                                }
                            }
                            // 右侧只有一个「勾选」圆圈：选中 ≠ 确认，确认在底部按钮。
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable { pick(entry.absolutePath) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (checked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    if (checked) "已选中" else "选中",
                                    tint = if (checked) MaterialTheme.colorScheme.primary else MutedText.copy(alpha = 0.45f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                if (allowCreateFolder) {
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
                            onClick = { showNewFolder = !showNewFolder; createError = "" },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Outlined.CreateNewFolder, null, modifier = Modifier.size(17.dp))
                            Text(
                                if (showNewFolder) "收起" else "新建",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                    if (showNewFolder) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newFolderName,
                                onValueChange = { newFolderName = it; createError = "" },
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
                                    val name = newFolderName.trim()
                                    if (name.isNotBlank()) {
                                        val dir = java.io.File(path, name)
                                        val ok = runCatching {
                                            if (dir.isDirectory) false else dir.mkdirs()
                                        }.getOrDefault(false)
                                        if (ok) {
                                            // 新建后直接选中，少点一次「选择」。
                                            selected = setOf(dir.absolutePath)
                                            newFolderName = ""
                                            showNewFolder = false
                                            createError = ""
                                            reloadToken++
                                        } else {
                                            createError = "无法在这里新建文件夹，可能没有写入权限"
                                        }
                                    }
                                },
                                enabled = newFolderName.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(52.dp)
                            ) { Text("新建", fontSize = 13.sp) }
                        }
                        if (createError.isNotBlank()) {
                            Text(
                                createError,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                Text(
                    hint,
                    fontSize = 12.sp,
                    color = MutedText,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            // 文件夹模式没勾选任何行时，默认选中「当前所在文件夹」。
            val ready = selected.isNotEmpty() || mode == TreePickerMode.FOLDER
            TextButton(
                enabled = ready,
                onClick = {
                    if (selected.isNotEmpty()) onConfirm(selected.toList()) else onConfirm(listOf(path))
                }
            ) {
                Text(
                    if (mode == TreePickerMode.FILE && selected.size > 1) "选择 ${selected.size} 个" else "选择"
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 全局统一的下拉刷新容器。
 *
 * 指示器用 Material3 默认的浅色圆底（`surfaceContainerHigh`），只把里面的箭头 /
 * 转圈换成主题色：圆底跟着主题色的「面」走，不会像纯主色圆底那样在浅色页面上
 * 抢眼；箭头跟着 primary，换主题配色时同步变，视觉上是一套。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val pullState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    // 兜底收圈：拉完松手后，指示器有时会一直停在标题栏上方不动。
    //
    // 实测（logcat MFB-PULL）：手指松开时 M3 会把位置动画到「阈值」（fraction 1.0）
    // 并调用 onRefresh；本地目录刷新只要 9ms，isRefreshing 从 true 落回 false 全部发生在
    // 同一帧之内，Compose 根本没观测到 true，于是它那条「刷新结束 → animateToHidden()」
    // 永远不会触发，位置就永久停在 1.0：圆圈和箭头明明白白留在屏幕顶部
    // （用户反馈的「刷新完卡住」）。网络慢的时候反而正常，所以只在本地目录容易撞上。
    //
    // 这里不再依赖「观测到 isRefreshing 变化」：由我们自己发起的那次刷新结束后，
    // 等阈值动画走完（isAnimating 落回 false）就把残余位置收回去。只有真的拉过才会跑，
    // 不会跟手指抢——手指在拉的时候 onRefresh 还没被调用过。
    val refreshingNow by rememberUpdatedState(isRefreshing)
    val startRefresh: () -> Unit = {
        onRefresh()
        scope.launch {
            // 最多看两分钟：刷新再慢（切网自愈重登）也该结束了，不会真跑满。
            repeat(1000) {
                kotlinx.coroutines.delay(120L)
                if (refreshingNow || pullState.isAnimating) return@repeat
                settlePullIndicator(pullState)
                return@launch
            }
        }
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = startRefresh,
        state = pullState,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isRefreshing,
                // 页面内容都避开了状态栏，指示器同样避开，不要压在时间和信号上面。
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                // containerColor 不传 = 用 Material3 默认的浅色圆底（之前的观感）。
                color = MaterialTheme.colorScheme.primary
            )
        },
        content = content
    )
}

/**
 * 把下拉指示器彻底收回起点。
 *
 * 为什么不能只调一次 [PullToRefreshState.animateToHidden]：它和指示器自己那条
 * 「松手后动画到阈值」共用同一个 Animatable，两者会互相取消。取消发生在动画中途时
 * 位置就停在半路——正是「刷新完了，圆圈箭头还挂在标题栏上方」的最终形态。
 *
 * 所以先跑 M3 的收起动画，再盯一小段时间：只要还有残余位置、而且此刻没有动画在跑，
 * 就 [PullToRefreshState.snapTo] 一口气写死到 0。snapTo 是这里唯一不依赖
 * 「另一条协程配不配合」的动作，取消当前动画并立刻落位。
 */
@OptIn(ExperimentalMaterial3Api::class)
private suspend fun settlePullIndicator(state: PullToRefreshState) {
    if (state.distanceFraction > 0f) runCatching { state.animateToHidden() }
    repeat(3) {
        if (state.distanceFraction <= 0f) return
        kotlinx.coroutines.delay(60L)
        if (state.distanceFraction > 0f && !state.isAnimating) runCatching { state.snapTo(0f) }
    }
}
