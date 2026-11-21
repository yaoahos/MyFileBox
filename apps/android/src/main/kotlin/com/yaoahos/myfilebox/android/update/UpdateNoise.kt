package com.yaoahos.myfilebox.android.update

/**
 * 更新日志的噪声判定：发布信息（包名、校验值、下载地址、安装说明…）
 * 不属于功能描述，展示出来只会干扰阅读。
 *
 * 这里只有一份规则，内置 `assets/updateLog.md` 的解析（[UpdateLog]）与
 * GitHub Release 正文的清洗（[conciseNotes]）都走它。以前两处各有一份词表，
 * 且松紧不同：内置那份只要整句里出现「版本号」「安装包」「签名」就整条丢弃，
 * 于是同一条文案在「更新日志」页看不到、在「发现新版本」弹窗里却看得到。
 *
 * 判定分两档，避免误伤正常条目：
 *  - [ALWAYS_NOISE]：出现即可判定为发布信息，绝不会是功能描述；
 *  - [PREFIX_NOISE]：只在行首出现时才算（「安装包体积减小」这种正常条目要留下）。
 */
private val ALWAYS_NOISE = listOf(
    "versionCode", "versionName", "SHA-256", "SHA256", "MD5", "校验值"
)

private val PREFIX_NOISE = listOf(
    "包名", "安装包：", "安装包:", "安装说明", "下载地址", "文件大小",
    "大小：", "大小:", "适用系统", "系统要求", "支持系统", "适用版本",
    "更新于", "certutil"
)

/** 一去掉 Markdown 记号、已 trim 的条目是否为发布信息。 */
internal fun isNoiseEntry(line: String): Boolean {
    if (ALWAYS_NOISE.any { line.contains(it, ignoreCase = true) }) return true
    return PREFIX_NOISE.any { line.startsWith(it, ignoreCase = true) }
}
