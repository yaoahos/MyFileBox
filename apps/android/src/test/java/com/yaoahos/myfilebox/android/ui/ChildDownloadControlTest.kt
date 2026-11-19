package com.yaoahos.myfilebox.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件夹下载子项调度器的回归测试：暂停 / 恢复续传 / 提前开始是本次新增能力，
 * 用纯 JVM 测试锁定它们对队列顺序的影响，避免以后改动把「恢复后从头下」
 * 或「提前的项没插到队首」改回去。
 */
class ChildDownloadControlTest {

    @Test
    fun `按入队顺序领取，领取后不重复发放`() {
        val c = ChildDownloadControl()
        c.enqueueAll(listOf("a", "b", "c"))
        assertEquals("a", c.nextKey())
        assertEquals("b", c.nextKey())
        assertFalse(c.isPaused("a"))
        // a、b 已不在队列；领取 c 后再无任务
        assertEquals("c", c.nextKey())
        assertEquals(null, c.nextKey())
    }

    @Test
    fun `暂停等待中的项后不会被领取，恢复时回到队首且标记续传`() {
        val c = ChildDownloadControl()
        c.enqueueAll(listOf("a", "b", "c"))
        c.pause("b")
        assertEquals("a", c.nextKey())
        // b 被暂停，跳过它取 c
        assertEquals("c", c.nextKey())
        assertEquals(null, c.nextKey())
        assertFalse(c.allFinished()) // b 还在暂停区

        assertTrue(c.resume("b"))
        assertTrue(c.shouldResume("b"))
        assertEquals("b", c.nextKey())
        // worker 领取后会消费续传标记（真实流程在 downloadToLocal 的 worker 里）
        assertTrue(c.consumeResume("b"))
        assertFalse(c.shouldResume("b")) // 续传标记消费一次
        // 模拟 worker 收尾：a、c、b 都完成
        c.finish("a"); c.finish("c"); c.finish("b")
        assertTrue(c.allFinished())
    }

    @Test
    fun `暂停下载中的项由 abortPaused 交还，恢复走续传`() {
        val c = ChildDownloadControl()
        c.enqueueAll(listOf("a", "b"))
        val running = c.nextKey()
        assertEquals("a", running)
        c.pause(running!!)          // UI 点暂停：项已在途，记入暂停
        assertTrue(c.isPaused("a"))
        c.abortPaused("a")        // 下载协程中断后交还在途
        assertFalse(c.allFinished())
        assertTrue(c.resume("a"))
        assertTrue(c.shouldResume("a"))
        assertEquals("a", c.nextKey())
    }

    @Test
    fun `提前开始把等待项插到队首，暂停项也会一起恢复`() {
        val c = ChildDownloadControl()
        c.enqueueAll(listOf("a", "b", "c"))
        c.pause("c")
        assertTrue(c.promote("c"))
        assertFalse(c.isPaused("c"))
        assertTrue(c.shouldResume("c"))
        assertEquals("c", c.nextKey())
        assertEquals("a", c.nextKey())
    }

    @Test
    fun `finish 后不再出现在队列，也不再是暂停态`() {
        val c = ChildDownloadControl()
        c.enqueueAll(listOf("a"))
        c.nextKey()
        c.finish("a")
        assertFalse(c.isPaused("a"))
        assertTrue(c.allFinished())
    }
}
