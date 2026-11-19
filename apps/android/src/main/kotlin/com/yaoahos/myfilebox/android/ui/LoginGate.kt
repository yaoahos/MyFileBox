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
internal fun LoginGate(state: MyFileBoxState, vm: AppViewModel) {
    val saved = remember { vm.savedLogin() }
    // 编辑连接时用当前会话的地址 / 账号预填，登录成功才替换，失败也不影响正在用的连接。
    var server by remember(state.session, state.connectionEditing) {
        mutableStateOf(state.session?.server ?: saved.first)
    }
    var account by remember(state.session, state.connectionEditing) {
        mutableStateOf(state.session?.account ?: saved.second)
    }
    var password by remember { mutableStateOf(saved.third) }
    var https by remember { mutableStateOf(true) }
    var remember by remember { mutableStateOf(true) }
    var allowSelfSigned by remember { mutableStateOf(true) }
    val loginInteraction = remember { MutableInteractionSource() }

    AppPullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().imePadding(),
            contentPadding = PaddingValues(bottom = BottomBarClearance)
        ) {
            item {
                PageTitle(
                    title = "群晖文件",
                    // 登录 / 编辑连接页还没进入任何群晖目录，「回到群晖首页」无从谈起，
                    // 放这里点了只会反复停在本页，干脆不显示。
                    trailing = { HeaderOverflow(vm, showHome = false) }
                )
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    AppBrandIcon(
                        modifier = Modifier.size(54.dp),
                        shape = RoundedCornerShape(17.dp),
                        contentDescription = "MyFileBox"
                    )
                    Text(
                        if (state.connectionEditing) "编辑连接" else "登录群晖 NAS",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = 7.dp)
                    )
                    Text(
                        if (state.connectionEditing) "修改后需要重新登录，取消则保持当前连接" else "安全访问你的私有云文件",
                        fontSize = 12.sp,
                        color = MutedText,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("服务器") },
                        shape = RoundedCornerShape(14.dp),
                        colors = dialogFieldColors()
                    )
                    // 明文只在用户关掉 HTTPS 时提示一次，不作为常驻说明。
                    if (!https) {
                        Text(
                            "关闭 HTTPS 后账号与密码将以明文传输，仅建议在可信内网使用",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("账号") },
                        shape = RoundedCornerShape(14.dp),
                        colors = dialogFieldColors()
                    )
                    Spacer(Modifier.height(7.dp))
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = "密码",
                        modifier = Modifier.fillMaxWidth(),
                        corner = 14.dp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("HTTPS", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        Switch(checked = https, onCheckedChange = { https = it })
                        Text("记住登录", fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
                        Switch(checked = remember, onCheckedChange = { remember = it })
                    }
                    // 清单 8（3.1.0）：恢复「允许自签名证书」选项，默认选中。
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("允许自签名证书", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        Switch(checked = allowSelfSigned, onCheckedChange = {
                            allowSelfSigned = it
                            vm.setAllowSelfSigned(it)
                        })
                    }
                    Spacer(Modifier.height(10.dp))
                    // 按钮与下方两行说明的间距：按钮是实心 46dp 高、文字行有 lineHeight
                    // 上下留白，同样的 dp 间距看起来不等（按钮→说明偏小、说明→说明偏大）。
                    // 按实测把上面一段加大、下面一段减小，让三段视觉行距一致。
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (state.connectionEditing) {
                                // 编辑连接只是回到登录页改参数，取消后原来的会话还在。
                                OutlinedButton(
                                    onClick = vm::cancelConnectionEditing,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(0.4f).height(48.dp)
                                ) { Text("取消", fontSize = 13.sp) }
                            }
                            Button(
                                // 没网时不发请求：群晖要依次试直连与中继，没网时每个通道都要
                                // 耗满超时，最后才给一句「已尝试 N 个通道均无法连接」——
                                // 用户真正要做的只是打开 Wi-Fi。
                                onClick = {
                                    // 表单没填全时先让校验说话——那些提示与有没有网无关，
                                    // 用「没联网」盖掉它，用户会去开 Wi-Fi 再回来，仍然登录不了。
                                    val filled = server.isNotBlank() && account.isNotBlank() && password.isNotBlank()
                                    val offline = if (filled) vm.offlineNotice() else null
                                    if (offline != null) vm.showMessage(offline)
                                    else vm.login(server, account, password, https, remember)
                                },
                                enabled = !state.loading,
                                interactionSource = loginInteraction,
                                modifier = Modifier.weight(1f).height(48.dp).pressScale(loginInteraction),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    when {
                                        state.loading -> "正在连接…"
                                        state.connectionEditing -> "保存并登录"
                                        else -> "登录"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        // 服务器支持什么单独做一行、放在按钮下方：原先塞进输入框的
                        // supportingText 会把「服务器」框撑得比账号/密码框高一截，三个框不齐。
                        Text(
                            "服务器可填写地址或 QuickConnect ID",
                            fontSize = 12.sp,
                            color = MutedText,
                            modifier = Modifier.padding(top = 14.dp)
                        )
                        // 登录失败原因常驻显示：Snackbar 一闪而过，用户根本看不清为什么连不上。
                        state.loginError?.takeIf { !state.loading }?.let { reason ->
                            Text(
                                reason,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Text(
                            "登录信息仅保存在本机，不会上传到第三方服务。",
                            fontSize = 12.sp,
                            color = MutedText,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

