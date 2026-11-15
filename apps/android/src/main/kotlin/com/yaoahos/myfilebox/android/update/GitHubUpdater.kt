package com.yaoahos.myfilebox.android.update

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通过 GitHub Releases API 检查并下载新版本。
 * 实现方式对齐 SafePassword 的 GitHubUpdater：限流 / 404 / 5xx / 坏内容分开提示，
 * 下载安装包前校验地址域名、ZIP 头与最小体积。
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

    /** 只允许从 GitHub 的域名取安装包。 */
    private val ALLOWED_HOSTS = listOf(
        "github.com", "api.github.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com"
    )

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
            date = release.optString("published_at").take(10).replace('-', '/')
        )
    }

    /** 内置更新日志里挑前几条，格式与发布说明一致。 */
    fun releaseNotes(notes: List<ReleaseNote>, limit: Int = 10): List<ReleaseNote> = notes.take(limit)

    /**
     * 下载地址校验规则（纯函数，可单测）：必须 https 且主机名精确落在白名单内。
     * 主机名按整串等值比较——github.com.evil.com 之类的后缀伪装过不了。
     */
    fun isAllowedDownloadUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = url.removePrefix("https://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return ALLOWED_HOSTS.any { host.equals(it, ignoreCase = true) }
    }

    /**
     * 下载安装包。三道校验：地址必须 https 且落在 GitHub 域名下、
     * 内容必须是 ZIP（APK 就是 zip，头两字节 "PK"）、体积不能小得离谱。
     */
    fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        if (!isAllowedDownloadUrl(url)) throw UpdateDownloadException("安装包地址无效")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw UpdateDownloadException("下载失败，HTTP $code")
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
