package com.yaoahos.myfilebox.android.update

/**
 * 把更新日志 / GitHub 发布说明清洗成可以直接展示的条目。
 *
 * 数据有两种形态：
 * - 内置 `assets/updateLog.md` 解析出来的 notes，本身已是逐条纯文本；
 * - GitHub Release 正文原样搬过来：首行是「日期 · 版本号」，条目带 `- ` 前缀和 `**` 加粗，
 *   偶尔还混着安装说明、包名、校验值。
 *
 * 展示前统一过这里，界面只负责渲染「· 条目」，不再出现 markdown 记号。
 */
fun conciseNotes(raw: String, limit: Int = Int.MAX_VALUE): List<String> =
    raw.lineSequence()
        .map { it.trim() }
        .map(::stripMarkdown)
        .filter { it.isNotEmpty() }
        // 发布信息（包名 / 校验值 / 下载地址…）与内置日志共用一份规则，
        // 不再各维护一份词表 —— 否则同一条文案会在两处表现不一致。
        .filterNot(::isNoiseEntry)
        // 纯 Markdown 结构行（标题、引用、表格、图片、分隔线）不当作条目。
        .filterNot { line ->
            line.startsWith("#") ||
                line.startsWith(">") ||
                line.startsWith("|") ||
                line.startsWith("![") ||
                line.startsWith("---")
        }
        // 发布说明首行是「日期 · 版本号」，和对话框自带的标题重复，直接丢掉。
        .filterNot { HEADING_LINE.matches(it) || DATE_ONLY.matches(it) }
        // GitHub Release 的 name 字段（如「MyFileBox 1.1.0」）有时被手动写进正文，
        // 对话框标题已经显示了版本，这行裸标题再出现一次纯属重复。
        .filterNot { PRODUCT_VERSION_LINE.matches(it) }
        .take(limit)
        .map { line ->
            // 结果长度不超过 MAX_ENTRY_CHARS：留一位给省略号。
            if (line.length > MAX_ENTRY_CHARS) line.take(MAX_ENTRY_CHARS - 1).trimEnd() + "…" else line
        }
        .toList()

/** 单条超过这个长度就截断，避免一条占满整屏。 */
private const val MAX_ENTRY_CHARS = 80

/** 去掉列表符号与加粗 / 行内代码标记，返回纯文本。 */
private fun stripMarkdown(line: String): String = line
    .removePrefix("- ")
    .removePrefix("* ")
    .removePrefix("+ ")
    .removePrefix("• ")
    .removePrefix("· ")
    .replace("**", "")
    .replace("`", "")
    .trim()

/** `2026/11/13 · 1.0.0` / `2026-11-13 1.0.0` 这类标题行。 */
private val HEADING_LINE = Regex("""^\d{4}[/-]\d{1,2}[/-]\d{1,2}\s*[·\-—]?\s*v?\d+(?:\.\d+)*$""")

/** 只有日期的一行。 */
private val DATE_ONLY = Regex("""^\d{4}[/-]\d{1,2}[/-]\d{1,2}$""")

/**
 * 「产品名 + 版本号」的裸标题行，如 `MyFileBox 1.1.0`。
 * 产品名限定 ASCII 字符，避免把「适配 Android 15」这类不带冒号前缀的正常条目
 * 误判成标题丢掉。
 */
private val PRODUCT_VERSION_LINE = Regex("""^[A-Za-z][A-Za-z0-9 ._-]*\s*v?\d+(?:\.\d+){1,3}$""")
