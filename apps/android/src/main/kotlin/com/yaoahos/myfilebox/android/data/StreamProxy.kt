package com.yaoahos.myfilebox.android.data

import android.util.Log
import com.yaoahos.myfilebox.android.StreamService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.SecureRandom

/**
 * 本机流式代理：把「需要认证的远程文件」包装成一个本地 http 地址。
 *
 * 为什么需要它：外部播放器（系统视频、VLC 等）只能接收一个 URL，没法替我们带
 * `X-SYNO-TOKEN` / Basic 认证头，也没法接受自签证书。App 在本机 loopback 起一个
 * 小 HTTP 服务，收到请求后带上认证头向上游取数据并原样转发（含 Range 分段），
 * 播放器拿到的就是标准的、可拖动进度的 HTTP 流。
 *
 * 地址形如 `http://127.0.0.1:<port>/s/<key>/<原始文件名>`：
 * 文件名后缀必须带上——不少播放器（小米视频、部分电视端 App）靠 URL 结尾的扩展名
 * 判断容器格式，纯 `/s/<key>` 会被直接判成「无法识别的格式」而播放失败；
 * 顺带播放器标题栏也能显示真实文件名，而不是一串随机 key。
 *
 * 只在进程内使用：源地址存内存，进程结束即失效，不写磁盘、不监听外网。
 */
object StreamProxy {

    /**
     * 一个可被播放的远端资源。
     * - url + headers：HTTP 系协议（WebDAV / DSM），直接向上游取数据。
     * - openStream：非 HTTP 协议（SMB/FTP/SFTP）用回调返回 InputStream 流式转发。
     *   offset 参数用于 Range 拖动（>=0 表示从该字节偏移开始）。
     * - contentLength：非 HTTP 协议必须尽量给出真实字节数（调用方从列表信息里带下来）。
     *   不知道总长时播放器无法拖动进度，而且发 Range 请求时我们无法回 206，
     *   只能把「从 offset 开始的数据」当成 200 的完整文件返回，播放器会解出花屏 / 直接失败。
     * - mime：上游是 octet-stream 或未知时用它作为 Content-Type。
     */
    data class Source(
        val url: String = "",
        val headers: Map<String, String> = emptyMap(),
        val contentLength: Long = -1L,
        val mime: String = "",
        val name: String = "",
        val openStream: ((offset: Long) -> InputStream)? = null
    )

    private val routes = ConcurrentHashMap<String, Source>()

    /** 应用上下文：用于启停保活前台服务（MIUI 后台网络限制，见 StreamService）。 */
    @Volatile
    private var appContext: android.content.Context? = null

    /** 正在传输的连接数：> 0 时保持前台服务，全部结束后延迟退出。 */
    private val activeStreams = java.util.concurrent.atomic.AtomicInteger(0)

    fun attach(context: android.content.Context) {
        appContext = context.applicationContext
    }

    private fun keepAliveStart() {
        activeStreams.incrementAndGet()
        appContext?.let { StreamService.start(it) }
    }

    private fun keepAliveEnd() {
        val left = activeStreams.decrementAndGet()
        if (left <= 0) {
            val ctx = appContext ?: return
            // 播放器拖动进度会短暂断开再连；90 秒内没新连接才真正退出前台。
            pool.execute {
                try {
                    Thread.sleep(90_000)
                } catch (_: InterruptedException) {
                    return@execute
                }
                if (activeStreams.get() <= 0) StreamService.stop(ctx)
            }
        }
    }

    // 播放器拖动进度条时会并发开好几条连接（新请求还没关，旧请求才刚被取消），
    // 4 条线程在 4K 大文件上容易被占满，表现为「拖一下就卡住不动」。
    private val pool = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "mfb-stream").apply { isDaemon = true }
    }

    // 上游取数据的客户端：跟随「允许自签证书」开关，与 DSM 主客户端口径一致。
    // 默认开关为 true（用户要求保持），此时信任自签——群晖 LAN 端点常见 https://<IP>:5001
    // + 自签证书，严格校验会握手失败、点了播放没画面（真机 3.9.12 实测）。
    // 用户在设置里关闭该开关后 configure(false) 切到严格校验；只影响代理向上游的连接，
    // 对外仍是 127.0.0.1 明文回环，播放器侧不受影响。
    private val insecureClient: OkHttpClient = run {
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), java.security.SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 关闭「允许自签证书」后使用：系统默认信任链 + 主机名校验，超时口径与上面一致。 */
    private val strictClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var client: OkHttpClient = insecureClient

    /** 由 ViewModel 初始化与设置变更时调用，让流代理与 DSM 仓库共用同一开关。 */
    fun configure(allowSelfSigned: Boolean) {
        client = if (allowSelfSigned) insecureClient else strictClient
    }

    @Volatile
    private var server: ServerSocket? = null

    private val SECURE_RANDOM = SecureRandom()

    @Volatile
    var port: Int = 0
        private set

    /** 注册一个源，返回可以直接交给播放器的本地地址。 */
    fun publish(source: Source): String {
        ensureServer()
        // 只保留最近 32 个源：一次播放一个地址，旧地址留着只会占内存。
        if (routes.size >= 32) routes.keys.take(routes.size - 31).forEach { routes.remove(it) }
        // 用密码学随机串做 key：nanoTime+url.hashCode 可预测，同机恶意应用能猜中地址直接读流。
        val key = ByteArray(16).also { SECURE_RANDOM.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        routes[key] = source
        val suffix = source.name.takeIf { it.isNotBlank() }?.let { safeSegment(it) }
        val url = if (suffix.isNullOrBlank()) "http://127.0.0.1:$port/s/$key"
        else "http://127.0.0.1:$port/s/$key/$suffix"
        Log.d("MFB-STREAM", "publish: $url")
        return url
    }

    fun release(url: String) {
        val key = url.substringAfter("/s/", "").substringBefore('/').substringBefore('?')
        if (key.isNotBlank()) routes.remove(key)
    }

    /** 文件名安全编码：保留扩展名可见，去掉会破坏路径的字符。 */
    private fun safeSegment(name: String): String {
        // 头部保留、尾部保留扩展名：超长文件名（蓝光原盘那种一长串）会让 URL 过长，
        // 个别播放器对超长 URL 处理不稳，截到 80 字符足够识别格式。
        val cleaned = name.replace('/', '_').replace('\\', '_').trim()
        val short = if (cleaned.length <= 80) cleaned else {
            val ext = cleaned.substringAfterLast('.', "")
            val base = cleaned.substringBeforeLast('.')
            val keep = (80 - ext.length - 1).coerceAtLeast(8)
            base.take(keep) + if (ext.isBlank()) "" else ".$ext"
        }
        return runCatching { URLEncoder.encode(short, "UTF-8").replace("+", "%20") }
            .getOrDefault(short)
    }

    private fun ensureServer() {
        if (server?.isClosed == false && port > 0) return
        synchronized(this) {
            if (server?.isClosed == false && port > 0) return
            val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            port = socket.localPort
            server = socket
            Thread({
                while (!socket.isClosed) {
                    val connection = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching { pool.execute { handle(connection) } }
                }
            }, "mfb-stream-accept").apply { isDaemon = true }.start()
        }
    }

    private fun handle(connection: Socket) {
        keepAliveStart()
        try {
            runCatching {
                connection.use { socket ->
                socket.soTimeout = 15_000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return@use
                val parts = requestLine.split(' ')
                if (parts.size < 2) return@use
                val method = parts[0].uppercase()
                val path = parts[1]
                var range: String? = null
                // 只关心 Range，其余头（UA/Cookie 等）没有用处。
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                val key = path.substringAfter("/s/", "").substringBefore('/').substringBefore('?')
                val source = routes[key]
                val output = socket.getOutputStream()
                if (source == null) {
                    Log.d("MFB-STREAM", "$method /s/$key -> 404 (expired)")
                    writeSimple(output, 404, "Not Found", "text/plain", "expired".toByteArray())
                    return@use
                }
                // 播放器会顺手探测同名字幕（把扩展名换成 .srt/.ass/.vtt 挨个试）。
                // 代理是按 key 取源的，不看文件名，这些探测会被当成「视频又请求了一遍」，
                // 白白占用连接（SMB/FTP 每次都要重新建会话，实测能把播放拖到超时）。
                // 扩展名与源文件不一致时直接 404，播放器立刻放弃探测。
                if (!sameExtension(path, source.name)) {
                    Log.d("MFB-STREAM", "$method ${path.substringAfterLast('/')} -> 404 (sidecar)")
                    writeSimple(output, 404, "Not Found", "text/plain", ByteArray(0))
                    return@use
                }
                val spec = parseRange(range)
                Log.d(
                    "MFB-STREAM",
                    "$method ${path.substringAfterLast('/')} range=$range length=${source.contentLength} " +
                        "http=${source.openStream == null}"
                )
                // 非 HTTP 源（SMB/FTP/SFTP）：用回调直接流式转发。
                val streamFactory = source.openStream
                if (streamFactory != null) {
                    serveStreamFactory(output, method, spec, source, streamFactory)
                    return@use
                }
                // HTTP 源：向上游转发。上游（群晖 WebDAV 等）在 WiFi / 中继链路上可能
                // 中途断流（真机 3.9.12 实测 4~30 秒不等），断了就从已发字节处重新
                // Range 续传，播放器看到的始终是一条连续的响应体，不会报 Source error。
                serveHttpSource(output, method, range, source)
            }
        }.onFailure { Log.d("MFB-STREAM", "handle failed: ${it.javaClass.simpleName} ${it.message}") }
        } finally {
            keepAliveEnd()
        }
    }

    /**
     * HTTP 源转发：响应头发一次，body 在上游断流时自动 Range 续传。
     *
     * 真机实测（群晖 LAN https + 1.4G mkv）：上游每隔几秒到几十秒会断一次连接
     * （SocketException / 连接重置），旧实现直接把异常当「播放器离开」吞掉，
     * 播放器收不全数据报 Source error。现在断流后按「已写字节 + 原始 Range 起点」
     * 重新请求上游，最多重试 5 次，播放器侧完全无感。
     */
    private fun serveHttpSource(
        output: OutputStream,
        method: String,
        range: String?,
        source: Source
    ) {
        val spec = parseRange(range)
        val total = source.contentLength
        // 播放器视角的 body 区间 [playerStart, playerEnd]（end=-1 表示到末尾）。
        var playerStart = spec?.start ?: 0L
        if (playerStart < 0 && total > 0) playerStart = (total + playerStart).coerceAtLeast(0L)
        playerStart = playerStart.coerceAtLeast(0L)
        val playerEnd = spec?.end ?: -1L

        // 播放器应当收到的 body 总长：有总长就能精确算；没有就等首次响应后按上游长度定。
        var expectedLength = when {
            playerEnd > 0 -> playerEnd - playerStart + 1
            total > 0 -> total - playerStart
            else -> -1L
        }

        var sent = 0L                    // 已经写给播放器的字节数（相对 body 起点）
        var headersWritten = false
        var attempt = 0
        while (attempt <= 5) {
            val call = try {
                val from = playerStart + sent
                val effectiveRange = if (from > 0 || playerEnd > 0) {
                    if (playerEnd > 0) "bytes=$from-$playerEnd" else "bytes=$from-"
                } else null
                val builder = Request.Builder().url(source.url)
                source.headers.forEach { (k, v) -> builder.addHeader(k, v) }
                if (!effectiveRange.isNullOrBlank()) builder.addHeader("Range", effectiveRange)
                if (method == "HEAD") builder.head()
                client.newCall(builder.build())
            } catch (t: Throwable) {
                Log.d("MFB-STREAM", "request build failed: ${t.javaClass.simpleName}")
                return
            }
            try {
                call.execute().use { response ->
                    val body = response.body
                    val contentType = body?.contentType()?.toString().orEmpty()
                    Log.d(
                        "MFB-STREAM",
                        "upstream code=${response.code} cr=${response.header("Content-Range")} " +
                            "len=${body?.contentLength()} skip-will=${if (response.code == 200) playerStart + sent else 0L}"
                    )
                    // 上游无视 Range 回 200（整文件）：从 body 里跳过已请求/已发的偏移，
                    // 否则播放器拿到错位字节直接解出花屏或 Source error。
                    val skip = if (response.code == 200) playerStart + sent else 0L
                    if (!headersWritten) {
                        if (expectedLength < 0) {
                            val upstreamLen = body?.contentLength() ?: -1L
                            expectedLength = if (upstreamLen > 0) upstreamLen - skip else -1L
                        }
                        val ranged = spec != null || playerStart > 0 || playerEnd > 0
                        val headers = StringBuilder()
                        headers.append("HTTP/1.1 ")
                            .append(if (ranged) "206 Partial Content" else "${response.code} OK")
                            .append("\r\n")
                        headers.append("Content-Type: ")
                            .append(pickContentType(contentType, source.mime)).append("\r\n")
                        if (expectedLength >= 0) {
                            headers.append("Content-Length: ").append(expectedLength).append("\r\n")
                        }
                        val endPos = when {
                            playerEnd > 0 -> playerEnd
                            total > 0 -> total - 1
                            expectedLength >= 0 -> playerStart + expectedLength - 1
                            else -> -1L
                        }
                        if (ranged && endPos >= 0 && total > 0) {
                            headers.append("Content-Range: bytes ").append(playerStart).append('-')
                                .append(endPos).append('/').append(total).append("\r\n")
                        }
                        headers.append("Accept-Ranges: bytes\r\n")
                        headers.append("Connection: close\r\n\r\n")
                        output.write(headers.toString().toByteArray(Charsets.UTF_8))
                        headersWritten = true
                    }
                    if (method != "HEAD" && body != null) {
                        body.byteStream().use { stream ->
                            if (skip > 0) skipFully(stream, skip)
                            sent += copyHttpBody(stream, output, expectedLength - sent)
                        }
                    }
                    output.flush()
                }
                return // 正常传完
            } catch (t: Throwable) {
                call.cancel()
                if (t is ClientGoneException) {
                    Log.d("MFB-STREAM", "client gone: ${t.javaClass.simpleName}")
                    return
                }
                attempt++
                Log.d(
                    "MFB-STREAM",
                    "upstream drop ($sent/$expectedLength sent), retry $attempt: ${t.javaClass.simpleName} ${t.message ?: ""}"
                )
                if (attempt > 5) {
                    Log.d("MFB-STREAM", "transfer aborted: ${t.javaClass.simpleName}")
                    return
                }
                try {
                    Thread.sleep(300L * attempt)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** 播放器断开（Broken pipe 等）——续传没有意义。 */
    private class ClientGoneException(cause: Throwable) : RuntimeException(cause)

    /** 上游读失败 —— 可以从断点续传。 */
    private class UpstreamDropException(cause: Throwable) : RuntimeException(cause)

    /**
     * 把上游 body 复制给播放器：输出端异常视为播放器离开，输入端异常视为上游断流。
     * [remaining] 为本段最多还应写多少字节（<0 表示不限）；返回实际写入字节数。
     */
    private fun copyHttpBody(input: InputStream, output: OutputStream, remaining: Long = -1L): Long {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (remaining < 0 || written < remaining) {
            val want = if (remaining < 0) buffer.size
            else minOf(buffer.size.toLong(), remaining - written).toInt()
            val read = try {
                input.read(buffer, 0, want)
            } catch (t: Throwable) {
                throw UpstreamDropException(t)
            }
            if (read < 0) break
            if (read == 0) continue
            try {
                output.write(buffer, 0, read)
                output.flush()
                written += read
            } catch (t: Throwable) {
                throw ClientGoneException(t)
            }
        }
        return written
    }

    /**
     * 非 HTTP 源的分段响应。
     *
     * 关键点：只要知道总长度就**必须**回 206 + Content-Range。旧实现在 length 未知时
     * 对「Range: bytes=1000000-」也回 200，然后把从 100 万字节开始的数据当成整个文件
     * 交给播放器，播放器按文件头解析 → 直接报「无法播放」。
     */
    private fun serveStreamFactory(
        output: OutputStream,
        method: String,
        spec: RangeSpec?,
        source: Source,
        factory: (Long) -> InputStream
    ) {
        val total = source.contentLength
        var start = spec?.start ?: 0L
        var end = spec?.end ?: -1L
        var status = 200
        val headers = StringBuilder()
        if (total > 0) {
            if (start < 0) start = (total + start).coerceAtLeast(0L)   // suffix：bytes=-N
            if (end < 0 || end > total - 1) end = total - 1
            if (start >= total) {
                val head = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Content-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                output.write(head.toByteArray(Charsets.UTF_8))
                output.flush()
                return
            }
            if (spec != null) status = 206
            headers.append("HTTP/1.1 ").append(status).append(' ')
                .append(if (status == 206) "Partial Content" else "OK").append("\r\n")
            headers.append("Content-Range: bytes ").append(start).append('-').append(end)
                .append('/').append(total).append("\r\n")
            headers.append("Content-Length: ").append(end - start + 1).append("\r\n")
        } else {
            // 总长未知（极少数情况：列表信息里没带 size）：仍然按播放器要求从 start 开始发，
            // 并用 206 + 开放区间告诉它「这是一段，长度未知」，避免被当成完整文件。
            headers.append("HTTP/1.1 ").append(if (spec != null) "206 Partial Content" else "200 OK")
                .append("\r\n")
            if (spec != null && start > 0) {
                headers.append("Content-Range: bytes ").append(start).append("-*/*\r\n")
            }
        }
        headers.append("Content-Type: ").append(pickContentType("", source.mime)).append("\r\n")
        headers.append("Accept-Ranges: bytes\r\n")
        headers.append("Connection: close\r\n\r\n")
        output.write(headers.toString().toByteArray(Charsets.UTF_8))
        if (method == "HEAD") {
            output.flush()
            return
        }
        // body 复制同样带断点续传：SMB/FTP/SFTP 的长连接在后台网络受限时也会
        // 中途断流，断了就从已发偏移重新打开流继续，播放器无感。
        var copied = 0L
        var attempt = 0
        val totalBody = if (total > 0 && end >= 0) end - start + 1 else -1L
        while (attempt <= 5) {
            try {
                factory(start + copied).use { stream ->
                    copied += copyHttpBody(stream, output, if (totalBody > 0) totalBody - copied else -1L)
                }
                break
            } catch (t: Throwable) {
                if (t is ClientGoneException) {
                    Log.d("MFB-STREAM", "client gone: ${t.javaClass.simpleName}")
                    return
                }
                attempt++
                Log.d(
                    "MFB-STREAM",
                    "stream drop ($copied sent), retry $attempt: ${t.javaClass.simpleName} ${t.message ?: ""}"
                )
                if (attempt > 5) {
                    Log.d("MFB-STREAM", "transfer aborted: ${t.javaClass.simpleName}")
                    return
                }
                try {
                    Thread.sleep(300L * attempt)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
        output.flush()
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    /**
     * 请求路径结尾的文件名与源文件是否同扩展名。
     * 请求里没带文件名（只有 /s/<key>）时一律放行。
     */
    private fun sameExtension(requestPath: String, sourceName: String): Boolean {
        if (sourceName.isBlank()) return true
        val requested = requestPath.substringAfterLast('/').substringBefore('?')
        if (requested.isBlank()) return true
        val decoded = runCatching { java.net.URLDecoder.decode(requested, "UTF-8") }
            .getOrDefault(requested)
        val wanted = decoded.substringAfterLast('.', "").lowercase()
        if (wanted.isBlank()) return true
        val actual = sourceName.substringAfterLast('.', "").lowercase()
        return actual.isBlank() || wanted == actual
    }
    /** 上游给的 octet-stream / 空类型没有意义，用我们知道的真实 MIME 补上。 */
    private fun pickContentType(upstream: String, fallback: String): String {
        val value = upstream.substringBefore(';').trim()
        val useless = value.isBlank() || value == "application/octet-stream" ||
            value == "binary/octet-stream" || value == "*/*"
        return if (useless && fallback.isNotBlank()) fallback else if (value.isBlank()) "application/octet-stream" else value
    }

    /** Range 解析结果：end = -1 表示到文件末尾；start < 0 表示后缀范围（bytes=-N）。 */
    private data class RangeSpec(val start: Long, val end: Long)

    private fun parseRange(raw: String?): RangeSpec? {
        val value = raw?.trim().orEmpty()
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        val spec = value.substringAfter('=').substringBefore(',').trim()
        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-', "").trim()
        return if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0L) null else RangeSpec(-suffix, -1L)
        } else {
            val start = startText.toLongOrNull() ?: return null
            RangeSpec(start, endText.toLongOrNull() ?: -1L)
        }
    }

    private fun writeSimple(output: OutputStream, code: Int, text: String, mime: String, body: ByteArray) {
        val head = "HTTP/1.1 $code $text\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (byte == '\n'.code) break
            if (byte != '\r'.code) buffer.write(byte)
            if (buffer.size() > 8192) break
        }
        return buffer.toString("UTF-8")
    }
}
