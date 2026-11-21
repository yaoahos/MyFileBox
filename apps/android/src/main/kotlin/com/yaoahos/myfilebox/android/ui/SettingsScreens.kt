package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.displayLeaf

import com.yaoahos.myfilebox.android.BuildConfig

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.zIndex
import com.yaoahos.myfilebox.android.update.GitHubUpdater
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.yaoahos.myfilebox.core.Session
import com.yaoahos.myfilebox.core.TabKey
import com.yaoahos.myfilebox.core.ThemeMode

@Composable
fun SettingsScreen(
    state: MyFileBoxState,
    vm: AppViewModel,
    // 滚动状态由页面宿主常驻持有：进「关于」再返回不会跳回顶部；
    // 而「退出设置再进来要回到顶部」由宿主在切换到设置页时统一重置。
    listState: LazyListState = rememberLazyListState()
) {
    var chooseDownload by remember { mutableStateOf(false) }
    var downloadPickerPath by remember { mutableStateOf("") }
    var showCacheDays by remember { mutableStateOf(false) }
    var showConcurrency by remember { mutableStateOf(false) }
    var confirmCleanCache by remember { mutableStateOf(false) }
    var openDefaultsExpanded by remember { mutableStateOf(false) }

    Column(
        // 设置页自带不透明底色：转场时不会透出上一页内容（残影）。
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // 返回箭头固定不随列表滚走（3.9.16）；设置页右上角不放三点按钮（清单 13）。
        SubPageHeader("设置", vm::closeScreen)
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
        item {
            // 外观相关的东西（模式 + 配色 + 动态取色）收进二级页，设置首页只留一行入口：
            // 一屏里既摆分段控件、又往下滚别的设置，眼睛没落点。
            SettingsSection("主题与外观") {
                SettingRow(
                    Icons.Outlined.Palette,
                    "主题与外观",
                    "${themeSubtitle(state.prefs.theme.mode)} · ${if (state.prefs.theme.dynamic) "动态取色" else "自定义配色"}",
                    onClick = vm::openAppearance
                )
            }
        }
        item {
            SettingsSection("启动") {
                DefaultTabRow(state.prefs.defaultTab, vm::setDefaultTab)
            }
        }
        item {
            SettingsSection("底栏") {
                val tabs = (state.prefs.tabOrder + listOf(TabKey.LOCAL, TabKey.SYNO, TabKey.NET)).distinct()
                ReorderGroup(count = tabs.size, keyOf = { tabs[it] }) { index ->
                    TabSettingRow(
                        tab = tabs[index],
                        state = state,
                        onToggle = { vm.toggleTab(tabs[index]) },
                        onMove = { vm.moveTab(tabs[index], it) }
                    )
                }
            }
        }
        item {
            SettingsSection("本地文件") {
                ModuleSettings(state, vm)
            }
        }
        item {
            // 「最近」的展示口径。默认都关掉噪音：但只影响「最近」这一处，
            // 进目录里看文件始终不受影响。
            SettingsSection("最近文件") {
                SettingRow(
                    Icons.Outlined.History,
                    "隐藏备份副本",
                    "「最近」不显示 .bak 等备份副本",
                    onClick = { vm.setHideRecentBackups(!state.hideRecentBackups) },
                    trailing = {
                        Switch(
                            checked = state.hideRecentBackups,
                            onCheckedChange = vm::setHideRecentBackups
                        )
                    }
                )
                SettingRow(
                    Icons.Outlined.CleaningServices,
                    "隐藏应用缓存",
                    "「最近」不显示微信、QQ 缓存",
                    onClick = { vm.setHideRecentCache(!state.hideRecentCache) },
                    trailing = {
                        Switch(
                            checked = state.hideRecentCache,
                            onCheckedChange = vm::setHideRecentCache
                        )
                    }
                )
            }
        }
        item {
            // 文件分类排序：直接摆成和首页一致的 4×2 网格，拖一行看到的就是成品。
            SettingsSection("文件分类排序") {
                val baseOrder = listOf("图片", "视频", "文档", "音频", "安装包", "压缩包", "最近", "收藏")
                val stored = state.prefs.categoryOrder
                // 空 order = 默认顺序；非空时补上缺失项（勿把 baseOrder 拼两遍）。
                val order = if (stored.isEmpty()) baseOrder
                else stored.filter { it in baseOrder } + baseOrder.filterNot { it in stored }
                CategoryOrderGrid(
                    order = order,
                    onMove = { from, to -> vm.moveCategory(order[from], to - from) }
                )
                Text(
                    "长按拖动调整顺序，即时生效；上方网格即首页实际显示效果。",
                    fontSize = 12.sp,
                    color = MutedText,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        item {
            SettingsSection("下载") {
                SettingRow(
                    Icons.Outlined.Download,
                    "默认下载位置",
                    displayLeaf(state.prefs.downloadFolder, state.localRoot, "未设置"),
                    onClick = {
                        downloadPickerPath = state.prefs.downloadFolder.ifBlank { state.localRoot }
                        chooseDownload = true
                    }
                )
                SettingRow(
                    Icons.Outlined.SwapHoriz,
                    "下载并发数",
                    "${state.prefs.downloadConcurrency} 个文件同时下载",
                    onClick = { showConcurrency = true }
                )
                Text(
                    "未设置位置时下载前会先询问；并发数范围为 1~6，数值越大越快，对 NAS 与弱网压力越大。",
                    fontSize = 12.sp,
                    color = MutedText,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        item {
            SettingsSection("打开方式") {
                val defaults = vm.openDefaultEntries()
                if (defaults.isEmpty()) {
                    SettingRow(
                        Icons.Outlined.Apps,
                        "默认打开方式",
                        "未设置 · 每次都会询问用哪个应用"
                    )
                } else {
                    // 默认打开方式可能积累几十条，默认只列前 3 条，避免把设置页拉得过长。
                    val shown = if (openDefaultsExpanded) defaults else defaults.take(3)
                    if (defaults.size > 3) {
                        SettingRow(
                            Icons.Outlined.Apps,
                            "默认打开方式",
                            "已记住 ${defaults.size} 项${if (openDefaultsExpanded) "" else " · 仅显示前 3 项"}",
                            onClick = { openDefaultsExpanded = !openDefaultsExpanded },
                            trailing = {
                                TextButton(onClick = { openDefaultsExpanded = !openDefaultsExpanded }) {
                                    Text(if (openDefaultsExpanded) "收起" else "展开", fontSize = 12.sp)
                                }
                            }
                        )
                    } else {
                        SettingRow(Icons.Outlined.Apps, "默认打开方式", "已记住 ${defaults.size} 项")
                    }
                    shown.forEach { (key, label) ->
                        SettingRow(
                            // 图标跟着类型走：压缩包 / 图片 / 安装包各用自己的图标，
                            // 全是「文档」图标时用户得逐行读文字才分得清。
                            openTypeIcon(key),
                            vm.openTypeLabel(key),
                            "始终用「$label」打开",
                            onClick = { vm.clearOpenDefault(key) },
                            trailing = {
                                TextButton(onClick = { vm.clearOpenDefault(key) }) {
                                    Text("改回询问", fontSize = 12.sp)
                                }
                            }
                        )
                    }
                    if (defaults.size > 3) {
                        TextButton(
                            onClick = { vm.clearAllOpenDefaults() },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                "清空全部默认打开方式（${defaults.size} 项）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Text(
                    "在「打开方式」里点应用右边的 ☆ 可记住选择；这里点一下即可恢复成每次询问。",
                    fontSize = 12.sp,
                    color = MutedText,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        item {
            SettingsSection("存储与缓存") {
                LaunchedEffect(Unit) { vm.refreshCacheSize() }
                // 清单 9：在线打开 / 预览产生的缓存既能定时清，也能一键手动清。
                SettingRow(
                    Icons.Outlined.CleaningServices,
                    "自动清理缓存",
                    if (state.prefs.cacheKeepDays <= 0) "永不自动清理"
                    else "超过 ${state.prefs.cacheKeepDays} 天自动清理",
                    onClick = { showCacheDays = true }
                )
                SettingRow(
                    Icons.Outlined.DeleteSweep,
                    "立即清理缓存",
                    "当前占用 ${com.yaoahos.myfilebox.android.data.LocalRepository.formatSize(state.cacheBytes)}",
                    onClick = { confirmCleanCache = true }
                )
                Text(
                    "缓存只包含在线打开、图片预览和缩略图，删掉不会影响任何原始文件。",
                    fontSize = 12.sp,
                    color = MutedText,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        item {
            // 备份与同步：备份（存本机加密文件）在上，同步（WebDAV 跨设备）在下，
            // 入口单独成段，放在「存储与缓存」之后——它属于数据安全，不属于日常开关。
            SettingsSection("备份与同步") {
                SettingRow(
                    Icons.Outlined.Backup,
                    "备份与同步",
                    if (state.sync.autoSyncOn) "云同步 · 自动同步${state.sync.interval.label}" else "加密备份配置 · 支持 WebDAV 同步",
                    onClick = vm::openBackupSync
                )
            }
        }
        item {
            // 「关于」从右上角更多菜单移到这里（清单 10）：不常用，放设置最底部。
            SettingsSection("其它") {
                SettingRow(
                    Icons.Outlined.Info,
                    "关于",
                    "版本、开源许可与更新",
                    onClick = vm::openAbout
                )
            }
        }
        }
    }

    if (confirmCleanCache) {
        AlertDialog(
            onDismissRequest = { confirmCleanCache = false },
            title = { Text("清理缓存") },
            text = {
                Text(
                    "确定清理所有缓存吗？当前占用 ${com.yaoahos.myfilebox.android.data.LocalRepository.formatSize(state.cacheBytes)}。" +
                        "缓存只包含在线打开、图片预览和缩略图，清理后下次使用会重新下载。"
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmCleanCache = false; vm.cleanCacheNow() }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { confirmCleanCache = false }) { Text("取消") } }
        )
    }
    if (showCacheDays) {
        // 保留周期：0 = 永不自动清理，其余按天数在冷启动 / 回前台时清理。
        AlertDialog(
            onDismissRequest = { showCacheDays = false },
            title = { Text("自动清理缓存", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    listOf(
                        0 to "永不自动清理",
                        1 to "1 天后清理",
                        7 to "7 天后清理",
                        30 to "30 天后清理"
                    ).forEach { (days, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    showCacheDays = false
                                    vm.setCacheKeepDays(days)
                                }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.prefs.cacheKeepDays == days,
                                onClick = {
                                    showCacheDays = false
                                    vm.setCacheKeepDays(days)
                                }
                            )
                            Text(label, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCacheDays = false }) { Text("取消") } }
        )
    }
    if (showConcurrency) {
        // 下载并发数：1 最省资源、6 最快（弱网/机械盘 NAS 建议 2~3）。
        AlertDialog(
            onDismissRequest = { showConcurrency = false },
            title = { Text("下载并发数", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "并发越大速度越快，对 NAS 与弱网压力越大；弱网或机械硬盘 NAS 建议 2~3。",
                        fontSize = 12.sp,
                        color = MutedText,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    (1..6).forEach { n ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    showConcurrency = false
                                    vm.setDownloadConcurrency(n)
                                }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.prefs.downloadConcurrency == n,
                                onClick = {
                                    showConcurrency = false
                                    vm.setDownloadConcurrency(n)
                                }
                            )
                            Text(
                                "$n 个文件同时下载" + when (n) {
                                    1 -> "（最稳，速度最慢）"
                                    6 -> "（最快，对 NAS 压力最大）"
                                    else -> ""
                                },
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showConcurrency = false }) { Text("取消") } }
        )
    }
    if (chooseDownload) {
        // 与「上传文件夹」完全同一套文件树（清单 12 / 13）：
        // 点行进入下一层、右侧圆圈单选、底部「选择」才确认。
        LocalTreePickerDialog(
            title = "默认下载位置",
            rootPath = state.localRoot,
            mode = TreePickerMode.FOLDER,
            initialPath = downloadPickerPath.ifBlank { state.localRoot },
            hint = "下载会保存到选中的文件夹；点行进入下一层，勾选后点「选择」",
            onDismiss = { chooseDownload = false },
            onConfirm = { paths ->
                chooseDownload = false
                paths.firstOrNull()?.let(vm::setDownloadFolder)
            }
        )
    }
}

@Composable
private fun ModuleSettings(state: MyFileBoxState, vm: AppViewModel) {
    val labels = mapOf(
        "local:categories" to ("文件分类" to "图片、视频、文档等快捷入口"),
        "local:folders" to ("内部存储" to "内部存储中的文件与文件夹")
    )
    // 分组标题已由外层 SettingsSection("本地文件") 提供，这里不再重复一行「本地文件」。
    listOf("local").forEach { group ->
        val keys = state.prefs.moduleOrder.filter { it.startsWith("$group:") && labels.containsKey(it) }
        ReorderGroup(count = keys.size, keyOf = { keys[it] }) { index ->
            val moduleKey = keys[index]
            val (name, subtitle) = labels[moduleKey] ?: (moduleKey to "")
            ReorderableRow({ vm.moveModule(moduleKey, it) }) { handleModifier ->
                ReorderHandle(handleModifier)
                Icon(
                    if (group == "syno") Icons.Outlined.Cloud else Icons.Outlined.Folder,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, fontSize = 12.sp, color = MutedText, maxLines = 1)
                }
                Switch(checked = vm.moduleVisible(moduleKey), onCheckedChange = { vm.toggleModule(moduleKey) })
            }
        }
    }
    Text(
        "关闭后对应模块会从首页移除，不占用版面；随时可以在这里重新开启。",
        fontSize = 12.sp,
        color = MutedText,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
private fun ReorderHandle(modifier: Modifier = Modifier) {
    // 6 点「拖拽指示」比两条横线更像把手，也更容易和「菜单」图标区分开；
    // 外面套一层 28dp 的方形触摸区，长按落点不必精确压在 18dp 的图标上。
    Box(
        modifier.size(28.dp).clip(RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.DragIndicator,
            contentDescription = "长按拖动排序",
            tint = MutedText.copy(alpha = 0.62f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 一次拖拽的共享状态：记录被拖行的原始位置、手指累计位移与净跨行数。
 * 拖拽过程中只改这里，不碰 ViewModel；松手时把净跨行数一次性提交。
 */
private class ReorderDragState(
    private val rowHeightPx: Float,
    private val count: Int
) {
    var activeIndex by mutableIntStateOf(-1)
    var dragOffsetPx by mutableFloatStateOf(0f)
    var netSteps by mutableIntStateOf(0)

    fun start(index: Int) {
        activeIndex = index
        dragOffsetPx = 0f
        netSteps = 0
    }

    fun dragBy(dy: Float) {
        val from = activeIndex
        if (from < 0 || rowHeightPx <= 0f) return
        val min = -from * rowHeightPx
        val max = (count - 1 - from) * rowHeightPx
        val next = (dragOffsetPx + dy).coerceIn(min, max)
        dragOffsetPx = next
        netSteps = (next / rowHeightPx).roundToInt()
    }

    /** 让位目标：被拖行跨过的行整体向反方向平移一行的高度。 */
    fun shiftTargetFor(index: Int): Float {
        val from = activeIndex
        if (from < 0 || rowHeightPx <= 0f) return 0f
        val target = from + netSteps
        return when {
            netSteps > 0 && index in (from + 1)..target -> -rowHeightPx
            netSteps < 0 && index in target until from -> rowHeightPx
            else -> 0f
        }
    }

    /** 结束拖拽并返回净跨行数，供被拖行一次性提交顺序。 */
    fun finish(): Int {
        val steps = if (activeIndex >= 0) netSteps else 0
        activeIndex = -1
        dragOffsetPx = 0f
        netSteps = 0
        return steps
    }
}

private val LocalReorderDrag = compositionLocalOf<ReorderDragState?> { null }
private val LocalReorderIndex = compositionLocalOf { 0 }

/** 包裹一组可排序行，统一协调让位动画与松手提交。 */
@Composable
private fun ReorderGroup(
    count: Int,
    keyOf: (Int) -> Any,
    content: @Composable (index: Int) -> Unit
) {
    val density = LocalDensity.current
    val state = remember { ReorderDragState(with(density) { 54.dp.toPx() }, count) }
    Column(Modifier.fillMaxWidth()) {
        for (index in 0 until count) {
            key(keyOf(index)) {
                CompositionLocalProvider(
                    LocalReorderDrag provides state,
                    LocalReorderIndex provides index
                ) {
                    content(index)
                }
            }
        }
    }
}

@Composable
private fun ReorderableRow(
    onMove: (Int) -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.(Modifier) -> Unit
) {
    val density = LocalDensity.current
    val shadowPx = with(density) { 12.dp.toPx() }
    val shared = LocalReorderDrag.current
    val index = LocalReorderIndex.current
    val currentOnMove = rememberUpdatedState(onMove)

    val isActive = shared != null && shared.activeIndex == index
    val dragging = shared != null && shared.activeIndex >= 0
    val shiftTarget = if (isActive) 0f else (shared?.shiftTargetFor(index) ?: 0f)
    val shift by animateFloatAsState(
        targetValue = shiftTarget,
        animationSpec = spring<Float>(),
        label = "reorder-shift-$index"
    )

    // 手势只挂在拖动把手上，而且要长按才生效：普通上下滑动列表不会误触发排序，
    // 想直接把列表当点击区扫过去也不会把行拖走（之前按下即拖，一碰就乱）。
    // 长按触发时给一次触觉反馈，用户能明确感知「现在开始拖了」。
    val haptics = LocalHapticFeedback.current
    val handleModifier = Modifier.pointerInput(shared, index) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                shared?.start(index)
            },
            onDragEnd = {
                val steps = shared?.finish() ?: 0
                if (steps != 0) currentOnMove.value(steps)
            },
            onDragCancel = { shared?.finish() },
            onDrag = { change, dragAmount ->
                change.consume()
                shared?.dragBy(dragAmount.y)
            }
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .zIndex(if (isActive) 4f else 0f)
            .graphicsLayer {
                // 松手后立即归零：让位动画只在拖拽过程中生效。
                // 否则提交新顺序的那一帧，被让位的行会从 -1 行的偏移回弹到 0，
                // 看起来就是「被替换位置的那一栏弹跳一下」。
                translationY = when {
                    isActive -> shared?.dragOffsetPx ?: 0f
                    dragging -> shift
                    else -> 0f
                }
                shadowElevation = if (isActive) shadowPx else 0f
            }
            .background(
                if (isActive) MaterialTheme.colorScheme.surface else Color.Transparent,
                RoundedCornerShape(14.dp)
            )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content(handleModifier)
        }
    }
}

@Composable
private fun TabSettingRow(tab: TabKey, state: MyFileBoxState, onToggle: () -> Unit, onMove: (Int) -> Unit) {
    val visible = when (tab) {
        TabKey.LOCAL -> state.prefs.showLocal
        TabKey.SYNO -> state.prefs.showSyno
        TabKey.NET -> state.prefs.showNet
    }
    val label = when (tab) {
        TabKey.LOCAL -> "本地文件"
        TabKey.SYNO -> "群晖文件"
        TabKey.NET -> "网络文件"
    }
    val icon = when (tab) {
        TabKey.LOCAL -> Icons.Outlined.Folder
        TabKey.SYNO -> Icons.Outlined.Cloud
        TabKey.NET -> Icons.Outlined.Wifi
    }
    ReorderableRow(onMove) { handleModifier ->
        // 把手放行首：与右侧开关拉开距离，长按拖拽时不会顺手把开关拨掉。
        ReorderHandle(handleModifier)
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(if (visible) "显示在底栏" else "已隐藏", fontSize = 12.sp, color = MutedText)
        }
        Switch(checked = visible, onCheckedChange = { onToggle() }, modifier = Modifier.padding(start = 2.dp))
    }
}

/**
 * 分类排序网格（4 列 × 2 行）。
 * 格子照首页分类卡的比例画（彩色圆角块 + 名称），拖动时其它格子实时让位，
 * 松手就落定 —— 这里看到的就是首页成品，不必来回切页确认顺序。
 */
@Composable
private fun CategoryOrderGrid(order: List<String>, onMove: (from: Int, to: Int) -> Unit) {
    val density = LocalDensity.current
    val columns = 4
    val gap = 8.dp
    val cellHeight = 72.dp

    var from by remember { mutableIntStateOf(-1) }
    var to by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // 让位规则：落在 from 与 to 之间的格子整体后退一格，和松手后的最终顺序一致。
    fun slotOf(index: Int): Int {
        if (from < 0 || index == from) return index
        return when {
            from < to && index in (from + 1)..to -> index - 1
            to < from && index in to until from -> index + 1
            else -> index
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
        val cellWidth = (maxWidth - gap * (columns - 1)) / columns
        val rows = (order.size + columns - 1) / columns
        val stepX = with(density) { (cellWidth + gap).toPx() }
        val stepY = with(density) { (cellHeight + gap).toPx() }
        val cellW = with(density) { cellWidth.toPx() }
        val cellH = with(density) { cellHeight.toPx() }

        Box(Modifier.fillMaxWidth().height(cellHeight * rows + gap * (rows - 1))) {
            // 槽位 → 像素坐标。位置只有这一个来源，松手前后不会各算一套。
            val pitchX = with(density) { (cellWidth + gap).toPx() }
            val pitchY = with(density) { (cellHeight + gap).toPx() }
            fun cellPos(slotIndex: Int) =
                Offset(pitchX * (slotIndex % columns), pitchY * (slotIndex / columns))

            order.forEachIndexed { index, name ->
                key(name) {
                    val slot = slotOf(index)
                    val dragging = from == index
                    // 拖拽中位置直接跟手（snap 不插值），松手后 spring 收到槽位中心：
                    // 动画的起点就是手指最后的落点，只需走半格以内的吸附距离，
                    // 不会像以前那样从原槽位整个滑向新位置（那正是「松手回弹一下」的原因）。
                    val target = if (dragging) cellPos(index) + dragOffset else cellPos(slot)
                    val pos by animateOffsetAsState(
                        targetValue = target,
                        animationSpec = if (dragging) {
                            snap()
                        } else {
                            spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
                        },
                        label = "cat-pos-$name"
                    )
                    CategoryGridTile(
                        name = name,
                        width = cellWidth,
                        height = cellHeight,
                        dragging = dragging,
                        modifier = Modifier
                            // offset 必须在 pointerInput 之前：手势的命中区要跟着格子一起位移，
                            // 反过来会把所有格子的点击区叠在网格左上角（抓到最后一个格子）。
                            .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                            .zIndex(if (dragging) 2f else 1f)
                            .pointerInput(name, index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        from = index
                                        to = index
                                        dragOffset = Offset.Zero
                                    },
                                    onDragEnd = {
                                        val f = from
                                        val t = to
                                        from = -1
                                        to = -1
                                        dragOffset = Offset.Zero
                                        if (f >= 0 && t >= 0 && f != t) onMove(f, t)
                                    },
                                    onDragCancel = {
                                        from = -1
                                        to = -1
                                        dragOffset = Offset.Zero
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount
                                        val start = from
                                        if (start >= 0) {
                                            // 用被拖格中心点落在哪个槽位判定目标，手指偏一点也不跳。
                                            val cx = (start % columns) * stepX + cellW / 2 + dragOffset.x
                                            val cy = (start / columns) * stepY + cellH / 2 + dragOffset.y
                                            val col = (cx / stepX).toInt().coerceIn(0, columns - 1)
                                            val row = (cy / stepY).toInt().coerceIn(0, rows - 1)
                                            to = (row * columns + col).coerceIn(0, order.lastIndex)
                                        }
                                    }
                                )
                            }
                    )
                }
            }
        }
    }
}

/** 网格里的单个分类：彩色圆角块 + 名称，比例与首页分类卡一致。 */
@Composable
private fun CategoryGridTile(
    name: String,
    width: Dp,
    height: Dp,
    dragging: Boolean,
    modifier: Modifier = Modifier
) {
    val visual = categories.firstOrNull { it.name == name }
    val icon = visual?.icon ?: Icons.Outlined.Folder
    val tint = visual?.tint ?: MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                val s = if (dragging) 1.07f else 1f
                scaleX = s
                scaleY = s
            }
            .then(
                if (dragging) {
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(tint),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Text(
                name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun DefaultTabRow(current: TabKey, onSelect: (TabKey) -> Unit) {
    Column(Modifier.padding(12.dp)) {
        Text("默认启动页", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(
            Modifier.fillMaxWidth().padding(top = 7.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(3.dp)
        ) {
            listOf(TabKey.LOCAL to "本地", TabKey.SYNO to "群晖", TabKey.NET to "网络").forEach { (tab, label) ->
                val active = current == tab
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent).clickable { onSelect(tab) }.padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) MaterialTheme.colorScheme.primary else MutedText)
                }
            }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        Column(Modifier.weight(1f).padding(start = 11.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 1.dp))
        }
        trailing?.invoke()
    }
}

@Composable
fun SubPageHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleBackButton(onClick = onBack)
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 12.dp))
        trailing?.invoke()
    }
}

private fun themeSubtitle(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "日间"
    ThemeMode.DARK -> "夜间"
}

@Composable
fun AboutScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 返回箭头固定（3.9.16），不随内容滚走。
        SubPageHeader("关于", { vm.closeScreen() }) { HeaderOverflow(vm, hideNavigation = true, showSettings = false) }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 30.dp)
        ) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // 点顶部图标或 MyFileBox 标题跳开源仓库（1.0.0）。
                val uriHandler = LocalUriHandler.current
                val openRepo: () -> Unit = {
                    runCatching { uriHandler.openUri(GitHubUpdater.REPO_PAGE) }
                }
                AppBrandIcon(
                    modifier = Modifier.size(78.dp).clickable(onClick = openRepo),
                    shape = RoundedCornerShape(23.dp),
                    contentDescription = "MyFileBox 开源仓库"
                )
                Text(
                    "MyFileBox",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = 10.dp).clickable(onClick = openRepo)
                )
                Text("群晖 NAS 文件管理客户端", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 2.dp))
                Text(
                    "直连 NAS · 本地优先",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 3.dp)
                )
                Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
            }
        }
        item { AboutSection("功能范围", "本地文件、群晖 NAS 与 WebDAV / SMB / FTP / SFTP 网络位置统一管理。支持跨端下载与上传，同端复制与移动。") }
        item { AboutSection("技术栈", "Kotlin + Jetpack Compose + Material 3，模块化 core / apps:android。") }
        item { AboutSection("安全与隐私", "账号与连接信息加密保存在本机；文件直连 NAS / WebDAV / SMB / FTP，不上传第三方服务器。") }
        item { AboutSection("声明", "MyFileBox 为独立开发的第三方客户端，与相关品牌厂商均无关联。") }
        item {
            SettingsSection("更新") {
                SettingRow(
                    Icons.Outlined.SystemUpdate,
                    "启动时检查更新",
                    "每次冷启动自动检查一次",
                    trailing = {
                        Switch(
                            checked = vm.updateCheckOnStartup(),
                            onCheckedChange = { vm.setUpdateCheckStartup(it) }
                        )
                    }
                )
                SettingRow(
                    Icons.Outlined.Refresh,
                    "检查更新",
                    "当前版本 ${BuildConfig.VERSION_NAME}",
                    onClick = { vm.checkForUpdate(manual = true) }
                )
                SettingRow(
                    Icons.Outlined.History,
                    "更新日志",
                    "查看历史版本的更新内容",
                    onClick = { vm.loadUpdateLog() }
                )
            }
        }
        }
    }
}

/**
 * 关于页里的一段说明：小标题在块外，正文放进圆角区域块。
 *
 * 与设置页的 [SettingsSection] 同一套写法，靠块与块之间的留白区分段落，
 * 不在块内画分隔线——标题和正文本来就是一段话，中间横一条线只会显得断。
 */
@Composable
private fun AboutSection(title: String, body: String) {
    SettingsSection(title) {
        Text(
            body,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)
        )
    }
}

/**
 * 「打开方式」里某一类文件的图标。
 *
 * key 可能是大类（audio / video / image），也可能是具体 MIME，所以按前缀和关键字判断；
 * 全部落到同一个「文档」图标时，用户只能逐行读文字才分得清是压缩包还是安装包。
 */
private fun openTypeIcon(key: String): ImageVector = when {
    key == "audio" -> Icons.Outlined.LibraryMusic
    key == "video" -> Icons.Outlined.Videocam
    key == "image" || key.startsWith("image/") -> Icons.Outlined.ImageIcon
    key.contains("zip") || key.contains("rar") || key.contains("7z") ||
        key.contains("tar") || key.contains("gzip") -> Icons.Outlined.FolderZip
    key.contains("pdf") -> Icons.Outlined.PictureAsPdf
    key.contains("android.package") -> Icons.Outlined.Android
    else -> Icons.Outlined.Description
}


