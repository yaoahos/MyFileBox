package com.yaoahos.myfilebox.android.ui

import android.app.Activity
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.yaoahos.myfilebox.android.data.LocalRepository
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import com.yaoahos.myfilebox.android.ui.theme.MyFileBoxTheme
import com.yaoahos.myfilebox.core.FileEntry
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import com.yaoahos.myfilebox.core.FileKind
import java.io.File
import com.yaoahos.myfilebox.core.ThemeMode
import com.yaoahos.myfilebox.core.TabKey
import com.yaoahos.myfilebox.core.effectiveDefaultTab
import com.yaoahos.myfilebox.android.ui.theme.semanticColors

@Composable
fun MyFileBoxApp(viewModel: AppViewModel) {
    val state = viewModel.state
    val dark = when (state.prefs.theme.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(dark) {
        val activity = context as? Activity
        if (activity != null) {
            val window = activity.window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
        onDispose { }
    }
    // BottomSheet / Dialog 会另起窗口覆盖 appearance，再 apply 一次以覆盖它们。
    LaunchedEffect(dark) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkLocalAccess()
                // 用户可能刚在浏览器 / 微信里下载了文件；回前台要重新扫一遍，
                // 否则首页分类和列表还停在旧快照。
                viewModel.refreshLocalOnResume()
                // 从「安装未知应用」授权页返回后，继续安装刚才点的 APK。
                viewModel.consumePendingInstall()
                // 切网后系统回调不一定送达（MIUI 上实测会漏），回前台再自检一次：
                // 端点还停在内网地址、而手机已经不在内网，就立刻换通道重连。
                viewModel.healSynoChannelOnForeground()
            }
            if (event == Lifecycle.Event.ON_STOP) {
                // 用户在「正在打开」期间直接退出 / 按 Home 离开：取消下载并清掉
                // 半截缓存，否则下次进来看不到进度，缓存目录却堆着无人认领的临时文件。
                viewModel.cancelOpeningOnExit()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 权限顺序：先弹系统的通知权限（系统级弹窗），再处理文件权限（直跳系统授权页，
    // 不再自绘引导框；只在安装后第一次自动跳）。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.afterNotificationPermission() }
    LaunchedEffect(Unit) {
        viewModel.runStartupPermissionFlow {
            runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    // 所有成功 / 失败 / 普通提示统一走这个顶部浮层：不黑底、不占位、不打断操作。
    val notice = state.message
    // 「打开方式」里选「用 MyFileBox 解压」后要弹的解压位置对话框：
    // 放在根组合里，任何入口（首页 / 目录 / 搜索结果）都能复用同一个对话框。
    var extractTarget by remember { mutableStateOf<FileEntry?>(null) }
    // 记住了「始终用自带解压」时，点压缩包不再经过「打开方式」，由 ViewModel 直接抛来
    // 待解压文件；这里接住并立刻清空，重组时不会反复弹窗。
    LaunchedEffect(state.extractRequest) {
        state.extractRequest?.let {
            extractTarget = it
            viewModel.consumeExtractRequest()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(noticeDurationMs(notice))
            viewModel.consumeMessage(notice)
        }
    }

    // 启动页口径：返回链的最后一站是「有效默认启动页」，不是固定的第一栏。
    // 用户在设置里改了默认启动页后，从任意页面一路返回应停在那一栏，再按一次才退出。
    val homeTab = effectiveDefaultTab(state.prefs)
    // 首页筛选中不算「可退出的首页」：此时返回键要先清掉筛选（见 performBack），
    // 否则 canGoBack=false 会让系统直接退出到桌面，筛选条件还留着。
    val homeFilterOn = state.nameFilter.isNotBlank() || state.extensionFilter.isNotBlank()
    val atMainHome = state.screen == Screen.MAIN && state.browser == null && state.search == null &&
        !state.recycleOpen && !state.transferCenterOpen && state.transfer == null &&
        state.fileSheet == null && state.preview == null && state.openWith == null &&
        state.pendingDelete.isEmpty() && state.pendingDeleteConnection == null && !state.homeSelecting &&
        !state.shareManagerOpen && !homeFilterOn
    val exitToDesktop = atMainHome && !state.connectionEditing && state.tab == homeTab
    // 系统返回键与左边缘手势返回共用同一个动作，保证优先级完全一致、不会出现两种返回行为。
    // 分支顺序严格对齐 pageKeyOf：返回永远作用于「当前显示的页面」，绝不能被
    // 显示在下层的状态抢走（3.9.18 修「设置页偶发要侧滑两次才能退出」的根因：
    // 从文件夹页进设置时 state.browser 还留着，旧顺序第一次返回先退看不见的
    // 浏览器栈，页面毫无变化，第二次才真正退出设置）。
    val performBack: () -> Unit = {
        when {
            // —— 浮在任何页面之上的弹层最先响应 ——
            state.pendingDelete.isNotEmpty() || state.pendingDeleteConnection != null -> viewModel.cancelDelete()
            state.preview != null -> viewModel.closePreview()
            state.openWith != null -> viewModel.closeOpenWith()
            state.transfer != null -> viewModel.closeTransfer()
            // —— 以下与 pageKeyOf 的判定顺序一致 ——
            // 系统页（设置 / 外观 / 关于 / 备份同步）排第一：pageKeyOf 里它们优先级最高，
            // 返回也必须先作用在看得见的这一页上。否则从回收站 / 搜索 / 传输进设置后，
            // 第一次返回会先把下层那个看不见的覆盖页关掉，页面纹丝不动，要按两次。
            state.screen != Screen.MAIN -> viewModel.closeScreen()
            // 覆盖页按「后打开的盖住先打开的」排序：传输最上，其次回收、共享、搜索。
            // 必须与 pageKeyOf 完全一致，否则第一次返回会去关下层那个看不见的页面。
            state.transferCenterOpen -> viewModel.closeTransferCenter()
            // 回收站多选态先退出选择，再关页面（清单 6）。
            state.recycleOpen && state.recycleSelecting -> viewModel.clearRecycleSelection()
            // 群晖回收站钻进了 #recycle 子目录：返回先回上一层，与左上角箭头同一条路径。
            state.recycleOpen && state.recycleDir.isNotBlank() -> viewModel.backRecycleFolder()
            state.recycleOpen -> viewModel.closeRecycle()
            state.shareManagerOpen -> viewModel.closeShareManager()
            state.search != null -> viewModel.closeSearch()
            // 浏览页多选：与 BrowserScreen 内部的 BackHandler 对齐，先清多选再退页面。
            state.browser != null && (state.selectionActive || state.selected.isNotEmpty()) -> viewModel.clearSelection()
            state.browser != null -> viewModel.backFromBrowser()
            // 首页筛选中：返回先清筛选，而不是直接退出 / 回第一栏。
            // 必须放在 browser 分支之后——浏览页自己的返回由 backFromBrowser 清筛选。
            state.screen == Screen.MAIN && homeFilterOn -> viewModel.clearNameFilter()
            // 编辑登录：没保存就返回时回到群晖文件首页，不退到桌面（清单 11）。
            state.connectionEditing -> viewModel.cancelConnectionEditing()
            // 首页多选时返回键先退出选择态，不要直接退出应用。
            state.homeSelecting -> viewModel.clearHomeSelection()
            state.screen == Screen.MAIN && state.screenStack.isNotEmpty() -> viewModel.closeScreen()
            // 其它栏首页：回有效默认启动页（设置里可改，不再固定回第一栏）。
            else -> viewModel.backToHomeTab()
        }
    }
    // 系统返回键 / Android 13+ 预测式返回统一由 PageHost 内的 PredictiveBackHandler 接管，
    // 这里不再注册第二个 BackHandler，避免两套返回拦截互相抢事件、动画分裂。
    val pageKey = pageKeyOf(state)
    val pageSnapshots = remember { LinkedHashMap<String, MyFileBoxState>() }
    pageSnapshots[pageKey] = state
    while (pageSnapshots.size > 8) pageSnapshots.remove(pageSnapshots.keys.firstOrNull())

    // staticCompositionLocalOf 的 value 一变就重组全部读取点；每次组合都新建这个 lambda
    // 会让任意 state.copy 都重建所有 FileThumb。按 viewModel 记住实例即可。
    val thumbLoader: RemoteThumbLoader = remember(viewModel) { { file -> viewModel.remoteThumbnail(file) } }
    MyFileBoxTheme(
        accent = state.prefs.theme.accent,
        dynamic = state.prefs.theme.dynamic,
        iconFollowsTheme = state.prefs.theme.iconFollowsTheme,
        darkTheme = dark
    ) {
        // 桌面启动图标要按「当前真正在用的主色」分档：动态取色时主色来自壁纸，
        // 只有主题确定之后才拿得到。换壁纸 / 改主题色 / 开关变化都会触发；
        // 前台只登记目标，退到后台才切换组件，多次改色会合并成最后一次。
        val primary = MaterialTheme.colorScheme.primary
        LaunchedEffect(primary, state.prefs.theme.iconFollowsTheme) {
            viewModel.syncAppIcon(primary.toArgb())
        }
        CompositionLocalProvider(LocalRemoteThumbLoader provides thumbLoader) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0)
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                // 页面与底栏共用整屏宽度。此前这里套了一层 widthIn(max = 900.dp) 的
                // 居中容器，平板 / 横屏上两侧会露出大块 background 色留白，且底栏被
                // 一起限宽成一条居中的实心横条；现在改为铺满，留白与横条一并消失。
                // 软件内所有页面共用这唯一一个宿主、唯一一套转场。
                // 不区分底栏 / 首页 / 里层 / 覆盖页，一律按「页面」处理。
                PageHost(
                    pageKey = pageKey,
                    canGoBack = !exitToDesktop,
                    onBack = performBack,
                    snapshots = pageSnapshots,
                    liveState = state,
                    vm = viewModel
                )
                // 固定底栏：画在页面宿主之外，不参与任何页面水平位移。
                // 宽度由 FixedBottomBar 内部收敛为居中胶囊，宽屏也不会拉成横贯色块；
                // 切换底栏时只有选中态瞬时更新，这一整块区域始终原地不动；
                // 进出非首页页面用极短的淡入淡出，避免底部出现空白闪一下。
                AnimatedVisibility(
                    visible = isHomePageKey(pageKey),
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(120)),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                ) {
                    FixedBottomBar(state, viewModel, state.tab)
                }
                // 顶部浮层：进度类与提示类互斥共用一个位置，永远是「一条通知」。
                //
                // 之前两者放同一个 Column 垂直堆叠：点文件打开时进度条出现、提示条
                // 同时淡出，AnimatedVisibility 退出的 140ms 里提示条仍占位，动画一结束
                // 提示条被移除，进度条瞬间上移一截——用户看到的就是「界面往上跳一下」。
                // 改成互斥后顶部永远只有一个浮层、位置恒定，不再互相推挤。
                //
                // 本地耗时任务（删除 / 还原 / 压缩 / 解压 / 粘贴）原本画在底部，
                // 同一屏上下两条浮层各说各的；现在统一收进顶部通知位，优先级：
                // 正在打开 > 本地任务 > 普通提示。
                val opening = state.openingFile
                val taskLabel = state.localTaskLabel.takeIf { state.localTaskRunning && it.isNotBlank() }
                when {
                    opening != null -> OpeningFileBar(
                        // 在线打开大文件时的可见进度：下载在后台进行，没有这条进度条
                        // 用户会以为「点了没反应」而反复点击、甚至直接退出。带取消按钮，
                        // 点取消即中断下载并清掉缓存的半截文件。
                        opening = opening,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp)
                            .padding(top = 6.dp),
                        onCancel = { viewModel.cancelOpening() }
                    )
                    taskLabel != null -> TaskBanner(
                        label = taskLabel,
                        onCancel = if (viewModel.isLocalTaskCancellable()) {
                            ({ viewModel.cancelLocalTask() })
                        } else null,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp)
                            .padding(top = 6.dp)
                    )
                    else -> NoticeBanner(
                        text = notice,
                        onDismiss = { notice?.let(viewModel::consumeMessage) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp)
                            .padding(top = 6.dp)
                    )
                }
            }
        }
        if (state.transfer != null) TransferSheet(state, viewModel)
        if (state.pendingDelete.isNotEmpty()) {
            val deleteCtx = state.browser?.ctx
            // 群晖删除是「移到 #recycle」，可以在「回收」里还原；网络位置（SMB/FTP/WebDAV）才是真删除。
            // 文案必须区分，旧实现一律写「永久删除」，用户根本不知道还能找回。
            val deleteText = when (deleteCtx) {
                FileContext.SYNO ->
                    "确定删除「${state.pendingDeleteLabel}」吗？文件会移入群晖回收站，可在「回收」里还原。"
                FileContext.NET ->
                    "确定删除「${state.pendingDeleteLabel}」吗？网络位置的文件会被直接删除，无法恢复。"
                else ->
                    "确定删除「${state.pendingDeleteLabel}」吗？本机文件会放入回收站，可从回收站恢复。"
            }
            AlertDialog(
                onDismissRequest = viewModel::cancelDelete,
                title = { Text("删除确认") },
                text = { Text(deleteText) },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("取消") } }
            )
        }
        val pendingConnection = state.pendingDeleteConnection?.let { id -> state.connections.firstOrNull { it.id == id } }
        if (pendingConnection != null) {
            AlertDialog(
                onDismissRequest = viewModel::cancelDelete,
                title = { Text("删除网络位置") },
                text = { Text("确定删除「${pendingConnection.name}」吗？只会删除 App 中保存的连接配置，不会删除服务器文件。") },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmDeleteConnection) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("取消") } }
            )
        }
        state.preview?.let { file ->
            PreviewDialog(file) { viewModel.closePreview() }
        }
        state.detailFile?.let { file ->
            FileDetailDialog(file, vm = viewModel, onDismiss = viewModel::closeDetail)
        }
        state.installFile?.let { file ->
            AlertDialog(
                onDismissRequest = viewModel::dismissInstallPrompt,
                title = { Text("需要安装权限", fontWeight = FontWeight.Bold) },
                text = {
                    Text("安装「${file.name}」需要允许 MyFileBox 安装未知来源应用。是否前往系统设置开启？")
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 关掉自己的弹窗，再跳到系统授权页；回来时自动继续安装。
                        viewModel.requestInstallPermission()
                    }) { Text("去开启") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissInstallPrompt) { Text("取消") }
                }
            )
        }
        state.updateInfo?.let { info ->
            // LocalUriHandler 是 @Composable 读取，必须在组合作用域里取，不能写进 onClick。
            val updateUriHandler = LocalUriHandler.current
            AlertDialog(
                onDismissRequest = viewModel::dismissUpdateDialog,
                title = { Text("发现新版本 ${info.version}", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        Modifier
                            // 统一口径：按「屏高 - 键盘预留」算，横屏不会被 380dp 顶出屏幕。
                            .heightIn(max = rememberDialogContentMaxHeight())
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Release 正文原样搬来时带 `**` 标题、`- ` 列表符和噪声行，必须过
                        // conciseNotes 再逐条渲染，否则 markdown 记号直接露在弹窗里
                        // （与「更新日志」弹窗表现也不一致）。
                        val notes = com.yaoahos.myfilebox.android.update.conciseNotes(info.notes)
                        if (notes.isEmpty()) {
                            Text(
                                "本次更新主要是问题修复与稳定性优化。",
                                fontSize = 12.sp,
                                color = MutedText,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            notes.forEach { line ->
                                Text(
                                    "· $line",
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        if (info.apkUrl.isBlank()) {
                            Text(
                                "这条发布没有附带安装文件，请到发布页手动下载。",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MutedText,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                        if (state.updateDownloading) {
                            Text(
                                "下载中 ${state.updateProgress}%（可在通知栏查看进度）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            LinearProgressIndicator(
                                progress = { state.updateProgress.coerceIn(0, 100) / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .height(5.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            )
                        }
                        if (state.updateError != null) {
                            Text(
                                state.updateError.orEmpty(),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    if (info.apkUrl.isBlank()) {
                        // 这条发布没有附带 .apk 资产：按钮以前是静默 disabled，
                        // 用户看到「发现新版本」却点不动、也没有任何说明。
                        // 改成直接去发布页，路走得通。
                        TextButton(
                            enabled = !state.updateDownloading,
                            onClick = {
                                runCatching {
                                    updateUriHandler.openUri(
                                        info.pageUrl.ifBlank {
                                            com.yaoahos.myfilebox.android.update.GitHubUpdater.RELEASES_PAGE
                                        }
                                    )
                                }
                            }
                        ) { Text("打开发布页") }
                    } else {
                        TextButton(
                            enabled = !state.updateDownloading,
                            onClick = viewModel::downloadUpdate
                        ) { Text(if (state.updateDownloading) "下载中…" else "立即更新") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissUpdateDialog) { Text("稍后") }
                }
            )
        }
        if (state.updateLogOpen) {
            val uriHandler = LocalUriHandler.current
            AlertDialog(
                onDismissRequest = viewModel::closeUpdateLog,
                title = { Text("更新日志", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        Modifier
                            // 同上：横屏屏高只有 360dp 上下，写死 420dp 会把标题和按钮顶出屏幕。
                            .heightIn(max = rememberDialogContentMaxHeight())
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (state.updateLogNotes.isEmpty()) {
                            Text(
                                if (state.updateLogLoading) "正在获取更新日志…" else "暂无更新日志。",
                                fontSize = 12.sp,
                                color = MutedText
                            )
                        }
                        state.updateLogNotes.forEachIndexed { index, note ->
                            // 除第一个版本外，每个版本前插一条分隔线（规范 5.3）。
                            if (index > 0) {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                            // 版本标题：日期 + 四个空格 + v版本号（规范 5.2）。
                            val date = note.date.trim()
                            val ver = note.version.trim()
                            Text(
                                when {
                                    date.isNotEmpty() && ver.isNotEmpty() -> "$date    v$ver"
                                    date.isNotEmpty() -> date
                                    ver.isNotEmpty() -> "v$ver"
                                    else -> "更新"
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            // 条目：「· 」前缀。Markdown 记号、与标题重复的日期行、发布信息
                            // 全部在 conciseNotes 里统一清掉（与 SafePassword 同一套规则），
                            // 远端 Release 正文原样搬来时也不会露出 `**` 与 `- `。
                            val entries = com.yaoahos.myfilebox.android.update.conciseNotes(note.notes)
                            if (entries.isEmpty()) {
                                Text(
                                    "问题修复与稳定性优化。",
                                    fontSize = 12.sp,
                                    color = MutedText,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            } else {
                                entries.forEach { line ->
                                    Text(
                                        "· $line",
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        modifier = Modifier.padding(top = 3.dp)
                                    )
                                }
                            }
                        }
                        // 列表末尾入口：更早的版本去 Releases 页看（规范 5.5）。
                        if (state.updateLogNotes.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        uriHandler.openUri(com.yaoahos.myfilebox.android.update.GitHubUpdater.RELEASES_PAGE)
                                    }
                                }
                            ) { Text("查看更早的版本") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = viewModel::closeUpdateLog) { Text("关闭") } }
            )
        }
        state.openWith?.let { file ->
            OpenWithDialog(
                file = file,
                apps = state.openWithApps,
                loading = state.openWithLoading,
                // 自带解压只对本地压缩包显示：远端文件还挂在协议上，
                // 解压要先下载，走的是另一条链路。
                canExtract = viewModel.canExtractLocally(file),
                onDismiss = viewModel::closeOpenWith,
                onLaunch = { viewModel.launchWith(it) },
                onLaunchAlways = { viewModel.launchWith(it, rememberForType = true) },
                onExtract = {
                    viewModel.closeOpenWith()
                    extractTarget = file
                },
                onExtractAlways = { viewModel.extractAlways(file) }
            )
        }
        extractTarget?.let { target ->
            ExtractDialog(
                rootPath = state.localRoot,
                listSubfolders = viewModel::downloadSubfolders,
                onConfirm = { dest ->
                    extractTarget = null
                    viewModel.extractArchive(target, dest)
                },
                onDismiss = { extractTarget = null }
            )
        }
        // 解压前发现同名文件：等用户选覆盖 / 跳过 / 两份都留。
        state.extractConflict?.let { prompt ->
            ExtractConflictDialog(
                prompt = prompt,
                onChoose = { viewModel.confirmExtractConflict(it) },
                onDismiss = { viewModel.cancelExtractConflict() }
            )
        }
        // 加密 7z：先要密码，再按普通流程扫冲突并解压。
        state.extractPassword?.let { prompt ->
            ExtractPasswordDialog(
                prompt = prompt,
                onConfirm = { viewModel.confirmExtractPassword(it) },
                onDismiss = { viewModel.cancelExtractPassword() }
            )
        }
        }
    }
}

@Composable
private fun FileDetailDialog(file: FileEntry, vm: AppViewModel, onDismiss: () -> Unit) {
    // 弹窗是独立窗口，页面顶部的「已复制」提示会被挡在弹窗下方看不见。
    // 复制反馈放在弹窗内部（标题旁），几秒后自动消失。
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("文件详情", fontWeight = FontWeight.Bold)
                if (copied) {
                    Text(
                        "已复制",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    file.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 18.sp
                )
                DetailRow("类型", if (file.isFolder) "文件夹" else fileKindLabel(file.kind))
                DetailRow("大小", if (file.isFolder) "${file.childCount} 项" else file.sizeLabel.ifBlank { "—" })
                // 详情里日期与时间都给全：列表为了省地方只显示其中一段，
                // 到了详情页再省就查不清到底是哪个时间点。
                DetailRow("修改时间", if (file.modified > 0) LocalRepository.formatFull(file.modified) else file.timeLabel.ifBlank { "—" })
                DetailRow("所在位置", file.folder.ifBlank { "内部存储" })
                // 路径行整行可点：复制反馈在弹窗内给（顶部提示会被弹窗挡住）。
                DetailRow("完整路径", file.path, onClick = {
                    vm.copyToClipboard(file.path, "路径")
                    copied = true
                })
                HorizontalDivider(
                    Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
                // 传输规则默认折叠，点一下展开、再点收回，与上面路径行同样的强调色文字。
                var rulesExpanded by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { rulesExpanded = !rulesExpanded }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "传输规则",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (rulesExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                }
                if (rulesExpanded) {
                    Text(
                        "同一端内可以复制、移动后到目标文件夹粘贴；本地与群晖 / 网络之间请用上传或下载。",
                        fontSize = 12.sp,
                        color = MutedText,
                        lineHeight = 17.sp
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun DetailRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            // 可复制行整行都是热区，不只是文字本身——长路径换行后也点得中。
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick) else Modifier)
            .padding(vertical = 5.dp)
    ) {
        Text(label, fontSize = 12.sp, color = MutedText, modifier = Modifier.widthIn(min = 64.dp))
        Text(
            value.ifBlank { "—" },
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).padding(start = 10.dp)
        )
    }
}

private val textPreviewExt = setOf("txt", "md", "json", "xml", "csv", "log")

/** core 中文档类枚举为 DOCUMENT；纯文本预览以扩展名白名单为准。 */
private fun isTextFile(file: FileEntry): Boolean =
    file.name.substringAfterLast('.', "").lowercase() in textPreviewExt

@Composable
private fun PreviewDialog(file: FileEntry, onClose: () -> Unit) {
    val isLocalFile = remember(file.path) {
        runCatching { File(file.path).isFile }.getOrDefault(false)
    }
    val textPreview = isLocalFile && isTextFile(file)
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("文件预览", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    // 触控区放大到 48dp（图标本身仍是 18dp），避免误点空白。
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, "关闭", modifier = Modifier.size(18.dp))
                    }
                }
                if (textPreview) {
                    TextPreview(file.path)
                } else {
                    val plate = if (file.kind == FileKind.VIDEO) Color(0xFF0E1116) else fileTint(file.kind).first
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .size(220.dp)
                            .background(plate, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            file.kind == FileKind.VIDEO -> AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoPath(file.path)
                                        val controller = MediaController(ctx)
                                        controller.setAnchorView(this)
                                        setMediaController(controller)
                                        setOnPreparedListener { it.isLooping = false; start() }
                                    }
                                },
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
                            )
                            file.kind == FileKind.AUDIO -> AudioPreview(file.path)
                            file.kind == FileKind.IMAGE -> FileThumb(file, 220.dp)
                            else -> Icon(
                                fileIcon(file.kind),
                                null,
                                tint = fileTint(file.kind).second,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }
                }
                Text(
                    file.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    "${file.sizeLabel} · ${file.timeLabel} · ${file.folder}",
                    fontSize = 12.sp,
                    color = MutedText,
                    modifier = Modifier.padding(top = 3.dp)
                )
                if (!isLocalFile) {
                    Text(
                        "远程文件会先下载到本机缓存再预览。",
                        fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TextPreview(path: String) {
    var text by remember(path) { mutableStateOf<String?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val buf = ByteArray(64 * 1024)
                val len = File(path).inputStream().use { it.read(buf) }
                if (len <= 0) "" else String(buf, 0, len, Charsets.UTF_8)
            }.getOrNull()
        }
        if (result == null) {
            failed = true
        } else {
            val lines = result.lines()
            text = lines.take(400).joinToString("\n") +
                if (lines.size > 400) "\n…（仅显示前 400 行）" else ""
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .size(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(12.dp)
    ) {
        when {
            failed -> Text(
                "无法读取该文本文件",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
            text == null -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp).align(Alignment.Center),
                strokeWidth = 2.dp
            )
            else -> Text(
                text.orEmpty(),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun AudioPreview(path: String) {
    var playing by remember(path) { mutableStateOf(true) }
    val player = remember(path) { MediaPlayer() }
    DisposableEffect(path) {
        runCatching {
            player.setDataSource(path)
            player.prepare()
            player.start()
        }
        onDispose { runCatching { player.release() } }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Outlined.Audiotrack,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(52.dp)
        )
        IconButton(
            onClick = {
                runCatching {
                    if (playing) player.pause() else player.start()
                    playing = !playing
                }
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            if (playing) {
                Text("暂停", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    "播放",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Text(if (playing) "正在播放" else "已暂停", fontSize = 12.sp, color = MutedText)
    }
}

/**
 * 应用图标缓存 + 异步加载。
 *
 * 「打开方式」弹窗里每个应用一行图标。图标要从 PackageManager 取 Drawable 再缩放成
 * Bitmap，纯同步做在主线程上，弹窗刚出现那一帧就要多花几十毫秒——表现为弹窗「先卡住
 * 再出现」。这里改成：能命中缓存就立即用，没命中先给占位、在 IO 线程解好再补。
 */
private object AppIconCache {
    private val lru = object : android.util.LruCache<String, ImageBitmap>(64) {}
    fun get(pkg: String): ImageBitmap? = lru.get(pkg)
    fun put(pkg: String, icon: ImageBitmap) = lru.put(pkg, icon)
}

@Composable
private fun rememberAppIcon(pkg: String): ImageBitmap? {
    val pm = LocalContext.current.packageManager
    var icon by remember(pkg) { mutableStateOf(AppIconCache.get(pkg)) }
    LaunchedEffect(pkg) {
        AppIconCache.get(pkg)?.let { icon = it; return@LaunchedEffect }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                pm.getApplicationIcon(pkg).toBitmap(38, 38).asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) {
            AppIconCache.put(pkg, loaded)
            icon = loaded
        }
    }
    return icon
}

@Composable
private fun OpenWithDialog(
    file: FileEntry,
    apps: List<OpenApp>,
    loading: Boolean,
    canExtract: Boolean,
    onDismiss: () -> Unit,
    onLaunch: (String) -> Unit,
    onLaunchAlways: (String) -> Unit,
    onExtract: () -> Unit,
    onExtractAlways: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开方式", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Icon(
                        if (file.kind == FileKind.AUDIO) Icons.Outlined.Audiotrack else Icons.Outlined.VideoFile,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        file.name,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (canExtract) {
                    // 自带解压排在已安装应用前面：压缩包的首要动作通常是解开，
                    // 而系统列表里往往只有一个「文件」应用，点进去还要再绕一圈。
                    // 行样式与应用行一致，右侧同样有「始终」星标——用户不用再猜
                    // 「为什么这个能记住、那个不能」。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onExtract)
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Unarchive,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Text(
                            "用 MyFileBox 解压",
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        )
                        IconButton(
                            onClick = onExtractAlways,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.StarBorder,
                                "始终用 MyFileBox 解压",
                                tint = MutedText,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                if (loading) {
                    // 刚打开时列表还在查（包管理器要扫一遍安装包），先给个转圈而不是空列表。
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在查找可用应用…", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(start = 10.dp))
                    }
                } else if (apps.isEmpty()) {
                    Text(
                        "没有找到能直接打开该文件的应用，可以试试下面的系统选择器。",
                        fontSize = 12.sp,
                        color = MutedText,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                Text(
                    "点应用 = 仅本次打开；点 ☆ = 始终用它打开。设置后可在「设置 → 默认打开方式」里改回来。",
                    fontSize = 11.sp,
                    color = MutedText,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                // 高度放宽到 420dp：只声明通配的万能查看器（WPS 等）排在具体应用之后，
                // 320dp 时代它们会被挤出可视区，用户以为「列表里没有 WPS」。
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    apps.forEach { app ->
                        // 图标异步取：原来在组合里同步 getApplicationIcon().toBitmap()，
                        // 每多一个应用就多一次解码 + 一次缩放，弹窗刚出来的那一帧全压在主线程上
                        // （实测最差单帧 117ms，其中测量布局就占 48ms）。现在先画占位，
                        // 解好再补上，弹窗动画期间不再有这段同步开销。
                        val icon = rememberAppIcon(app.pkg)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onLaunch(app.pkg) }
                                .padding(vertical = 7.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (icon != null) {
                                Image(icon, null, modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)))
                            } else {
                                Box(
                                    Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.Apps, null, tint = MutedText, modifier = Modifier.size(18.dp))
                                }
                            }
                            Text(
                                app.label,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 12.dp)
                            )
                            // 「仅本次」与「始终」都是点一下就开：点应用开一次，点右侧 ☆ 记住并开。
                            // 行尾不再挂一个「打开」字样 —— 它既不可单独点，又让人以为还要再点一次。
                            IconButton(
                                onClick = { onLaunchAlways(app.pkg) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.StarBorder,
                                    "始终用 ${app.label} 打开",
                                    tint = MutedText,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                // 小米 / 红米等机型第一次用某个应用打开时，系统会弹「启动应用，是否允许」。
                // 这是 MIUI 的链式启动拦截，不是应用报错，先说明一句，免得用户以为是故障。
                val miui = android.os.Build.MANUFACTURER.contains("xiaomi", true) ||
                    android.os.Build.MANUFACTURER.contains("redmi", true) ||
                    android.os.Build.BRAND.contains("xiaomi", true) ||
                    android.os.Build.BRAND.contains("redmi", true) ||
                    android.os.Build.BRAND.contains("poco", true)
                if (miui && apps.isNotEmpty()) {
                    Text(
                        "首次用某个应用打开时，系统可能询问「启动应用」，选择「始终允许」后不再提示。",
                        fontSize = 11.sp,
                        color = MutedText,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onLaunch("") }
                        .padding(vertical = 9.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Apps, null, tint = MutedText, modifier = Modifier.size(18.dp))
                    Text(
                        "用系统选择器打开",
                        fontSize = 13.sp,
                        color = MutedText,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

enum class NoticeTone { INFO, SUCCESS, WARN, ERROR }

private const val NOTICE_BASE_MS = 1_200L
private const val NOTICE_ERROR_BASE_MS = 2_400L
private const val NOTICE_EXTRA_PER_CHAR_MS = 35L
private const val NOTICE_EXTRA_MAX_MS = 1_600L

/**
 * 顶部提示停留时长。原先 2/3.5 秒底 + 每字 60ms（上限 4 秒）会让一句普通
 * 成功提示挂 2.6 秒、长错误提示接近 7 秒，挡住内容又不好手动关。
 * 现在普通提示约 1.5~2.8 秒，错误约 2.7~4 秒，长句仍有额外阅读时间。
 */
internal fun noticeDurationMs(text: String): Long {
    val base = if (noticeTone(text) == NoticeTone.ERROR) NOTICE_ERROR_BASE_MS else NOTICE_BASE_MS
    val extra = (text.length * NOTICE_EXTRA_PER_CHAR_MS).coerceAtMost(NOTICE_EXTRA_MAX_MS)
    return base + extra
}

/**
 * 文案分类只影响颜色和停留时长，不改变任何业务逻辑。
 * 失败类优先匹配，避免「已删除 0 项，操作失败」被判成成功。
 */
internal fun noticeTone(text: String): NoticeTone = when {
    listOf(
        "失败", "错误", "无法", "不能", "未找到", "未读取", "请先", "请填写", "请检查",
        "已过期", "不存在", "超时", "不可用", "拒绝", "离线", "未登录", "密码错误",
        "不支持", "仅支持", "没有可打开", "没有应用",
        // 传输记录点开时目标文件 / 文件夹已被删：必须判红，否则「…已被移动或删除」
        // 会被下面的 startsWith("已") 误判成绿色成功色，用户看不出是失败。
        "已被移动或删除", "已删除", "已丢失", "找不到该"
    ).any { text.contains(it) } -> NoticeTone.ERROR
    text.contains("成功") || text.contains("完成") || text.contains("可用") ||
        text.startsWith("已") -> NoticeTone.SUCCESS
    text.contains("注意") || text.contains("建议") -> NoticeTone.WARN
    else -> NoticeTone.INFO
}

/**
 * 顶部提示浮层：圆角卡片 + 状态色图标，宽度受限、自动消失、点一下也能关。
 * 它只是叠在内容上方的一小块，不整屏遮罩，所以不影响继续操作。
 */
/** 在线打开大文件的进度条：显示文件名 + 进度 + 取消按钮，避免用户以为卡住而反复点击 / 退出。 */
@Composable
private fun OpeningFileBar(opening: OpeningFile, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val fraction = if (opening.hasByteProgress && opening.totalBytes > 0L)
        (opening.completedBytes.toFloat() / opening.totalBytes.toFloat()).coerceIn(0f, 1f)
    else -1f
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        ),
        modifier = modifier.widthIn(max = 560.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "正在打开「${opening.name}」",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 9.dp).weight(1f)
                )
                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("取消", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (fraction >= 0f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                )
                Text(
                    "${LocalRepository.formatSize(opening.completedBytes)} / " +
                        LocalRepository.formatSize(opening.totalBytes),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                )
                Text(
                    "正在缓存到本机，请稍候…",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

/**
 * 进行中的本地任务（删除 / 还原 / 压缩 / 解压 / 粘贴）。
 *
 * 结构、圆角、阴影、内边距与 [NoticeBanner] 完全一致——它就是「顶部通知」的
 * 另一种状态；只把强调色从提示条的 primary/error 换成 tertiary，让「还在跑」
 * 和「跑完了」在同一个位置上一眼可分。左侧转圈代替提示条的静态图标。
 */
@Composable
private fun TaskBanner(label: String, onCancel: (() -> Unit)?, modifier: Modifier = Modifier) {
    // 短操作（删几个小文件）几十毫秒就结束，进度条一闪而过反而像界面抽了一下。
    // 延迟 300ms 再出现：期间操作若已完成，这个组件会被移出组合，用户什么也看不到。
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(140)),
        modifier = modifier
    ) {
        val accent = MaterialTheme.colorScheme.tertiary
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                }
                Text(
                    label,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = 10.dp)
                )
                if (onCancel != null) {
                    TextButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("取消", fontSize = 12.sp, color = accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var lineCount by remember { mutableStateOf(1) }
    LaunchedEffect(text) {
        if (text != null) {
            shown = text
            lineCount = 1
            visible = true
        } else {
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        // 纯淡入淡出：提示条带垂直位移（slideInVertically）时，出现/消失那一下
        // 会把下方内容往上拽一截，用户看到的就是「点开文件时界面往上跳一下」。
        // 顶部浮层只做透明度变化，位置始终固定，不再牵动视觉。
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(140)),
        modifier = modifier
    ) {
        val tone = noticeTone(shown)
        val accent = when (tone) {
            NoticeTone.ERROR -> MaterialTheme.colorScheme.error
            NoticeTone.SUCCESS -> semanticColors().online
            NoticeTone.WARN -> Color(0xFFE8A23B)
            NoticeTone.INFO -> MaterialTheme.colorScheme.primary
        }
        Surface(
            onClick = onDismiss,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                // 单行提示必须与图标垂直居中对齐；多行（长报错可滚动）时光标落在
                // 文本块正中反而显得偏移，改用顶部对齐。
                verticalAlignment = if (lineCount > 1) Alignment.Top else Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .padding(top = if (lineCount > 1) 2.dp else 0.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when (tone) {
                            NoticeTone.ERROR -> Icons.Outlined.ErrorOutline
                            NoticeTone.SUCCESS -> Icons.Outlined.CheckCircle
                            NoticeTone.WARN -> Icons.Outlined.Info
                            NoticeTone.INFO -> Icons.Outlined.Info
                        },
                        null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    shown,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    // 行数决定图标对齐方式：单行居中、多行顶部对齐。
                    onTextLayout = { lineCount = it.lineCount },
                    // 长文案（完整报错、路径、HTTP 状态 + 服务端回包）不能被裁掉：
                    // 高度给到约 8 行，超出在浮层内滚动，仍然一眼能看到开头。
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = 10.dp)
                        .heightIn(max = 168.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
