package com.yaoahos.myfilebox.android.update

import android.content.Context

/**
 * 内置更新日志。数据格式与 GitHub 发布说明一致：
 * 标题 `**YYYY/MM/DD · X.Y.Z**`，条目 `- 动词 描述`。
 *
 * ```
 * **YYYY/MM/DD · X.Y.Z**
 * - 修复 示例问题
 * - 优化 示例体验
 * ```
 */
object UpdateLog {
    private const val ASSET_NAME = "updateLog.md"

    /** `2026/11/10 · 2.6.0` / `2026-11-10 2.6.0` / `2026/11/10` 都能解析。 */
    private val HEADER = Regex("""^(\d{4}[-/]\d{1,2}[-/]\d{1,2})(?:\s*[·\-—]\s*|\s+)?(v?\d+(?:\.\d+)*)?$""")

    /** 条目行首的列表符号。 */
    private val BULLET = Regex("""^[-*+•]\s+""")

    /** 纯日期行（正文里常见的附加行）。 */
    private val DATE_ONLY = Regex("""^\d{4}[/-]\d{1,2}[/-]\d{1,2}$""")

    /** 「日期 + 版本号」标题行（整段日志塞进 Release 正文时不重复显示）。 */
    private val DATE_VERSION = Regex("""^\d{4}[/-]\d{1,2}[/-]\d{1,2}\s*[·\-—]?\s*v?\d+(?:\.\d+)*$""")

    /** 噪声关键词：命中即丢弃该条目（发布信息、校验值之类不该进日志正文）。 */
    private val NOISE = listOf(
        "包名", "versionCode", "versionName", "版本号", "系统要求", "适用系统", "适用版本",
        "安装说明", "安装包", "下载地址", "文件大小", "SHA", "MD5", "签名", "校验值", "更新于"
    )

    /** 某版本的条目全被过滤掉时的兜底文案。 */
    const val FALLBACK_NOTE = "问题修复与稳定性优化。"

    fun load(context: Context): List<ReleaseNote> {
        val text = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        return parse(text)
    }

    internal fun parse(text: String): List<ReleaseNote> {
        val notes = mutableListOf<ReleaseNote>()
        var date = ""
        var version = ""
        val bullets = mutableListOf<String>()
        var rawBulletCount = 0

        fun flush() {
            when {
                // 标题后一条条目都没有 → 整版丢弃（规范四）。
                date.isNotBlank() && bullets.isNotEmpty() -> notes += ReleaseNote(
                    version = version, name = "", notes = bullets.joinToString("\n"), date = date
                )
                // 有条目但全被过滤 → 兜底一句（规范 5.4），不要留空。
                date.isNotBlank() && rawBulletCount > 0 -> notes += ReleaseNote(
                    version = version, name = "", notes = FALLBACK_NOTE, date = date
                )
            }
            bullets.clear()
            rawBulletCount = 0
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.length in 4..40 && line.startsWith("**") && line.endsWith("**")) {
                flush()
                val body = line.trim('*').trim()
                val match = HEADER.matchEntire(body)
                if (match != null) {
                    date = match.groupValues[1].trim()
                    version = match.groupValues[2].trim().removePrefix("v")
                } else {
                    date = body
                    version = ""
                }
            } else if (BULLET.containsMatchIn(line)) {
                // 只把列表符号开头的行当条目，其余行全部忽略（规范四）。
                rawBulletCount += 1
                cleanEntry(line)?.let { bullets += it }
            }
        }
        flush()
        return notes
    }

    /**
     * 条目清洗（规范六）：去行首列表符号、去加粗与反引号、剔噪声与特殊行。
     * 返回 null 表示该行应丢弃。
     */
    internal fun cleanEntry(raw: String): String? {
        var line = raw.trim()
        line = BULLET.replace(line, "").trim()
        line = line.replace("**", "").replace("`", "").trim()
        if (line.isBlank()) return null
        if (NOISE.any { line.contains(it, ignoreCase = true) }) return null
        if (line.startsWith("#") || line.startsWith(">") || line.startsWith("|") ||
            line.startsWith("![") || line.startsWith("---")
        ) return null
        if (DATE_ONLY.matches(line) || DATE_VERSION.matches(line)) return null
        return line
    }

    /** 版本号按数字段比较（规范七）：`1.0.10` 排在 `1.0.9` 后面，不按字符串比。 */
    fun compareVersions(a: String, b: String): Int {
        val left = versionSegments(a)
        val right = versionSegments(b)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun versionSegments(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+', ' ')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

    /**
     * 合并本地与远端日志：同版本以远端（已发布正文）为准，
     * 按版本号降序去重，最多取 [limit] 个版本，更早的交给「查看更早的版本」。
     */
    fun merge(local: List<ReleaseNote>, remote: List<ReleaseNote>, limit: Int = 5): List<ReleaseNote> {
        val byVersion = LinkedHashMap<String, ReleaseNote>()
        local.forEach { byVersion.putIfAbsent(it.version.trim(), it) }
        remote.forEach { byVersion[it.version.trim()] = it }
        return byVersion.values
            .sortedWith { a, b -> compareVersions(b.version, a.version) }
            .take(limit)
    }
}
