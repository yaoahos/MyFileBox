package com.yaoahos.myfilebox.android.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份 / 同步可携带的配置分区。
 *
 * 只收「设置项」这一类可重建的偏好，不碰任何文件内容、缓存、传输记录、
 * 回收站索引与会话令牌（SID 绑定单次登录，换设备也必须重新登录）。
 */
enum class BackupSection(val key: String, val label: String) {
    SYNO("syno", "群晖登录"),
    NETWORK("network", "网络位置"),
    THEME("theme", "主题与外观"),
    LOCAL("local", "本地文件"),
    CATEGORY("category", "文件分类与排序"),
    TABS("tabs", "底栏与启动"),
    DOWNLOAD("download", "下载与缓存"),
    UPDATE("update", "更新检查");

    companion object {
        fun ofKey(key: String): BackupSection? = entries.firstOrNull { it.key == key }
    }
}

/** 解密成功后的备份说明：UI 先告诉用户「里面有什么」，再让用户决定是否覆盖。 */
data class BackupInfo(
    val sections: List<BackupSection>,
    val exportedAt: Long,
    val appVersion: String,
    val schema: Int
)

/**
 * 恢复结果。
 *
 * [connectionsNeedingPassword] / [synoPasswordMissing] 用来告诉用户「配置都回来了，
 * 但钥匙得自己补」：新版备份按安全约定不保存密码，恢复后这些条目要重新输入一次。
 */
data class ApplyResult(
    val sections: List<BackupSection>,
    val connectionsNeedingPassword: Int = 0,
    val synoPasswordMissing: Boolean = false
)

/**
 * 配置备份：把设置项打包成 JSON，整体交给 [BackupCrypto] 加密。
 *
 * 四条硬约束：
 * 1. **没有任何明文落盘**。JSON 只在内存里存在，写出去的第一个字节就是密文文件头。
 * 2. **口令不进文件**。文件里只有「口令校验值」（HMAC 截断 8 字节），只能用来
 *    区分「密码错」和「文件坏」，反推不出口令。
 * 3. **不含任何密码**。群晖登录密码与每条网络连接的密码都不导出，只保存地址、
 *    端口、账号、路径这些「连到哪」的信息。原因：备份会被放到网盘或另一台设备，
 *    把一批服务器密码集中进一个只由「8 位口令」保护的文件，等于把安全性从设备
 *    绑定的 Keystore 降到用户记忆的口令。恢复后首次连接需重新输入密码。
 * 4. **连接清单导入时仍用本机 Keystore 加密落盘**，本机静态存储始终设备绑定。
 */
object ConfigBackup {

    /**
     * 早期版本用的是「一设备一份」之前的单文件名。
     *
     * 仍然保留：老用户云端就这一个文件，新版恢复列表里把它作为「旧版备份」列出来，
     * 让用户能把自己那份取回来，而不是升级后凭空找不到配置。
     */
    const val LEGACY_CLOUD_FILE = "myfilebox-config.mfbx"
    const val FILE_SUFFIX = ".mfbx"
    private const val FILE_PREFIX = "MyFileBox-config-"
    private const val SCHEMA = 1
    private const val ROOT_APP = "MyFileBox"

    // ---- 各分区下辖的偏好键（与 AppViewModel 的 loadPrefs / persist 一一对应）
    private val THEME_KEYS = listOf(
        "theme_mode", "theme_accent", "theme_dynamic", "theme_icon_follows"
    )
    private val TABS_KEYS = listOf(
        "tab_order", "show_local", "show_syno", "show_net", "default_tab"
    )
    private val LOCAL_KEYS = listOf("module_order", "hidden_modules")
    private val CATEGORY_KEYS = listOf(
        "category_order", "section_views", "tab_views", "view_mode",
        "sort_key", "sort_dir",
        "local_sort_key", "local_sort_dir",
        "syno_sort_key", "syno_sort_dir",
        "net_sort_key", "net_sort_dir"
    )
    private val DOWNLOAD_KEYS = listOf("download_folder", "download_concurrency", "cache_keep_days", "recycle_days")
    private val UPDATE_KEYS = listOf("update_check_startup")

    /** 备份文件名：MyFileBox-config-20261115-2025.mfbx */
    fun suggestedFileName(now: Long = System.currentTimeMillis()): String =
        FILE_PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) + FILE_SUFFIX

    fun looksLikeBackup(name: String): Boolean = name.endsWith(FILE_SUFFIX, ignoreCase = true)

    /** 组装并加密。口令太短由 [BackupCrypto] 拒绝（WEAK_PASSWORD）。 */
    fun export(
        prefs: SharedPreferences,
        password: CharArray,
        appVersion: String,
        now: Long = System.currentTimeMillis()
    ): ByteArray = BackupCrypto.encrypt(buildPayload(prefs.all, appVersion, now).toByteArray(Charsets.UTF_8), password)

    /** 只解密看内容，不落任何改动。密码错误 / 文件损坏都会抛 [BackupCryptoException]。 */
    fun inspect(bytes: ByteArray, password: CharArray): Pair<BackupInfo, JSONObject> {
        val json = JSONObject(String(BackupCrypto.decrypt(bytes, password), Charsets.UTF_8))
        if (json.optString("app") != ROOT_APP) {
            throw BackupCryptoException(BackupError.BAD_FORMAT, "这不是 MyFileBox 的配置备份")
        }
        val schema = json.optInt("schema", 0)
        if (schema > SCHEMA) {
            throw BackupCryptoException(BackupError.UNSUPPORTED_VERSION, "备份来自更新的版本，请先升级应用")
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val sections = BackupSection.entries.filter { data.has(it.key) }
        val info = BackupInfo(
            sections = sections,
            exportedAt = json.optLong("exportedAt", 0L),
            appVersion = json.optString("appVersion", ""),
            schema = schema
        )
        return info to data
    }

    /**
     * 把解密出来的分区写回本机偏好。
     *
     * 先算出所有需要重新加密的凭据，全部成功才提交：任何一步加密失败都整体放弃，
     * 不会出现「主题导入成功、网络位置被清空」这种半截状态。
     */
    fun apply(prefs: SharedPreferences, data: JSONObject): ApplyResult {
        val editor = prefs.edit()
        val applied = mutableListOf<BackupSection>()
        var connectionsNeedingPassword = 0
        var synoPasswordMissing = false

        data.optJSONObject(BackupSection.SYNO.key)?.let { o ->
            o.optString("loginServer").takeIf { it.isNotBlank() }?.let { editor.putString("login_server", it) }
            o.optString("loginAccount").takeIf { it.isNotBlank() }?.let { editor.putString("login_account", it) }
            if (o.has("allowSelfSigned")) {
                editor.putBoolean("allow_self_signed", o.optBoolean("allowSelfSigned", true))
            }
            val password = o.optString("loginPassword")
            if (password.isNotBlank()) {
                // 旧版备份带密码：照常导入，保持向后兼容。
                val enc = SecretVault.encrypt(password)
                if (enc.isBlank()) throw IllegalStateException("凭据加密失败，本次导入已取消")
                editor.putString("login_password", enc)
            } else {
                // 新版备份不含密码：本机也没存过才需要提示补输，已有密码就别打扰。
                val local = prefs.getString("login_password", "").orEmpty()
                synoPasswordMissing = local.isBlank()
            }
            applied += BackupSection.SYNO
        }

        data.optJSONObject(BackupSection.NETWORK.key)?.let { o ->
            val raw = o.optString("connections")
            if (raw.isNotBlank()) {
                val enc = SecretVault.encrypt(raw)
                if (enc.isBlank()) throw IllegalStateException("凭据加密失败，本次导入已取消")
                editor.putString("connections", enc)
                connectionsNeedingPassword = countPasswordlessConnections(raw)
            }
            applied += BackupSection.NETWORK
        }

        data.optJSONObject(BackupSection.THEME.key)?.let { o ->
            copyKeys(o, THEME_KEYS, prefs, editor)
            applied += BackupSection.THEME
        }
        data.optJSONObject(BackupSection.LOCAL.key)?.let { o ->
            copyKeys(o, LOCAL_KEYS, prefs, editor)
            applied += BackupSection.LOCAL
        }
        data.optJSONObject(BackupSection.CATEGORY.key)?.let { o ->
            copyKeys(o, CATEGORY_KEYS, prefs, editor)
            applied += BackupSection.CATEGORY
        }
        data.optJSONObject(BackupSection.TABS.key)?.let { o ->
            copyKeys(o, TABS_KEYS, prefs, editor)
            applied += BackupSection.TABS
        }
        data.optJSONObject(BackupSection.DOWNLOAD.key)?.let { o ->
            copyKeys(o, DOWNLOAD_KEYS, prefs, editor)
            applied += BackupSection.DOWNLOAD
        }
        data.optJSONObject(BackupSection.UPDATE.key)?.let { o ->
            copyKeys(o, UPDATE_KEYS, prefs, editor)
            applied += BackupSection.UPDATE
        }

        if (applied.isEmpty()) {
            throw BackupCryptoException(BackupError.BAD_FORMAT, "备份里没有可导入的配置")
        }
        editor.apply()
        return ApplyResult(applied, connectionsNeedingPassword, synoPasswordMissing)
    }

    /**
     * 按「本机现有的值类型」原样拷贝。
     *
     * 不写死每个键的类型是有意的：以后新增一个字符串设置项，只要加进上面的 key 列表，
     * 导出 / 导入自动覆盖，不必在三个地方各改一遍。
     */
    private fun copyKeys(
        src: JSONObject,
        keys: List<String>,
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor
    ) {
        keys.forEach { key ->
            if (!src.has(key) || src.isNull(key)) return@forEach
            when (val local = prefs.all[key]) {
                is Boolean -> editor.putBoolean(key, src.optBoolean(key, local))
                is Int -> editor.putInt(key, src.optInt(key, local))
                is Long -> editor.putLong(key, src.optLong(key, local))
                is Float -> editor.putFloat(key, src.optDouble(key, local.toDouble()).toFloat())
                is Set<*> -> src.optJSONArray(key)?.let { values ->
                    editor.putStringSet(key, values.toStringSet())
                } ?: Unit // 类型不符时跳过，不能把本机设置覆盖成空集合
                is String -> editor.putString(key, src.optString(key, local))
                else -> when (val value = src.opt(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putLong(key, value.toLong())
                    is JSONArray -> editor.putStringSet(key, value.toStringSet())
                    else -> editor.putString(key, value?.toString().orEmpty())
                }
            }
        }
    }

    /** 组装载荷。参数是 Map 而不是 SharedPreferences，单元测试可以在纯 JVM 上验证结构。 */
    internal fun buildPayload(all: Map<String, *>, appVersion: String, now: Long): String {
        val data = JSONObject()

        data.put(BackupSection.SYNO.key, JSONObject().apply {
            put("loginServer", all["login_server"] as? String ?: "")
            put("loginAccount", all["login_account"] as? String ?: "")
            // 不写登录密码：备份只搬运「连到哪、用哪个账号」，不搬运钥匙。
            put("allowSelfSigned", all["allow_self_signed"] as? Boolean ?: true)
        })

        data.put(BackupSection.NETWORK.key, JSONObject().apply {
            // 容器整体已加密，这里放明文 JSON；导入时再用本机 Keystore 加密落盘。
            // 但每条连接都剔除 password：备份落在网盘/另一台设备上时，
            // 不该把一批服务器密码集中到一个「口令保护」的文件里。
            put("connections", stripConnectionPasswords(
                SecretVault.decrypt(all["connections"] as? String ?: "[]")
            ))
        })

        data.put(BackupSection.THEME.key, JSONObject().apply { THEME_KEYS.forEach { putAny(it, all) } })
        data.put(BackupSection.LOCAL.key, JSONObject().apply { LOCAL_KEYS.forEach { putAny(it, all) } })
        data.put(BackupSection.CATEGORY.key, JSONObject().apply { CATEGORY_KEYS.forEach { putAny(it, all) } })
        data.put(BackupSection.TABS.key, JSONObject().apply { TABS_KEYS.forEach { putAny(it, all) } })
        data.put(BackupSection.DOWNLOAD.key, JSONObject().apply { DOWNLOAD_KEYS.forEach { putAny(it, all) } })
        data.put(BackupSection.UPDATE.key, JSONObject().apply { UPDATE_KEYS.forEach { putAny(it, all) } })

        return JSONObject().apply {
            put("app", ROOT_APP)
            put("schema", SCHEMA)
            put("appVersion", appVersion)
            put("exportedAt", now)
            put("data", data)
        }.toString()
    }

    /**
     * 这份备份里是否含密码（旧格式会含）。
     *
     * 云同步覆盖前会把旧文件存成 `.prev` 当回滚点；若旧文件含密码，就不该再复制一份
     * 到云端。解密失败按「含密码」处理——宁可少一个回滚点，也不多留一份钥匙。
     */
    fun containsPasswords(bytes: ByteArray, password: CharArray): Boolean = try {
        val json = JSONObject(String(BackupCrypto.decrypt(bytes, password), Charsets.UTF_8))
        val data = json.optJSONObject("data")
        if (data == null) {
            false
        } else {
            val synoHas = data.optJSONObject("syno")?.optString("loginPassword").orEmpty().isNotBlank()
            val conns = data.optJSONObject("network")?.optString("connections").orEmpty()
            synoHas || (conns.isNotBlank() && conns.contains("\"password\""))
        }
    } catch (e: Exception) {
        true
    }

    /** 把连接列表 JSON 里每一条的 `password` 去掉，其余字段原样保留。 */
    internal fun stripConnectionPasswords(raw: String): String = runCatching {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.remove("password")
        }
        arr.toString()
    }.getOrDefault("[]")

    /** 统计有多少条连接没有密码（恢复后需要用户补一次）。 */
    private fun countPasswordlessConnections(raw: String): Int = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).count { arr.optJSONObject(it)?.optString("password").isNullOrBlank() }
    }.getOrDefault(0)

    private fun JSONObject.putAny(key: String, all: Map<String, *>) {
        when (val value = all[key]) {
            is Set<*> -> put(key, JSONArray().apply { value.forEach { put(it?.toString().orEmpty()) } })
            null -> Unit
            else -> put(key, value)
        }
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
    }
}
