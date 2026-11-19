package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKinds
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.NetworkConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okio.source
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebDavRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(NetTimeouts.NET_CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NetTimeouts.READ_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    @Volatile
    var lastError: String? = null
        private set

    fun clearError() { lastError = null }

    fun base(connection: NetworkConnection): String {
        val rawPath = connection.path.trim()
        // 用户直接填了完整 URL 时，不要再拼协议和端口。
        if (rawPath.startsWith("http://", true) || rawPath.startsWith("https://", true)) {
            return rawPath.trimEnd('/')
        }
        val scheme = if (connection.secure || connection.port == 443) "https" else "http"
        val port = if ((scheme == "https" && connection.port == 443) || (scheme == "http" && connection.port == 80)) {
            ""
        } else ":${connection.port}"
        val path = rawPath.ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }
        return "$scheme://${connection.host}$port${path.trimEnd('/')}"
    }

    /**
     * WebDAV 的所有请求都必须是绝对 URL。旧代码在目标根目录为空时直接传 "/"，
     * OkHttp 会因为 URL 没有 scheme 而抛异常，表现就是「上传失败」。
     * 相对路径统一以 [base] 为前缀补全；已经是绝对 URL 的则原样返回。
     */
    fun absoluteUrl(connection: NetworkConnection, value: String): String {
        val raw = value.trim()
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw.trimEnd('/')
        val root = base(connection).trimEnd('/')
        val rel = raw.trim('/').replace('\\', '/')
        if (rel.isBlank()) return root
        // connection.path 已经被 base() 包含，避免 /dav 被拼成 /dav/dav。
        val rootPath = runCatching { URI(root).rawPath.orEmpty().trim('/') }.getOrDefault("")
        if (rootPath.isNotEmpty()) {
            if (rel == rootPath) return root
            if (rel.startsWith("$rootPath/")) return "$root/${rel.removePrefix("$rootPath/")}"
        }
        return "$root/$rel"
    }

    /** 供传输目标列表使用：WebDAV 的根必须是完整 URL，其它协议用远端路径。 */
    fun rootPath(connection: NetworkConnection): String = base(connection).trimEnd('/')

    /**
     * 连通性探测。
     *
     * 必须显式带 `Depth: 0`：PROPFIND 不写 Depth 时按 RFC 2518 默认是 `infinity`，
     * 不少服务端（含部分轻量 WebDAV / Nginx 模块 / 群晖反代）对 infinity 直接回 500，
     * 于是出现「测试说失败、点进去却能看到目录」的矛盾现象。
     * 兜底再换几种常见写法各试一次，只有全部失败才报错。
     */
    fun test(connection: NetworkConnection): Boolean {
        val root = base(connection)
        android.util.Log.d(
            "MFB-DAV",
            "test 入参 host=${connection.host} port=${connection.port} path=${connection.path} " +
                "secure=${connection.secure} -> base=$root"
        )
        val attempts = listOf(
            Triple(root.trimEnd('/') + "/", false, false),   // 目录 URL + Depth: 0（最标准）
            Triple(root.trimEnd('/'), false, false),          // 不带结尾斜杠 + Depth: 0
            Triple(root.trimEnd('/') + "/", true, false),     // 少数服务端只认 Depth: 1
            // 兜底：部分服务端（OpenList / 某些 AList 版本）解析带 body 的 PROPFIND 时直接 500，
            // 空 body 等价于 allprop，兼容性更好。
            Triple(root.trimEnd('/') + "/", false, true)
        )
        var lastCode = 0
        var lastBody = ""
        attempts.forEach { (url, depthOne, emptyBody) ->
            // 500 说明地址、认证、路径都已通过，失败发生在服务端处理阶段（实测 OpenList
            // 固定 5 秒后返回 500）。换尾斜杠、换 Depth 都不会改变结果，唯一还可能起作用的
            // 是请求体，因此 500 之后只补试一次「空 body」，其余写法直接跳过——否则用户要
            // 白等 4 × 5 秒才看到结论。
            if (lastCode == 500 && !emptyBody) return@forEach
            val request = request(connection, url)
                .method("PROPFIND", if (emptyBody) null else propfindBody())
                .header("Depth", if (depthOne) "1" else "0")
                .build()
            val code = runCatching {
                client.newCall(request).execute().use { resp ->
                    if (resp.code in 200..299) return true
                    lastCode = resp.code
                    // 500 时把服务端返回的片段带上，用户能看出是地址错还是服务端不支持。
                    lastBody = resp.body?.string().orEmpty().trim()
                    android.util.Log.d(
                        "MFB-DAV",
                        "PROPFIND $url depth=${if (depthOne) 1 else 0} emptyBody=$emptyBody -> ${resp.code} " +
                            "server=${resp.header("Server")} body=${lastBody.take(200)}"
                    )
                    resp.code
                }
            }.getOrElse { e ->
                android.util.Log.w("MFB-DAV", "PROPFIND failed ${e.javaClass.name}")
                // 连接层异常（DNS / 拒绝连接 / 超时）无从重试，直接抛给上层。
                throw IllegalStateException(friendlyNetError(e, "无法连接该服务器"))
            }
            // 只有服务端「明确拒绝方法 / 服务端内部错误」才继续换写法重试。
            if (code != 500 && code != 501 && code != 405) {
                throw IllegalStateException(
                    when (code) {
                        401 -> "认证失败（401）：用户名或密码错误"
                        403 -> "无权限（403）：该账户无权访问此目录"
                        404 -> "路径不存在：请检查路径"
                        507 -> "存储空间不足（507）"
                        else -> "服务器返回 HTTP $code"
                    }
                )
            }
        }
        val hint = lastBody.take(80).replace(Regex("\\s+"), " ").takeIf { it.isNotBlank() }
        // 500 单独给一句可执行的结论：走到这里说明地址、端口、路径和认证都已通过
        // （否则会是 401/403/404），是服务端 WebDAV 处理本身失败。
        // 网页端能打开不代表 WebDAV 可用——网页走的是服务商自己的 API，不是 WebDAV 协议。
        if (lastCode == 500) {
            throw IllegalStateException(
                "服务端错误（500）：存储离线或响应超时，请检查服务端挂载"
            )
        }
        throw IllegalStateException(
            "服务器返回 HTTP $lastCode" + if (hint != null) "：$hint" else ""
        )
    }

    /**
     * 列出目录内容。
     *
     * [allowMissing] 为真时把 404 当成「目录还不存在」返回空列表：配置同步的目录
     * 是应用自己建的，首次使用（一次都还没上传）时它本来就不存在，此时报
     * "HTTP 404" 只会让人以为同步坏了，实际要提示的是「云端还没有备份」。
     */
    suspend fun list(
        connection: NetworkConnection,
        url: String,
        allowMissing: Boolean = false
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        val absolute = absoluteUrl(connection, url)
        val request = request(connection, absolute.trimEnd('/') + "/")
            .method("PROPFIND", propfindBody())
            .header("Depth", "1")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) {
                if (allowMissing && response.code == 404) return@use emptyList()
                // 状态码本身就是结论：401/403/404 各自要做的事完全不同，
                // 统一写成「HTTP 404」等于让用户自己去查表。
                lastError = httpStatusHint("WebDAV 目录读取失败", response.code)
                throw IllegalStateException(lastError)
            }
            val xml = response.body?.string().orEmpty()
            parse(xml, absolute)
        }
    }

    /** 供外部播放器流式播放的源：WebDAV 本身就是 HTTP，带上 Basic 认证即可。 */
    fun streamSource(connection: NetworkConnection, remotePath: String): StreamProxy.Source =
        StreamProxy.Source(
            url = absoluteUrl(connection, remotePath),
            headers = if (connection.username.isNotBlank()) {
                mapOf("Authorization" to Credentials.basic(connection.username, connection.password))
            } else emptyMap()
        )

    suspend fun download(
        connection: NetworkConnection,
        url: String,
        target: File,
        abort: (() -> Boolean)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request(connection, absoluteUrl(connection, url)).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    lastError = httpStatusHint("WebDAV 下载失败", response.code)
                    return@use false
                }
                target.parentFile?.mkdirs()
                response.body?.byteStream()?.use { input ->
                    target.outputStream().use { output -> copyWithAbort(input, output, abort) }
                }
                target.length() > 0
            }
        }.onFailure {
            // 以前异常被 getOrDefault 吞掉，界面上只剩「目录不可写」这种猜谜文案。
            lastError = "WebDAV 下载异常：${friendlyNetError(it, "网络连接失败", host = connection.host)}"
        }.getOrDefault(false)
    }

    suspend fun upload(
        connection: NetworkConnection,
        local: File,
        dirUrl: String,
        name: String,
        onBytes: ((Long) -> Unit)? = null,
        abort: (() -> Boolean)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = absoluteUrl(connection, dirUrl)
            // 上传整个本地文件夹时会产生多层子目录，必须逐级 MKCOL，
            // 否则父目录不存在时 PUT 会返回 409，表现为整个上传失败。
            ensureCollection(connection, dir)
            val target = dir.trimEnd('/') + "/" + encodeSegment(name)
            val body = object : okhttp3.RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = local.length()
                override fun writeTo(sink: okio.BufferedSink) {
                    CountingInputStream(local.inputStream(), onBytes, abort).use { input ->
                        sink.writeAll(input.source())
                    }
                }
            }
            client.newCall(request(connection, target).put(body).build()).execute().use { response ->
                if (!response.isSuccessful) lastError = httpStatusHint("WebDAV 上传失败", response.code)
                response.isSuccessful
            }
        }.onFailure {
            lastError = "WebDAV 上传异常：${friendlyNetError(it, "网络连接失败", host = connection.host)}"
        }.getOrDefault(false)
    }

    /**
     * 把一段内存字节 PUT 到连接根目录下的 [name]。
     *
     * 配置备份只有几 KB，不需要像上传文件那样做流式进度与断点；
     * 走同一套 [request] / [ensureCollection] / 错误文案，行为与普通上传一致。
     */
    suspend fun putBytes(connection: NetworkConnection, name: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = base(connection)
                ensureCollection(connection, dir)
                val target = dir.trimEnd('/') + "/" + encodeSegment(name)
                client.newCall(request(connection, target).put(bytes.toRequestBody(OCTET)).build())
                    .execute().use { response ->
                        if (!response.isSuccessful) {
                            // 404 / 409 都是「目录这一层不对劲」：上面已经试过 MKCOL，
                            // 还失败说明路径本身写错了或该位置不可写，得把路径回显出来，
                            // 否则用户只看到一句「配置上传失败：路径不存在」无从下手。
                            lastError = if (response.code == 404 || response.code == 409) {
                                "配置上传失败：云端目录 ${connection.path} 不存在或不可写，" +
                                    "请检查地址与路径"
                            } else httpStatusHint("配置上传失败", response.code)
                        }
                        response.isSuccessful
                    }
            }.onFailure {
                lastError = "配置上传异常：${friendlyNetError(it, "网络连接失败", host = connection.host)}"
            }.getOrDefault(false)
        }

    /**
     * 读取连接根目录下 [name] 的完整内容。
     *
     * 404 单独给出「云端还没有配置备份」：这是首次使用同步的正常状态，
     * 不该和认证失败、网络异常混在同一句「HTTP 404」里。
     */
    suspend fun getBytes(connection: NetworkConnection, name: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = base(connection).trimEnd('/') + "/" + encodeSegment(name)
                client.newCall(request(connection, target).get().build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = if (response.code == 404) "云端还没有配置备份"
                        else httpStatusHint("配置下载失败", response.code)
                        return@use null
                    }
                    response.body?.bytes()
                }
            }.onFailure {
                lastError = "配置下载异常：${friendlyNetError(it, "网络连接失败", host = connection.host)}"
            }.getOrNull()
        }

    private val OCTET = "application/octet-stream".toMediaType()

    /** 在 dirUrl 下新建一个文件夹；已存在（405）也视为成功。 */
    suspend fun createFolder(connection: NetworkConnection, dirUrl: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = absoluteUrl(connection, dirUrl)
                ensureCollection(connection, dir)
                val target = dir.trimEnd('/') + "/" + encodeSegment(name)
                client.newCall(request(connection, target).method("MKCOL", null).build())
                    .execute().use { response ->
                        if (!response.isSuccessful && response.code != 405) {
                            lastError = httpStatusHint("新建文件夹失败", response.code)
                        }
                        response.isSuccessful || response.code == 405
                    }
            }.onFailure {
                lastError = "新建文件夹异常：${friendlyNetError(it, "网络连接失败", host = connection.host)}"
            }.getOrDefault(false)
        }

    /** 重命名文件（夹）：MOVE 到同目录新名字（Destination 头）。 */
    suspend fun rename(connection: NetworkConnection, url: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = absoluteUrl(connection, url)
                val parent = source.trimEnd('/').substringBeforeLast('/')
                val dest = "$parent/" + encodeSegment(newName)
                client.newCall(
                    request(connection, source).method("MOVE", null)
                        .header("Destination", dest)
                        .header("Overwrite", "F")
                        .build()
                ).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = httpStatusHint("WebDAV 重命名失败", response.code)
                    }
                    response.isSuccessful
                }
            }.onFailure {
                lastError = "WebDAV 重命名异常：${friendlyNetError(it, "网络连接失败", host = connection.host)}"
            }.getOrDefault(false)
        }

    suspend fun delete(connection: NetworkConnection, urls: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            var allDone = true
            // 同 SMB：不能用 all{} 短路，否则第一个 404 / 403 会让后面全部跳过。
            urls.forEach { url ->
                val ok = runCatching {
                    client.newCall(request(connection, absoluteUrl(connection, url)).delete().build())
                        .execute().use { it.isSuccessful || it.code == 404 }
                }.getOrDefault(false)
                if (!ok) allDone = false
            }
            allDone
        }.getOrDefault(false)
    }

    suspend fun copyMove(connection: NetworkConnection, urls: List<String>, destDirUrl: String, move: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val destDir = absoluteUrl(connection, destDirUrl)
                ensureCollection(connection, destDir)
                var allDone = true
                // 不能用 all{} 短路：同 delete，第一项失败会让同批其余条目全部跳过。
                urls.forEach { raw ->
                    val url = absoluteUrl(connection, raw)
                    val name = encodeSegment(url.trimEnd('/').substringAfterLast('/'))
                    val dest = destDir.trimEnd('/') + "/" + name
                    val method = if (move) "MOVE" else "COPY"
                    val request = request(connection, url)
                        .method(method, null)
                        .header("Destination", dest)
                        .header("Overwrite", "F")
                        .build()
                    val ok = runCatching {
                        client.newCall(request).execute().use { it.code in 200..299 }
                    }.getOrDefault(false)
                    if (!ok) allDone = false
                }
                allDone
            }.onFailure {
                lastError = "WebDAV 批量操作异常：${friendlyNetError(it, "网络连接失败", host = connection.host)}"
            }.getOrDefault(false)
        }

    /**
     * 逐级 MKCOL 建目录。base() 的上级层级视为服务器已存在的根，只对它及其之后的
     * 层级发请求；405（已存在）与 2xx 都算成功。
     *
     * 注意最后一级（也就是 base 自己）必须发一次 MKCOL：同步使用的却是
     * `/MyFileBox` 这种「应用自己创建」的目录，早先把 base 整个当成已存在的根，
     * 结果 PUT 到一个不存在的目录，服务端回 409 Conflict——表现就是
     * 「立即上传失败 / 同步开启不了」，而 PROPFIND 根目录却是通的。
     */
    private fun ensureCollection(connection: NetworkConnection, dirUrl: String) {
        val target = URI(dirUrl)
        val root = URI(base(connection))
        val prefix = "${target.scheme}://${target.authority}"
        val rootSegments = root.rawPath.orEmpty().trim('/').split('/').filter { it.isNotBlank() }.size
        val segments = target.rawPath.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        var current = prefix
        segments.forEachIndexed { index, segment ->
            current = "$current/$segment"
            if (index < rootSegments - 1) return@forEachIndexed
            runCatching {
                client.newCall(request(connection, current).method("MKCOL", null).build())
                    .execute().use { /* 201 created / 405 exists -> 都视为就绪 */ }
            }
        }
    }

    private fun request(connection: NetworkConnection, url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (connection.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(connection.username, connection.password))
        }
        return builder
    }

    private fun propfindBody() = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>""".trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())

    private fun parse(xml: String, requestUrl: String): List<FileEntry> {
        if (xml.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagNameNS("DAV:", "response")
        val out = mutableListOf<FileEntry>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val href = text(element, "DAV:", "href")
            if (href.isBlank()) continue
            val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
            val full = if (decoded.startsWith("http")) decoded else {
                val baseUri = URI(requestUrl)
                "${baseUri.scheme}://${baseUri.authority}${if (decoded.startsWith("/")) decoded else "/$decoded"}"
            }
            if (full.trimEnd('/') == requestUrl.trimEnd('/')) continue
            val name = decoded.trimEnd('/').substringAfterLast('/').ifBlank { decoded.trimEnd('/').substringAfterLast('/') }
            if (name.isBlank()) continue
            val prop = firstChild(element, "DAV:", "propstat")?.let { firstChild(it, "DAV:", "prop") }
            val isDir = (prop?.let {
                firstChild(it, "DAV:", "resourcetype")?.getElementsByTagNameNS("DAV:", "collection")?.length
            } ?: 0) > 0
            val length = prop?.let { text(it, "DAV:", "getcontentlength").toLongOrNull() } ?: 0L
            val modifiedText = prop?.let { text(it, "DAV:", "getlastmodified") }.orEmpty()
            val modified = parseHttpDate(modifiedText)
            out.add(
                FileEntry(
                    id = full,
                    name = name,
                    kind = if (isDir) FileKind.FOLDER else FileKinds.of(name),
                    sizeLabel = if (isDir) "" else LocalRepository.formatSize(length),
                    timeLabel = LocalRepository.formatStamp(modified),
                    folder = requestUrl.trimEnd('/').substringAfterLast('/'),
                    path = full,
                    sizeBytes = length,
                    modified = modified
                )
            )
        }
        return out
    }

    private fun text(parent: Element, ns: String, tag: String): String {
        val list = parent.getElementsByTagNameNS(ns, tag)
        return if (list.length > 0) list.item(0).textContent.orEmpty() else ""
    }

    private fun firstChild(parent: Element, ns: String, tag: String): Element? {
        val children: NodeList = parent.getElementsByTagNameNS(ns, tag)
        return children.item(0) as? Element
    }

    private fun parseHttpDate(value: String): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time ?: 0L
    }.getOrDefault(0L)

    private fun encodeSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
