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
import androidx.compose.material.icons.outlined.ArrowBack
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
/** 选择态统一提示：此时轻点和长按都是勾选/取消，长按无额外效果，别再误导用户。 */
internal const val SELECTING_HINT = "轻点勾选或取消 · 右上角可全选"


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
@OptIn(ExperimentalFoundationApi::class)
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
                    tint = semanticColors().star,
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
    // 这里是配套的「浅底 + 饱和前景」装饰色对，跟随主题反而会破坏层次，保持固定值。
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

/**
 * 悬浮底栏统一的表面色与描边（标签栏、选择操作栏共用一份）。
 *
 * 浅色用页面卡片同款白，深色比页面底色亮一档；两端都比纯色块更贴近页面本身。
 * 再压一圈极淡描边把轮廓收干净——只靠阴影的话，浅蓝底上会显成一团灰雾。
 */
@Composable
private fun barSurface(): Pair<Color, BorderStroke> {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val color = if (dark) cs.surfaceContainerHigh else cs.surface
    val border = BorderStroke(1.dp, cs.outline.copy(alpha = if (dark) 0.85f else 0.8f))
    return color to border
}

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
    val colorScheme = MaterialTheme.colorScheme
    val (barColor, barBorder) = barSurface()
    // 选中态只靠图标/文字的颜色与字重区分：早先版本在选中项下垫了一层主题色
    // 胶囊，在底栏这种小面积上会显成一块「补丁」，与周围的图标文字不在同一层。
    // 悬浮胶囊：圆角 + 轻阴影与背景自然分离，不再是一整条铺满屏幕、
    // 顶边还压着分隔线的实心色块。底部安全区由外层 FixedBottomBar 统一处理。
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = barColor,
        shape = RoundedCornerShape(28.dp),
        border = barBorder,
        shadowElevation = 4.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(62.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = tab == active
                val interaction = remember { MutableInteractionSource() }
                val foreground by animateColorAsState(
                    targetValue = if (selected) selectedAccent
                    else colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                    label = "tab-fg"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interaction,
                            indication = null
                        ) { onSelect(tab) }
                        .pressScale(interaction, pressedScale = 0.96f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 6.dp),
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
    val (barColor, barBorder) = barSurface()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = barColor,
        shape = RoundedCornerShape(28.dp),
        border = barBorder,
        shadowElevation = 4.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            actions.forEach { action ->
                val tint = when {
                    !action.enabled -> MutedText.copy(alpha = 0.55f)
                    action.danger -> MaterialTheme.colorScheme.error
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
                    action.danger -> MaterialTheme.colorScheme.error
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
 * 密码可留空（不加密），非空时 ZIP 用 AES-256、7Z 用原生加密。
 * 选中态与排序方向一致——无框，只用强调色文字 + 对勾。
 */


/**
 * 页面左上角的返回按钮：圆形底 + 2dp 阴影、36dp 触区、18dp 图标。
 *
 * 回收站 / 共享管理以前直接摆裸 [IconButton]（24dp 图标、没有底色），
 * 和浏览页 / 搜索 / 设置二级页的圆形按钮并排看就是两套风格。
 * 现在所有页面只走这一个组件；选择态需要换成叉号时传 [icon]，文案传 [label]。
 */
@Composable
fun CircleBackButton(
    onClick: () -> Unit,
    label: String = "返回",
    icon: ImageVector = Icons.Outlined.ArrowBack
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.size(36.dp)
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(icon, label, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * 面包屑：空间够就整串显示；不够就从最外层开始一段一段丢掉（前面补「…」）。
 * 每一段都保持完整的文件夹名，绝不截成半截；只有「丢到只剩最后一段仍放不下」时
 * 才由最后一段自己收省略号——那时已经没有可丢的了。
 *
 * 浏览页、搜索位置选择、文件夹选择器共用这一套取舍规则，避免出现「这里截一半、
 * 那里直接省略」的两种表现。
 */
@Composable
fun PathBreadcrumbs(
    labels: List<String>,
    modifier: Modifier = Modifier,
    /** true = 当前层主色加粗（选择器）；false = 上层主色、当前层灰字（浏览页）。 */
    emphasizeCurrent: Boolean = true,
    chevronSize: Dp = 13.dp,
    /** 下标小于它的段不可点（最外层的端名不对应任何页面）。 */
    clickableFrom: Int = 0,
    onCrumbClick: ((Int) -> Unit)? = null
) {
    if (labels.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 12.sp)
    val padX = if (onCrumbClick != null) 3.dp else 0.dp
    val padY = if (onCrumbClick != null) 2.dp else 0.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val chevronPx = with(density) { chevronSize.toPx() }
        val padPx = with(density) { padX.toPx() } * 2
        val widthPx = with(density) { constraints.maxWidth.toFloat() }
        val morePx = measurer.measure(AnnotatedString("…"), style, maxLines = 1).size.width.toFloat()
        val labelPx = labels.map {
            measurer.measure(AnnotatedString(it), style, maxLines = 1).size.width.toFloat() + padPx
        }
        // 从最外层往里丢，第一个放得下的起点就是最终起点；都放不下就只留最后一段。
        val start = labels.indices.firstOrNull { candidate ->
            val body = (candidate..labels.lastIndex).sumOf { labelPx[it].toDouble() }.toFloat() +
                chevronPx * (labels.lastIndex - candidate)
            val head = if (candidate > 0) morePx + chevronPx else 0f
            head + body <= widthPx - 6f
        } ?: labels.lastIndex
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (start > 0) {
                Text("…", fontSize = 12.sp, color = MutedText, maxLines = 1)
                Icon(
                    Icons.Outlined.ChevronRight, null,
                    modifier = Modifier.size(chevronSize), tint = MutedText
                )
            }
            for (i in start..labels.lastIndex) {
                val isLast = i == labels.lastIndex
                val primary = if (emphasizeCurrent) isLast else !isLast
                Text(
                    labels[i],
                    fontSize = 12.sp,
                    fontWeight = if (emphasizeCurrent && isLast) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (primary) MaterialTheme.colorScheme.primary else MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        // 最后一段允许被压窄（自身收省略号），其余段按完整宽度摆放。
                        .then(if (isLast) Modifier.weight(1f, fill = false) else Modifier)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            enabled = !isLast && onCrumbClick != null && i >= clickableFrom
                        ) { onCrumbClick?.invoke(i) }
                        .padding(horizontal = padX, vertical = padY)
                )
                if (!isLast) {
                    Icon(
                        Icons.Outlined.ChevronRight, null,
                        modifier = Modifier.size(chevronSize), tint = MutedText
                    )
                }
            }
        }
    }
}
