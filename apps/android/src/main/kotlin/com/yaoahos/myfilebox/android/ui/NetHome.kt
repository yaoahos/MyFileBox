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
import androidx.compose.foundation.layout.ColumnScope
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
import com.yaoahos.myfilebox.core.connectionAddressLabel
import kotlin.math.abs
import com.yaoahos.myfilebox.android.ui.theme.semanticColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetHome(state: MyFileBoxState, vm: AppViewModel) {
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
        //
        // 设备状态（飞行模式 / 没网）已由列表里那行常驻说明说清时不再挂这条：
        // 两行同一句话会重复，且这种状态下点重试必然还是失败。
        if (state.connectHint && vm.networkNotice() == null) {
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
            contentPadding = PaddingValues(bottom = BottomBarClearance)
        ) {
            if (state.nameFilter.isNotBlank() || state.extensionFilter.isNotBlank()) {
                val hit = state.connections.count {
                    itemMatchesFilter(it.name, true, state.nameFilter, state.extensionFilter)
                }
                item { ActiveFilterBar(vm, matched = hit, total = state.connections.size) }
            }
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
                .filter { itemMatchesFilter(it.name, true, state.nameFilter, state.extensionFilter) }
            val netFilterOn = state.nameFilter.isNotBlank() || state.extensionFilter.isNotBlank()
            if (sortedConnections.isEmpty() && netFilterOn) {
                item { FilterEmptyHint(vm) }
            } else if (sortedConnections.isEmpty()) {
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
                color = if (connection.online) semanticColors().online else MutedText,
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
            color = if (connection.online) semanticColors().online else MutedText,
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
        DialogActionTone.DANGER -> MaterialTheme.colorScheme.error
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
    // 交给统一外壳：竖屏是 AlertDialog（与重命名、压缩这些表单同一套外观），
    // 横屏 / 分屏自动换成底部面板并随键盘上移。
    //
    // 为什么不自己留一套 Dialog：Dialog 是独立窗口，横屏时既不跟着键盘 resize，
    // 也拿不到 ime insets，键盘一弹就把整块输入区盖住（实测可用高度只剩 120dp 上下，
    // 四个按钮排一行就吃光了）。ModalBottomSheet 长在主窗口里，imePadding 直接生效。
    // 于是按钮也跟着重排：取消 / 保存留在标题行随时可点，测试 / 删除挪进内容区，
    // 谁都不会被键盘或手势条吃掉。
    val saveInteraction = remember { MutableInteractionSource() }
    // 测试结果固定在内容区下方，不随表单滚动：放进滚动区末尾时，只要键盘一弹
    // 或字段稍多，它就落到可视区外（曾经只剩一条缝），用户还得自己往上推。
    val resultText = if (testing) "正在测试…" else testResult.orEmpty()
    val footerContent: (@Composable ColumnScope.() -> Unit)? =
        if (resultText.isBlank()) null else { { TestResultBanner(resultText, testing, testResult) } }
    FormDialog(
        title = if (initial == null) "添加网络位置" else "编辑网络连接",
        onDismiss = onDismiss,
        spacing = 8.dp,
        footer = footerContent,
        confirmButton = {
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
                modifier = Modifier.height(48.dp).pressScale(saveInteraction),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text("保存", fontSize = 13.5.sp, maxLines = 1, softWrap = false)
            }
        }
    ) {
                if (formError != null) {
                    InlineNotice(formError.orEmpty(), MaterialTheme.colorScheme.error)
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
            // 测试 / 删除挪进内容区（原来是四个按钮排一行）。
            // 横屏弹出键盘后标题行只有「取消 / 保存」两个，才不会被挤成一条缝；
            // 这两个都是有实际动作但不该抢主操作位置的，各占半行即可。
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
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
                        text = "删除连接",
                        tone = DialogActionTone.DANGER,
                        modifier = Modifier.weight(1f),
                        onClick = onDelete
                    )
                }
            }
    }
}

/**
 * 连接测试结果条：绿=可用，红=失败，长报错最多 5 行（超出自身可滚动）。
 *
 * 由 [FormDialog] 的 footer 承载，固定在内容区下方，不随表单滚动。
 */
@Composable
private fun TestResultBanner(text: String, testing: Boolean, raw: String?) {
    val accent = when {
        testing -> MutedText
        raw?.startsWith("连接可用") == true -> semanticColors().online
        else -> MaterialTheme.colorScheme.error
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
            text,
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

