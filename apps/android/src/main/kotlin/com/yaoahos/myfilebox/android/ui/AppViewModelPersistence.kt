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


    // ---------------------------------------------------------------- prefs

internal fun AppViewModel.loadPrefs(): AppPrefs {
        val base = AppPrefs()
        val mode = runCatching { ThemeMode.valueOf(prefsStore.getString("theme_mode", "SYSTEM")!!) }
            .getOrDefault(ThemeMode.SYSTEM)
        val accent = prefsStore.getLong("theme_accent", base.theme.accent)
        val dynamic = prefsStore.getBoolean("theme_dynamic", base.theme.dynamic)
        val iconFollowsTheme = prefsStore.getBoolean("theme_icon_follows", false)
        val order = prefsStore.getString("tab_order", null)
            ?.split(",")
            ?.mapNotNull { runCatching { TabKey.valueOf(it) }.getOrNull() }
            ?.filter { it in TabKey.entries }
            ?.distinct()
            ?.let { stored -> stored + TabKey.entries.filterNot { it in stored } }
            ?: base.tabOrder
        val modules = prefsStore.getString("module_order", null)
            ?.split(",")
            // local:recent / syno:recent 两个模块已下线（最近只留在文件分类里），
            // 老配置里可能还存着这两个键，加载时直接滤掉。
            ?.filter { it.isNotBlank() && it != "local:recent" && it != "syno:recent" }
            ?.let { stored -> stored + base.moduleOrder.filterNot { it in stored } }
            ?: base.moduleOrder
        val hidden = prefsStore.getStringSet("hidden_modules", emptySet()).orEmpty().toSet()
        val views = mutableMapOf<String, String>()
        runCatching {
            JSONObject(prefsStore.getString("section_views", "{}") ?: "{}").let { json ->
                json.keys().forEach { key -> views[key] = json.optString(key) }
            }
        }
        val tabViews = mutableMapOf("local" to "grid", "syno" to "grid", "net" to "grid")
        runCatching {
            JSONObject(prefsStore.getString("tab_views", "{}") ?: "{}").let { json ->
                json.keys().forEach { key ->
                    val value = json.optString(key)
                    if (value == "grid" || value == "list") tabViews[key] = value
                }
            }
        }
        return base.copy(
            defaultTab = runCatching { TabKey.valueOf(prefsStore.getString("default_tab", "LOCAL")!!) }
                .getOrDefault(base.defaultTab),
            showLocal = prefsStore.getBoolean("show_local", true),
            showSyno = prefsStore.getBoolean("show_syno", true),
            showNet = prefsStore.getBoolean("show_net", true),
            tabOrder = order,
            downloadFolder = prefsStore.getString("download_folder", "") ?: "",
            downloadConcurrency = prefsStore.getInt("download_concurrency", 3).coerceIn(1, 6),
            theme = ThemePrefs(mode, accent, dynamic, iconFollowsTheme),
            moduleOrder = modules,
            hiddenModules = hidden,
            sectionViews = base.sectionViews + views,
            viewMode = prefsStore.getString("view_mode", "grid") ?: "grid",
            tabViews = tabViews,
            favorites = prefsStore.getStringSet("favorites", emptySet()).orEmpty().toSet(),
            sortKey = prefsStore.getString("sort_key", "name") ?: "name",
            sortDir = prefsStore.getString("sort_dir", "asc") ?: "asc",
            tabSorts = buildMap {
                for (tab in listOf("local", "syno", "net")) {
                    prefsStore.getString("${tab}_sort_key", null)?.let { put("${tab}_key", it) }
                    prefsStore.getString("${tab}_sort_dir", null)?.let { put("${tab}_dir", it) }
                }
            },
            volumeId = prefsStore.getString("volume_id", "") ?: "",
            recycleDays = prefsStore.getInt("recycle_days", 30),
            cacheKeepDays = prefsStore.getInt("cache_keep_days", 7),
            categoryOrder = prefsStore.getString("category_order", null)
                ?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
            openDefaults = runCatching {
                val raw = prefsStore.getString("open_defaults", null) ?: return@runCatching emptyMap()
                val json = JSONObject(raw)
                buildMap {
                    json.keys().forEach { key ->
                        val pkg = json.optString(key)
                        if (pkg.isNotBlank()) put(key, pkg)
                    }
                }
            }.getOrDefault(emptyMap())
        )
    }


internal fun AppViewModel.loadSession(): Session? {
        val server = prefsStore.getString("session_server", null) ?: return null
        return Session(
            server = server,
            account = SecretVault.decrypt(prefsStore.getString("session_account", "") ?: ""),
            name = prefsStore.getString("session_name", "") ?: "",
            nas = prefsStore.getString("session_nas", "") ?: "",
            mode = prefsStore.getString("session_mode", "") ?: "",
            sid = SecretVault.decrypt(prefsStore.getString("session_sid", "") ?: ""),
            synoToken = SecretVault.decrypt(prefsStore.getString("session_syno_token", "") ?: ""),
            https = prefsStore.getBoolean("session_https", true),
            endpoint = prefsStore.getString("session_endpoint", null)
        )
    }


internal fun AppViewModel.loadConnections(): List<NetworkConnection> {
        val raw = SecretVault.decrypt(prefsStore.getString("connections", "[]") ?: "[]")
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                NetworkConnection(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    protocol = o.optString("protocol"),
                    host = o.optString("host"),
                    port = o.optInt("port"),
                    path = o.optString("path", "/"),
                    online = o.optBoolean("online", false),
                    latency = o.optInt("latency", 0),
                    username = o.optString("username", ""),
                    password = o.optString("password", ""),
                    secure = o.optBoolean("secure", false)
                )
            }
        }.getOrDefault(emptyList())
    }


internal fun AppViewModel.loadTransfers(): List<TransferTask> {
        val raw = prefsStore.getString("transfer_history", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val rawStatus = runCatching { TransferStatus.valueOf(o.optString("status", "SUCCESS")) }
                    .getOrDefault(TransferStatus.SUCCESS)
                // 进程被杀 / 被强制停止时，记录还停在 RUNNING。以前这里直接丢弃这条
                // 记录，结果是写了一半的文件（本地目标或远端目标）留在原地，界面上
                // 再也找不到它，用户也就没有入口去清理。改成「已暂停」并说明原因，
                // 用户可以直接取消传输，把残留连同记录一起清掉。
                // QUEUED 同理：排队中的记录重启后没有执行者，一并标成「已暂停」，
                // 否则会一直停在「等待中」。
                val interrupted = rawStatus == TransferStatus.RUNNING || rawStatus == TransferStatus.QUEUED
                val status = if (interrupted) TransferStatus.PAUSED else rawStatus
                TransferTask(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    mode = runCatching { TransferMode.valueOf(o.optString("mode", "DOWNLOAD")) }
                        .getOrDefault(TransferMode.DOWNLOAD),
                    title = o.optString("title", ""),
                    detail = o.optString("detail", ""),
                    total = o.optInt("total", 0),
                    // 旧记录没有这个字段：读回 false，打开时按 children / 路径回退推断。
                    folderTask = o.optBoolean("folderTask", false),
                    completed = o.optInt("completed", 0),
                    totalBytes = o.optLong("totalBytes", 0L),
                    completedBytes = o.optLong("completedBytes", 0L),
                    status = status,
                    startedAt = o.optLong("startedAt", 0L),
                    finishedAt = o.optLong("finishedAt", 0L).takeIf { it > 0L },
                    error = o.optString("error", "").ifBlank {
                        when {
                            rawStatus == TransferStatus.QUEUED -> "上次排队中被中断，可重试"
                            interrupted -> "上次传输被中断"
                            else -> null
                        }
                    },
                    from = o.optString("from", ""),
                    to = o.optString("to", ""),
                    target = o.optString("target", ""),
                    conflictOriginal = o.optString("conflictOriginal", "").ifBlank { null },
                    targetPreExisting = o.optBoolean("targetPreExisting", false),
                    targetCtx = o.optString("targetCtx", ""),
                    targetNet = o.optString("targetNet", ""),
                    sourceCtx = o.optString("sourceCtx", ""),
                    sourceNet = o.optString("sourceNet", ""),
                    sources = o.optJSONArray("sources").toStringList(),
                    // 旧记录没有这个字段：读回空表，展示时按 sources 回退推断。
                    sourceItems = o.optJSONArray("sourceItems").toStringList(),
                    children = runCatching {
                        val arr = o.optJSONArray("children") ?: org.json.JSONArray()
                        (0 until arr.length()).mapNotNull { ci ->
                            val c = arr.optJSONObject(ci) ?: return@mapNotNull null
                            TransferChild(
                                key = c.optString("key", ""),
                                name = c.optString("name", ""),
                                path = c.optString("path", ""),
                                sizeBytes = c.optLong("sizeBytes", 0L),
                                completedBytes = c.optLong("completedBytes", 0L),
                                status = runCatching { TransferChildStatus.valueOf(c.optString("status", "QUEUED")) }
                                    .getOrDefault(TransferChildStatus.QUEUED),
                                error = c.optString("error", "").ifBlank { null },
                                target = c.optString("target", ""),
                                preExisting = c.optBoolean("preExisting", false),
                                conflictOriginal = c.optString("conflictOriginal", "").ifBlank { null }
                            )
                        }
                    }.getOrDefault(emptyList()),
                    createdDirs = o.optJSONArray("createdDirs").toStringList().toSet(),
                    uploadedTargets = o.optJSONArray("uploadedTargets").toStringList().toSet()
                )
            }
        }.getOrDefault(emptyList()).take(30)
    }


/**
 * 传输记录的高频写入节流。
 *
 * 上传时每成功一个文件都要把远端路径登记进任务（删任务要按它清理），
 * 以前每次都全量序列化整份历史——包括每个任务的 children 数组。几百个文件的
 * 文件夹任务就是几百次 O(n) 序列化，而且都压在主线程上。
 * 终态（完成 / 失败 / 暂停 / 删除）仍走 [persistTransfers] 立即落盘。
 */
private const val TRANSFER_PERSIST_MIN_INTERVAL_MS = 800L
private var lastTransferPersistAt = 0L

internal fun AppViewModel.persistTransfersThrottled() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTransferPersistAt < TRANSFER_PERSIST_MIN_INTERVAL_MS) return
        lastTransferPersistAt = now
        // 快照在主线程取（读 Compose State 安全），序列化 + SharedPreferences 写盘
        // 挪到 IO：一个上千文件的文件夹任务，整份记录 JSON 可达 MB 级，
        // 之前每次节流落盘都卡主线程，列表滑动会掉帧。
        val snapshot = transfers.filterNot { it.quiet }.take(30)
        viewModelScope.launch(Dispatchers.IO) { persistTransferSnapshot(snapshot) }
    }


internal fun AppViewModel.persistTransfers() =
        persistTransferSnapshot(transfers.filterNot { it.quiet }.take(30))


private fun AppViewModel.persistTransferSnapshot(snapshot: List<TransferTask>) {
        val arr = JSONArray()
        // 静默任务（同端复制移动、在线打开缓存）不写记录、重启后也不该出现。
        snapshot.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("mode", t.mode.name)
                    .put("title", t.title)
                    .put("detail", t.detail)
                    .put("total", t.total)
                    // 旧记录没有这一项：读回 false，由打开逻辑按 children / 路径回退推断。
                    .put("folderTask", t.folderTask)
                    .put("completed", t.completed)
                    .put("totalBytes", t.totalBytes)
                    .put("completedBytes", t.completedBytes)
                    .put("status", t.status.name)
                    .put("startedAt", t.startedAt)
                    .put("finishedAt", t.finishedAt ?: 0L)
                    .put("error", t.error ?: "")
                    .put("from", t.from)
                    .put("to", t.to)
                    .put("target", t.target)
                    .put("conflictOriginal", t.conflictOriginal ?: "")
                    .put("targetPreExisting", t.targetPreExisting)
                    .put("targetCtx", t.targetCtx)
                    .put("targetNet", t.targetNet)
                    .put("sourceCtx", t.sourceCtx)
                    .put("sourceNet", t.sourceNet)
                    .put("sources", JSONArray(t.sources))
                    .put("sourceItems", JSONArray(t.sourceItems))
                    .put("children", JSONArray().also { arr ->
                        t.children.forEach { c ->
                            arr.put(
                                JSONObject()
                                    .put("key", c.key)
                                    .put("name", c.name)
                                    .put("path", c.path)
                                    .put("sizeBytes", c.sizeBytes)
                                    .put("completedBytes", c.completedBytes)
                                    .put("status", c.status.name)
                                    .put("error", c.error ?: "")
                                    .put("target", c.target)
                                    .put("preExisting", c.preExisting)
                                    .put("conflictOriginal", c.conflictOriginal ?: "")
                            )
                        }
                    })
                    .put("uploadedTargets", JSONArray(t.uploadedTargets))
                    .put("createdDirs", JSONArray(t.createdDirs))
            )
        }
        prefsStore.edit().putString("transfer_history", arr.toString()).apply()
    }


internal fun AppViewModel.updateTransfer(id: String, transform: (TransferTask) -> TransferTask) {
        transfers = transfers.map { if (it.id == id) transform(it) else it }
    }


    // 分类计数落盘：全盘扫描要十几二十秒，冷启动先用上次的数字，
    // 否则分类卡片会显示一串「0 项」，看起来像没读到文件。
internal fun AppViewModel.loadCounts(): Map<FileKind, Int> = runCatching {
        val raw = prefsStore.getString("category_counts", null) ?: return emptyMap()
        val json = JSONObject(raw)
        FileKind.entries.mapNotNull { kind ->
            val value = json.optInt(kind.name, -1)
            if (value >= 0) kind to value else null
        }.toMap()
    }.getOrDefault(emptyMap())


internal fun AppViewModel.saveCounts(counts: Map<FileKind, Int>) {
        runCatching {
            val json = JSONObject()
            counts.forEach { (kind, value) -> json.put(kind.name, value) }
            prefsStore.edit().putString("category_counts", json.toString()).apply()
        }
    }


internal fun AppViewModel.persist() {        val prefs = state.prefs
        val editor = prefsStore.edit()
            .putString("theme_mode", prefs.theme.mode.name)
            .putLong("theme_accent", prefs.theme.accent)
            .putBoolean("theme_dynamic", prefs.theme.dynamic)
            .putBoolean("theme_icon_follows", prefs.theme.iconFollowsTheme)
            .putString("tab_order", prefs.tabOrder.joinToString(",") { it.name })
            .putBoolean("show_local", prefs.showLocal)
            .putBoolean("show_syno", prefs.showSyno)
            .putBoolean("show_net", prefs.showNet)
            .putString("default_tab", prefs.defaultTab.name)
            .putString("download_folder", prefs.downloadFolder)
            .putInt("download_concurrency", prefs.downloadConcurrency.coerceIn(1, 6))
            .putString("module_order", prefs.moduleOrder.joinToString(","))
            .putStringSet("hidden_modules", prefs.hiddenModules)
            .putString("volume_id", prefs.volumeId)
            .putString("section_views", JSONObject().apply {
                prefs.sectionViews.forEach { (key, value) -> put(key, value) }
            }.toString())
            .putString("sort_key", prefs.sortKey)
            .putString("sort_dir", prefs.sortDir)
            .putString("local_sort_key", prefs.tabSorts["local_key"] ?: prefs.sortKey)
            .putString("local_sort_dir", prefs.tabSorts["local_dir"] ?: prefs.sortDir)
            .putString("syno_sort_key", prefs.tabSorts["syno_key"] ?: prefs.sortKey)
            .putString("syno_sort_dir", prefs.tabSorts["syno_dir"] ?: prefs.sortDir)
            .putString("net_sort_key", prefs.tabSorts["net_key"] ?: prefs.sortKey)
            .putString("net_sort_dir", prefs.tabSorts["net_dir"] ?: prefs.sortDir)
            .putString("view_mode", prefs.viewMode)
            .putString("tab_views", JSONObject().apply {
                prefs.tabViews.forEach { (key, value) -> put(key, value) }
            }.toString())
            .putStringSet("favorites", prefs.favorites)
            .putInt("recycle_days", prefs.recycleDays)
            .putInt("cache_keep_days", prefs.cacheKeepDays)
            .putString("category_order", prefs.categoryOrder.joinToString(","))
            .putString("open_defaults", JSONObject().apply {
                prefs.openDefaults.forEach { (key, value) -> put(key, value) }
            }.toString())
        // If encryption fails we keep the previous stored value instead of
        // writing plaintext or wiping the user's saved connections.
        val encryptedConnections = SecretVault.encrypt(connectionsJson())
        if (encryptedConnections.isNotBlank()) editor.putString("connections", encryptedConnections)
        val session = state.session
        if (rememberSession && session != null) {
            val encAccount = SecretVault.encrypt(session.account)
            val encSid = SecretVault.encrypt(session.sid)
            if (encAccount.isNotBlank() && encSid.isNotBlank()) {
                editor.putString("session_server", session.server)
                    .putString("session_account", encAccount)
                    .putString("session_name", session.name)
                    .putString("session_nas", session.nas)
                    .putString("session_mode", session.mode)
                    .putString("session_sid", encSid)
                    .putString("session_syno_token", SecretVault.encrypt(session.synoToken))
                    .putString("session_endpoint", session.endpoint)
                    .putBoolean("session_https", session.https)
            }
        } else {
            editor.remove("session_server").remove("session_account").remove("session_name")
                .remove("session_nas").remove("session_mode").remove("session_sid")
                .remove("session_syno_token")
                .remove("session_https").remove("session_endpoint")
        }
        editor.apply()
    }


internal fun AppViewModel.connectionsJson(): String {
        val arr = JSONArray()
        state.connections.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id).put("name", c.name).put("protocol", c.protocol)
                    .put("host", c.host).put("port", c.port).put("path", c.path)
                    .put("online", c.online).put("latency", c.latency)
                    .put("username", c.username).put("password", c.password)
                    .put("secure", c.secure)
            )
        }
        return arr.toString()
    }
