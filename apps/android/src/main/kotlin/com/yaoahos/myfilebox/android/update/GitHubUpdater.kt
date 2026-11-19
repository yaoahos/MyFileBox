package com.yaoahos.myfilebox.android.update

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通过 GitHub Releases API 检查并下载新版本。
 * 限流 / 404 / 5xx / 坏内容分开提示，下载安装包时逐跳校验域名、
 * ZIP 头与最小体积。
 */
object GitHubUpdater {
    const val REPO = "yaoahos/MyFileBox"
    const val REPO_PAGE = "https://github.com/$REPO"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    /** 只取最新一版，省流量也少烧限流配额。 */
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=50"
    private const val USER_AGENT = "MyFileBox-Android"

    /** 安装包至少这么大；被劫持成错误页时不可能达到。 */
    private const val MIN_APK_BYTES = 512L * 1024L

    /** GitHub 下载地址通常跳到 objects.githubusercontent.com，逐跳校验，最多允许 5 跳。 */
    private const val MAX_REDIRECTS = 5

    /**
     * 只允许从 GitHub 的域名取安装包。
     *
     * 下载会跳两次：`github.com/.../releases/download/...` 先 302 到
     * `release-assets.githubusercontent.com`（资产实际所在的 CDN），
     * 早期还会落在 `objects.githubusercontent.com` / `github-releases.githubusercontent.com`。
     * 这些主机名由 GitHub 分配、会随其 CDN 调整而变，逐个写死会在改版后整条下载链路失效，
     * 因此对 `*.githubusercontent.com` 统一按后缀放行。
     */
    private val ALLOWED_HOSTS = listOf("github.com", "api.github.com")

    /** GitHub 的 CDN 域：`.githubusercontent.com` 必须带前导点，避免 evilgithubusercontent.com 混入。 */
    private const val CDN_HOST_SUFFIX = ".githubusercontent.com"

    /** 跳到了 GitHub 之外的域名，多半是被劫持；提示要走得通的手动路径。 */
    private const val BLOCKED_URL_MESSAGE = "下载地址不在 GitHub 域名内，请到发布页手动下载"

    /** 拉取最新版本（单条）。 */
    fun fetchLatest(attempts: Int = 3): UpdateInfo {
        val text = fetchWithRetry(LATEST_API, attempts)
        return runCatching { toUpdateInfo(JSONObject(text)) }
            .getOrElse { throw BadPayloadException() }
    }

    /** 拉取发布列表（在线更新日志用）。 */
    fun fetchReleases(attempts: Int = 3): List<ReleaseNote> {
        val text = fetchWithRetry(RELEASES_API, attempts)
        return runCatching {
            val arr = org.json.JSONArray(text)
            (0 until arr.length()).mapNotNull { index ->
                arr.optJSONObject(index)?.let { toReleaseNote(it) }
            }
        }.getOrElse { throw BadPayloadException() }
    }

    /**
     * 网络抖动重试两三次就够；限流、404、4xx/5xx、内容异常都不重试——
     * 重试不会变好，只会多烧一份配额。
     */
    private fun fetchWithRetry(url: String, attempts: Int): String {
        var lastError: Exception? = null
        for (attempt in 1..attempts) {
            try {
                return requestBody(url)
            } catch (e: UpdateException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < attempts) Thread.sleep(700L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("检查更新失败")
    }

    private fun requestBody(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code == 404) {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw NoReleaseException()
            }
            if (code == 403 || code == 429) {
                val reset = connection.getHeaderField("X-RateLimit-Reset")?.trim()?.toLongOrNull()
                connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw RateLimitedException(reset)
            }
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw UpdateUnavailableException(code)
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun toUpdateInfo(release: JSONObject): UpdateInfo {
        val tag = release.optString("tag_name")
        val version = normalize(tag)
        val assets = release.optJSONArray("assets")
        var apkUrl = ""
        var apkSize = 0L
        for (index in 0 until (assets?.length() ?: 0)) {
            val asset = assets?.optJSONObject(index) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url")
                apkSize = asset.optLong("size", 0L)
                break
            }
        }
        return UpdateInfo(
            version = version,
            tag = tag,
            name = release.optString("name").ifBlank { "MyFileBox $version" },
            notes = release.optString("body").trim(),
            apkUrl = apkUrl,
            pageUrl = release.optString("html_url").ifBlank { "$RELEASES_PAGE/tag/$tag" },
            size = apkSize
        )
    }

    private fun toReleaseNote(release: JSONObject): ReleaseNote? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name")
        if (tag.isBlank()) return null
        val version = normalize(tag)
        return ReleaseNote(
            version = version,
            name = release.optString("name").ifBlank { "MyFileBox $version" },
            notes = release.optString("body").trim(),
            date = localDate(release.optString("published_at"))
        )
    }

    /**
     * `published_at` 是 UTC 的 ISO 时间（如 `2026-11-19T16:24:30Z`），
     * 直接截前 10 位会显示成 UTC 的日期——东八区凌晨发布时比本地日期早一天，
     * 与更新日志正文里写的日期对不上。按本机时区换算后再取日期。
     */
    internal fun localDate(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            java.time.Instant.parse(iso)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        }.getOrElse { iso.take(10).replace('-', '/') }
    }

    /** 内置更新日志里挑前几条，格式与发布说明一致。 */
    fun releaseNotes(notes: List<ReleaseNote>, limit: Int = 10): List<ReleaseNote> = notes.take(limit)

    /**
     * 下载地址校验规则（纯函数，可单测）：必须 https，主机名要么精确等于
     * `github.com` / `api.github.com`，要么落在 `*.githubusercontent.com` 下。
     * 按整串比较（后缀必须带前导点）——github.com.evil.com、
     * evil-githubusercontent.com 这类伪装过不了。
     */
    fun isAllowedDownloadUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = url.removePrefix("https://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .lowercase()
        if (host.isEmpty()) return false
        if (ALLOWED_HOSTS.any { host == it }) return true
        // 必须比后缀长，`githubusercontent.com` 本身（无子域）不算。
        return host.length > CDN_HOST_SUFFIX.length && host.endsWith(CDN_HOST_SUFFIX)
    }

    /** 把 Location 解析成绝对地址；相对跳转与协议相对跳转都按 URL 规则处理。 */
    internal fun resolveRedirect(base: String, location: String): String? =
        runCatching { URL(URL(base), location).toString() }.getOrNull()

    /**
     * 下载安装包。四道校验：每一跳地址都必须是 https 且落在 GitHub 域名下、
     * 跳转次数受限、内容必须是 ZIP（APK 就是 zip，头两字节 "PK"）、体积不能小得离谱。
     */
    fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        var current = url
        var active: HttpURLConnection? = null
        // 不能依赖 instanceFollowRedirects：那样只校验初始 URL，302 到任意域名都会放行。
        for (hop in 0 until MAX_REDIRECTS) {
            if (!isAllowedDownloadUrl(current)) throw UpdateDownloadException(BLOCKED_URL_MESSAGE)
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            // 取响应码本身就可能抛（超时、连接被重置），这条路径同样要断开，否则连接泄漏。
            val code = try {
                connection.responseCode
            } catch (e: Exception) {
                connection.disconnect()
                throw e
            }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw UpdateDownloadException("下载地址跳转无效")
                val next = resolveRedirect(current, location)
                    ?: throw UpdateDownloadException("下载地址跳转无效")
                if (hop == MAX_REDIRECTS - 1) throw UpdateDownloadException("下载地址跳转次数过多")
                current = next
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw UpdateDownloadException("下载失败，HTTP $code")
            }
            active = connection
            break
        }
        val connection = active ?: throw UpdateDownloadException("下载地址跳转次数过多")
        try {
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            var downloaded = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (target.length() < MIN_APK_BYTES || !looksLikeZip(target)) {
                target.delete()
                throw UpdateDownloadException("安装包内容异常，已取消下载")
            }
            onProgress(100)
        } finally {
            connection.disconnect()
        }
    }

    private fun looksLikeZip(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun normalize(version: String): String =
        version.trim().removePrefix("v").removePrefix("V").ifBlank { version.trim() }

    private fun compare(a: String, b: String): Int {
        val left = numbers(a)
        val right = numbers(b)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun numbers(version: String): List<Int> = normalize(version)
        .split('.', '-', '+', ' ')
        .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
}
