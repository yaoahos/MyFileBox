package com.yaoahos.myfilebox.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.android.TransferNotifications
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/** 操作动词：暂停/恢复按钮按操作类型叫「暂停上传」「恢复下载」等。 */
private fun transferVerb(mode: TransferMode): String = when (mode) {
    TransferMode.DOWNLOAD -> "下载"
    TransferMode.UPLOAD -> "上传"
    TransferMode.COPY -> "复制"
    TransferMode.MOVE -> "移动"
}

private fun transferTime(epoch: Long): String =
    if (epoch <= 0L) "刚刚" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epoch))

/** 折叠态路径：只保留末尾两级，保证「从 → 到」两端都能一眼看到。 */
private fun shortLoc(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length <= 24) return trimmed
    val parts = trimmed.split('/', '\\').filter { it.isNotBlank() }
    val tail = if (parts.size >= 2) parts.takeLast(2).joinToString("/") else parts.lastOrNull().orEmpty()
    if (tail.isBlank()) return trimmed.takeLast(24)
    return if (parts.size >= 3) "…/$tail" else tail
}

/**
 * 传输中心：全屏页面（与设置页同款样式），左上角返回箭头，无右上角 X。
 * 进行中 / 记录 两栏样式一致，有内容时列表顶部对齐。
 */
@Composable
fun TransferCenter(state: MyFileBoxState, vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val visible = state.transfers.filterNot { it.quiet }
    val running = visible.filter { it.status == TransferStatus.RUNNING || it.status == TransferStatus.PAUSED }
    val history = visible.filter { it.status != TransferStatus.RUNNING && it.status != TransferStatus.PAUSED }
    var menuTask by remember { mutableStateOf<TransferTask?>(null) }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = 0) { 2 }
    val coroutineScope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) { tab = pagerState.currentPage }
    androidx.compose.runtime.LaunchedEffect(tab) {
        if (pagerState.currentPage != tab) pagerState.animateScrollToPage(tab)
    }

    // 返回由 PageHost 的 BackHandler 统一处理（closeTransferCenter 在 performBack 中）。

    // 全屏页面：与设置页同款（不透明底色 + SubPageHeader 返回箭头）。
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding()
    ) {
        SubPageHeader("传输", vm::closeTransferCenter)

        // 副标题要如实反映状态：全部暂停时不能再写「正在传输」。
        val activeCount = running.count { it.status == TransferStatus.RUNNING }
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
                    items(shown, key = { it.id }) { task ->
                        TransferTaskCard(
                            task = task,
                            onClick = { vm.openTransferTask(task) },
                            onLongPress = { menuTask = task }
                        )
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
            onRedownload = { vm.redownloadTransfer(menu); menuTask = null }
        )
    }
}

/**
 * 长按传输任务弹出的操作菜单。
 * 进行中：暂停 / 删除（删记录+文件）。取消传输已去除，暂停保留进度。
 * 记录：删除记录 / 删除记录和文件 / 恢复（仅已暂停）/ 重新下载（仅文件丢失）。
 * 所有不可恢复操作（删除记录、删除文件）均弹窗确认。
 */
@Composable
private fun TransferActionMenu(
    task: TransferTask,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteRecord: () -> Unit,
    onResume: () -> Unit,
    onRedownload: () -> Unit
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
                TextButton(onClick = { confirmDeleteFile = false; onDeleteAll() }) { Text("删除", color = Color(0xFFE25C5C)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFile = false }) { Text("取消") } }
        )
        return
    }
    if (confirmDeleteRecord) {
        AlertDialog(
            onDismissRequest = { confirmDeleteRecord = false },
            title = { Text("删除记录") },
            text = { Text("确定删除「${task.title}」的传输记录吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteRecord = false; onDeleteRecord() }) { Text("删除", color = Color(0xFFE25C5C)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteRecord = false }) { Text("取消") } }
        )
        return
    }
    if (confirmDeleteRunning) {
        AlertDialog(
            onDismissRequest = { confirmDeleteRunning = false },
            title = { Text("删除任务") },
            text = { Text("确定删除「${task.title}」的传输任务吗？已传文件和记录都会被删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteRunning = false; onDeleteAll() }) { Text("删除", color = Color(0xFFE25C5C)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteRunning = false }) { Text("取消") } }
        )
        return
    }

    val verb = transferVerb(task.mode)
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
                if (task.status == TransferStatus.RUNNING) {
                    ActionItem(Icons.Outlined.Pause, "暂停$verb", "保留进度，可随时恢复", accent, onPause)
                    ActionItem(
                        Icons.Outlined.Delete, "删除任务",
                        if (task.mode == TransferMode.UPLOAD) "停止上传并删除记录和远端文件" else "取消${verb}并删除记录和文件",
                        Color(0xFFE25C5C)
                    ) { confirmDeleteRunning = true }
                } else if (task.status == TransferStatus.PAUSED) {
                    ActionItem(Icons.Outlined.PlayArrow, "恢复${verb}", "从暂停处继续", Color(0xFF15A46B), onResume)
                    ActionItem(Icons.Outlined.Delete, "删除任务", "删除记录和已传文件", Color(0xFFE25C5C)) { confirmDeleteRunning = true }
                } else {
                    if (task.status == TransferStatus.MISSING) {
                        ActionItem(Icons.Outlined.Refresh, "重新下载", "从原位置重新下载文件", accent, onRedownload)
                    }
                    ActionItem(Icons.Outlined.DeleteOutline, "删除记录", "只删除记录，保留文件", Color(0xFFE67E22)) { confirmDeleteRecord = true }
                    // 下载删本机文件，上传删远端文件——两种都需要「记录 + 文件」一起清掉，
                    // 否则远端会留下用户以为已经删掉的文件。
                    if (task.mode == TransferMode.DOWNLOAD || task.mode == TransferMode.UPLOAD) {
                        ActionItem(
                            Icons.Outlined.Delete, "删除文件",
                            if (task.mode == TransferMode.UPLOAD) "同时删除记录和远端已上传文件" else "同时删除记录和已下载文件",
                            Color(0xFFE25C5C)
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
    onLongPress: () -> Unit
) {
    val statusColor = when (task.status) {
        TransferStatus.RUNNING -> MaterialTheme.colorScheme.primary
        // 暂停用橙色，不以取消的灰色显示。
        TransferStatus.PAUSED -> Color(0xFFE67E22)
        TransferStatus.SUCCESS -> Color(0xFF15A46B)
        TransferStatus.FAILED -> MaterialTheme.colorScheme.error
        TransferStatus.CANCELLED -> MutedText
        TransferStatus.MISSING -> Color(0xFFE67E22)
    }
    var expanded by remember(task.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
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
                Text(
                    task.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp)
                )
                Text(
                    when (task.status) {
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
            }

            val meta = buildString {
                append(transferModeLabel(task.mode))
                if (task.totalBytes > 0L) {
                    append(" · ")
                    append(TransferNotifications.formatBytes(task.totalBytes))
                }
                if (task.total > 1) append(" · ${task.total} 项")
                append(" · ")
                append(transferTime(task.finishedAt ?: task.startedAt))
            }
            Text(
                meta,
                fontSize = 12.sp,
                color = MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 42.dp, top = 1.dp)
            )

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
                            TransferNotifications.formatBytes(task.totalBytes) +
                            if (task.total > 1) " · ${task.completed}/${task.total} 项" else "",
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
                        "${task.completed} / ${task.total} 项 · ${task.detail}",
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 42.dp, top = 2.dp)
                )
            }

            if (task.from.isNotBlank() || task.to.isNotBlank()) {
                val from = task.from.ifBlank { "—" }
                val to = task.to.ifBlank { "—" }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 42.dp, top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (expanded) {
                        Column(Modifier.weight(1f)) {
                            PathLine("从", from)
                            PathLine("到", to)
                        }
                    } else {
                        Text(
                            "${shortLoc(from)}  →  ${shortLoc(to)}",
                            fontSize = 11.sp,
                            color = MutedText.copy(alpha = 0.95f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        if (expanded) "收起路径" else "展开路径",
                        tint = MutedText,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PathLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 11.sp, color = MutedText, modifier = Modifier.width(16.dp))
        Text(
            value,
            fontSize = 11.sp,
            color = MutedText.copy(alpha = 0.95f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
