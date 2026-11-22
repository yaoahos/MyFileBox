package com.yaoahos.myfilebox.android.ui
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yaoahos.myfilebox.android.BuildConfig
import com.yaoahos.myfilebox.android.TransferCancellation
import com.yaoahos.myfilebox.android.TransferNotifications
import com.yaoahos.myfilebox.android.TransferService
import com.yaoahos.myfilebox.android.data.BackupCrypto
import com.yaoahos.myfilebox.android.data.BackupCryptoException
import com.yaoahos.myfilebox.android.data.BackupError
import com.yaoahos.myfilebox.android.data.CloudConfigFile
import com.yaoahos.myfilebox.android.data.ConfigBackup
import com.yaoahos.myfilebox.android.data.ConfigCloudSync
import com.yaoahos.myfilebox.android.data.ConfigSyncScheduler
import com.yaoahos.myfilebox.android.data.ConfigSyncStore
import com.yaoahos.myfilebox.android.data.DeviceProfile
import com.yaoahos.myfilebox.android.data.PREFS_NAME
import com.yaoahos.myfilebox.android.data.SyncInterval
import com.yaoahos.myfilebox.android.data.SyncSettings
import com.yaoahos.myfilebox.android.data.DsmRepository
import com.yaoahos.myfilebox.android.data.AppIconSwitcher
import com.yaoahos.myfilebox.android.data.nameMatchesFilter
import com.yaoahos.myfilebox.android.data.itemMatchesFilter
import com.yaoahos.myfilebox.android.data.filterVisible
import com.yaoahos.myfilebox.android.data.hostLooksPrivate
import com.yaoahos.myfilebox.android.data.needsChannelRepair
import com.yaoahos.myfilebox.android.data.LocalRepository
import com.yaoahos.myfilebox.android.data.ExtractConflict
import com.yaoahos.myfilebox.android.data.ArchivePasswordRequired
import com.yaoahos.myfilebox.android.data.TransferAbortedException
import com.yaoahos.myfilebox.android.data.RecentPolicy
import com.yaoahos.myfilebox.android.data.NetworkRepository
import com.yaoahos.myfilebox.android.data.SecretVault
import com.yaoahos.myfilebox.android.data.SftpRepository
import com.yaoahos.myfilebox.android.data.StreamProxy
import com.yaoahos.myfilebox.android.data.SynoRecycleIndex
import com.yaoahos.myfilebox.android.data.ParsedServer
import com.yaoahos.myfilebox.android.data.QuickConnectEndpoint
import com.yaoahos.myfilebox.android.data.friendlyNetError
import com.yaoahos.myfilebox.android.data.DeviceNet
import com.yaoahos.myfilebox.android.data.NetStatus
import com.yaoahos.myfilebox.android.data.offlineNetworkMessage
import com.yaoahos.myfilebox.android.data.networkStatusNotice
import com.yaoahos.myfilebox.core.AppPrefs
import com.yaoahos.myfilebox.core.effectiveDefaultTab
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FileKinds
import com.yaoahos.myfilebox.core.FolderCard
import com.yaoahos.myfilebox.core.NetworkConnection
import com.yaoahos.myfilebox.core.RecycleItem
import com.yaoahos.myfilebox.core.Session
import com.yaoahos.myfilebox.core.ShareFolder
import com.yaoahos.myfilebox.core.TabKey
import com.yaoahos.myfilebox.core.ThemeMode
import com.yaoahos.myfilebox.core.ThemePrefs
import com.yaoahos.myfilebox.core.Volume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID


internal fun AppViewModel.selectTab(tab: TabKey) {
        if (tab !in visibleTabs() || tab == state.tab) return
        // 换底栏一律回到该栏首页：不再缓存「上次停在哪一层」。缓存过位置的话，
        // 从传输记录进过文件夹之后切回来会被直接拽进那个目录，看起来像跳过底栏。
        state = state.copy(
            tab = tab,
            browser = null,
            browserStack = emptyList(),
            selected = emptySet(),
            selectionActive = false,
            homeSelected = emptySet(),
            homeSelecting = false,
            search = null,
            // 覆盖页状态一并清掉：底栏只在首页可见，正常不会带着覆盖页切栏，
            // 但万一状态残留，pageKeyOf 会让新栏首页被旧覆盖页盖住（点了像没反应）。
            // recycleDir 一起归零：不然再进回收站会停在上次钻进去的子目录。
            recycleOpen = false,
            recycleDir = "",
            shareManagerOpen = false,
            transferCenterOpen = false,
            // 换底栏 = 换当前位置，筛选词不沿用。
            nameFilter = "",
            extensionFilter = "",
            // 切换底栏也是进入新页面，统一动画方向不能沿用上一次的脏值。
            navDir = 1
        )
    }


/** FileContext 与底栏一一对应；用于阻止把网络页缓存到本地栏（面包屑跳 SMB 的根因）。 */
internal fun AppViewModel.tabOfContext(ctx: FileContext): TabKey = when (ctx) {
        FileContext.LOCAL -> TabKey.LOCAL
        FileContext.SYNO -> TabKey.SYNO
        FileContext.NET -> TabKey.NET
    }

    /**
     * 「更多 → 首页」：回到指定端的根首页，而不是只退一层。
     *
     * 与 backFromBrowser 不同：无论当前在多深的目录 / 分类 / 收藏页，都一步回到该端首页；
     * 若该端不在可见底栏里（被用户在设置里隐藏）则什么也不做，避免跳到一个看不到的页。
     */
internal fun AppViewModel.goHome(ctx: FileContext) {
        val tab = when (ctx) {
            FileContext.LOCAL -> TabKey.LOCAL
            FileContext.SYNO -> TabKey.SYNO
            FileContext.NET -> TabKey.NET
        }
        if (tab !in visibleTabs()) return
        state = state.copy(
            tab = tab,
            browser = null,
            browserStack = emptyList(),
            selected = emptySet(),
            selectionActive = false,
            homeSelected = emptySet(),
            homeSelecting = false,
            search = null,
            recycleOpen = false,
            recycleDir = "",
            shareManagerOpen = false,
            // 传输中心也是覆盖页，回首页必须一起清掉，否则页面上还盖着传输列表。
            transferCenterOpen = false,
            navDir = -1
        )
    }


internal fun AppViewModel.toggleCategoryExpanded() {
        state = state.copy(categoryExpanded = !state.categoryExpanded)
    }


internal fun AppViewModel.swipeTab(direction: Int) {
        val tabs = visibleTabs()
        if (tabs.size < 2) return
        val index = tabs.indexOf(state.tab).coerceAtLeast(0)
        val next = (index + direction).coerceIn(0, tabs.lastIndex)
        if (next != index) selectTab(tabs[next])
    }


/**
 * 返回到有效默认启动页。已在启动页时返回 false，交还系统默认（退出应用）。
 * 默认启动页由设置决定；被隐藏时退到第一个可见页，再兜底本地文件。
 */
internal fun AppViewModel.backToHomeTab(): Boolean {
    val home = effectiveDefaultTab(state.prefs)
    if (state.tab == home) return false
    selectTab(home)
    return true
}


internal fun AppViewModel.visibleTabs(prefs: AppPrefs = state.prefs): List<TabKey> = prefs.tabOrder.filter {
        when (it) {
            TabKey.LOCAL -> prefs.showLocal
            TabKey.SYNO -> prefs.showSyno
            TabKey.NET -> prefs.showNet
        }
    }


internal fun AppViewModel.setDefaultTab(tab: TabKey) {
        val next = state.prefs.copy(defaultTab = tab)
        state = state.copy(prefs = next)
        persist()
    }


internal fun AppViewModel.toggleTab(tab: TabKey) {
        val visible = visibleTabs()
        if (tab in visible && visible.size == 1) {
            state = state.copy(message = "至少保留一个底栏入口")
            return
        }
        val prefs = state.prefs
        val next = when (tab) {
            TabKey.LOCAL -> prefs.copy(showLocal = !prefs.showLocal)
            TabKey.SYNO -> prefs.copy(showSyno = !prefs.showSyno)
            TabKey.NET -> prefs.copy(showNet = !prefs.showNet)
        }
        val nextVisible = visibleTabs(next)
        val fallback = if (next.defaultTab in nextVisible) next.defaultTab else nextVisible.firstOrNull() ?: TabKey.LOCAL
        state = state.copy(prefs = next, tab = if (state.tab in nextVisible) state.tab else fallback)
        persist()
    }


internal fun AppViewModel.moveTab(tab: TabKey, delta: Int) {
        val order = state.prefs.tabOrder.toMutableList()
        val index = order.indexOf(tab)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (target == index) return
        order.removeAt(index)
        order.add(target, tab)
        state = state.copy(prefs = state.prefs.copy(tabOrder = order))
        persist()
    }


    /** 本地文件分类排序（清单 4）：拖拽换位，按名字列表持久化。 */
internal fun AppViewModel.moveCategory(name: String, delta: Int) {
        val base = listOf("图片", "视频", "文档", "音频", "安装包", "压缩包", "最近", "收藏")
        val order = state.prefs.categoryOrder.ifEmpty { base }.toMutableList()
        val index = order.indexOf(name)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (target == index) return
        order.removeAt(index)
        order.add(target, name)
        state = state.copy(prefs = state.prefs.copy(categoryOrder = order))
        persist()
    }


    /** Navigation follows "how you got here, that's how you go back". */
internal fun AppViewModel.openScreen(next: Screen) {
        if (next == state.screen) return
        val origin = state.screen
        state = state.copy(screen = next, screenStack = state.screenStack + origin, navDir = 1)
    }


internal fun AppViewModel.openSettings() { openScreen(Screen.SETTINGS) }

internal fun AppViewModel.openAbout() { openScreen(Screen.ABOUT) }


    /** 设置 → 主题与外观：外观模式、主题配色、动态取色都收到这一页。 */
internal fun AppViewModel.openAppearance() { openScreen(Screen.APPEARANCE) }


internal fun AppViewModel.closeScreen() {
        val stack = state.screenStack
        val target = stack.lastOrNull() ?: Screen.MAIN
        state = state.copy(screen = target, screenStack = stack.dropLast(1), navDir = -1)
    }


internal fun AppViewModel.setThemeMode(mode: ThemeMode) {
        state = state.copy(prefs = state.prefs.copy(theme = state.prefs.theme.copy(mode = mode)))
        persist()
    }


    /**
     * 选一个主题色。
     *
     * 顺手关掉动态取色：用户主动挑颜色，就是要这一套配色，不能还被壁纸色压着。
     */
internal fun AppViewModel.setAccent(color: Long) {
        state = state.copy(
            prefs = state.prefs.copy(
                theme = state.prefs.theme.copy(accent = color, dynamic = false)
            )
        )
        persist()
    }


internal fun AppViewModel.setDynamicColor(on: Boolean) {
        if (state.prefs.theme.dynamic == on) return
        state = state.copy(prefs = state.prefs.copy(theme = state.prefs.theme.copy(dynamic = on)))
        persist()
    }


    /**
     * 应用图标是否跟随主题色。关闭时用原始蓝色图标，开启后应用内图标按主色
     * 实时染色、桌面启动图标切到对应色相档；桌面组件的切换由 AppRoot 的
     * syncAppIcon 在拿到本次主色后统一执行，避免这里拿到的是旧的 accent。
     */
internal fun AppViewModel.setIconFollowsTheme(on: Boolean) {
        if (state.prefs.theme.iconFollowsTheme == on) return
        state = state.copy(prefs = state.prefs.copy(theme = state.prefs.theme.copy(iconFollowsTheme = on)))
        persist()
    }


    /**
     * 把桌面启动图标的目标档位同步到「当前真正在用的主色」——动态取色时主色
     * 来自壁纸，不是 prefs.accent，因此只能由主题确定后的 Compose 层回调进来。
     * 这里只登记目标，真正切换组件由 MainActivity.onStop 在退到后台时执行，
     * 避免禁用当前正在运行的启动组件把设置页直接踢回桌面。
     */
internal fun AppViewModel.syncAppIcon(primary: Int) {
        AppIconSwitcher.request(getApplication(), state.prefs.theme.iconFollowsTheme, primary)
    }


    // ---------------------------------------------------------------- login / session

internal fun AppViewModel.login(server: String, account: String, password: String, https: Boolean, remember: Boolean) {
        if (server.isBlank() || account.isBlank() || password.isBlank()) {
            state = state.copy(message = "请填写服务器、账号和密码")
            return
        }
        rememberSession = remember
        state = state.copy(loading = true, message = null, loginError = null)
        viewModelScope.launch {
            val parsed = dsm.parseServer(server, https)
            // QuickConnect 要分内网 / 外网通道：内网只走局域网直连，
            // 外网先试端口映射 / DDNS，全部不通才用官方中转。
            val candidates = if (parsed.quickConnect) {
                dsm.quickConnectCandidates(parsed.host)
            } else {
                listOf(QuickConnectEndpoint(parsed.baseUrl, parsed.display))
            }
            if (candidates.isEmpty()) {
                val reason = "未找到该 QuickConnect ID，请检查 ID 或网络"
                state = state.copy(loading = false, message = reason, loginError = reason)
                return@launch
            }
            var lastError = "登录失败"
            val errors = mutableListOf<String>()
            for (endpoint in candidates) {
                val provisional = Session(
                    server = parsed.display,
                    account = account,
                    name = account,
                    nas = "群晖 NAS",
                    mode = if (parsed.quickConnect) endpoint.label else "局域网直连",
                    https = parsed.https,
                    endpoint = endpoint.baseUrl
                )
                val result = dsm.login(provisional, password)
                if (result.success && result.sid != null) {
                    // 缓存到内存：切网后自动重连用。没勾「记住密码」也能无感恢复。
                    cachedPassword = password
                    val session = provisional.copy(
                        sid = result.sid,
                        synoToken = result.synoToken.orEmpty(),
                        nas = "DSM 已连接"
                    )
                    val saved = if (remember) saveLogin(server, account, password) else {
                        clearSavedLogin()
                        true
                    }
                    state = state.copy(
                        session = session,
                        connectionEditing = false,
                        loading = false,
                        tab = TabKey.SYNO,
                        editSynoOpen = false,
                        loginError = null,
                        message = (if (parsed.quickConnect) "已通过 QuickConnect 连接 ${parsed.display} · ${endpoint.label}"
                        else "已连接 ${parsed.display}") +
                            if (!saved) "（凭据加密失败，未保存密码）" else ""
                    )
                    rememberSession = remember
                    // 手动登录成功：解除「退出后不自动登录」的抑制。
                    autoLoginSuppressed = false
                    prefsStore.edit().putBoolean("auto_login_suppressed", false).apply()
                    persist()
                    refreshSyno()
                    return@launch
                }
                lastError = result.error ?: "登录失败"
                errors += lastError
                // 账号/密码/两步验证这类鉴权错误，换通道结果一样，立刻给结论，
                // 不再把剩下的候选挨个试一遍（数据网络下能省掉十几秒）。
                if (dsm.lastErrorCode in 400..410) break
            }
            val reason = summarizeLoginErrors(errors, candidates.size)
            state = state.copy(loading = false, message = reason, loginError = reason)
        }
    }


internal fun AppViewModel.describeServer(server: String, https: Boolean): String = dsm.describeServer(server, https)


internal fun AppViewModel.allowSelfSigned(): Boolean = prefsStore.getBoolean("allow_self_signed", true)


internal fun AppViewModel.setAllowSelfSigned(enabled: Boolean) {
        prefsStore.edit().putBoolean("allow_self_signed", enabled).apply()
        syncTlsSetting(enabled)
    }

/** 把「允许自签」开关同步到 DSM 仓库与流代理，两者必须口径一致。 */
internal fun AppViewModel.syncTlsSetting(enabled: Boolean) {
    dsm.allowSelfSignedCertificates(enabled)
    com.yaoahos.myfilebox.android.data.StreamProxy.configure(enabled)
}


internal fun AppViewModel.savedLogin(): Triple<String, String, String> = Triple(
        prefsStore.getString("login_server", "") ?: "",
        prefsStore.getString("login_account", "") ?: "",
        SecretVault.decrypt(prefsStore.getString("login_password", "") ?: "")
    )


    /** 返回是否安全保存成功。加密失败时绝不回退明文，也不留下空的密码项。 */
internal fun AppViewModel.saveLogin(server: String, account: String, password: String): Boolean {
        val encrypted = SecretVault.encrypt(password)
        if (encrypted.isBlank()) {
            clearSavedLogin()
            return false
        }
        prefsStore.edit()
            .putString("login_server", server)
            .putString("login_account", account)
            .putString("login_password", encrypted)
            .apply()
        return true
    }


internal fun AppViewModel.clearSavedLogin() {
        prefsStore.edit().remove("login_password").apply()
    }


    /** "去登录" from a secondary page: keep the origin so back returns there. */
internal fun AppViewModel.goToLogin() {
        val origin = state.screen
        state = state.copy(
            screen = Screen.MAIN,
            screenStack = if (origin == Screen.MAIN) state.screenStack else state.screenStack + origin,
            tab = TabKey.SYNO
        )
    }


internal fun AppViewModel.openEditSyno() {
        // 只切到登录页让用户改参数，不清 session：登录成功才替换，失败也不影响正在用的连接。
        state = state.copy(connectionEditing = true)
    }


internal fun AppViewModel.cancelConnectionEditing() { state = state.copy(connectionEditing = false) }


internal fun AppViewModel.closeEditSyno() { state = state.copy(editSynoOpen = false) }


internal fun AppViewModel.logout() {
        state = state.copy(
            session = null,
            shares = emptyList(),
            volumes = emptyList(),
            browser = null,
            browserStack = emptyList(),
            screen = Screen.MAIN,
            screenStack = emptyList(),
            tab = TabKey.SYNO,
            search = null,
            editSynoOpen = false,
            message = "已退出登录"
        )
        // 退出登录只结束会话，密码保留：下次登录表单直接预填，不用重新输一遍。
        // 但要抑制冷启动自动登录，否则「退出」等于没退。
        autoLoginSuppressed = true
        prefsStore.edit().putBoolean("auto_login_suppressed", true).apply()
        rememberSession = true
        persist()
    }


/**
 * 彻底删除群晖连接：会话、保存的服务器 / 账号 / 密码一起清掉。
 *
 * 与 [logout] 的区别是「退出登录」只结束会话、密码留着方便下次登录；而删连接意味着
 * 这台 NAS 不想再用了（换设备、卖机器、只是想清掉保存的凭据），必须连凭据一起清。
 */
internal fun AppViewModel.deleteSynoConnection() {
        state = state.copy(
            session = null,
            shares = emptyList(),
            volumes = emptyList(),
            browser = null,
            browserStack = emptyList(),
            screen = Screen.MAIN,
            screenStack = emptyList(),
            tab = TabKey.SYNO,
            search = null,
            editSynoOpen = false,
            searchIndexNet = emptyMap(),
            message = "已删除群晖连接"
        )
        // 凭据都清了，自动登录抑制也没意义（没有可登录的东西），恢复默认。
        autoLoginSuppressed = false
        rememberSession = true
        prefsStore.edit()
            .remove("login_server")
            .remove("login_account")
            .remove("login_password")
            .putBoolean("auto_login_suppressed", false)
            .apply()
        persist()
    }


internal fun AppViewModel.editConnection(server: String, account: String, password: String = "") {
        val current = state.session ?: return
        if (server.isBlank() || account.isBlank()) {
            state = state.copy(message = "服务器和账号不能为空")
            return
        }
        if (password.isBlank()) {
            state = state.copy(message = "编辑连接需要重新输入密码")
            return
        }
        rememberSession = true
        state = state.copy(loading = true)
        viewModelScope.launch {
            val parsed = dsm.parseServer(server, current.https)
            val candidates = if (parsed.quickConnect) {
                dsm.quickConnectCandidates(parsed.host)
            } else listOf(QuickConnectEndpoint(parsed.baseUrl, parsed.display))
            if (candidates.isEmpty()) {
                val reason = "未找到该 QuickConnect ID"
                state = state.copy(loading = false, message = reason, loginError = reason)
                return@launch
            }
            var lastError = "连接失败"
            val errors = mutableListOf<String>()
            for (endpoint in candidates) {
                val provisional = current.copy(
                    server = parsed.display,
                    account = account,
                    https = parsed.https,
                    endpoint = endpoint.baseUrl
                )
                val result = dsm.login(provisional, password)
                if (result.success && result.sid != null) {
                    cachedPassword = password
                    val saved = saveLogin(server, account, password)
                    state = state.copy(
                        session = provisional.copy(sid = result.sid, synoToken = result.synoToken.orEmpty()),
                        connectionEditing = false,
                        loading = false,
                        editSynoOpen = false,
                        loginError = null,
                        message = "连接信息已更新" + if (!saved) "（凭据加密失败，未保存密码）" else ""
                    )
                    persist()
                    refreshSyno()
                    return@launch
                }
                lastError = result.error ?: "连接失败"
                errors += lastError
                if (dsm.lastErrorCode in 400..410) break
            }
            val reason = summarizeLoginErrors(errors, candidates.size)
            state = state.copy(loading = false, message = reason, loginError = reason)
        }
    }


    /**
     * 多个通道全部失败时的总结提示：指出「被拦截 / 连不上」等真实原因，
     * 并给出下一步怎么办，而不是只丢一个底层错误。
     */
internal fun AppViewModel.summarizeLoginErrors(errors: List<String>, tried: Int): String {
        if (errors.isEmpty()) return "登录失败"
        val joined = errors.joinToString("；")
        return when {
            // 先看鉴权类结果：能走到「账号或密码错误」说明通道已经通了。
            joined.contains("账号或密码") -> "账号或密码错误"
            joined.contains("两步验证") -> "该账号开启了两步验证，请先在 DSM 关闭或使用专用账号"
            joined.contains("网页") ->
                "已尝试 $tried 个通道，返回的都是网页而非 DSM 数据：请确认填的是地址或 QuickConnect ID"
            joined.contains("超时") || joined.contains("无法连接") || joined.contains("网络") ->
                "已尝试 $tried 个通道均无法连接，请确认 NAS 在线后重试"
            else -> errors.last()
        }
    }


    /** 只清除指定那条消息；传入 null 时无条件清空（兼容旧调用）。 */
internal fun AppViewModel.consumeMessage(message: String? = null) {
        if (message == null || state.message == message) state = state.copy(message = null)
    }


internal fun AppViewModel.showMessage(message: String) { state = state.copy(message = message) }


    /** 复制到剪贴板并在顶部给出「已复制」——详情里的路径都是整行点一下即复制。 */
internal fun AppViewModel.copyText(text: String, label: String = "内容") {
        copyToClipboard(text, label)
        showMessage("已复制")
    }

    /** 只写入剪贴板、不弹顶部提示：AlertDialog 是独立窗口，顶部提示条会在弹窗下方看不见，
     *  这类场景由弹窗自己在内部显示「已复制」（见 FileDetailDialog / RecycleDetailDialog）。 */
internal fun AppViewModel.copyToClipboard(text: String, label: String = "内容") {
        val clipboard = getApplication<Application>()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }


    /** 退出选择模式：所有清空 selected 的场景都必须走这里，避免残留空的选择模式。 */
internal fun AppViewModel.exitSelection() {
        state = state.copy(
            selected = emptySet(),
            selectionActive = false,
            homeSelected = emptySet(),
            homeSelecting = false
        )
    }


    // ---------------------------------------------------------------- local data

internal fun AppViewModel.hasLocalAccess(): Boolean = local.hasAccess()


    /** Re-check access when returning from the system permission screen. */
internal fun AppViewModel.checkLocalAccess() {
        val access = local.hasAccess()
        if (access != state.localAccess) {
            state = state.copy(localAccess = access)
            if (access) refreshLocal()
        }
    }


internal fun AppViewModel.requestLocalAccess() {
        val context = getApplication<Application>()
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { state = state.copy(message = "无法打开系统授权页面") }
    }


    /**
     * 启动权限流程：先弹系统的通知权限，再处理文件权限。
     * 文件权限没有系统弹窗，直接跳系统「所有文件访问」页（不再自绘引导框）；
     * 只在安装后第一次自动跳，之后靠首页的权限横幅手动触发，避免每次启动都弹设置页。
     */
internal fun AppViewModel.runStartupPermissionFlow(onNeedNotification: () -> Unit) {
        val needNotification = android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                getApplication(), android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needNotification) {
            onNeedNotification()
            return
        }
        afterNotificationPermission()
    }


    /** 通知权限弹完后：不再自动跳系统设置页，改为在首页显示权限横幅，由用户主动点「去授权」。 */
internal fun AppViewModel.afterNotificationPermission() {
        if (local.hasAccess()) return
        // 只提示一次横幅，不再自动跳转系统授权页（用户反馈体验不好）。
        state = state.copy(message = "需要文件访问权限才能浏览本机文件，请点击首页顶部的授权提示")
    }


    /**
     * 从别的应用切回来时重新扫描本地存储：用户很可能刚在浏览器 / 微信里存了文件。
     * 之前只有 [checkLocalAccess] 在权限状态变化时才刷新，新文件要重启应用才看得到。
     * 首次 resume 紧跟 init 那次扫描，跳过以免重复全盘遍历。
     */
internal fun AppViewModel.refreshLocalOnResume() {
        // 缓存按天过期，回到前台顺手清一次即可，不必等下次冷启动。
        pruneTransferCache()
        if (!resumedBefore) {
            resumedBefore = true
            return
        }
        // 全盘扫描不便宜，刚扫过就没必要再来一次。
        if (System.currentTimeMillis() - lastScanAt < 8_000) return
        refreshLocal()
    }


    // ---------------------------------------------------------------- settings / modules / connections

internal fun AppViewModel.moduleVisible(key: String): Boolean = key !in state.prefs.hiddenModules


internal fun AppViewModel.toggleModule(key: String) {
        val hidden = state.prefs.hiddenModules.toMutableSet()
        if (!hidden.add(key)) hidden.remove(key)
        state = state.copy(prefs = state.prefs.copy(hiddenModules = hidden))
        persist()
    }


internal fun AppViewModel.moveModule(key: String, delta: Int) {
        val group = key.substringBefore(":")
        val order = state.prefs.moduleOrder.toMutableList()
        val indices = order.indices.filter { order[it].startsWith("$group:") }
        if (indices.isEmpty()) return
        val localOrder = indices.map { order[it] }.toMutableList()
        val index = localOrder.indexOf(key)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, localOrder.lastIndex)
        if (target == index) return
        localOrder.removeAt(index)
        localOrder.add(target, key)
        indices.forEachIndexed { i, position -> order[position] = localOrder[i] }
        state = state.copy(prefs = state.prefs.copy(moduleOrder = order))
        persist()
    }


internal fun AppViewModel.volume(id: String = state.prefs.volumeId): Volume? =
        state.volumes.firstOrNull { it.id == id } ?: state.volumes.firstOrNull()


internal fun AppViewModel.setVolume(id: String) {
        state = state.copy(prefs = state.prefs.copy(volumeId = id), message = "已切换存储空间")
        persist()
    }


internal fun AppViewModel.openCleaner() {
        val context = getApplication<Application>()
        val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { state = state.copy(message = "已打开系统存储设置") }
            .onFailure { state = state.copy(message = "无法打开系统存储设置") }
    }


    /** 返回是否保存成功；失败时对话框保持打开，用户不用重新输入。 */
    /**
     * 解析用户输入的服务器地址。支持三种写法：
     * ① 纯 IP/域名（192.168.1.100）② 带端口（192.168.1.100:5255）③ 完整 URL（http://192.168.1.100:5255/dav）
     * 返回 (host, port, path)，port/path 为空串表示用户没填、用表单里的值。
     */
internal fun AppViewModel.parseServerInput(raw: String): Triple<String, String, String> {
        var s = raw.trim()
        var scheme = ""
        if (s.startsWith("https://", true)) { scheme = "https"; s = s.removePrefix("https://").removePrefix("HTTPS://") }
        else if (s.startsWith("http://", true)) { scheme = "http"; s = s.removePrefix("http://").removePrefix("HTTP://") }
        if (scheme.isEmpty()) return Triple(raw.trim(), "", "")
        // 完整 URL：拆出 host[:port]/path]
        val slashIdx = s.indexOf('/')
        val hostPort = if (slashIdx >= 0) s.substring(0, slashIdx) else s
        val path = if (slashIdx >= 0) s.substring(slashIdx) else ""
        val colonIdx = hostPort.lastIndexOf(':')
        val host = if (colonIdx > 0) hostPort.substring(0, colonIdx) else hostPort
        val port = if (colonIdx > 0) hostPort.substring(colonIdx + 1) else if (scheme == "https") "443" else "80"
        return Triple(host, port, path)
    }


internal fun AppViewModel.saveConnection(id: String?, name: String, protocol: String, host: String, port: String, username: String, password: String, path: String, secure: Boolean): Boolean {
        // 连接配置变更，网络搜索索引作废（清单 11）。
        if (state.searchIndexNet.isNotEmpty()) state = state.copy(searchIndexNet = emptyMap())
        // 用户可能把完整 URL 填进「服务器地址」，自动拆出 host/port/path。
        val (parsedHost, parsedPort, parsedPath) = parseServerInput(host)
        val effectivePort = parsedPort.ifBlank { port }
        val effectivePath = parsedPath.ifBlank { path }
        val effectiveSecure = secure || parsedPort == "443"
        val portNumber = effectivePort.toIntOrNull()
        if (parsedHost.isBlank()) {
            state = state.copy(message = "保存失败：请填写服务器地址")
            return false
        }
        if (portNumber == null || portNumber !in 1..65535) {
            state = state.copy(message = "保存失败：端口需在 1-65535 之间")
            return false
        }
        // 名称不再是必填项：留空时用主机地址当名字，卡片上仍能分辨。
        val displayName = name.trim().ifBlank { parsedHost.trim() }
        val connection = NetworkConnection(
            id = id ?: "nc-${UUID.randomUUID().toString().take(8)}",
            name = displayName,
            protocol = protocol,
            host = parsedHost.trim(),
            port = portNumber,
            path = if (protocol == "SMB") effectivePath.trim() else effectivePath.ifBlank { "/" },
            online = false,
            latency = 0,
            username = username,
            password = password,
            secure = effectiveSecure
        )
        val next = if (id == null) state.connections + connection else state.connections.map { if (it.id == id) connection else it }
        state = state.copy(connections = next, message = if (id == null) "已添加「$displayName」" else "连接已更新")
        persist()
        testConnection(connection)
        return true
    }


internal fun AppViewModel.addConnection(
        name: String,
        protocol: String,
        host: String,
        port: String,
        path: String = "/",
        username: String = "",
        password: String = "",
        secure: Boolean = true
    ): Boolean = saveConnection(null, name, protocol, host, port, username, password, path, secure)


internal fun AppViewModel.updateConnection(
        id: String,
        name: String,
        protocol: String,
        host: String,
        port: String,
        path: String = "/",
        username: String = "",
        password: String = "",
        secure: Boolean = true
    ): Boolean {
        val old = state.connections.firstOrNull { it.id == id }
        return saveConnection(
            id, name, protocol, host, port,
            username.ifBlank { old?.username.orEmpty() },
            password.ifBlank { old?.password.orEmpty() },
            path.ifBlank { old?.path ?: "/" },
            secure
        )
    }


internal fun AppViewModel.contextOf(tab: TabKey): FileContext = when (tab) {
        TabKey.LOCAL -> FileContext.LOCAL
        TabKey.SYNO -> FileContext.SYNO
        TabKey.NET -> FileContext.NET
    }
