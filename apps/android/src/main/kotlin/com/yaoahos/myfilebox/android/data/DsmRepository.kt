package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKinds
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.RecycleItem
import com.yaoahos.myfilebox.core.Session
import com.yaoahos.myfilebox.core.ShareFolder
import com.yaoahos.myfilebox.core.ShareLink
import com.yaoahos.myfilebox.core.Volume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ParsedServer(
    val host: String,
    val port: Int,
    val https: Boolean,
    val quickConnect: Boolean,
    val display: String,
    val baseUrl: String
)

/** QuickConnect 的三条通道：局域网直连、外网直连（端口映射 / DDNS）、官方中转。 */
enum class QcChannel { LAN, DIRECT, RELAY }

data class QuickConnectEndpoint(
    val baseUrl: String,
    val label: String,
    val channel: QcChannel = QcChannel.DIRECT
)

private fun buildClient(
    insecure: Boolean,
    connectSeconds: Long = NetTimeouts.CONNECT_SECONDS,
    readSeconds: Long = NetTimeouts.READ_SECONDS
): OkHttpClient = OkHttpClient.Builder()
    .apply {
        if (insecure) {
            // Only used when the user explicitly enables "允许自签名证书".
            // Certificate and hostname checks stay ON by default.
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            sslSocketFactory(ssl.socketFactory, trust)
            hostnameVerifier { _, _ -> true }
        }
    }
    .connectTimeout(connectSeconds, TimeUnit.SECONDS)
    .readTimeout(readSeconds, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

/** 一次上传尝试的参数组合：sid 放哪、path 放哪、是否带 Cookie。 */
private data class UploadPlan(
    val version: String,
    val querySid: Int,
    val bodyFields: Boolean,
    val bodySid: Boolean,
    val cookie: Boolean
)

class DsmRepository {
    private var client: OkHttpClient = buildClient(insecure = false)

    /**
     * 切网后专用客户端：旧网络里的端点已经不可达，用短超时尽快失败，
     * 让界面立刻给出「网络已变化」，而不是让用户对着转圈等满 12 秒连接超时。
     */
    private var fastClient: OkHttpClient = buildClient(
        insecure = false,
        connectSeconds = NetTimeouts.QUICK_CONNECT_SECONDS,
        readSeconds = NetTimeouts.QUICK_READ_SECONDS
    )

    private var insecureTls = false

    /** Explicit opt-in for self-signed NAS certificates; off by default. */
    fun allowSelfSignedCertificates(enabled: Boolean) {
        insecureTls = enabled
        client = buildClient(insecure = enabled)
        fastClient = buildClient(
            insecure = enabled,
            connectSeconds = NetTimeouts.QUICK_CONNECT_SECONDS,
            readSeconds = NetTimeouts.QUICK_READ_SECONDS
        )
    }

    /** Human-readable reason for the last failed upload/login, consumed by the UI. */
    @Volatile var lastError: String? = null
    fun parseServer(raw: String, preferHttps: Boolean): ParsedServer {
        var input = raw.trim().removeSuffix("/")
        var https = preferHttps
        if (input.startsWith("http://", true)) {
            https = false
            input = input.substring(7)
        } else if (input.startsWith("https://", true)) {
            https = true
            input = input.substring(8)
        }
        input = input.substringBefore('/')
        var host = input
        var port = if (https) 5001 else 5000
        if (input.startsWith("[")) {
            val end = input.indexOf(']')
            if (end > 0) {
                host = input.substring(1, end)
                val tail = input.substring(end + 1)
                if (tail.startsWith(":")) port = tail.substring(1).toIntOrNull() ?: port
            }
        } else if (input.count { it == ':' } == 1) {
            host = input.substringBefore(':')
            port = input.substringAfter(':').toIntOrNull() ?: port
        }
        val qc = !host.contains('.') && !host.equals("localhost", true) &&
            host.toLongOrNull() == null && host.count { it == '.' } == 0
        if (qc) {
            // Keep the QuickConnect ID. The real endpoint is resolved at login time.
            return ParsedServer(host, 443, true, true, host, "")
        }
        val display = if ((https && port == 443) || (!https && port == 80)) host else "$host:$port"
        return ParsedServer(host, port, https, false, display, "${if (https) "https" else "http"}://$display")
    }

    /**
     * Resolve a QuickConnect ID into direct DSM endpoints. QuickConnect's
     * <id>.quickconnect.to host serves an HTML portal, not the DSM WebAPI, so it
     * must never be used directly for API calls.
     */
    suspend fun resolveQuickConnect(id: String): List<QuickConnectEndpoint> = withContext(Dispatchers.IO) {
        val payload = JSONArray()
            .put(
                JSONObject()
                    .put("version", 1)
                    .put("command", "get_server_info")
                    .put("stop_when_error", false)
                    .put("stop_when_success", false)
                    .put("id", "dsm_portal_https")
                    .put("serverID", id)
                    .put("is_gofile", false)
            )
            .put(
                JSONObject()
                    .put("version", 1)
                    .put("command", "get_server_info")
                    .put("stop_when_error", false)
                    .put("stop_when_success", false)
                    .put("id", "dsm_portal")
                    .put("serverID", id)
                    .put("is_gofile", false)
            ).toString()
        val endpoints = LinkedHashMap<String, QuickConnectEndpoint>()
        var resolvedAny = false
        // 三个官方解析服务器并发送短超时请求：串行等待被墙 / 被劫持的域名会让
        // 「正在连接…」卡到 30 秒以上。并发后总耗时只等于最慢的那个（<=10s）。
        val resolverClient = buildClient(insecureTls, connectSeconds = 3, readSeconds = 4)
        val hosts = listOf(
            "https://global.quickconnect.cn/Serv.php",
            "https://global.quickconnect.com/Serv.php",
            // 明文 HTTP 在部分运营商数据网络下会被劫持成网页，统一走 HTTPS。
            "https://global.quickconnect.to/Serv.php"
        )
        val responses = coroutineScope {
            hosts.mapIndexed { index, host ->
                async {
                    index to runCatching {
                        val body = payload.toRequestBody("application/json".toMediaType())
                        resolverClient.newCall(Request.Builder().url(host).post(body).build()).execute().use {
                            it.body?.string().orEmpty()
                        }
                    }.getOrNull().orEmpty().trim()
                }
            }.awaitAll()
        }
        for ((_, text) in responses.sortedBy { it.first }) {
            if (text.isBlank() || text == "null" || !text.startsWith("[")) continue
            val array = runCatching { JSONArray(text) }.getOrNull() ?: continue
            // 请求里同时问了 dsm_portal_https 与 dsm_portal，任一返回有效端点都要收下；
            // 旧逻辑只取第 0 项，数据网络下 https 探测失败时会把可用的 http 通道一并丢掉。
            for (n in 0 until array.length()) {
                val entry = array.optJSONObject(n) ?: continue
                if (entry.optInt("errno", 0) != 0) continue
                val server = entry.optJSONObject("server") ?: continue
                resolvedAny = true
                val port = entry.optJSONObject("service")?.optInt("port", 0)?.takeIf { it in 1..65535 }
                    ?: server.optInt("port", 0).takeIf { it in 1..65535 }
                    ?: server.optJSONObject("external")?.optInt("port", 0)?.takeIf { it in 1..65535 }
                    ?: 5001
                val relayHost = entry.optJSONObject("env")?.optString("control_host").orEmpty()
                fun add(hostValue: String?, label: String, channel: QcChannel = QcChannel.DIRECT) {
                    val value = hostValue?.trim().orEmpty().removeSuffix("/")
                    if (value.isBlank() || value.equals("NULL", true)) return
                    val base = if (value.startsWith("http://") || value.startsWith("https://")) value
                    else "https://$value" + if (value.substringAfterLast(':').toIntOrNull() != null) "" else ":$port"
                    endpoints.putIfAbsent(base, QuickConnectEndpoint(base, label, channel))
                }
                val lanObject = server.optJSONObject("lan")
                add(lanObject?.optString("ip"), "局域网", QcChannel.LAN)
                if (lanObject == null) add(server.optString("lan"), "局域网", QcChannel.LAN)
                val interfaces = server.optJSONArray("interface")
                if (interfaces != null) {
                    for (i in 0 until interfaces.length()) {
                        val iface = interfaces.optJSONObject(i) ?: continue
                        val ip = iface.optString("ip")
                        if (ip.isBlank() || ip == "NULL") continue
                        val local = runCatching { java.net.InetAddress.getByName(ip).isSiteLocalAddress }.getOrDefault(false)
                        add(ip, if (local) "局域网" else "外网", if (local) QcChannel.LAN else QcChannel.DIRECT)
                    }
                }
                val smart = server.optJSONObject("smartdns")
                if (smart != null) {
                    add(smart.optString("host"), "直连", QcChannel.DIRECT)
                    val smartLan = smart.optJSONArray("lan")
                    if (smartLan != null && smartLan.length() > 0) {
                        add(smartLan.optString(0), "局域网", QcChannel.LAN)
                    }
                } else {
                    add(server.optString("smartdns"), "直连", QcChannel.DIRECT)
                }
                val external = server.optJSONObject("external")
                add(external?.optString("ip"), "外网", QcChannel.DIRECT)
                add(server.optString("ddns"), "DDNS", QcChannel.DIRECT)
                add(server.optString("fqdn"), "域名", QcChannel.DIRECT)
                // 中转域名只在 443 上监听，不能套用 DSM 的 $port。
                fun addRelay(hostValue: String?) {
                    val value = hostValue?.trim().orEmpty().removeSuffix("/")
                    if (value.isBlank() || value.equals("NULL", true)) return
                    val base = if (value.startsWith("http://") || value.startsWith("https://")) value
                    else "https://$value"
                    endpoints.putIfAbsent(base, QuickConnectEndpoint(base, "中转", QcChannel.RELAY))
                }
                // QuickConnect 真正的中继 API 入口（DS file / DSM 网页版同款）：
                //   https://<id>.<relay_region>.quickconnect.<tld>   例：yaoahos.cn.quickconnect.cn
                // 该域名在 443 上会 307 跳到实际节点 <id>.<region><n>.quickconnect.<tld>
                //（cn5 / cn6 …），由中继直接转发 DSM WebAPI。
                // 旧实现拼的 <id>.quickconnect.to / .cn / .com 是「Access via Synology」门户页，
                // 返回的是 HTML 而不是 DSM JSON —— 纯数据网络下据此登录必然失败。
                val env = entry.optJSONObject("env")
                val region = env?.optString("relay_region").orEmpty().trim()
                val controlTld = relayHost.substringAfterLast('.', "").ifBlank { "cn" }
                val regions = if (region.isNotEmpty()) listOf(region) else listOf("cn", "us", "eu")
                regions.forEach { addRelay("$id.$it.quickconnect.$controlTld") }
            }
            // 拿到完整有效结果就停，避免再等被墙/被劫持的备用解析域名拖慢登录；
            // 若本轮全是无效条目（劫持页），继续试下一个解析服务器。
            if (resolvedAny) break
        }
        endpoints.values.toList()
    }

    /** 设备当前所处的内网网段（/24），用于判断能否走局域网直连。 */
    private fun localSubnets(): Set<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress.orEmpty().substringBeforeLast('.') }
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrDefault(emptySet())

    private fun subnetOf(host: String): String? = runCatching {
        val address = java.net.InetAddress.getByName(host)
        if (address is java.net.Inet4Address && address.isSiteLocalAddress) {
            address.hostAddress.orEmpty().substringBeforeLast('.')
        } else null
    }.getOrNull()

    /**
     * 探测端点是否是真正的 DSM WebAPI 入口；成功时返回「跟随重定向后的」base。
     * QuickConnect 中继节点会 307 到 <id>.<region><n>.quickconnect.<tld>，把最终 base
     * 记下来，后续登录 / 上传就不用每次都多一跳。
     */
    private fun probeFinalBase(baseUrl: String): String? = runCatching {
        val probeClient = buildClient(insecureTls, connectSeconds = 5, readSeconds = 8)
        probeClient.newCall(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth")
                .get()
                .build()
        ).execute().use { response ->
            if (response.code !in 200..299) return@use null
            val text = response.body?.string().orEmpty().trimStart()
            // 运营商劫持 / QuickConnect 门户都会返回 200 的 HTML，只有真正的 DSM
            // 才会给 {"success":...} 的 JSON。
            if (!(text.startsWith("{") && text.contains("\"success\""))) return@use null
            val url = response.request.url
            buildString {
                append(url.scheme).append("://").append(url.host)
                if (url.port > 0 && url.port != 443 && url.port != 80) append(':').append(url.port)
            }
        }
    }.getOrNull()

    /**
     * 按「内网 / 外网」挑选 QuickConnect 通道：
     * - 设备与 NAS 在同一内网：只走局域网直连，完全不发中转请求；
     * - 外网（含纯数据网络）：直连与官方中转一起探测，都不通则仍返回中转候选让登录阶段重试。
     */
    /**
     * 上一次确认可用的外网通道（QuickConnect ID → 时间戳 + 已钉住的 base）。
     * 冷启动重连、切前后台重连都走同一条通道，没必要每次都重新解析一遍
     *（解析 + 探测在数据网络下要二十多秒）。
     */
    private val fastEndpointCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** 网络切换后清空 QuickConnect 端点缓存，强制重新解析（LAN 地址可能失效）。 */
    fun clearQcCache() { fastEndpointCache.clear() }

    /** 网络切换后踢掉连接池里的死连接（旧 LAN 地址的 socket 已不可达）。 */
    fun evictConnections() {
        runCatching { client.connectionPool.evictAll() }
    }

    suspend fun quickConnectCandidates(id: String): List<QuickConnectEndpoint> = withContext(Dispatchers.IO) {
        // 2 分钟内用过同一个 ID：先探上次那条通道，命中就直接用，省掉整套解析。
        fastEndpointCache[id]?.let { (at, base) ->
            if (System.currentTimeMillis() - at < 2 * 60_000L) {
                val pinned = probeFinalBase(base)
                if (pinned != null) {
                    fastEndpointCache[id] = System.currentTimeMillis() to pinned
                    return@withContext listOf(QuickConnectEndpoint(pinned, "中转", QcChannel.RELAY))
                }
            }
        }
        // 官方区域中继域名可由 QuickConnect ID 直接拼出，不依赖解析服务器。
        // 运营商数据网络下 global.quickconnect.* 解析服务常不可达，若因此返回空列表，
        // 登录阶段会直接报「未找到该 QuickConnect ID」。
        val builtInRelay = listOf(
            QuickConnectEndpoint("https://$id.cn.quickconnect.cn", "中转", QcChannel.RELAY),
            // .to 与 .cn 是同一套区域中继，运营商劫持其一时另一条仍可用。
            QuickConnectEndpoint("https://$id.cn.quickconnect.to", "中转", QcChannel.RELAY)
        )
        val all = resolveQuickConnect(id)
        val relay = (all.filter { it.channel == QcChannel.RELAY } + builtInRelay)
            .distinctBy { it.baseUrl }
        // 解析服务不可达时仍给出中继候选，交给登录阶段用正常超时逐个试。
        if (all.isEmpty()) return@withContext relay
        val lan = all.filter { it.channel == QcChannel.LAN }
        val direct = all.filter { it.channel == QcChannel.DIRECT }

        val lanSubnets = localSubnets()
        val sameLan = lan.any { endpoint ->
            val host = runCatching { java.net.URI(endpoint.baseUrl).host }.getOrNull().orEmpty()
            val subnet = subnetOf(host)
            subnet != null && subnet in lanSubnets
        }
        if (sameLan) {
            val reachableLan = probeAll(lan)
            if (reachableLan.isNotEmpty()) {
                return@withContext (reachableLan + direct.take(1) + relay).distinctBy { it.baseUrl }
            }
        }
        // 外网：直连优先，官方中继始终作为兜底候选。
        // 中继也要参与探测：正确的区域中继域名会 307 到具体节点并返回 DSM JSON，
        // 探测通过后 base 被钉到最终节点（如 example.cn6.quickconnect.cn），登录一次命中。
        // 直连与中继并发探测：顺序探的话，没有端口映射的 NAS 会把 12s 连接超时先耗完，
        // 纯数据网络下光是"找到可用通道"就要等半分钟。
        // 直连只给 4s（真有端口映射时 1s 内就能应答），中继给 8s（要经一次 307 跳转）。
        val (reachableDirect, reachableRelay) = coroutineScope {
            val directJob = async { probeAll(direct, 4_000) }
            val relayJob = async { probeAll(relay, 8_000) }
            directJob.await() to relayJob.await()
        }
        // 只有「确认过是外网、中继可用」才写缓存：内网场景走局域网直连，不该被中继截胡。
        reachableRelay.firstOrNull()?.let { fastEndpointCache[id] = System.currentTimeMillis() to it.baseUrl }
        (reachableDirect + reachableRelay + relay).distinctBy { it.baseUrl }
    }

    /**
     * 切网后的快速通道：直接试官方区域中继域名（不依赖解析服务），3 秒内可达就用。
     *
     * 命中时省掉整套 QuickConnect 解析（Serv.php + 直连探测 4s + 中继探测 8s）——
     * 那正是「切网后点刷新要等十几秒」的主因。解析服务在运营商网络下本来也常不可达。
     */
    suspend fun fastRelayCandidate(id: String): QuickConnectEndpoint? = withContext(Dispatchers.IO) {
        val builtIn = listOf(
            QuickConnectEndpoint("https://$id.cn.quickconnect.cn", "中转", QcChannel.RELAY),
            QuickConnectEndpoint("https://$id.cn.quickconnect.to", "中转", QcChannel.RELAY)
        )
        probeAll(builtIn, 3_000).firstOrNull()
    }

    /** 并发探测一组端点，返回可达的（base 已钉到最终节点）并保持原顺序。 */
    private fun probeAll(endpoints: List<QuickConnectEndpoint>, waitMs: Long = 10_000): List<QuickConnectEndpoint> {
        if (endpoints.isEmpty()) return emptyList()
        val results = java.util.concurrent.ConcurrentHashMap<String, String>()
        val threads = endpoints.map { endpoint ->
            Thread { results[endpoint.baseUrl] = probeFinalBase(endpoint.baseUrl) ?: "" }.apply { start() }
        }
        threads.forEach { runCatching { it.join(waitMs) } }
        return endpoints.mapNotNull { endpoint ->
            val pinned = results[endpoint.baseUrl]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (pinned == endpoint.baseUrl) endpoint else endpoint.copy(baseUrl = pinned)
        }.distinctBy { it.baseUrl }
    }

    fun describeServer(raw: String, preferHttps: Boolean): String {
        val value = raw.trim()
        if (value.isBlank()) return "自动识别 DSM 地址、IP、域名或 QuickConnect ID"
        val parsed = parseServer(value, preferHttps)
        return if (parsed.quickConnect) {
            "https://${parsed.host}:5001 · 或 QuickConnect ID 自动解析"
        } else {
            "${if (parsed.https) "HTTPS" else "HTTP"} · ${parsed.baseUrl}"
        }
    }

    suspend fun login(session: Session, password: String): DsmLoginResult = withContext(Dispatchers.IO) {
        // 每次登录都重置，避免上一轮的错误码影响本轮「鉴权失败就停」的判断。
        lastErrorCode = 0

        fun attempt(askSynoToken: Boolean): DsmLoginResult {
            val base = baseOf(session)
            val builder = FormBody.Builder()
                .add("api", "SYNO.API.Auth")
                .add("version", "6")
                .add("method", "login")
                .add("account", session.account)
                .add("passwd", password)
                .add("session", "FileStation")
                .add("format", "sid")
            if (askSynoToken) builder.add("enable_syno_token", "yes")
            return runCatching {
                client.newCall(
                    Request.Builder().url("$base/webapi/auth.cgi").post(builder.build()).build()
                ).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val json = asJson(text)
                    if (!json.optBoolean("success")) {
                        val err = dsmError(json)
                        android.util.Log.w(
                            "MFB-DSM",
                            "login synoToken=$askSynoToken → fail code=$lastErrorCode base=$base"
                        )
                        return@use DsmLoginResult(false, null, err)
                    }
                    val data = json.optJSONObject("data")
                    val sid = data?.optString("sid").orEmpty()
                    if (sid.isBlank()) return@use DsmLoginResult(false, null, "登录成功但未返回 SID")
                    val token = data?.optString("synotoken").orEmpty()
                    android.util.Log.w(
                        "MFB-DSM",
                        "login synoToken=$askSynoToken → ok sid=[$sid] token=[$token] base=$base"
                    )
                    DsmLoginResult(true, sid, null, token)
                }
            }.getOrElse { DsmLoginResult(false, null, friendlyNetError(it, "网络连接失败")) }
        }

        // DSM 7 默认开启 CSRF 校验：上传 / 新建 / 删除这类写操作必须带 SynoToken，
        // 只带 _sid 会被拒（119 会话无效 / 401 认证失败），而浏览不受影响——
        // 这就是「能看文件、不能上传」的根因。老版本 DSM 不认这个参数，遇到
        // 参数类错误时回退到不带 token 的登录，保证还能正常浏览。
        val first = attempt(true)
        if (first.success) return@withContext first
        if (lastErrorCode in listOf(100, 101, 102, 103, 104, 114)) {
            return@withContext attempt(false)
        }
        first
    }

    suspend fun shares(session: Session): List<ShareFolder> = withContext(Dispatchers.IO) {
        val json = runCatching { api(session, "SYNO.FileStation.List", "2", "list_share") }
            .onFailure {
                android.util.Log.w(
                    "MFB-DSM",
                    "shares failed: ${it.javaClass.simpleName}: ${it.message} code=$lastErrorCode " +
                        "tokenLen=${session.synoToken.length}"
                )
            }
            .getOrNull() ?: return@withContext emptyList()
        val arr = json.optJSONObject("data")?.optJSONArray("shares") ?: JSONArray()
        (0 until arr.length()).mapNotNull { index ->
            val item = arr.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
            val path = item.optString("path")
            val additional = item.optJSONObject("additional")
            val size = additional?.optJSONObject("volume")?.optLong("size", 0L) ?: 0L
            ShareFolder(
                key = name,
                title = name,
                subtitle = path,
                count = 0,
                items = emptyList(),
                path = path
            )
        }
    }

    /**
     * 列目录。[quick] = true 时改用短超时客户端：切网窗口内旧端点必然不可达，
     * 4 秒连接超时就能收场，不必让界面干等 12 秒。
     */
    suspend fun list(session: Session, path: String, quick: Boolean = false): List<FileEntry> = withContext(Dispatchers.IO) {
        val base = baseOf(session)
        val body = FormBody.Builder()
            .add("api", "SYNO.FileStation.List")
            .add("version", "2")
            .add("method", "list")
            .add("folder_path", path)
            .add("additional", "[\"size\",\"time\",\"type\",\"owner\"]")
            .add("limit", "1000")
            .add("_sid", session.sid)
            .build()
        // 登录一旦申请了 SynoToken（CSRF 模式），读写请求都必须带它：
        // 只带 _sid 会被判「119 会话无效」。
        val request = Request.Builder().url("$base/webapi/entry.cgi").post(body)
        if (session.synoToken.isNotBlank()) request.addHeader("X-SYNO-TOKEN", session.synoToken)
        (if (quick) fastClient else client).newCall(request.build()).execute().use { response ->
            val json = asJson(response.body?.string().orEmpty())
            if (!json.optBoolean("success")) throw IllegalStateException(dsmError(json))
            val arr = json.optJSONObject("data")?.optJSONArray("files") ?: JSONArray()
            (0 until arr.length()).mapNotNull { index ->
                val item = arr.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name")
                // 回收站目录在普通浏览里隐藏（清单 5）：统一进「回收」页看。
                if (name == "#recycle") return@mapNotNull null
                val itemPath = item.optString("path")
                val isDir = item.optBoolean("isdir")
                val additional = item.optJSONObject("additional")
                val size = additional?.optLong("size", 0L)
                    ?: additional?.optJSONObject("size")?.optLong("size", 0L)
                    ?: 0L
                val mtime = additional?.optJSONObject("time")?.optLong("mtime", 0L) ?: 0L
                FileEntry(
                    id = itemPath,
                    name = name,
                    kind = if (isDir) FileKind.FOLDER else FileKinds.of(name),
                    sizeLabel = if (isDir) "" else LocalRepository.formatSize(size),
                    timeLabel = LocalRepository.formatStamp(mtime * 1000),
                    folder = path.substringAfterLast('/'),
                    path = itemPath,
                    sizeBytes = size,
                    modified = mtime * 1000
                )
            }
        }
    }

    /** 下载直链：外部播放器 / 本地代理都复用这一份参数拼法。 */
    fun downloadUrl(session: Session, path: String): String {
        val base = baseOf(session)
        return "$base/webapi/entry.cgi?api=SYNO.FileStation.Download&version=2&method=download" +
            "&mode=download&path=${encode(path)}&_sid=${encode(session.sid)}"
    }

    /**
     * 下载到本机文件。
     *
     * 关键：登录时申请了 SynoToken（CSRF 模式）后，下载同样要带 `X-SYNO-TOKEN` 头。
     * 旧实现只带 `_sid`，DSM 回的是 200 + `{"error":{"code":119}}`（38 字节 JSON），
     * 被当成二进制落盘 → 表现就是「不管下载什么都秒成功，文件是空的/打不开」。
     * 这里显式识别 JSON 错误、写临时文件后再改名，并校验字节数为 0 的情况。
     */
    suspend fun download(
        session: Session,
        path: String,
        target: File,
        onBytes: ((Long) -> Unit)? = null,
        shouldAbort: () -> Boolean = { false },
        resume: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        lastError = null
        lastErrorCode = 0
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.part")
            // 断点续传：.part 已有数据时带 Range 头从断点继续，恢复下载不再从头覆盖。
            val existing = if (resume && tmp.exists()) tmp.length() else 0L
            val request = Request.Builder().url(downloadUrl(session, path)).get()
            if (existing > 0) request.addHeader("Range", "bytes=$existing-")
            if (session.synoToken.isNotBlank()) request.addHeader("X-SYNO-TOKEN", session.synoToken)
            client.newCall(request.build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    lastErrorCode = response.code
                    lastError = "下载失败：DSM 返回 HTTP ${response.code}"
                    return@use false
                }
                val mime = body.contentType()?.toString().orEmpty()
                if (mime.contains("json", true) || mime.contains("text/html", true)) {
                    val text = body.string()
                    val json = runCatching { asJson(text) }.getOrNull()
                    lastErrorCode = json?.optJSONObject("error")?.optInt("code", -1) ?: -1
                    lastError = "群晖拒绝下载：${if (json != null) dsmError(json) else "返回的是网页而不是文件"}"
                    return@use false
                }
                // 206 = 服务器支持断点续传，追加写；200 = 不支持，整个重来。
                val append = response.code == 206 && existing > 0
                var written = if (append) existing else 0L
                try {
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(tmp, append).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                // 主动检查暂停/取消标志：阻塞 IO 本身不响应协程取消。
                                if (shouldAbort()) {
                                    throw kotlinx.coroutines.CancellationException("用户暂停/取消下载")
                                }
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                out.write(buffer, 0, read)
                                written += read
                                onBytes?.invoke(written)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 暂停/取消：保留 .part 临时文件，恢复时可复用。
                    throw e
                } catch (e: Throwable) {
                    // 真实错误（网络断/超时）时清理 .part，避免残留垃圾。
                    runCatching { tmp.delete() }
                    throw e
                }
                if (written <= 0L) {
                    tmp.delete()
                    lastError = "下载内容为空，请检查该文件是否可读"
                    lastErrorCode = -2
                    return@use false
                }
                if (target.exists()) target.delete()
                val moved = tmp.renameTo(target)
                if (!moved) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // CancellationException 必须透传，不能被吞掉，否则协程取消无法生效。
            throw e
        } catch (e: Throwable) {
            lastError = "下载失败：${friendlyNetError(e, "网络异常")}"
            android.util.Log.w("MFB-DSM", "download ${e.javaClass.simpleName}: ${e.message} path=$path")
            false
        }
    }

    /** 供外部播放器流式播放的源（本地代理转发，带认证头）。 */
    fun streamSource(session: Session, path: String): StreamProxy.Source = StreamProxy.Source(
        url = downloadUrl(session, path),
        headers = if (session.synoToken.isNotBlank()) mapOf("X-SYNO-TOKEN" to session.synoToken)
        else emptyMap()
    )

    /**
     * 服务端缩略图：SYNO.FileStation.Thumb v2 直接返回 JPEG 字节，
     * 不用把整个视频/图片下载回来再解码。视频和图片都走这里。
     */
    suspend fun thumbnail(session: Session, path: String, size: Int = 256): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${session.server}/webapi/entry.cgi?" +
                    "api=SYNO.FileStation.Thumb&version=2&method=get" +
                    "&path=${java.net.URLEncoder.encode(path, "UTF-8")}" +
                    "&size=$size&_sid=${session.sid}" +
                    (if (session.synoToken.isNotBlank()) "&_syno_token=${session.synoToken}" else "")
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Cookie", "id=${session.sid}")
                    .apply {
                        if (session.synoToken.isNotBlank()) addHeader("X-SYNO-TOKEN", session.synoToken)
                    }
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bytes = resp.body?.bytes() ?: return@withContext null
                    // JSON 响应 = 出错了，不是图片
                    if (bytes.size < 32 && bytes.toString(Charsets.UTF_8).trimStart().startsWith("{")) null
                    else bytes.takeIf { it.size > 100 }
                }
            }.getOrNull()
        }

    /** 最近一次 DSM 错误码（119=会话无效、106=会话超时等），供 UI 决定是否自动换 SID 重试。 */
    @Volatile var lastErrorCode: Int = 0

    suspend fun upload(
        session: Session,
        local: File,
        destDir: String,
        name: String = local.name,
        onBytes: ((Long) -> Unit)? = null,
        abort: (() -> Boolean)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        lastError = null
        lastErrorCode = 0
        // 官方《Synology File Station API Guide》SYNO.FileStation.Upload v2：
        // 参数以 multipart part 传入，二进制 file 必须是最后一个 part；
        // 官方《DSM Login Web API Guide》通用错误码 119 = Invalid session。
        // 实战确认：_sid 必须同时出现在 URL query 与 form body —— 只放一处会返回
        // 119（会话无效）或 101（缺少参数）。
        // 另加 Cookie id=<sid>：走 QuickConnect 中继时请求会 307 到实际节点，
        // 中继重写 Location 会把 query 里的 _sid 二次编码，导致 119；
        // Cookie 不参与 URL 重写，是跨通道最稳的一条认证路径。
        // 上传专用：读超时压到 15s。DSM 遇到不认识的参数组合会一直不返回，
        // 用全局 30s 的话四组试完要两分钟。
        android.util.Log.w(
            "MFB-DSM",
            "upload start sid=[${session.sid}] len=${session.sid.length} " +
                "plus=${session.sid.contains('+')} slash=${session.sid.contains('/')} eq=${session.sid.contains('=')} " +
                "pct=${session.sid.contains('%')} path=$destDir"
        )
        val uploadClient = client.newBuilder()
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        /**
         * 上传一次，multipart 由本类逐字节拼（不用 OkHttp 的 MultipartBody）。
         *
         * 实测对比（DSM 7.2）：同样的参数用 Python requests 能成功，用 OkHttp 的
         * MultipartBody 一直 401 / 超时——差别就在 part 的字节格式。这里手工拼出
         * `Content-Disposition` + 空行 + 值的朴素格式，与 requests 一致：
         * 只有 Content-Disposition（file part 再加 Content-Type），不写 per-part 其它头。
         */
        fun attempt(version: String): Boolean {
            val base = baseOf(session)
            val started = System.currentTimeMillis()
            val boundary = "----MyFileBox" + System.nanoTime().toString(16)
            val url = "$base/webapi/entry.cgi?api=SYNO.FileStation.Upload&method=upload" +
                "&version=$version&_sid=${encode(session.sid)}"

            val fields = linkedMapOf(
                "api" to "SYNO.FileStation.Upload",
                "version" to version,
                "method" to "upload",
                "path" to destDir,
                "create_parents" to "true",
                "overwrite" to "true",
                "_sid" to session.sid
            )
            val fieldBytes = java.io.ByteArrayOutputStream()
            fields.forEach { (k, v) ->
                fieldBytes.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
                        .toByteArray(Charsets.UTF_8)
                )
            }
            val fileHead = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
                "filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .toByteArray(Charsets.UTF_8)
            val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val head = fieldBytes.toByteArray()
            val fileLen = local.length()

            val body = object : RequestBody() {
                override fun contentType() = "multipart/form-data; boundary=$boundary".toMediaType()
                override fun contentLength() = head.size.toLong() + fileHead.size + fileLen + tail.size
                override fun writeTo(sink: okio.BufferedSink) {
                    sink.write(head)
                    sink.write(fileHead)
                    // 暂停上传：阻塞写 socket 时协程 cancel 打断不了，
                    // 只有主动抛异常才能让 OkHttp 收手（上层据此判定「已暂停」）。
                    if (onBytes == null) {
                        local.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (abort?.invoke() == true) throw TransferAbortedException()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                sink.write(buffer, 0, read)
                            }
                        }
                    } else {
                        // 边读边报进度：传输中心能显示真实字节数，而不是只有一个转圈。
                        var sent = 0L
                        local.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (abort?.invoke() == true) throw TransferAbortedException()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                sink.write(buffer, 0, read)
                                sent += read
                                onBytes(sent)
                            }
                        }
                    }
                    sink.write(tail)
                }
            }
            val request = Request.Builder().url(url).post(body)
            if (session.synoToken.isNotBlank()) request.addHeader("X-SYNO-TOKEN", session.synoToken)
            return uploadClient.newCall(request.build()).execute().use { response ->
                val json = runCatching { asJson(response.body?.string().orEmpty()) }.getOrNull()
                val tag = "v$version token=${session.synoToken.isNotBlank()}"
                if (json == null) {
                    lastError = "群晖返回网页而非 DSM 数据，请检查地址或通道"
                    android.util.Log.w("MFB-DSM", "upload $tag → NOT JSON (http=${response.code})")
                    false
                } else {
                    val ok = json.optBoolean("success")
                    val code = json.optJSONObject("error")?.optInt("code", -1) ?: -1
                    android.util.Log.w(
                        "MFB-DSM",
                        "upload $tag → success=$ok code=$code http=${response.code} " +
                            "path=$destDir base=$base cost=${System.currentTimeMillis() - started}ms bytes=${body.contentLength()}"
                    )
                    if (!ok) {
                        lastErrorCode = code
                        lastError = "群晖拒绝上传（错误码 $code）：${dsmError(json)}"
                    } else {
                        lastError = null
                        lastErrorCode = 0
                    }
                    ok
                }
            }
        }
        runCatching {
            attempt("2")
        }.getOrElse {
            lastError = "上传请求失败：${friendlyNetError(it, "网络异常")}"
            android.util.Log.w("MFB-DSM", "upload exception ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    suspend fun delete(session: Session, paths: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = api(
                session, "SYNO.FileStation.Delete", "2", "delete",
                FormBody.Builder()
                    .add("path", JSONArray(paths).toString())
            )
            json != null
        }.getOrDefault(false)
    }

    /** 在 parent 下新建文件夹。force_parent 允许父目录不存在时一并创建。 */
    suspend fun createFolder(session: Session, parent: String, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = api(
                session, "SYNO.FileStation.CreateFolder", "2", "create",
                FormBody.Builder()
                    .add("folder_path", parent)
                    .add("name", name)
                    .add("force_parent", "true")
            )
            json != null
        }.getOrDefault(false)
    }

    /** 重命名文件（夹）。path 与 name 是一一对应的 JSON 数组（SYNO.FileStation.Rename）。 */
    suspend fun rename(session: Session, path: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = api(
                session, "SYNO.FileStation.Rename", "2", "rename",
                FormBody.Builder()
                    .add("path", JSONArray(listOf(path)).toString())
                    .add("name", JSONArray(listOf(newName)).toString())
            )
            json != null
        }.getOrDefault(false)
    }

    /**
     * 生成群晖官方的共享链接（SYNO.FileStation.Sharing v3 create）。
     * [password] 为空表示不设访问密码；[expireMinutes] 0 表示永不失效。
     * 返回可直接粘贴的链接；失败返回 null，具体原因见 lastError。
     */
    suspend fun createShareLink(
        session: Session,
        path: String,
        password: String = "",
        expireMinutes: Int = 0
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder()
                .add("path", JSONArray(listOf(path)).toString())
                .add("secure", "false")
                .add("expire_time", expireMinutes.toString())
            if (password.isNotBlank()) body.add("password", password)
            val json = api(
                session, "SYNO.FileStation.Sharing", "3", "create", body
            ) ?: return@runCatching null
            val link = json.optJSONObject("data")
                ?.optJSONArray("links")
                ?.optJSONObject(0)
                ?.optString("url")
                .orEmpty()
            if (link.isBlank()) {
                lastError = "群晖未返回共享链接，请确认该文件所在共享文件夹允许共享"
                null
            } else {
                link
            }
        }.getOrElse {
            lastError = "生成共享链接失败：${friendlyNetError(it, "请检查 DSM 版本与共享权限")}"
            null
        }
    }

    /**
     * 列出全部共享链接（SYNO.FileStation.Sharing v3 list）。
     * [forceClean] 为 true 时让 DSM 顺带清除失效链接（清单 4 的「清除无效链接」）。
     */
    suspend fun listShareLinks(session: Session, forceClean: Boolean = false): List<ShareLink> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder()
                    .add("offset", "0")
                    .add("limit", "1000")
                if (forceClean) body.add("force_clean", "true")
                val json = api(session, "SYNO.FileStation.Sharing", "3", "list", body)
                    ?: return@runCatching emptyList()
                val data = json.optJSONObject("data") ?: return@runCatching emptyList()
                // 不同 DSM 版本字段名有出入：links / shares；id / link_id。
                val arr = when {
                    data.has("links") -> data.optJSONArray("links")
                    data.has("shares") -> data.optJSONArray("shares")
                    else -> data.optJSONArray("links")
                } ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").ifBlank { o.optString("link_id") }
                    val path = o.optString("path")
                    val url = o.optString("url").ifBlank { o.optString("link") }
                    val passwordFlag = when {
                        o.has("password") && o.optBoolean("password", false) -> true
                        o.has("has_password") -> o.optBoolean("has_password", false)
                        o.optString("password").isNotBlank() &&
                            !o.optString("password").equals("null", true) -> true
                        else -> false
                    }
                    // 到期时间可能是秒级时间戳、毫秒时间戳或字符串；0 = 永不过期。
                    val rawExpire = o.optLong("expire_time", 0L).takeIf { it > 0 }
                        ?: o.optLong("date_expired", 0L).takeIf { it > 0 }
                        ?: runCatching { o.optString("expire").toLong() }.getOrDefault(0L)
                    val expireAt = when {
                        rawExpire <= 0L -> 0L
                        rawExpire > 10_000_000_000L -> rawExpire          // 已是毫秒
                        else -> rawExpire * 1000L                          // 秒 → 毫秒
                    }
                    ShareLink(
                        id = if (id.isBlank()) "$i" else id,
                        path = path,
                        url = url,
                        hasPassword = passwordFlag,
                        expireAt = expireAt,
                        invalid = url.isBlank()
                    )
                }
            }.getOrElse {
                lastError = "读取共享链接失败：${friendlyNetError(it, "请稍后重试")}"
                emptyList()
            }
        }

    /** 取消共享（SYNO.FileStation.Sharing v3 delete）：按链接 id 批量删除。 */
    suspend fun deleteShareLinks(session: Session, ids: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray(ids)
                val json = api(
                    session, "SYNO.FileStation.Sharing", "3", "delete",
                    FormBody.Builder()
                        .add("id", arr.toString())
                        .add("link_id", arr.toString())
                )
                json != null
            }.getOrElse {
                lastError = "取消共享失败：${friendlyNetError(it, "请稍后重试")}"
                false
            }
        }

    /**
     * 列出群晖回收站（各共享文件夹的 #recycle）内容（清单 5）。
     * #recycle 下的相对路径就是原始相对路径，所以原始位置 = 共享根 + 相对路径。
     * [topLevelOnly] 为 true 时只取每个 #recycle 的第一层（一键恢复的执行单位）。
     * [tracked] 是本应用删除时记录的删除单元：整只文件夹按记录展示，不展开内部文件。
     * 记录的单元先逐个独立验证存在性——不能依赖下面的截断遍历（一个塞满
     * 几千个文件的目录就能把 500 条预算吃光，导致后面的子树根本走不到）。
     */
    suspend fun listRecycleBin(
        session: Session,
        sharePaths: List<String>,
        topLevelOnly: Boolean = false,
        tracked: List<RecycleItem> = emptyList()
    ): List<RecycleItem> = withContext(Dispatchers.IO) {
        runCatching {
            // 1) 独立验证删除单元还在不在（列父目录查一次，顺带刷新大小）。
            val validated = tracked.mapNotNull { unit ->
                val parent = unit.trashPath.substringBeforeLast('/', "")
                if (parent.isBlank()) return@mapNotNull null
                val hit = runCatching { list(session, parent) }.getOrDefault(emptyList())
                    .firstOrNull { it.path.trimEnd('/') == unit.trashPath.trimEnd('/') }
                    ?: return@mapNotNull null
                if (unit.isFolder) unit
                else unit.copy(sizeBytes = hit.sizeBytes)
            }
            // 2) BFS 遍历实况（给未记录的条目兜底）。
            val out = mutableListOf<RecycleItem>()
            for (share in sharePaths) {
                val root = share.trimEnd('/')
                val binRoot = "$root/#recycle"
                val queue = ArrayDeque<String>()
                queue.add(binRoot)
                while (queue.isNotEmpty()) {
                    val dir = queue.removeFirst()
                    val items = runCatching { list(session, dir) }.getOrDefault(emptyList())
                    var added = 0
                    for (item in items) {
                        if (item.name == "#recycle") continue
                        // 子目录总是排队：保证单个大目录不会饿死后面的目录。
                        if (item.isFolder && !topLevelOnly) queue.add(item.path)
                        // 列条目有预算：每个目录最多 250 条、总共最多 500 条。
                        if (out.size >= 500 || added >= 250) continue
                        val rel = item.path.removePrefix("$binRoot/").removePrefix(binRoot)
                            .trimStart('/')
                        out += RecycleItem(
                            id = item.path,
                            name = item.name,
                            originalPath = "$root/${rel.trimStart('/')}",
                            trashPath = item.path,
                            deletedAt = item.modified,
                            sizeBytes = item.sizeBytes,
                            isFolder = item.isFolder
                        )
                        added++
                    }
                    if (topLevelOnly && dir == binRoot) break
                }
            }
            mergeTrackedRecycle(out, validated)
        }.getOrElse {
            lastError = "读取回收站失败：${friendlyNetError(it, "请稍后重试")}"
            emptyList()
        }
    }

    /**
     * 把回收站里的一项恢复到原始位置。原始父目录缺失时逐级递归创建。
     * 恢复本质 = 从 #recycle 移回原处（与 File Station 的「还原」一致）。
     * 整只文件夹（删除单元）会连同内部文件一次性移回。
     */
    suspend fun restoreRecycleItem(session: Session, item: RecycleItem): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val destParent = item.originalPath.substringBeforeLast('/', "")
                if (destParent.isNotBlank()) {
                    // 删掉整个文件夹后原始父链可能整条消失，必须逐级补齐，
                    // 只建一层时深层文件还原会因目录不存在而失败。
                    val parts = destParent.trim('/').split('/').filter { it.isNotBlank() }
                    for (i in 1 until parts.size) {
                        createFolder(session, "/" + parts.subList(0, i).joinToString("/"), parts[i])
                    }
                }
                val ok = copyMove(session, listOf(item.trashPath), destParent, removeSource = true)
                // #recycle 是按原路径建目录的（/Backup/Temp/Ces/0.log → #recycle/Temp/Ces/0.log）。
                // 文件被移走后，这些空壳目录会留在回收站里，列表上就凭空多出一个「Ces」。
                // 还原成功后把变空的祖先目录一并清掉，只保留 #recycle 本身。
                if (ok) {
                    // CopyMove 可能是异步任务（start 立刻返回 taskid）：必须等源路径
                    // 真的消失再判空，否则目录还差一拍「非空」，空壳就清不掉（3.9.18）。
                    awaitRecyclePathGone(session, item.trashPath)
                    pruneEmptyRecycleDirs(session, item.trashPath)
                }
                ok
            }.getOrElse {
                lastError = "恢复失败：${friendlyNetError(it, "请稍后重试")}"
                false
            }
        }

    /**
     * 等待 #recycle 里的某个路径真正消失（CopyMove 是异步任务时的收尾同步）。
     * 超时就放弃等待：后面的清理只是幂等的空目录删除，慢一拍也不致命。
     */
    private suspend fun awaitRecyclePathGone(session: Session, trashPath: String) {
        val parent = trashPath.substringBeforeLast('/', "")
        if (parent.isBlank()) return
        repeat(20) {
            val stillThere = runCatching {
                list(session, parent).any { it.path.trimEnd('/') == trashPath.trimEnd('/') }
            }.getOrDefault(false)
            if (!stillThere) return
            kotlinx.coroutines.delay(150)
        }
    }

    /**
     * 从被还原项的父目录一路向上，删掉 #recycle 里的空目录。
     * 只删「确实为空」的目录，遇到非空立即停止，不会误删还有其它待还原文件的目录。
     */
    private suspend fun pruneEmptyRecycleDirs(session: Session, trashPath: String) {
        val index = trashPath.indexOf("/#recycle")
        if (index < 0) return
        val recycleRoot = trashPath.substring(0, index + "/#recycle".length)
        var dir = trashPath.substringBeforeLast('/', "")
        var guard = 0
        while (dir.length > recycleRoot.length && dir.startsWith("$recycleRoot/") && guard++ < 32) {
            val empty = runCatching {
                val json = api(
                    session, "SYNO.FileStation.List", "2", "list",
                    FormBody.Builder().add("folder_path", dir).add("limit", "1")
                )
                json?.optJSONObject("data")?.optInt("total", -1) == 0
            }.getOrDefault(false)
            if (!empty) break
            val deleted = runCatching {
                api(
                    session, "SYNO.FileStation.Delete", "2", "delete",
                    FormBody.Builder()
                        .add("path", JSONArray(listOf(dir)).toString())
                        .add("recursive", "false")
                ) != null
            }.getOrDefault(false)
            if (!deleted) break
            dir = dir.substringBeforeLast('/', "")
        }
    }

    /** 彻底删除回收站里的项目（#recycle 内的永久删除）。 */
    suspend fun deleteRecycleItems(session: Session, paths: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = api(
                    session, "SYNO.FileStation.Delete", "2", "delete",
                    FormBody.Builder()
                        .add("path", JSONArray(paths).toString())
                        .add("recursive", "true")
                )
                val ok = json != null
                // 与还原同款：清掉只剩空壳的上层目录，否则回收站列表会多出
                // 一个「实际并不存在」的上层文件夹（3.9.18 修）。
                if (ok) paths.forEach { pruneEmptyRecycleDirs(session, it) }
                ok
            }.getOrElse {
                lastError = "删除失败：${friendlyNetError(it, "请稍后重试")}"
                false
            }
        }

    suspend fun copyMove(session: Session, paths: List<String>, dest: String, removeSource: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = api(
                    session, "SYNO.FileStation.CopyMove", "3", "start",
                    FormBody.Builder()
                        .add("path", JSONArray(paths).toString())
                        .add("dest_folder_path", dest)
                        .add("overwrite", "false")
                        .add("remove_src", if (removeSource) "true" else "false")
                )
                json != null
            }.getOrDefault(false)
        }

    suspend fun volumes(session: Session): List<Volume> = withContext(Dispatchers.IO) {
        runCatching {
            val json = api(session, "SYNO.Core.Storage.Volume", "1", "list") ?: return@runCatching emptyList()
            val arr = json.optJSONObject("data")?.optJSONArray("volumes") ?: JSONArray()
            (0 until arr.length()).mapNotNull { index ->
                val item = arr.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").ifBlank { "v${index + 1}" }
                val total = item.optLong("size", 0L).toDouble() / 1024.0 / 1024.0 / 1024.0
                val used = item.optLong("used_size", 0L).toDouble() / 1024.0 / 1024.0 / 1024.0
                Volume(
                    id = id,
                    name = item.optString("display_name").ifBlank { "存储空间 ${index + 1}" },
                    usedTb = used / 1024.0,
                    totalTb = total / 1024.0,
                    percent = if (total > 0) ((used / total) * 100).toInt() else 0,
                    type = item.optString("device_type").ifBlank { item.optString("type") }
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun api(
        session: Session,
        api: String,
        version: String,
        method: String,
        extra: FormBody.Builder = FormBody.Builder()
    ): JSONObject? = withContext(Dispatchers.IO) {
        val base = baseOf(session)
        val body = extra
            .add("api", api)
            .add("version", version)
            .add("method", method)
            .add("_sid", session.sid)
            .build()
        // CSRF 模式下所有 entry.cgi 请求都要带 X-SYNO-TOKEN，只带 _sid 会 119。
        val request = Request.Builder().url("$base/webapi/entry.cgi").post(body)
        if (session.synoToken.isNotBlank()) request.addHeader("X-SYNO-TOKEN", session.synoToken)
        client.newCall(request.build()).execute().use { response ->
            val json = asJson(response.body?.string().orEmpty())
            if (!json.optBoolean("success")) throw IllegalStateException(dsmError(json))
            json
        }
    }

    private var favoriteProbeKey: String? = null
    private var favoriteVersion: Int = 0

    /**
     * DSM 6.2 之后才有 SYNO.FileStation.Favorite（官方「收藏」），
     * 且不同型号支持的最高版本不同（1 或 2）。这里按 endpoint 探测一次并缓存版本号：
     * 返回 0 表示该 NAS 没有这个接口，界面上就完全不出现「收藏」。
     */
    suspend fun favoriteApiVersion(session: Session): Int = withContext(Dispatchers.IO) {
        val key = baseOf(session)
        if (favoriteProbeKey == key) return@withContext favoriteVersion
        val version = runCatching {
            val url = "$key/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query" +
                "&query=SYNO.FileStation.Favorite"
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val json = asJson(response.body?.string().orEmpty())
                if (!json.optBoolean("success")) {
                    0
                } else {
                    val max = json.optJSONObject("data")
                        ?.optJSONObject("SYNO.FileStation.Favorite")
                        ?.optInt("maxVersion", 0) ?: 0
                    // add / delete 都是 version 2 才提供
                    if (max >= 2) max else 0
                }
            }
        }.getOrDefault(0)
        // 只缓存成功的探测：网络抖动失败时缓存 0 会让「收藏」永久消失
        // （synoFavoriteSupported 被钉死 false），下次操作必须重试探测。
        if (version > 0) {
            favoriteProbeKey = key
            favoriteVersion = version
        }
        version
    }

    suspend fun supportsFavorite(session: Session): Boolean = favoriteApiVersion(session) > 0

    /**
     * 调用官方收藏接口添加 / 移除。
     * 官方文档：path 是「带引号的单个 JSON 字符串」，add 还要 name，且只支持文件夹。
     */
    suspend fun setFavorite(session: Session, paths: List<String>, on: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (paths.isEmpty()) return@withContext false
            val version = favoriteApiVersion(session)
            if (version <= 0) {
                lastError = "这台群晖不支持官方收藏接口"
                return@withContext false
            }
            val v = version.toString()
            runCatching {
                paths.forEach { path ->
                    val body = FormBody.Builder().add("path", JSONObject.quote(path))
                    if (on) body.add("name", JSONObject.quote(path.trimEnd('/').substringAfterLast('/')))
                    api(session, "SYNO.FileStation.Favorite", v, if (on) "add" else "delete", body)
                }
                true
            }.getOrElse {
                lastError = "收藏操作失败：${friendlyNetError(it)}"
                false
            }
        }

    private fun asJson(text: String): JSONObject {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("<")) {
            throw IllegalStateException("服务器返回网页而非 DSM 数据，请检查地址或改用 QuickConnect")
        }
        return JSONObject(trimmed)
    }

    private fun baseOf(session: Session): String {
        val endpoint = session.endpoint?.trim().orEmpty()
        if (endpoint.isNotEmpty()) return endpoint.removeSuffix("/")
        val raw = session.server.trim().removeSuffix("/")
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return "${if (session.https) "https" else "http"}://$raw"
    }

    private fun dsmError(json: JSONObject): String {
        val code = json.optJSONObject("error")?.optInt("code", -1) ?: -1
        lastErrorCode = code
        return when (code) {
            // SYNO.API.Auth 登录专用码
            400 -> "账号或密码错误"
            401 -> "账号已被停用"
            402 -> "需要两步验证"
            403 -> "需要两步验证"
            404 -> "两步验证失败"
            407 -> "IP 已被封禁"
            408 -> "密码已过期"
            409 -> "密码必须修改"
            410 -> "密码已过期"
            // 《DSM Login Web API Guide》通用码 / FileStation 文件操作码
            100 -> "群晖返回未知错误"
            101 -> "缺少 api / method / version 参数"
            102 -> "该接口不存在"
            103 -> "该接口方法不存在"
            104 -> "该接口版本不支持此功能"
            105 -> "当前会话没有该操作权限"
            106 -> "会话已超时"
            107 -> "会话被重复登录打断"
            108 -> "文件上传失败"
            109, 110, 111, 117, 118 -> "网络不稳定或群晖繁忙"
            114 -> "缺少该接口所需参数"
            115 -> "不允许上传文件"
            116 -> "该站点不允许此操作"
            119 -> "会话无效（_sid 未被识别）"
            150 -> "请求来源 IP 与登录 IP 不一致"
            1001 -> "接口不存在，可能不是群晖 DSM"
            -1 -> "群晖返回了未知错误"
            else -> "群晖错误码 $code"
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}

data class DsmLoginResult(
    val success: Boolean,
    val sid: String?,
    val error: String?,
    val synoToken: String? = null
)

/**
 * DSM 的 #recycle 保留原目录结构（删 /photo/2025/a.jpg 会落到
 * #recycle/2025/a.jpg），递归展开后 2025 和 a.jpg 会同时出现，
 * 看上去就是「每一层目录都并列列出」。
 *
 * 这里只保留「真正被删的那一项」：某个目录只要还有下级，它就只是原路径的
 * 中间层，丢掉；空目录保留（那是被整个删掉的空文件夹）。
 */
internal fun leafRecycleItems(items: List<RecycleItem>): List<RecycleItem> {
    val paths = items.map { it.trashPath.trimEnd('/') }.toSet()
    return items.filter { item ->
        if (!item.isFolder) return@filter true
        val self = item.trashPath.trimEnd('/')
        paths.none { other -> other != self && other.startsWith("$self/") }
    }
}

/**
 * 把 #recycle 实况与「删除单元」记录合并成回收站列表（[tracked] 已验证存在）：
 * - 记录的单元整只展示——文件夹就是一行，不展开内部文件；
 * - 单元内部的子项不再单列（还原文件夹会连内容一并回来）；
 * - 单元的祖先目录是保存原路径的中间层壳，也不单列；
 * - 记录嵌套时只保留最外层单元（先删文件再删所在文件夹的场景）；
 * - 没有记录的条目（外部删除）退回 [leafRecycleItems] 规则兜底。
 */
internal fun mergeTrackedRecycle(raw: List<RecycleItem>, tracked: List<RecycleItem>): List<RecycleItem> {
    val present = tracked
    val valid = present.filter { t ->
        val self = t.trashPath.trimEnd('/')
        present.none { o ->
            o.isFolder && o.trashPath.trimEnd('/') != self &&
                self.startsWith(o.trashPath.trimEnd('/') + "/")
        }
    }
    val unitPaths = valid.map { it.trashPath.trimEnd('/') }.toSet()
    val unitFolders = valid.filter { it.isFolder }.map { it.trashPath.trimEnd('/') }
    val untracked = raw.filter { item ->
        val p = item.trashPath.trimEnd('/')
        // 单元自身、单元内部 → 归并进单元；
        // 单元的祖先 → 只是中间层壳目录，不单列。
        p !in unitPaths &&
            unitFolders.none { f -> p.startsWith("$f/") } &&
            unitPaths.none { u -> u.startsWith("$p/") }
    }
    val units = valid.map { unit ->
        if (!unit.isFolder) unit
        else unit.copy(
            childCount = raw.count {
                it.trashPath.trimEnd('/').substringBeforeLast('/') == unit.trashPath.trimEnd('/')
            }
        )
    }
    return (units + leafRecycleItems(untracked)).sortedByDescending { it.deletedAt }
}
