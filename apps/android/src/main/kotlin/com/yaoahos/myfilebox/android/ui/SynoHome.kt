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
import kotlin.math.abs
import com.yaoahos.myfilebox.android.ui.theme.semanticColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SynoHome(state: MyFileBoxState, vm: AppViewModel, onVolume: () -> Unit) {
    var showAccount by remember { mutableStateOf(false) }
    var confirmDeleteSyno by remember { mutableStateOf(false) }
    val sharesByKey = state.shares.associateBy { it.key }
    val modules = state.prefs.moduleOrder
        .filter { it.startsWith("syno:") && vm.moduleVisible(it) }
    // 冷启动/静默登录后共享列表可能还没拉到：进到群晖首页发现是空的就自动刷一次，
    // 不用用户手动下拉刷新才看到文件夹。
    LaunchedEffect(state.session?.sid) {
        if (state.session != null && state.shares.isEmpty()) vm.refreshSyno()
    }
    AppPullToRefreshBox(
        isRefreshing = state.refreshing,
        // 只拉共享与卷（并行），不再连带全盘扫描和连接探活。
        onRefresh = vm::refreshSynoTab,
        modifier = Modifier.fillMaxSize()
    ) {
        val shareCards = vm.sortedFolders(state.shares.map {
            FolderCard(
                name = it.title,
                meta = "",
                kind = FileKind.FOLDER,
                path = it.key,
                date = it.subtitle,
                count = 0
            )
        }).filter {
            itemMatchesFilter(it.name, true, state.nameFilter, state.extensionFilter)
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().pullRefreshArea()) {
        // 顶栏固定（3.9.16）：共享文件夹多时也能随时点搜索 / 账号 / 更多。
        PageTitle(
            title = "群晖文件",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 传输只在「更多」里出现一次（清单 3）。
                    HeaderIcon(Icons.Outlined.Search, "搜索") { vm.openSearch(FileContext.SYNO) }
                    HeaderIcon(Icons.Outlined.Person, "账号") { showAccount = true }
                    HeaderOverflow(vm, FileContext.SYNO, showFilter = true)
                }
            }
        )
        // 群晖首页原来也没有设备状态说明：飞行模式下 WiFi 单独打开时，用户只看到
        // 过期的共享列表，连不上还要等满超时。这一行只说状态，不拦内网请求。
        val deviceNotice = vm.networkNotice()
        DeviceNoticeRow(deviceNotice)
        // 进共享文件夹要先重新解析通道再登录，切网后实测 7~8 秒；这段时间首页原本
        // 毫无变化，用户只能反复点。openShare 会在 1 秒没结果时挂上 connectHint，
        // 这里把它说出来，并给一个能点的重试。
        //
        // 设备状态已经在上面的常驻行说清时不再挂这条：两行同一句话，用户看到的是
        // 「飞行模式已开启…」重复两遍；何况这种状态下点重试必然还是失败。
        if (state.connectHint && deviceNotice == null) {
            ConnectHintBar(
                state, vm,
                waitingText = "正在连接群晖…",
                failedText = "网络已变化，请下拉刷新",
                onRetry = vm::refreshSynoTab
            )
        }
        LazyColumn(
            state = LocalPageListState.current,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = BottomBarClearance)
        ) {
            if (state.nameFilter.isNotBlank() || state.extensionFilter.isNotBlank()) {
                item { ActiveFilterBar(vm, matched = shareCards.size, total = state.shares.size) }
            }
            modules.forEach { key ->
                when (key) {
                    "syno:storage" -> item { NasCard(state.session, vm.volume(), onVolume) }
                    "syno:shares" -> {
                        item {
                            SectionTitle("文件夹", trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HomeSortButton(vm, "syno")
                                    SectionViewToggle(state, vm, "synoShares")
                                }
                            })
                        }
                        item {
                            val openShare: (FolderCard) -> Unit = { card ->
                                sharesByKey[card.path]?.let(vm::openShare)
                            }
                            // 共享文件夹同理：筛没了要说清楚是筛没了。
                            if (shareCards.isEmpty() &&
                                (state.nameFilter.isNotBlank() || state.extensionFilter.isNotBlank())
                            ) {
                                FilterEmptyHint(vm)
                            } else if (vm.viewMode(TabKey.SYNO) == "grid") {
                                FolderGrid(shareCards, onClick = openShare, showCount = false)
                            } else {
                                FolderList(shareCards, onClick = openShare, showCount = false)
                            }
                        }
                    }
                }
            }
            if (modules.isEmpty()) {
                item { EmptyModules("群晖首页显示已全部隐藏") }
            }
        }
        }
    }
    if (showAccount && state.session != null) {
        ModalBottomSheet(
            onDismissRequest = { showAccount = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp).imePadding()) {
                Text("群晖账号", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "${state.session.account}@${state.session.server}",
                    fontSize = 12.sp,
                    color = MutedText,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(16.dp))
                FolderSheetAction(Icons.Outlined.Edit, "编辑连接", onClick = {
                    showAccount = false
                    vm.openEditSyno()
                })
                FolderSheetAction(Icons.Outlined.Logout, "退出登录", onClick = {
                    showAccount = false
                    vm.logout()
                }, danger = true)
                // 退出登录会把密码留着方便下次登录；不想再用这台 NAS、或者只想清掉
                // 保存在本机的凭据时，得有「删除连接」这条路（原来没有，只能一直留着）。
                FolderSheetAction(Icons.Outlined.Delete, "删除连接", onClick = {
                    showAccount = false
                    confirmDeleteSyno = true
                }, danger = true)
            }
        }
    }
    if (confirmDeleteSyno) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSyno = false },
            title = { Text("删除群晖连接", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将清除本机保存的服务器地址、账号与密码，不会影响群晖上的文件。\n" +
                        "之后需要重新输入完整连接信息才能登录。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSyno = false
                    vm.deleteSynoConnection()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSyno = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun NasCard(session: Session?, volume: Volume?, onVolume: () -> Unit) {
    // 整块渐变都由主题色派生：写死深蓝起笔会让用户选了红 / 绿之后，
    // 卡片变成「深蓝 → 蓝 → 红」的三段杂色。
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(accent.shade(0.72f), accent.shade(0.34f), accent)
                )
            )
            .padding(18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Cloud, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text("PRIVATE CLOUD  ·  在线", color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp, letterSpacing = 1.2.sp)
                    Text("${session?.name ?: "Leo"} 的私有云", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    onClick = onVolume,
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.14f)
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(volume?.name ?: "存储空间 1", color = Color.White, fontSize = 12.sp)
                        Icon(Icons.Outlined.ChevronRight, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
                    }
                }
            }
            Text("${session?.server ?: "192.168.1.100:5001"} · HTTPS", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.Bottom) {
                Text("%.2f TB".format(volume?.usedTb ?: 2.42), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
                Text(" / %.0f TB".format(volume?.totalTb ?: 4.0), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
                Spacer(Modifier.weight(1f))
                Text("${volume?.percent ?: 60}% 已用", color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth((volume?.percent ?: 60) / 100f).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.92f)))
            }
            Text("DSM 7.2   ·   12 ms   ·   ${volume?.type ?: "SHR"}   ·   ${stateCountLabel(volume)}", color = Color.White.copy(alpha = 0.52f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun stateCountLabel(volume: Volume?): String = if (volume?.id == "v2") "4 个共享文件夹" else "6 个共享文件夹"

/**
 * 设备联网状态说明行：网络首页与群晖首页共用。
 *
 * 纯展示、不可点击，不会吞掉列表首行的点击。飞行模式开着但把 WiFi 单独打开、
 * 或 WiFi 连着却上不了外网时也要显示——这两种情况 activeNetwork 还在，
 * 原来的 offlineNotice 返回 null，用户只看到过期的「● 在线」卡片和超时。
 *
 * 状态查询必须由调用方在 item lambda 之外算好传进来：LazyColumn 的 item 有自己的
 * 重组作用域，参数只有不变的 vm 时会被跳过，网络回调后就一直停在首次的 null。
 */
@Composable
internal fun DeviceNoticeRow(notice: String?) {
    val text = notice ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Wifi, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * 列表页上的连接等待提示：请求超过 1 秒还没有结果时挂出，可点重试、可关闭。
 *
 * 网络位置与群晖首页共用一个实现。两边都出现过「点了没反应、用户反复点」，而卡片和
 * 文件夹的状态不能改（在线就是在线，进目录只是读一次），所以只能另起一条说明在等什么。
 */
@Composable
internal fun ConnectHintBar(
    state: MyFileBoxState,
    vm: AppViewModel,
    waitingText: String,
    failedText: String,
    onRetry: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .pressClickable(onRetry)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Wifi, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            // 设备状态由调用方先过滤（非空时不显示本条），这里只谈请求本身。
            if (state.loading) waitingText else failedText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
        IconButton(onClick = vm::dismissNetworkHint, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Outlined.Close, "关闭提示", tint = MutedText, modifier = Modifier.size(14.dp))
        }
    }
}

