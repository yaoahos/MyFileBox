package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.TabKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 从收藏跳进目录后的转场方向。
 *
 * 从收藏点进 /photo/Temp 时，返回栈是「收藏 → photo → Temp」，但这几层里
 * photo 从来没被真正打开过。页面栈若不知道这条链，用户按返回时 photo 的 key
 * 不在栈里，就会被判成「前进」——画面从右边滑进来，方向反了。
 */
class JumpPageChainTest {

    private fun page(title: String, location: String, path: String, favorites: Boolean = false) =
        BrowserState(
            title = title,
            path = path,
            ctx = FileContext.SYNO,
            items = emptyList(),
            location = location,
            favorites = favorites
        )

    private val fav = page("收藏", "", "收藏", favorites = true)
    private val photo = page("photo", "/photo", "群晖文件 / photo")
    private val temp = page("Temp", "/photo/Temp", "群晖文件 / photo / Temp")

    @Test
    fun `跳转链以底栏首页开头、目标页结尾`() {
        val chain = jumpPageChain(TabKey.SYNO, listOf(fav, photo), temp)
        assertEquals(
            listOf("home/SYNO", browserKeyOf(fav), browserKeyOf(photo), browserKeyOf(temp)),
            chain
        )
    }

    @Test
    fun `进入算前进，逐级返回都算返回`() {
        val chain = jumpPageChain(TabKey.SYNO, listOf(fav, photo), temp)
        val stack = PageStack()
        assertEquals(PageMotionKind.Forward, resolvePageMotion(stack, chain.last(), chain))
        // 返回上一目录（这一页此前没有真正打开过，全靠链补进栈）
        assertEquals(PageMotionKind.Back, resolvePageMotion(stack, browserKeyOf(photo)))
        // 再返回到收藏
        assertEquals(PageMotionKind.Back, resolvePageMotion(stack, browserKeyOf(fav)))
        // 再返回到群晖首页
        assertEquals(PageMotionKind.Back, resolvePageMotion(stack, "home/SYNO"))
    }

    /** 两个目录下各有一个「Temp」：path 按真实层级写，才不会算成同一个页面。 */
    @Test
    fun `同名目录在不同路径下是不同页面`() {
        val a = page("Temp", "/a/Temp", "群晖文件 / a / Temp")
        val b = page("Temp", "/b/Temp", "群晖文件 / b / Temp")
        assertNotEquals(browserKeyOf(a), browserKeyOf(b))
    }
}
