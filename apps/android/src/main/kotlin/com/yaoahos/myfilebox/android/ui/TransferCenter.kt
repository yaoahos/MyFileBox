package com.yaoahos.myfilebox.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.android.TransferNotifications
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.yaoahos.myfilebox.android.ui.theme.semanticColors

private fun transferIcon(mode: TransferMode): ImageVector = when (mode) {
    TransferMode.DOWNLOAD -> Icons.Outlined.Download
    TransferMode.UPLOAD -> Icons.Outlined.Upload
    TransferMode.COPY -> Icons.Outlined.ContentCopy
    TransferMode.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
}

private fun transferModeLabel(mode: TransferMode): String = when (mode) {
    TransferMode.DOWNLOAD -> "下载"
    TransferMode.UPLOAD -> "上传"
    TransferMode.COPY -> "复制"
    TransferMode.MOVE -> "移动"
}

/** 操作动词：暂停/恢复按钮按操作类型叫「暂停上传」「恢复下载」等。 */private fun transferVerb(mode: TransferMode): String = when (mode) {
    TransferMode.DOWNLOAD -> "下载"
    TransferMode.UPLOAD -> "上传"
    TransferMode.COPY -> "复制"
    TransferMode.MOVE -> "移动"
}

private fun transferTime(epoch: Long): String =
    if (epoch <= 0L) "刚刚" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epoch))

/** 详情里的时间：卡片上的 `MM-dd HH:mm` 会被截断，这里给完整年月日。 */
private fun fullTime(epoch: Long): String =
    if (epoch <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epoch))

/**
 * 长路径 / 长文件名在窄屏上要能完整换行：路径本身没有空格，Compose 默认不会断行。
 * 在分隔符后和长连续段中间插入零宽空格，只影响排版，复制出来的仍是原字符串。
 */
private fun wrapLongPath(value: String): String {
    if (value.length <= 18) return value
    val out = StringBuilder(value.length + 16)
    var run = 0
    value.forEach { ch ->
        out.append(ch)
        run++
        if (ch == '/' || ch == '\\' || run >= 18) {
            out.append('\u200B')
            run = 0
        }
    }
    return out.toString()
}

/**
 * 传输中心：全屏页面（与设置页同款样式），左上角返回箭头，无右上角 X。
 * 进行中 / 记录 两栏样式一致，有内容时列表顶部对齐。
 */
@Composable
fun TransferCenter(state: MyFileBoxState, vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val visible = vm.transfers.filterNot { it.quiet }
    // 排队中的也算「进行中」：多选传输按条目拆成多条记录，还没轮到的显示「等待中」。
    val running = visible.filter {
        it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED ||
            it.status == TransferStatus.PAUSED
    }
    val history = visible.filter { it.status != TransferStatus.RUNNING && it.status != TransferStatus.PAUSED }
    var menuTask by remember { mutableStateOf<TransferTask?>(null) }
    var detailTask by remember { mutableStateOf<TransferTask?>(null) }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = 0) { 2 }
    val coroutineScope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) { tab = pagerState.currentPage }
    androidx.compose.runtime.LaunchedEffect(tab) {
        if (pagerState.currentPage != tab) pagerState.animateScrollToPage(tab)
    }

    // 返回由 PageHost 的 BackHandler 统一处理（closeTransferCenter 在 performBack 中）。

    // 全屏页面：与设置页同款（不透明底色 + SubPageHeader 返回箭头）。
    //
    // 顶部间距用稳定的状态栏高度，而不是 statusBarsPadding()：点记录里的文件会启动外部
    // 应用，MIUI 在 Activity 失焦那一瞬把 statusBars insets 算小，直接读 insets 会让整页
    // 上移约 40px、约 0.35 秒后再跳回来——真机录屏实测到的「打开文件界面往上跳」。
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = rememberStableStatusBarTop())
            .imePadding()
    ) {
        SubPageHeader("传输", vm::closeTransferCenter)

        // 副标题要如实反映状态：全部暂停时不能再写「正在传输」。
        val activeCount = running.count {
            it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED
        }
        val pausedCount = running.size - activeCount
        Text(
            when {
                activeCount > 0 && pausedCount > 0 -> "$activeCount 个任务正在传输 · $pausedCount 个已暂停"
                activeCount > 0 -> "$activeCount 个任务正在传输"
                pausedCount > 0 -> "$pausedCount 个任务已暂停"
                else -> "查看传输进度和记录"
            },
            fontSize = 13.sp,
            color = MutedText,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )

        // Tab 切换条：进行中 / 记录，样式与首页分栏一致。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TransferTab("进行中", running.size, pagerState.currentPage == 0, Modifier.weight(1f)) {
                coroutineScope.launch { pagerState.animateScrollToPage(0) }
            }
            TransferTab("记录", history.size, pagerState.currentPage == 1, Modifier.weight(1f)) {
                coroutineScope.launch { pagerState.animateScrollToPage(1) }
            }
        }

        if (pagerState.currentPage == 1 && history.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Outlined.History, null, modifier = Modifier.size(15.dp))
                    Text("清空记录", fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) { page ->
            val shown = if (page == 0) running else history
            if (shown.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 58.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        if (page == 0) Icons.Outlined.Upload else Icons.Outlined.History,
                        null,
                        tint = MutedText.copy(alpha = 0.55f),
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        if (page == 0) "没有正在进行的传输" else "还没有传输记录",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Text(
                        if (page == 0) "上传、下载的进度会在通知栏和这里同时显示"
                        else "完成的上传、下载会保留在这里，长按可管理",
                        fontSize = 13.sp,
                        color = MutedText,
                        modifier = Modifier.padding(top = 4.dp, start = 20.dp, end = 20.dp)
                    )
                }
            } else {
                // 有内容时列表靠顶部显示，不垂直居中；行距紧凑。
                LazyColumn(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(shown, key = { _, it -> it.id }) { index, task ->
                        TransferTaskCard(
                            task = task,
                            onClick = {
                                // 已完成的记录（单文件 / 文件夹 / 多选）都按「打开产物」处理：
                                // 文件夹直接跳到目标目录，单文件直接打开。只有未完成 / 失败
                                // 的记录才进详情，让用户看逐文件状态和失败原因。
                                if (task.status == TransferStatus.SUCCESS) vm.openTransferTask(task)
                                else detailTask = task
                            },
                            onLongPress = { menuTask = task },
                            onInfo = { detailTask = task }
                        )
                        // 记录之间留出间距：只靠卡片自身的圆角和边框，相邻两张看着仍然黏在一起。
                        if (index != shown.lastIndex) Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空传输记录") },
            text = { Text("确定清空所有传输记录吗？正在进行的传输不会被清除。") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearTransferHistory() }) {
                    Text("清空")
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
    val menu = menuTask
    if (menu != null) {
        TransferActionMenu(
            task = menu,
            onDismiss = { menuTask = null },
            onPause = {
                // 不可恢复操作也要确认——但暂停是可逆的，直接执行。
                vm.pauseTransfer(menu.id); menuTask = null
            },
            onDeleteAll = {
                vm.deleteTransferFile(menu); menuTask = null
            },
            onDeleteRecord = { vm.deleteTransferRecord(menu.id); menuTask = null },
            onResume = { vm.resumeTransfer(menu); menuTask = null },
            onRedownload = { vm.redownloadTransfer(menu); menuTask = null },
            onRetry = { vm.retryTransfer(menu); menuTask = null }
        )
    }
    // 详情弹窗必须读实时任务：子项暂停/恢复/提前后按钮与状态要立刻刷新，
    // 不能用打开那一刻的快照（否则点了「暂停」弹窗里还是显示旧状态）。
    val detailLive = detailTask?.let { snap -> vm.transfers.firstOrNull { it.id == snap.id } }
    detailLive?.let { detail ->
        // 端名统一从记录的 sourceCtx / targetCtx 推：群晖的 Backup 与 SMB 里的 Backup 同名，
        // 详情里不带端名就分不清这条记录的两端各是哪一端。
        fun endpointOf(ctx: String, net: String): String = when (ctx) {
            FileContext.SYNO.name -> "群晖 NAS"
            FileContext.NET.name -> vm.state.connections.firstOrNull { it.id == net }
                ?.let { "${it.protocol} · ${it.name}" } ?: "网络存储"
            FileContext.LOCAL.name -> "内部存储"
            else -> ""
        }
        TransferDetailDialog(
            task = detail,
            endpoint = endpointOf(detail.targetCtx, detail.targetNet),
            sourceEndpoint = endpointOf(detail.sourceCtx, detail.sourceNet),
            onDismiss = { detailTask = null },
            onRetryChild = { child -> vm.retryTransferChild(detail, child) },
            onPauseChild = { child -> vm.pauseTransferChild(detail, child) },
            onResumeChild = { child -> vm.resumeTransferChild(detail, child) },
            onPromoteChild = { child -> vm.promoteTransferChild(detail, child) },
            onRetryAll = { vm.retryTransfer(detail); detailTask = null },
            onOpen = { vm.openTransferTask(detail); detailTask = null }
        )
    }
}

/**
 * 传输详情：完整展示来源 / 目标路径（可复制）、状态、失败原因，以及每个子文件的状态。
 * 解决「从哪到哪显示不全、失败原因看不到、无法针对性重试」的问题。
 */
@Composable
private fun TransferDetailDialog(
    task: TransferTask,
    /** 目标所属的端：群晖 NAS / SMB · 服务器地址。列表卡片上不显示了，详情里必须保留。 */
    endpoint: String = "",
    /** 来源所属的端，口径同上。 */
    sourceEndpoint: String = "",
    onDismiss: () -> Unit,
    onRetryChild: (TransferChild) -> Unit,
    onPauseChild: (TransferChild) -> Unit,
    onResumeChild: (TransferChild) -> Unit,
    onPromoteChild: (TransferChild) -> Unit,
    onRetryAll: () -> Unit,
    onOpen: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var showChildren by remember { mutableStateOf(false) }
    val statusText = when (task.status) {
        TransferStatus.QUEUED -> "等待中"
        TransferStatus.RUNNING -> "传输中"
        TransferStatus.PAUSED -> "已暂停"
        TransferStatus.SUCCESS -> "已完成"
        TransferStatus.FAILED -> "失败"
        TransferStatus.CANCELLED -> "已取消"
        TransferStatus.MISSING -> "文件丢失"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text("传输详情", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            // 限高 + 可滚动：文件再多也不会把弹窗撑满屏幕，全部内容都能翻到。
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 标题不截断：名字再长也能在弹窗里滚动看完。
                Text(
                    wrapLongPath(task.title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
                Text(
                    "${transferModeLabel(task.mode)} · $statusText · ${task.completed}/${task.total} 项 · " +
                        "${TransferNotifications.formatBytes(task.completedBytes)} / ${TransferNotifications.formatBytes(task.totalBytes)}",
                    fontSize = 12.sp,
                    // 文件丢失 / 失败：整段状态行也染红，与列表卡片的状态色一致，
                    // 用户点开详情同样能一眼看出这条记录的产物已经不在了。
                    color = if (task.status == TransferStatus.MISSING || task.status == TransferStatus.FAILED)
                        MaterialTheme.colorScheme.error else MutedText,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                // 时间在详情里必须给全（yyyy-MM-dd HH:mm）：列表卡片上的时间一行放不下
                // 会被截断，这里要能看到完整时间。
                Text(
                    if (task.status == TransferStatus.RUNNING) "开始于 ${fullTime(task.startedAt)}"
                    else "完成于 ${fullTime(task.finishedAt ?: task.startedAt)}",
                    fontSize = 12.sp,
                    color = MutedText,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                // 来源 / 目标用带底色与图标的标签，和路径正文分离，避免「从/到」被读进路径。
                // 老记录或远端根目录可能没存到路径，退回用源路径 / 落地路径兜底，
                // 免得详情里只剩一个「—」，看不出这次传的到底是什么、传到哪。
                EndpointChip(
                    icon = Icons.Outlined.Download,
                    label = "来源",
                    tint = MaterialTheme.colorScheme.primary,
                    // 与「目标」同一套规则：单条目给完整路径（含文件名 / 文件夹名），
                    // 多选逐条列出（文件按文件、文件夹按文件夹，各带名字）。
                    value = transferSourcePathsOf(task).let { paths ->
                        if (paths.isEmpty()) task.from.ifBlank { "—" }
                        else listOfNotNull(sourceEndpoint.takeIf { it.isNotBlank() }, paths.joinToString("\n"))
                            .joinToString(" · ")
                    },
                    clipboard = clipboard
                )
                EndpointChip(
                    icon = Icons.Outlined.Upload,
                    label = "目标",
                    tint = semanticColors().success,
                    // 路径规则与「来源」一致（见 transferTargetPathsOf）：
                    // 单文件 / 单文件夹给产物本身的完整路径，多选逐条给各自的落地路径。
                    value = transferTargetPathsOf(task).let { paths ->
                        if (paths.isEmpty()) "—"
                        else listOfNotNull(endpoint.takeIf { it.isNotBlank() }, paths.joinToString("\n"))
                            .joinToString(" · ")
                    },
                    clipboard = clipboard
                )

                if (!task.error.isNullOrBlank()) {
                    Text(
                        task.error,
                        fontSize = 12.sp,
                        color = if (task.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error else MutedText,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }

                if (task.children.isNotEmpty()) {
                    val ok = task.children.count { it.status == TransferChildStatus.SUCCESS }
                    val failed = task.children.count { it.status == TransferChildStatus.FAILED }
                    val pausedN = task.children.count { it.status == TransferChildStatus.PAUSED }
                    val foldLabel = buildString {
                        append("文件明细（").append(ok).append('/').append(task.children.size).append(" 完成")
                        if (failed > 0) append(" · ").append(failed).append(" 失败")
                        if (pausedN > 0) append(" · ").append(pausedN).append(" 暂停")
                        append("）")
                    }
                    FoldHeader(
                        expanded = showChildren,
                        label = foldLabel,
                        onClick = { showChildren = !showChildren }
                    )
                    if (showChildren) {
                        Column(Modifier.fillMaxWidth()) {
                            task.children.forEach { child ->
                                TransferChildRow(
                                    child, task.status,
                                    onRetry = { onRetryChild(child) },
                                    onPause = { onPauseChild(child) },
                                    onResume = { onResumeChild(child) },
                                    onPromote = { onPromoteChild(child) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if ((task.status == TransferStatus.FAILED || task.status == TransferStatus.PAUSED) &&
                    task.children.isEmpty()
                ) {
                    TextButton(onClick = onRetryAll) { Text("重试全部") }
                }
                if (task.status == TransferStatus.SUCCESS) {
                    TextButton(onClick = onOpen) { Text("打开") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

/** 带图标与底色的「来源 / 目标」标签行，路径单独占一行可点复制。 */
@Composable
private fun EndpointChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    value: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { clipboard.setText(AnnotatedString(value)) }
            .background(tint.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint, modifier = Modifier.padding(start = 5.dp))
            Icon(
                Icons.Outlined.ContentCopy, "复制路径", tint = tint.copy(alpha = 0.7f),
                modifier = Modifier.size(12.dp).padding(start = 4.dp)
            )
        }
        Text(
            // 多条路径用换行分开（多选时来源 / 目标各条各占一行），逐行做长路径断行。
            value.split("\n").joinToString("\n") { wrapLongPath(it) },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun FoldHeader(expanded: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedText, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            if (expanded) "收起" else "展开",
            tint = MutedText,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun TransferChildRow(
    child: TransferChild,
    taskStatus: TransferStatus,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPromote: () -> Unit
) {
    val (label, color, icon) = when (child.status) {
        TransferChildStatus.SUCCESS -> Triple("完成", semanticColors().success, Icons.Outlined.CheckCircle)
        TransferChildStatus.FAILED -> Triple("失败", MaterialTheme.colorScheme.error, Icons.Outlined.ErrorOutline)
        TransferChildStatus.RUNNING -> Triple("传输中", MaterialTheme.colorScheme.primary, Icons.Outlined.Schedule)
        TransferChildStatus.PAUSED -> Triple("已暂停", semanticColors().warning, Icons.Outlined.Pause)
        TransferChildStatus.QUEUED -> Triple("等待中", MutedText, Icons.Outlined.Schedule)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            // 展示完整相对路径：长名字靠零宽空格换行，不再用省略号截掉。
            Text(wrapLongPath(child.name), fontSize = 12.sp, lineHeight = 16.sp)
            val detail = buildString {
                append(label)
                if (child.sizeBytes > 0L) append(" · ${TransferNotifications.formatBytes(child.completedBytes)}/${TransferNotifications.formatBytes(child.sizeBytes)}")
                if (!child.error.isNullOrBlank()) append(" · ${child.error}")
            }
            Text(
                wrapLongPath(detail),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = if (child.status == TransferChildStatus.FAILED) MaterialTheme.colorScheme.error else MutedText
            )
        }
        // 单文件操作：仅下载任务、且任务仍在运行时才有意义（结束后的暂停/提前不可操作）。
        if (taskStatus == TransferStatus.RUNNING) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (child.status) {
                    TransferChildStatus.FAILED -> ChildActionButton("重试", MaterialTheme.colorScheme.error, onRetry)
                    TransferChildStatus.RUNNING -> ChildActionButton("暂停", MaterialTheme.colorScheme.primary, onPause)
                    TransferChildStatus.QUEUED -> {
                        ChildActionButton("暂停", MaterialTheme.colorScheme.primary, onPause)
                        ChildActionButton("开始", semanticColors().success, onPromote)
                    }
                    TransferChildStatus.PAUSED -> ChildActionButton("恢复", semanticColors().success, onResume)
                    else -> Unit
                }
            }
        }
    }
}

/** 子项操作按钮：统一高度与最小宽度，浅色底 + 语义色文字，避免多个 TextButton 挤成一片。 */
@Composable
private fun ChildActionButton(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

/**
 * 长按传输任务弹出的操作菜单。
 * 进行中：暂停 / 删除任务（删记录+文件）。
 * 暂停：恢复 / 删除记录（保留文件）/ 取消传输（删记录+文件，含半成品与空目录）。
 * 记录：删除记录 / 删除文件 / 重新下载（仅文件丢失）/ 重试（失败或暂停）。
 * 所有不可恢复操作（删除记录、删除文件、取消传输）均弹窗确认。
 */
@Composable
private fun TransferActionMenu(
    task: TransferTask,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteRecord: () -> Unit,
    onResume: () -> Unit,
    onRedownload: () -> Unit,
    onRetry: () -> Unit
) {
    // 确认弹窗：删除文件（不可恢复）
    var confirmDeleteFile by remember { mutableStateOf(false) }
    // 确认弹窗：删除记录（不可恢复）
    var confirmDeleteRecord by remember { mutableStateOf(false) }
    // 确认弹窗：删除进行中任务（含取消+删文件）
    var confirmDeleteRunning by remember { mutableStateOf(false) }

    if (confirmDeleteFile) {
        AlertDialog(
            onDismissRequest = { confirmDeleteFile = false },
            title = { Text("删除文件") },
            text = {
                Text(
                    when (task.mode) {
                        TransferMode.UPLOAD ->
                            "确定删除「${task.title}」的传输记录和远端已上传文件吗？本机源文件不会被删除。"
                        TransferMode.DOWNLOAD ->
                            "确定删除「${task.title}」的已下载文件和传输记录吗？此操作不可撤销。"
                        else ->
                            "确定删除「${task.title}」的传输记录吗？此操作不可撤销。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteFile = false; onDeleteAll() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFile = false }) { Text("取消") } }
        )
        return
    }
    if (confirmDeleteRecord) {
        AlertDialog(
            onDismissRequest = { confirmDeleteRecord = false },
            title = { Text("删除记录") },
            text = {
                Text(
                    if (task.status == TransferStatus.PAUSED) {
                        if (task.mode == TransferMode.UPLOAD) {
                            "确定删除「${task.title}」的传输记录吗？远端已上传的文件会保留，此操作不可撤销。"
                        } else {
                            "确定删除「${task.title}」的传输记录吗？已下载的文件会保留在目标位置，此操作不可撤销。"
                        }
                    } else {
                        "确定删除「${task.title}」的传输记录吗？此操作不可撤销。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteRecord = false; onDeleteRecord() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteRecord = false }) { Text("取消") } }
        )
        return
    }
    if (confirmDeleteRunning) {
        // 暂停任务走「取消传输」语义：删除记录并清理已下载的文件（含 .part 与空目录）。
        val cancelling = task.status == TransferStatus.PAUSED
        AlertDialog(
            onDismissRequest = { confirmDeleteRunning = false },
            title = { Text(if (cancelling) "取消传输" else "删除任务") },
            text = {
                Text(
                    if (cancelling) {
                        "确定取消「${task.title}」吗？传输记录和已下载（含未完成）的文件都会被删除，此操作不可撤销。"
                    } else {
                        "确定删除「${task.title}」的传输任务吗？已传文件和记录都会被删除，此操作不可撤销。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteRunning = false; onDeleteAll() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteRunning = false }) { Text("取消") } }
        )
        return
    }

    val verb = transferVerb(task.mode)
    // 只有群晖下载能真正断点续传（进度留在 .part 里）。FTP / SFTP / SMB / WebDAV
    // 暂停会当场清掉半截文件，恢复是从这个文件的开头重来；上传则一律整文件重传。
    // 文案必须跟着实际行为走，否则用户会以为「保留进度」而实际上从 0 开始。
    val resumable = task.mode == TransferMode.DOWNLOAD && task.sourceCtx == FileContext.SYNO.name
    // 「暂停 / 重新下载」这类次要操作用主题色；删除、恢复继续用语义色（红 / 绿），
    // 它们表达的是危险与成功，不该跟着主题改变含义。
    val accent = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Text("传输操作", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(task.title, fontSize = 13.sp, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (task.status == TransferStatus.QUEUED) {
                    ActionItem(
                        Icons.Outlined.Delete, "从队列移除",
                        "这条还没开始，移除后不再传输", MaterialTheme.colorScheme.error
                    ) { confirmDeleteRecord = true }
                } else if (task.status == TransferStatus.RUNNING) {
                    ActionItem(
                        Icons.Outlined.Pause, "暂停$verb",
                        if (resumable) "保留进度，可随时恢复"
                        else "已传完的部分保留，未传完的文件从头开始",
                        accent, onPause
                    )
                    ActionItem(
                        Icons.Outlined.Delete, "删除任务",
                        if (task.mode == TransferMode.UPLOAD) "停止上传并删除记录和远端文件" else "取消${verb}并删除记录和文件",
                        MaterialTheme.colorScheme.error
                    ) { confirmDeleteRunning = true }
                } else if (task.status == TransferStatus.PAUSED) {
                    ActionItem(
                        Icons.Outlined.PlayArrow, "恢复${verb}",
                        if (resumable) "从暂停处继续" else "未完成的文件从头开始",
                        semanticColors().success, onResume
                    )
                    ActionItem(
                        Icons.Outlined.DeleteOutline, "删除记录",
                        if (task.mode == TransferMode.UPLOAD) "保留远端已上传文件，仅从列表移除"
                        else "保留已下载文件，仅从列表移除",
                        semanticColors().warning
                    ) { confirmDeleteRecord = true }
                    ActionItem(
                        Icons.Outlined.Delete, "取消传输",
                        if (task.mode == TransferMode.UPLOAD) "停止上传并删除记录和远端已上传文件"
                        else "删除记录和已下载文件（含未完成部分）",
                        MaterialTheme.colorScheme.error
                    ) { confirmDeleteRunning = true }
                } else {
                    if (task.status == TransferStatus.MISSING) {
                        ActionItem(Icons.Outlined.Refresh, "重新下载", "从原位置重新下载文件", accent, onRedownload)
                    }
                    if (task.status == TransferStatus.FAILED) {
                        ActionItem(
                            Icons.Outlined.Refresh, "重试${verb}",
                            "重新发起这次${verb}，可只重试失败的子文件", accent, onRetry
                        )
                    }
                    ActionItem(
                        Icons.Outlined.DeleteOutline, "删除记录",
                        if (task.mode == TransferMode.UPLOAD) "仅从列表移除，保留远端已上传文件"
                        else "仅从列表移除，保留已下载文件",
                        semanticColors().warning
                    ) { confirmDeleteRecord = true }
                    // 下载删本机文件，上传删远端文件——两种都需要「记录 + 文件」一起清掉，
                    // 否则远端会留下用户以为已经删掉的文件。
                    if (task.mode == TransferMode.DOWNLOAD || task.mode == TransferMode.UPLOAD) {
                        ActionItem(
                            Icons.Outlined.Delete, "删除文件",
                            if (task.mode == TransferMode.UPLOAD) "同时删除记录和远端已上传文件" else "同时删除记录和已下载文件",
                            MaterialTheme.colorScheme.error
                        ) { confirmDeleteFile = true }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, desc: String, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, fontSize = 12.sp, color = MutedText)
        }
    }
}

@Composable
private fun TransferTab(label: String, count: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.primary else MutedText
    Row(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg)
        if (count > 0) {
            Text(
                " $count",
                fontSize = 13.sp,
                color = fg.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

/**
 * 一条传输记录 / 进行中的任务。
 * 进行中与记录使用同一张卡片样式；暂停状态用橙色区分（非灰色取消色）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransferTaskCard(
    task: TransferTask,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onInfo: () -> Unit
) {
    val statusColor = when (task.status) {
        TransferStatus.QUEUED -> MutedText
        TransferStatus.RUNNING -> MaterialTheme.colorScheme.primary
        // 暂停用橙色，不以取消的灰色显示。
        TransferStatus.PAUSED -> semanticColors().warning
        TransferStatus.SUCCESS -> semanticColors().success
        TransferStatus.FAILED -> MaterialTheme.colorScheme.error
        TransferStatus.CANCELLED -> MutedText
        // 文件丢失 / 目标被删是失败状态，必须是红色而非橙色警告，
        // 用户点开记录才能一眼看出这条的产物已经不在了。
        TransferStatus.MISSING -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        // 记录与记录之间靠这条细边框分隔：只靠卡片底色太淡，连续看下来是一片灰。
        border = BorderStroke(1.dp, MutedText.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 列表卡片只留「多大、什么时候」，操作类型与来源 / 目标目录全部收进详情：
            // 一行里塞「上传 → SMB · 服务器地址 · /共享目录/文件」必然被截断。
            // 文件夹 / 多选不显示总体积：几千个文件的合计数对「认出这条记录」没有帮助，
            // 详情里「已传 / 总量」是准确数字；单文件的大小本身就是这条的关键信息，保留。
            val isFolderish = task.folderTask || task.children.isNotEmpty() || task.total > 1
            val meta = buildString {
                if (task.totalBytes > 0L && !isFolderish) {
                    append(TransferNotifications.formatBytes(task.totalBytes))
                }
                if (task.total > 1) {
                    if (isNotEmpty()) append(" · ")
                    append("${task.total} 项")
                }
                if (isNotEmpty()) append(" · ")
                append(transferTime(task.finishedAt ?: task.startedAt))
            }
            // 图标（左）、标题 + 信息（中）、状态与详情（右）共用同一行居中。
            // 标题与信息行是同一个 Column 里紧贴的两行：不再各占一行加外边距，
            // 两者间距只由 Text 自身行高决定。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(transferIcon(task.mode), null, tint = statusColor, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        wrapLongPath(task.title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                        // 标题只占一行：两行会把卡片撑高、把「大小 · 时间」挤到视线之外，
                        // 完整名字进详情看。
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        meta,
                        fontSize = 12.sp,
                        color = MutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    when (task.status) {
                        TransferStatus.QUEUED -> "等待中"
                        TransferStatus.RUNNING -> "传输中"
                        TransferStatus.PAUSED -> "已暂停"
                        TransferStatus.SUCCESS -> "已完成"
                        TransferStatus.FAILED -> "失败"
                        TransferStatus.CANCELLED -> "已取消"
                        TransferStatus.MISSING -> "文件丢失"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                // 来源、目标、文件数量和逐文件状态统一放进详情，列表只留一眼要看的内容。
                // 触控区放大到 48dp（图标本身仍是 18dp），与关闭按钮口径一致。
                IconButton(onClick = onInfo, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.Info, "查看详情",
                        tint = MutedText, modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (task.status == TransferStatus.RUNNING || task.status == TransferStatus.PAUSED) {
                if (task.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                        color = statusColor,
                        trackColor = statusColor.copy(alpha = 0.12f)
                    )
                    Text(
                        "${TransferNotifications.formatBytes(task.completedBytes)} / " +
                            TransferNotifications.formatBytes(task.totalBytes),
                        fontSize = 12.sp,
                        color = MutedText,
                        modifier = Modifier.padding(start = 42.dp, top = 3.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                        color = statusColor,
                        trackColor = statusColor.copy(alpha = 0.12f)
                    )
                    Text(
                        task.detail.ifBlank { "正在传输" },
                        fontSize = 12.sp,
                        color = MutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 42.dp, top = 3.dp)
                    )
                }
            } else if ((task.status == TransferStatus.FAILED || task.status == TransferStatus.CANCELLED) && task.error != null) {
                Text(
                    task.error,
                    fontSize = 12.sp,
                    color = if (task.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error else MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 42.dp, top = 2.dp)
                )
            }
        }
    }
}
