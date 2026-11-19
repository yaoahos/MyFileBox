package com.yaoahos.myfilebox.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeDurationTest {

    @Test
    fun `普通成功提示一两秒就消失`() {
        val duration = noticeDurationMs("已收藏「报告.docx」")
        assertTrue("实际 $duration ms", duration in 1_500L..2_000L)
    }

    @Test
    fun `错误提示比普通提示久但不超过四秒`() {
        val short = noticeDurationMs("密码错误")
        val ordinary = noticeDurationMs("已收藏")
        assertTrue("短错误应读到：$short ms", short >= 2_500L)
        assertTrue("错误应比普通久：$short vs $ordinary", short > ordinary)
        assertTrue("错误不能挂着不走：$short ms", short <= 4_000L)
    }

    @Test
    fun `长文案有上限不会无限延长`() {
        val text = "复制失败：" + "文件太多，请稍后重试。".repeat(20)
        val long = noticeDurationMs(text)
        val longer = noticeDurationMs(text + "补充一句")
        assertEquals("超过上限后应保持一致", 4_000L, long)
        assertEquals(long, longer)
    }

    @Test
    fun `短提示不额外拖时间`() {
        assertEquals(1_305L, noticeDurationMs("已复制"))
    }

    @Test
    fun `文件丢失或文件夹已删除的提示判为错误色`() {
        // 「…已被移动或删除」以「已」开头，若不看全文会被误判成 SUCCESS 绿色。
        // 传输记录点开时目标不存在必须显红，用户才能看出这是失败。
        assertEquals(NoticeTone.ERROR, noticeTone("「Avatar」的目标文件夹已被移动或删除"))
        assertEquals(NoticeTone.ERROR, noticeTone("「report.docx」已被移动或删除，无法打开"))
        assertEquals(NoticeTone.ERROR, noticeTone("找不到该网络位置的连接，无法定位文件夹"))
        // 同时时长也要按错误提示走（比普通成功久）。
        assertTrue(noticeDurationMs("「Avatar」的目标文件夹已被移动或删除") >
            noticeDurationMs("已收藏"))
    }
}
