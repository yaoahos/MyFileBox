package com.yaoahos.myfilebox.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 底栏之间必须按左右顺序滑动，不能跟着「以前去过哪一栏」反转。 */
class PageNavTest {

    private fun motion(vararg keys: String): List<PageMotionKind> {
        val stack = PageStack()
        return keys.map { resolvePageMotion(stack, it) }
    }

    @Test
    fun `底栏切换按左右顺序决定方向`() {
        val kinds = motion("home/LOCAL", "home/NET", "home/SYNO", "home/LOCAL", "home/SYNO")
        assertEquals(PageMotionKind.Forward, kinds[0])
        assertEquals(PageMotionKind.Tab(toRight = true), kinds[1])
        // 中栏在右栏左边：必须往回滑，而不是继续往右。
        assertEquals(PageMotionKind.Tab(toRight = false), kinds[2])
        assertEquals(PageMotionKind.Tab(toRight = false), kinds[3])
        assertEquals(PageMotionKind.Tab(toRight = true), kinds[4])
    }

    @Test
    fun `同一组来回切换方向不会反转`() {
        val stack = PageStack()
        // 本地 → 网络 → 本地 → 网络：两次「本地到网络」必须都是往右。
        val kinds = listOf("home/LOCAL", "home/NET", "home/LOCAL", "home/NET")
            .map { resolvePageMotion(stack, it) }
        assertEquals(PageMotionKind.Tab(toRight = true), kinds[1])
        assertEquals(PageMotionKind.Tab(toRight = false), kinds[2])
        assertEquals(PageMotionKind.Tab(toRight = true), kinds[3])
    }

    @Test
    fun `登录页也算底栏页`() {
        val stack = PageStack()
        resolvePageMotion(stack, "home/LOCAL")
        assertEquals(PageMotionKind.Tab(toRight = true), resolvePageMotion(stack, "login/SYNO"))
        assertEquals(PageMotionKind.Tab(toRight = true), resolvePageMotion(stack, "login/NET"))
    }

    @Test
    fun `底栏切换后进入文件夹仍是前进返回仍是返回`() {
        val stack = PageStack()
        resolvePageMotion(stack, "home/LOCAL")
        resolvePageMotion(stack, "home/NET")
        val enter = resolvePageMotion(stack, "browser/NET//photo/photo/")
        assertEquals(PageMotionKind.Forward, enter)
        val back = resolvePageMotion(stack, "home/NET")
        assertEquals(PageMotionKind.Back, back)
        // 切过来时栈被重设成单层，返回后不会把旧栏目又露出来。
        assertEquals(listOf("home/NET"), stack.keys)
    }

    @Test
    fun `传输跳转深页后进与逐级退方向一致`() {
        val stack = PageStack()
        resolvePageMotion(stack, "home/NET")
        resolvePageMotion(stack, "transfer")
        val leaf = "browser/NET//Backup/Temp/mfbDeep/mfbDeep/"
        val mid = "browser/NET//Backup/Temp/Temp/"
        val root = "browser/NET//Backup/Backup/"
        val chain = listOf("home/NET", root, mid, leaf)

        // 跳转落地：即使 leaf 以前访问过，也强制按前进从右边进。
        val enter = resolvePageMotion(stack, leaf, chain)
        assertEquals(PageMotionKind.Forward, enter)
        assertEquals(chain, stack.keys)

        // 逐级返回：每层 key 都在链里，方向必须都是 Back（从左边退）。
        assertEquals(PageMotionKind.Back, resolvePageMotion(stack, mid))
        assertEquals(PageMotionKind.Back, resolvePageMotion(stack, root))
        assertEquals(PageMotionKind.Back, resolvePageMotion(stack, "home/NET"))
    }

    @Test
    fun `文件夹多级返回仍按栈判定`() {
        val stack = PageStack()
        resolvePageMotion(stack, "home/LOCAL")
        resolvePageMotion(stack, "browser/LOCAL//a/a/")
        val deep = resolvePageMotion(stack, "browser/LOCAL//a/b/b/")
        assertEquals(PageMotionKind.Forward, deep)
        val up = resolvePageMotion(stack, "browser/LOCAL//a/a/")
        assertEquals(PageMotionKind.Back, up)
        assertTrue(stack.keys.size == 2)
    }
}
