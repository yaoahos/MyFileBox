package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.android.ui.isPresetAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题色输入解析。
 *
 * 这类「用户手打颜色」的输入最容易在边界上出错：少一位、混进全角数字、
 * 或者填了非法字符。这里锁定「合法就转成 ARGB，不合法就给 null」——
 * 宁可提示一次，也不要静默套一个用户没要的颜色。
 */
class AccentColorTest {

    @Test
    fun `带井号的标准写法`() {
        assertEquals(0xFF1F6BFFL, parseAccentHex("#1F6BFF"))
    }

    @Test
    fun `不带井号也认`() {
        assertEquals(0xFF1F6BFFL, parseAccentHex("1F6BFF"))
    }

    @Test
    fun `小写字母归一`() {
        assertEquals(0xFF1F6BFFL, parseAccentHex("#1f6bff"))
    }

    @Test
    fun `前后空格忽略`() {
        assertEquals(0xFF1F6BFFL, parseAccentHex("  #1F6BFF  "))
    }

    @Test
    fun `黑色是合法颜色`() {
        assertEquals(0xFF000000L, parseAccentHex("#000000"))
    }

    @Test
    fun `位数不足拒绝`() {
        assertNull(parseAccentHex("#1F6BF"))
    }

    @Test
    fun `位数超出拒绝`() {
        assertNull(parseAccentHex("#1F6BFFA"))
    }

    @Test
    fun `非十六进制字符拒绝`() {
        assertNull(parseAccentHex("#GGGGGG"))
    }

    @Test
    fun `全角数字拒绝`() {
        // Character.isDigit() 会放过这些，所以实现里必须用 '0'..'9' 区间判断。
        assertNull(parseAccentHex("１２３４５６"))
    }

    @Test
    fun `空串拒绝`() {
        assertNull(parseAccentHex(""))
        assertNull(parseAccentHex("#"))
    }

    @Test
    fun `回填时丢掉 alpha`() {
        assertEquals("#1F6BFF", accentToHex(0xFF1F6BFFL))
        assertEquals("#1F6BFF", accentToHex(0x801F6BFFL))
    }

    @Test
    fun `解析与回填互逆`() {
        val parsed = parseAccentHex("#EC4899")
        assertEquals("#EC4899", accentToHex(parsed!!))
    }

    @Test
    fun `动态取色回落时认得出预置色和自选色`() {
        assertTrue(isPresetAccent(0xFF1F6BFFL))
        assertTrue(isPresetAccent(0xFF475569L))
        assertFalse(isPresetAccent(0xFFF97399L))
    }
}
