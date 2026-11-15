package com.yaoahos.myfilebox.android.data

/**
 * 解析用户输入的主题色。
 *
 * 支持 `#RRGGBB` 与 `RRGGBB`（大小写都行），返回带不透明 alpha 的 ARGB。
 * 不合法一律返回 null，交给调用方明确提示——绝不「猜一个颜色」画上去，
 * 否则用户会以为输入成功了，实际主题没变。
 */
fun parseAccentHex(input: String): Long? {
    val raw = input.trim().removePrefix("#")
    if (raw.length != 6) return null
    // 刻意用区间判断：Character.isDigit() 对全角数字（１２３）也返回 true，
    // 那种输入应该拒绝而不是悄悄当成合法值。
    if (!raw.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return null
    val rgb = runCatching { raw.toLong(16) }.getOrNull() ?: return null
    return 0xFF000000L or rgb
}

/** 颜色值转 `#RRGGBB`（丢掉 alpha），用于回填输入框。 */
fun accentToHex(color: Long): String =
    String.format(java.util.Locale.US, "#%06X", color and 0xFFFFFF)
