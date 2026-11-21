package com.yaoahos.myfilebox.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yaoahos.myfilebox.core.TabKey

private const val PAGE_SEARCH = "search/"
private const val PAGE_RECYCLE = "recycle/"
private const val PAGE_BROWSER = "browser/"
private const val PAGE_SHARES = "shares"
private const val PAGE_SETTINGS = "settings"
private const val PAGE_APPEARANCE = "appearance"
private const val PAGE_ABOUT = "about"
private const val PAGE_BACKUP_SYNC = "backupSync"
internal const val PAGE_HOME = "home/"
internal const val PAGE_LOGIN = "login/"
private const val PAGE_TRANSFER = "transfer"

/**
 * 全软件唯一的「当前页面」标识。
 *
 * 这里刻意不按层级、底栏、首页、里层来分组：状态里凡是能独立占满一屏的
 * 内容都算一个页面，任何页面变化都会得到一个新的 key，因此都会走同一套转场。
 *
 * 覆盖清单（共 9 类页面，均为全屏）：
 *  1 本地文件首页 / 2 群晖文件首页 / 3 网络文件首页       -> home/{tab}
 *  4 群晖登录页 / 编辑连接页                              -> login/SYNO
 *  5 文件夹页、8 个文件分类页、收藏页、共享文件夹页、网络位置页 -> browser/{...}
 *  6 搜索页                                                -> search/{ctx}
 *  7 回收站（本地 / 群晖，含 #recycle 的每一层）            -> recycle/{ctx}/{dir}
 *  8 共享链接管理                                          -> shares
 *  9 设置页 / 关于页                                       -> settings / about
 *
 * 非页面覆盖层（AlertDialog、ModalBottomSheet、Dialog、顶部提示条、侧边抽屉）
 * 使用各自的系统标准动画，不参与页面转场。
 */
/** 底栏只在「底栏首页 / 登录页」显示；其它全屏页面不显示。 */
internal fun isHomePageKey(key: String): Boolean =
    key.startsWith(PAGE_HOME) || key.startsWith(PAGE_LOGIN)

internal fun pageKeyOf(s: MyFileBoxState): String = when {
    // 系统页（设置 / 外观 / 关于 / 备份同步）排在最前面。
    // 这几页可以从搜索、回收站、分享、传输这些覆盖页里的「更多 → 设置」进入，
    // 而覆盖页的状态字段在进设置时并不会被清掉 —— 只要它们排在前面，页面 key
    // 就还是覆盖页，表现就是「点设置没反应」。返回时 screen 回到 MAIN，覆盖页
    // 状态仍在，页面自动回到原处，所以这里只需要调整顺序，不用清状态。
    s.screen == Screen.SETTINGS -> PAGE_SETTINGS
    s.screen == Screen.APPEARANCE -> PAGE_APPEARANCE
    s.screen == Screen.ABOUT -> PAGE_ABOUT
    s.screen == Screen.BACKUP_SYNC -> PAGE_BACKUP_SYNC
    // 覆盖页之间可以互相跳转（搜索 → 回收、回收 → 传输、搜索 → 传输……），
    // 而跳转时下层那个状态字段不会清空。静态的 when 只能表达固定优先级，
    // 所以这里的顺序必须等于「可到达的打开顺序」：后打开的排在前面。
    //   传输 ← 回收 / 搜索 / 分享（各页「更多」里都有）
    //   回收 ← 搜索（搜索页「更多」里有收藏 / 回收 / 共享）
    //   分享 ← 搜索（群晖搜索页「更多」里有共享）
    // 旧顺序把 search / recycleOpen 放在 transferCenterOpen 之前，于是
    // 从回收站或搜索页点「传输」时页面 key 不变，表现就是「点了没反应」。
    s.transferCenterOpen -> PAGE_TRANSFER
    // 回收站的 key 必须带「当前钻到 #recycle 的哪一层」。
    // 只写 recycle/{ctx} 时，进 / 出子目录 key 不变，AnimatedContent 不认这是一次
    // 页面切换，内容直接硬切、没有任何位移——与浏览页（key 含 path）对比就是两套观感。
    s.recycleOpen -> "$PAGE_RECYCLE${s.recycleContext}/${s.recycleDir}"
    s.shareManagerOpen -> PAGE_SHARES
    s.search != null -> "$PAGE_SEARCH${s.search.ctx}"
    s.browser != null -> browserKeyOf(s.browser)
    s.tab == TabKey.SYNO && (s.connectionEditing || s.session == null) -> "$PAGE_LOGIN${s.tab}"
    else -> "$PAGE_HOME${s.tab}"
}

/** 文件夹页的 pageKey：页面宿主与「跳转压栈」必须共用同一套拼法，方向才判得准。 */
internal fun browserKeyOf(b: com.yaoahos.myfilebox.android.ui.BrowserState): String =
    "$PAGE_BROWSER${b.ctx}/${b.localCategory.orEmpty()}/${b.path}/${b.title}/${b.netId.orEmpty()}"

/**
 * 首页的滚动状态提到页面宿主层持有：页面进出 AnimatedContent 时不会重建，
 * 因此从文件夹返回首页不会跳回顶部。
 */
val LocalPageListState = compositionLocalOf { LazyListState() }

/** 按页面 key 渲染对应页面。s 是当前页的实时状态，或是下层页的快照。 */
@Composable
private fun PageContent(
    key: String,
    s: MyFileBoxState,
    vm: AppViewModel,
    listStates: Map<TabKey, LazyListState>,
    settingsListState: LazyListState
) = when {
    key.startsWith(PAGE_SEARCH) -> SearchScreen(s, vm)
    key.startsWith(PAGE_RECYCLE) -> RecycleScreen(s, vm)
    key == PAGE_SHARES -> ShareManagerScreen(s, vm)
    key == PAGE_TRANSFER -> TransferCenter(s, vm)
    key == PAGE_SETTINGS -> SettingsScreen(s, vm, settingsListState)
    key == PAGE_APPEARANCE -> AppearanceScreen(s, vm)
    key == PAGE_ABOUT -> AboutScreen(vm)
    key == PAGE_BACKUP_SYNC -> BackupSyncScreen(s, vm)
    key.startsWith(PAGE_BROWSER) -> BrowserScreen(s, vm)
    else -> {
        val tab = if (key.startsWith(PAGE_HOME) || key.startsWith(PAGE_LOGIN)) {
            runCatching { TabKey.valueOf(key.substringAfter('/')) }.getOrDefault(s.tab)
        } else s.tab
        CompositionLocalProvider(LocalPageListState provides (listStates[tab] ?: LazyListState())) {
            MainShell(s, vm, tabOverride = tab)
        }
    }
}

/**
 * 统一页面宿主：软件内所有页面共用这一个 AnimatedContent，没有第二套转场。
 *
 * 返回手势一律交给系统：返回键、Android 10+ 的左右边缘返回手势、Android 13+
 * 的预测式返回，最终都收敛成一次 [onBack] 调用，页面只播一遍 300ms 水平位移转场。
 *
 * 清单 1：这里不再自绘「侧滑跟手 + 下层页面预览」。自绘手势必须在手势层里
 * 同时叠一份下层页面，松手回弹 / 提交时两份内容会有一帧不一致，
 * 表现就是「侧滑释放后当前页闪一下」。去掉预览后返回仍然是系统的跟手手势，
 * 页面只做一次干净的转场，不再有闪帧。
 */
@Composable
fun PageHost(
    pageKey: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    snapshots: Map<String, MyFileBoxState>,
    liveState: MyFileBoxState,
    vm: AppViewModel,
    modifier: Modifier = Modifier
) {
    val currentOnBack by rememberUpdatedState(onBack)
    val stack = remember { PageStack() }
    // 三个底栏首页各持一份滚动状态，宿主常驻 → 页面重建也不丢滚动位置。
    val listStates = remember { TabKey.values().associateWith { LazyListState() } }
    // 设置页的滚动状态同样常驻：从「关于 / 主题与外观 / 备份与同步」返回时留在原位。
    val settingsListState = remember { LazyListState() }
    var previousPageKey by remember { mutableStateOf("") }
    // 离开回收站就把「钻到第几层」归零：切底栏、去设置、被别的覆盖页盖住再回来，
    // 都必须从各共享 #recycle 的汇总层开始（之前 recycleDir 会残留，回来停在子目录）。
    LaunchedEffect(pageKey) {
        if (!pageKey.startsWith(PAGE_RECYCLE) && liveState.recycleDir.isNotBlank()) {
            vm.resetRecycleToSummary()
        }
    }

    LaunchedEffect(pageKey) {
        // 只有在「从设置以外的页面进来」时才回到顶部——用户明确要求的是
        // 「退出设置再进入要从头开始」，而不是「每次进出子页都被拽回去」。
        val fromSubPage = previousPageKey == PAGE_ABOUT ||
            previousPageKey == PAGE_APPEARANCE ||
            previousPageKey == PAGE_BACKUP_SYNC
        if (pageKey == PAGE_SETTINGS && !fromSubPage) settingsListState.scrollToItem(0)
        previousPageKey = pageKey
    }

    // 底栏之间按左右顺序滑动（并重置回退栈），其它页面仍按真实回退栈判断前进 / 返回。
    // 转场方向：跳转落到深页时，state 会带一条「首页/父级/目标」的完整链，
    // 这里据此重建页面栈，保证进入是前进、逐级返回都是返回，不会因为目标 key
    // 以前访问过就判成相反方向。链的归属 key 必须等于本次 pageKey，避免异步
    // 状态下一次跳转的链被下一页误用；消费后由宿主清掉。
    val pendingChain = liveState.pendingPageChain
    val motion = remember(pageKey) {
        val chainForThisPage = pendingChain.takeIf { it.isNotEmpty() && it.last() == pageKey }
        resolvePageMotion(stack, pageKey, chainForThisPage)
    }
    LaunchedEffect(pageKey, pendingChain) {
        if (pendingChain.isNotEmpty() && pendingChain.last() == pageKey) vm.consumePageChain()
    }

    // 系统返回（按键 / 手势 / 预测式返回）统一走这里，与 AppRoot 的 performBack 完全一致。
    BackHandler(enabled = canGoBack) { currentOnBack() }

    // 左边缘侧滑兜底：部分 ROM（MIUI/HyperOS）在页面刚切换后的一小段时间里
    // 会漏掉左边缘的系统返回手势，表现是「左边缘滑不动，先从右边滑一次才恢复」。
    // 这里自己再挂一个边缘手势：只在页面可返回时生效，只认「从左边缘起手、向右滑」，
    // 触发时直接调同一个 onBack。系统正常接管时会给 App 发 CANCEL，
    // 我们的手势随之取消，因此不会出现「一次滑动退两层」。
    val density = LocalDensity.current
    val edgePx = with(density) { PageMotion.EDGE_DP.dp.toPx() }
    val commitPx = with(density) { 56.dp.toPx() }
    val edgeBack = if (!canGoBack) {
        Modifier
    } else {
        Modifier.pointerInput(pageKey) {
            var armed = false
            var dragged = 0f
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    armed = offset.x <= edgePx
                    dragged = 0f
                },
                onHorizontalDrag = { change, amount ->
                    if (!armed) return@detectHorizontalDragGestures
                    dragged += amount
                    // 只消费「向右」的位移，向左的可视作误触，交还给下层（切底栏手势）。
                    if (dragged > 0f) change.consume()
                },
                onDragEnd = {
                    if (armed && dragged >= commitPx) currentOnBack()
                    armed = false
                    dragged = 0f
                },
                onDragCancel = {
                    armed = false
                    dragged = 0f
                }
            )
        }
    }

    Box(modifier.fillMaxSize().clipToBounds()) {
        AnimatedContent(
            targetState = pageKey,
            contentKey = { it },
            modifier = Modifier.fillMaxSize().clipToBounds().then(edgeBack),
            transitionSpec = { motionTransition(motion) },
            label = "page-motion"
        ) { key ->
            // 每个页面都铺一层不透明底色：有的页面根节点自带背景，有的没有
            //（例如设置页就是一个 LazyColumn），转场那 300ms 里透明部分会透出
            // 下层页面的内容，看起来就是「残影」。宿主层统一兜底。
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                // 当前页用实时状态；正在退出的下层页用进入时的快照，
                // 避免两个页面读到同一份新状态而显示成一样的内容。
                PageContent(
                    key,
                    if (key == pageKey) liveState else snapshots[key] ?: liveState,
                    vm,
                    listStates,
                    settingsListState
                )
            }
        }
    }
}
