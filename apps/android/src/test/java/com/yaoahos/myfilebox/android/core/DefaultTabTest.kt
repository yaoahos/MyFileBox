package com.yaoahos.myfilebox.android.core

import com.yaoahos.myfilebox.core.AppPrefs
import com.yaoahos.myfilebox.core.TabKey
import com.yaoahos.myfilebox.core.effectiveDefaultTab
import org.junit.Assert.assertEquals
import org.junit.Test

/** 返回链与冷启动共用的「有效启动页」口径。 */
class DefaultTabTest {

    @Test
    fun `visible default tab is kept`() {
        val prefs = AppPrefs(defaultTab = TabKey.NET)
        assertEquals(TabKey.NET, effectiveDefaultTab(prefs))
    }

    @Test
    fun `hidden default tab falls back to first visible tab`() {
        // 默认页被关掉时不能强行切到不可见页，按底栏顺序退到第一个仍可见的页。
        val prefs = AppPrefs(defaultTab = TabKey.SYNO, showLocal = false, showSyno = false)
        assertEquals(TabKey.NET, effectiveDefaultTab(prefs))
    }

    @Test
    fun `tab order decides fallback, not enum order`() {
        val prefs = AppPrefs(
            defaultTab = TabKey.LOCAL,
            showLocal = false,
            tabOrder = listOf(TabKey.NET, TabKey.SYNO, TabKey.LOCAL)
        )
        assertEquals(TabKey.NET, effectiveDefaultTab(prefs))
    }

    @Test
    fun `all tabs hidden still yields local`() {
        val prefs = AppPrefs(
            defaultTab = TabKey.NET,
            showLocal = false, showSyno = false, showNet = false
        )
        assertEquals(TabKey.LOCAL, effectiveDefaultTab(prefs))
    }
}
