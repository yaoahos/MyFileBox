package com.yaoahos.myfilebox.android.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yaoahos.myfilebox.core.NetworkConnection
import java.util.concurrent.TimeUnit

/** 本应用的偏好文件名：ViewModel、备份、同步、后台任务必须写同一份，改一处即可。 */
const val PREFS_NAME = "myfilebox_prefs"

/** 自动同步周期：0 = 关闭。WorkManager 的最小周期是 15 分钟，这里给的档位都远大于它。 */
enum class SyncInterval(val hours: Int, val label: String) {
    OFF(0, "关闭"),
    HOURS_6(6, "每 6 小时"),
    DAY_1(24, "每天"),
    DAYS_7(168, "每 7 天");

    companion object {
        fun ofHours(hours: Int): SyncInterval = entries.firstOrNull { it.hours == hours } ?: OFF
    }
}

/**
 * 云同步配置。
 *
 * [password] 是 WebDAV 账号密码，[hasCipher] 标记「配置密码」是否已设置。
 * 两者在内存里是明文，落盘一律走 [SecretVault]（设备绑定 Keystore）；
 * 而备份文件里的配置密码永远不落盘内容，只用于派生密钥。
 */
/**
 * 云端配置「一设备一份」的命名与身份。
 *
 * 文件名形如 `myfilebox-config-Xiaomi_Pad_6S_Pro-3f9a.mfbx`：
 * 前半是本机可读名（默认取系统「设备名称」，取不到用「厂商 + 型号」，用户可改），
 * 后半是 4 位短码。
 *
 * **为什么要短码**：同型号两台设备（家里两台同款手机、公司配的同款平板）型号串
 * 完全一致，只靠型号名会互相覆盖——那正是「不同设备要独立」要避免的事。
 * 短码首次生成后固定不变，改名也不变，因此能稳定区分「同一台」与「另一台」。
 */
object DeviceProfile {
    private const val PREFIX = "myfilebox-config-"
    private const val SUFFIX = ".mfbx"
    private const val KEY_ID = "sync_device_id"
    private const val KEY_NAME = "sync_device_name"
    private const val KEY_LAST_FILE = "sync_device_last_file"
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** 4 位短码：首次使用时生成并落盘，之后不再变。 */
    fun id(prefs: SharedPreferences): String {
        prefs.getString(KEY_ID, null)?.takeIf { it.length == 4 }?.let { return it }
        val generated = (0 until 4).map { ALPHABET.random() }.joinToString("")
        prefs.edit().putString(KEY_ID, generated).apply()
        return generated
    }

    /** 显示名：改过就用改过的，否则用设备型号，再不行用厂商 + 型号。 */
    fun name(prefs: SharedPreferences): String =
        prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: hardwareName()

    fun setName(prefs: SharedPreferences, name: String) {
        prefs.edit().putString(KEY_NAME, name.trim()).apply()
    }

    /**
     * 本机默认名：优先读**只读的设备型号**（`ro.product.marketname` 等系统属性）。
     * 小米等 ROM 的 `Build.MODEL` 填的是认证型号（24018RPACC 这类），直接展示没人认得；
     * 属性都取不到时再回落「厂商 + 型号」。注意不能用 `Settings.Global` 的
     * `device_name`：那是用户可改的「设备名称」（蓝牙名），不是型号。
     */
    fun hardwareName(): String = resolvedName(marketingModel(), Build.MANUFACTURER, Build.MODEL)

    /** 拆成纯函数便于单测：设备型号 > 厂商+型号（型号已带厂商则不重复）> Android。 */
    internal fun resolvedName(modelName: String?, manufacturer: String, model: String): String {
        val fromProperty = modelName?.trim().orEmpty()
        if (fromProperty.isNotEmpty()) return fromProperty
        val maker = manufacturer.trim()
        val rawModel = model.trim()
        val joined = if (maker.isNotEmpty() && !rawModel.lowercase().startsWith(maker.lowercase())) {
            "$maker $rawModel"
        } else rawModel
        return joined.trim().ifBlank { "Android" }
    }

    /** 只读的营销型号属性，按优先级取第一个非空值。 */
    private val MODEL_PROPS = listOf(
        "ro.product.marketname",
        "ro.product.odm.marketname",
        "ro.product.vendor.marketname",
        "ro.product.system.marketname"
    )

    /** 读一次就缓存：属性读取不便宜，设置页每次重组不该重复读。 */
    @Volatile
    private var cachedModel: String? = null

    private fun marketingModel(): String? {
        cachedModel?.let { return it.ifBlank { null } }
        val value = MODEL_PROPS.firstNotNullOfOrNull { key ->
            systemProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        }
        cachedModel = value ?: ""
        return value
    }

    /**
     * 读系统属性：先反射 `android.os.SystemProperties.get`（快），被隐藏 API 限制
     * 拦下时退回 `getprop` 命令（不需要权限）。两者都失败返回 null。
     */
    private fun systemProperty(key: String): String? {
        runCatching {
            val get = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
            (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            process.inputStream.bufferedReader().use { it.readText() }
                .trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** 文件名里只留字母、数字、中文、下划线与连字符，避免 WebDAV 端拼路径出岔。 */
    fun slug(text: String): String = text.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
        .ifBlank { "device" }

    fun fileName(prefs: SharedPreferences): String =
        PREFIX + slug(name(prefs)) + "-" + id(prefs) + SUFFIX

    /** 从文件名反解出（显示名, 短码）；不是本应用的配置返回 null。 */
    fun parse(fileName: String): Pair<String, String>? {
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) return null
        val body = fileName.removePrefix(PREFIX).removeSuffix(SUFFIX)
        val dash = body.lastIndexOf('-')
        if (dash <= 0 || dash == body.length - 1) return null
        val id = body.substring(dash + 1)
        if (id.length != 4) return null
        return body.substring(0, dash).replace('_', ' ').trim() to id
    }

    /** 上次成功上传用的文件名：改名后据此把旧文件清掉，不在云端留幽灵设备。 */
    fun lastFileName(prefs: SharedPreferences): String =
        prefs.getString(KEY_LAST_FILE, "").orEmpty()

    fun markFileName(prefs: SharedPreferences, fileName: String) {
        prefs.edit().putString(KEY_LAST_FILE, fileName).apply()
    }
}

/** 云端的一份设备配置，供恢复时列表选择。 */
data class CloudConfigFile(
    val fileName: String,
    val deviceName: String,
    val deviceId: String,
    val sizeBytes: Long,
    val modified: Long,
    /** 是不是本机上传的那份（恢复时默认选中它）。 */
    val isLocal: Boolean = false
) {
    /** 「Xiaomi 14 · 3f9a」；旧版单文件没有短码时只显示名字。 */
    val label: String get() = if (deviceId.isBlank()) deviceName else "$deviceName · $deviceId"
}

/**
 * 云同步配置。
 *
 * [password] 是 WebDAV 账号密码，[hasCipher] 标记「配置密码」是否已设置。
 * 两者在内存里是明文，落盘一律走 [SecretVault]（设备绑定 Keystore）；
 * 配置密码本身不进备份文件，只用于派生密钥。
 */
data class SyncSettings(
    val host: String = "",
    val port: Int = DEFAULT_WEBDAV_PORT,
    val path: String = DEFAULT_SYNC_PATH,
    val username: String = "",
    val password: String = "",
    val secure: Boolean = true,
    /**
     * 自动同步周期（小时）。默认每天一次：配好 WebDAV 就该「配完即生效」，
     * 再要求用户回来把开关从「关闭」拨到某个周期，等于默认不工作。
     * 用户主动选「关闭」时存 0，与「还没选过」区分开（老用户存的 0 也不会被改）。
     */
    val autoHours: Int = SyncInterval.DAY_1.hours,
    val hasCipher: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastResult: String = "",
    /** 本机在云端的配置名（可改）；为空时优先系统「设备名称」，再回落「厂商 + 型号」。 */
    val deviceName: String = ""
) {
    val interval: SyncInterval get() = SyncInterval.ofHours(autoHours)
    val configured: Boolean get() = host.isNotBlank()

    /**
     * 自动同步是否真的在跑：连接填好了、周期不是「关闭」。
     *
     * 没有单独的「开启同步」开关——早先那个开关是多余的：用户在「自动同步」里
     * 选「关闭」就等于关掉，选任何周期就等于开启。两个开关叠在一起只会出现
     * 「总开关关着、周期选着」这种互相矛盾的中间状态。
     */
    val autoSyncOn: Boolean get() = configured && autoHours > 0

    /**
     * 复用统一的 [NetworkConnection]，WebDAV 请求构造、绝对 URL 补全、错误文案全部一致。
     *
     * 地址栏允许用户直接粘贴完整 URL（`https://nas.local:5006/dav`）——输入框写的是
     * 「服务器地址或域名」，粘贴整条链接是最自然的动作。这里把它拆成 scheme / 主机 /
     * 端口 / 路径四段再交给 [NetworkConnection]：否则 `base()` 会拼出
     * `https://https://nas.local…` 这种非法 URL，OkHttp 直接抛异常，
     * 用户看到的就是一句「配置上传异常」（同步一直失败的典型原因之一）。
     */
    fun connection(): NetworkConnection {
        var hostPart = host.trim()
        var pathPart = path.ifBlank { DEFAULT_SYNC_PATH }
        var secureFlag = secure
        if (hostPart.startsWith("http://", true) || hostPart.startsWith("https://", true)) {
            secureFlag = hostPart.startsWith("https://", true)
            val rest = hostPart.substringAfter("://")
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                hostPart = rest.substring(0, slash)
                val inlinePath = rest.substring(slash)
                // 路径栏还是默认值时，用地址里带的路径作为 WebDAV 根，再补上应用自己的
                // 目录：坚果云这类服务的根就是 `https://dav.jianguoyun.com/dav/`，
                // 少了 /MyFileBox 会把备份文件撒在用户网盘根目录里。
                if (path.isBlank() || path.trim() == DEFAULT_SYNC_PATH) {
                    val trimmed = inlinePath.trimEnd('/')
                    pathPart = if (trimmed.endsWith(DEFAULT_SYNC_PATH)) trimmed
                    else trimmed + DEFAULT_SYNC_PATH
                }
            } else {
                hostPart = rest
            }
        }
        hostPart = hostPart.trimEnd('/')
        var portPart = port
        val colon = hostPart.lastIndexOf(':')
        if (colon > 0 && hostPart.indexOf(']') < colon) {
            hostPart.substring(colon + 1).toIntOrNull()?.let {
                portPart = it
                hostPart = hostPart.substring(0, colon)
            }
        }
        return NetworkConnection(
            id = "config-sync",
            name = "配置同步",
            protocol = "webdav",
            host = hostPart,
            port = portPart,
            path = pathPart,
            online = false,
            latency = 0,
            username = username,
            password = password,
            secure = secureFlag
        )
    }

    companion object {
        const val DEFAULT_WEBDAV_PORT = 5006
        const val DEFAULT_SYNC_PATH = "/MyFileBox"
    }
}

/** 同步配置的持久化：键名统一 `sync_` 前缀，与业务设置分开，清空同步不会误删别的设置。 */
class ConfigSyncStore(private val prefs: SharedPreferences) {

    fun load(): SyncSettings {
        val cipher = prefs.getString("sync_cipher", "").orEmpty()
        return SyncSettings(
            host = prefs.getString("sync_host", "").orEmpty(),
            port = prefs.getInt("sync_port", SyncSettings.DEFAULT_WEBDAV_PORT),
            path = prefs.getString("sync_path", SyncSettings.DEFAULT_SYNC_PATH).orEmpty(),
            username = prefs.getString("sync_user", "").orEmpty(),
            password = SecretVault.decrypt(prefs.getString("sync_pass", "").orEmpty()),
            secure = prefs.getBoolean("sync_secure", true),
            autoHours = prefs.getInt("sync_auto_hours", SyncInterval.DAY_1.hours),
            hasCipher = cipher.isNotBlank(),
            lastSyncAt = prefs.getLong("sync_last_at", 0L),
            lastResult = prefs.getString("sync_last_result", "").orEmpty(),
            deviceName = DeviceProfile.name(prefs)
        )
    }

    /** 保存连接信息。[cipher] 为 null 表示沿用原来的配置密码（未设置过则保持未设置）。 */
    fun save(settings: SyncSettings, cipher: String? = null) {
        val editor = prefs.edit()
            // 老版本存过「同步总开关」；现在由「自动同步」周期表达开关，这个键不再读，
            // 清除时顺手删掉，免得日后误以为它还在起作用。
            .remove("sync_enabled")
            .putString("sync_host", settings.host.trim())
            .putInt("sync_port", settings.port)
            .putString("sync_path", settings.path.ifBlank { SyncSettings.DEFAULT_SYNC_PATH })
            .putString("sync_user", settings.username)
            .putBoolean("sync_secure", settings.secure)
            .putInt("sync_auto_hours", settings.autoHours)

        // 设备名走 DeviceProfile 自己的键：改名后下次上传即换新文件名，
        // 不跟 sync_* 混在一起，清除同步设置时也不会凭空把设备名抹掉。
        if (settings.deviceName.isNotBlank() && settings.deviceName.trim() != DeviceProfile.name(prefs)) {
            DeviceProfile.setName(prefs, settings.deviceName)
        }

        // 密码为空 = 用户没改：保留原值，避免「编辑时留空就把密码清掉」。
        if (settings.password.isNotEmpty()) {
            val encrypted = SecretVault.encrypt(settings.password)
            if (encrypted.isNotBlank()) editor.putString("sync_pass", encrypted)
        }
        if (cipher != null && cipher.isNotEmpty()) {
            val encrypted = SecretVault.encrypt(cipher)
            if (encrypted.isNotBlank()) editor.putString("sync_cipher", encrypted)
        }
        editor.apply()
    }

    /** 配置密码（明文，仅用于生成备份密钥；用完请自行清空 char 数组）。 */
    fun cipher(): CharArray =
        SecretVault.decrypt(prefs.getString("sync_cipher", "").orEmpty()).toCharArray()

    fun clear() {
        prefs.edit()
            .remove("sync_enabled").remove("sync_host").remove("sync_port").remove("sync_path")
            .remove("sync_user").remove("sync_pass").remove("sync_secure")
            .remove("sync_auto_hours").remove("sync_cipher")
            .remove("sync_last_at").remove("sync_last_result")
            .apply()
    }

    fun markResult(at: Long, result: String) {
        prefs.edit().putLong("sync_last_at", at).putString("sync_last_result", result).apply()
    }
}

/** 云端读写的唯一实现：本地备份、手动同步、后台定时同步都走这里，行为不会出现两套。 */
object ConfigCloudSync {

    sealed interface Push {
        data class Ok(val at: Long) : Push
        data class Fail(val message: String) : Push
    }

    /**
     * 上传本机配置。
     *
     * 覆盖前先把云端现有文件挪到 `.prev`：任何一次误覆盖都还能捞回上一版，
     * 而配置本身只有几 KB，多留一份几乎没有成本。
     */
    suspend fun push(
        prefs: SharedPreferences,
        settings: SyncSettings,
        cipher: CharArray,
        appVersion: String,
        now: Long = System.currentTimeMillis()
    ): Push {
        if (!settings.configured) return Push.Fail("请先填写 WebDAV 地址")
        if (cipher.size < BackupCrypto.MIN_PASSWORD_LENGTH) {
            return Push.Fail("配置密码至少 ${BackupCrypto.MIN_PASSWORD_LENGTH} 位")
        }
        val fileName = DeviceProfile.fileName(prefs)
        val connection = settings.connection()
        val dav = WebDavRepository()
        val bytes = try {
            ConfigBackup.export(prefs, cipher, appVersion, now)
        } catch (e: BackupCryptoException) {
            return Push.Fail(e.message ?: "配置加密失败")
        }

        // 只覆盖「自己这一份」：云端现在按设备分文件，别人的配置不会被这次上传动到。
        val previous = runCatching { dav.getBytes(connection, fileName) }.getOrNull()
        if (previous != null && BackupCrypto.isBackupFile(previous)) {
            dav.putBytes(connection, fileName + ".prev", previous)
        }
        dav.clearError()
        val ok = dav.putBytes(connection, fileName, bytes)
        if (!ok) return Push.Fail(dav.lastError ?: "上传失败")

        // 改过设备名时，旧文件名已经没人认领：顺手删掉，免得云端堆一串幽灵设备。
        // 删不掉也不影响本次上传结果（下次改名还会再试）。
        val stale = DeviceProfile.lastFileName(prefs)
        if (stale.isNotBlank() && stale != fileName) {
            val urls = listOf(stale, "$stale.prev")
                .map { runCatching { dav.absoluteUrl(connection, it) }.getOrDefault("") }
                .filter { it.isNotEmpty() }
            if (urls.isNotEmpty()) runCatching { dav.delete(connection, urls) }
        }
        DeviceProfile.markFileName(prefs, fileName)
        return Push.Ok(now)
    }

    /** 拉取指定设备的那一份配置（由用户在列表里选，不隐式挑「本机」）。 */
    suspend fun fetch(settings: SyncSettings, fileName: String): ByteArray {
        if (!settings.configured) throw IllegalStateException("请先填写 WebDAV 地址")
        val dav = WebDavRepository()
        dav.clearError()
        val bytes = dav.getBytes(settings.connection(), fileName)
            ?: throw IllegalStateException(dav.lastError ?: "云端还没有这份配置")
        return bytes
    }

    /**
     * 列出云端所有设备的配置。
     *
     * 本机那份排最前（恢复时默认选它），其余按修改时间倒序——用户通常想找
     * 「另一台设备最近上传的那份」。文件名里的短码保证同型号两台设备也能分开。
     */
    suspend fun list(prefs: SharedPreferences, settings: SyncSettings): List<CloudConfigFile> {
        if (!settings.configured) throw IllegalStateException("请先填写 WebDAV 地址")
        val dav = WebDavRepository()
        dav.clearError()
        val connection = settings.connection()
        val entries = dav.list(connection, dav.rootPath(connection), allowMissing = true)
        val localFile = DeviceProfile.fileName(prefs)
        return entries.mapNotNull { entry ->
            if (entry.isFolder) return@mapNotNull null
            val parsed = DeviceProfile.parse(entry.name)
            if (parsed == null) {
                // 旧版「一设备一份」之前的单文件：单独列一条，升级后仍能找回自己的配置。
                if (entry.name == ConfigBackup.LEGACY_CLOUD_FILE) {
                    CloudConfigFile(
                        fileName = entry.name,
                        deviceName = "旧版备份",
                        deviceId = "",
                        sizeBytes = entry.sizeBytes,
                        modified = entry.modified
                    )
                } else null
            } else {
                CloudConfigFile(
                    fileName = entry.name,
                    deviceName = parsed.first,
                    deviceId = parsed.second,
                    sizeBytes = entry.sizeBytes,
                    modified = entry.modified,
                    isLocal = entry.name == localFile
                )
            }
        }.sortedWith(
            compareByDescending<CloudConfigFile> { it.fileName == localFile }
                .thenByDescending { it.modified }
        )
    }
}

/**
 * 后台定时同步只做「上传本机配置」这一件事。
 *
 * 刻意不在这里做下载覆盖：后台没有任何界面可以确认，一旦云端是旧配置，
 * 静默覆盖本机就是不可撤销的破坏。下载恢复一律由用户在「备份与同步」页触发。
 */
class ConfigSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, 0)
        val store = ConfigSyncStore(prefs)
        val settings = store.load()
        if (settings.autoHours <= 0 || !settings.configured || !settings.hasCipher) {
            return Result.success()
        }
        val cipher = store.cipher()
        if (cipher.isEmpty()) return Result.success()
        val version = runCatching {
            applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        val result = ConfigCloudSync.push(prefs, settings, cipher, version)
        return when (result) {
            is ConfigCloudSync.Push.Ok -> {
                store.markResult(result.at, "自动同步完成")
                Result.success()
            }
            is ConfigCloudSync.Push.Fail -> {
                // 只有「重试也没用」的才算真失败；其余一律交给 WorkManager 退避重试。
                // 早期实现用中文子串猜「像不像网络问题」，猜漏了就把一次网络抖动当成
                // 永久失败，得等下一个周期（可能 24 小时）才补上——用户以为一直在备份。
                val permanent = isPermanentSyncFailure(result.message)
                store.markResult(
                    System.currentTimeMillis(),
                    if (permanent) result.message else "${result.message}（将自动重试）"
                )
                BackupFailureNotifier.notify(applicationContext, prefs, result.message, permanent)
                if (permanent) Result.success() else Result.retry()
            }
        }
    }
}


/**
 * 失败是否属于「重试也没用」。
 *
 * 只认凭据 / 配置 / 路径这几类确定性问题（密码不对、地址没填、认证被拒、路径不存在）；
 * 超时、DNS 解析失败、连接被拒、5xx、TLS 握手失败等一律按可重试处理。宁可多试几次，
 * 也不要让一次网络波动把这场备份彻底跳过。
 */
internal fun isPermanentSyncFailure(message: String): Boolean =
    message.contains("密码") || message.contains("请先填写") ||
        message.contains("配置加密失败") || message.contains("认证") ||
        message.contains("权限") ||
        message.contains("HTTP 401") || message.contains("HTTP 403") ||
        message.contains("HTTP 404") || message.contains("HTTP 405")

/** 自动同步的调度入口：周期变了就更新，关掉就撤销，不留重复任务。 */
object ConfigSyncScheduler {
    private const val WORK_NAME = "myfilebox-config-sync"

    fun apply(context: Context, autoHours: Int) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (autoHours <= 0) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ConfigSyncWorker>(autoHours.toLong(), TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            // 失败重试从 1 分钟起指数退避（默认策略是 30 秒起、上限 5 小时）。
            // 网络恢复后能较快补上，真断网时也不会频繁唤醒。
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        runCatching {
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}


/**
 * 自动备份失败提醒。
 *
 * 后台失败时用户界面上一片安静——不提醒的话，配置可能几周都没上传成功，用户却以为
 * 一切正常。同一个问题 24 小时内只提醒一次，避免周期任务本身变成骚扰源。
 */
private object BackupFailureNotifier {
    private const val CHANNEL_ID = "myfilebox_backup"
    private const val NOTIFY_ID = 0x4D46
    private const val KEY_LAST_NOTICE = "sync_fail_notice_at"
    private const val COOLDOWN_MS = 24 * 60 * 60 * 1000L

    fun notify(context: Context, prefs: SharedPreferences, reason: String, permanent: Boolean) {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_NOTICE, 0L) < COOLDOWN_MS) return
        // Android 13+ 没授予通知权限时直接跳过：既发不出去，也不该占掉冷却窗口。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "备份提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "自动备份没有完成时提醒"
                }
            )
        }
        val pending = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val tail = if (permanent) {
            "需要你处理：打开「设置 → 备份与同步」修正后点「立即上传」。"
        } else {
            "网络恢复后会自动重试，也可以打开「设置 → 备份与同步」点「立即上传」。"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("自动备份没有完成")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason\n\n$tail"))
            .setAutoCancel(true)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
        runCatching {
            manager.notify(NOTIFY_ID, notification)
            prefs.edit().putLong(KEY_LAST_NOTICE, now).apply()
        }
    }
}
