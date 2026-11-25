package com.yaoahos.myfilebox.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Android/data`、`Android/obb` 的系统限制判断。
 *
 * 关键边界是包名前缀：`com.yaoahos.myfilebox` 是 `com.yaoahos.myfilebox.debug`
 * 的前缀，按 `contains` 判断会把调试包的目录误认成自己的，于是正式版进去看到的是
 * 「空文件夹」而不是「系统限制」提示。
 */
class RestrictedDirTest {

    private val root = "/storage/emulated/0"
    private val release = "com.yaoahos.myfilebox"
    private val debug = "com.yaoahos.myfilebox.debug"

    @Test
    fun `android data root is restricted`() {
        assertTrue(isRestrictedSystemDir("$root/Android/data", release))
        assertTrue(isRestrictedSystemDir("$root/Android/obb/", release))
    }

    @Test
    fun `other apps data dir is restricted`() {
        assertTrue(isRestrictedSystemDir("$root/Android/data/com.tencent.mm", release))
        assertTrue(isRestrictedSystemDir("$root/Android/obb/com.tencent.mm/files", release))
    }

    @Test
    fun `own package dir is readable`() {
        assertFalse(isRestrictedSystemDir("$root/Android/data/$release", release))
        assertFalse(isRestrictedSystemDir("$root/Android/data/$release/files", release))
        assertFalse(isRestrictedSystemDir("$root/Android/obb/$release", release))
    }

    @Test
    fun `own package prefix must not swallow the debug package`() {
        // 正式版看调试包的目录：不是自己的，仍然受限
        assertTrue(isRestrictedSystemDir("$root/Android/data/$debug", release))
        assertTrue(isRestrictedSystemDir("$root/Android/data/$debug/files", release))
        // 反过来也一样
        assertTrue(isRestrictedSystemDir("$root/Android/data/$release", debug))
        assertFalse(isRestrictedSystemDir("$root/Android/data/$debug/files", debug))
    }

    @Test
    fun `android root and media are not restricted`() {
        assertFalse(isRestrictedSystemDir("$root/Android", release))
        assertFalse(isRestrictedSystemDir("$root/Android/media", release))
        assertFalse(isRestrictedSystemDir("$root/Android/media/$release", release))
        assertFalse(isRestrictedSystemDir("$root/DCIM", release))
    }

    @Test
    fun `blank path is not restricted`() {
        assertFalse(isRestrictedSystemDir("", release))
    }
}
