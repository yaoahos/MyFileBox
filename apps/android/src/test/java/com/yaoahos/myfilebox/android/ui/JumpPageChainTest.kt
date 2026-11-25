package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.TabKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 从收藏跳进目录后的返回链与转场方向。
 *
 * 从收藏点进 /photo/Temp 时，返回链是「photo → Temp」，收藏页只作入口、不进栈 ——
 * 面包屑上本来就没有收藏，返回却要穿过它，用户会多按一次。
 *
 * photo 这一层从来没被真正打开过，页面栈若不知道这条链，用户按返回时 photo 的 key
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
    fun `跳转链以底栏首页开头、目标页结尾，不含收藏页`() {
        val chain = jumpPageChain(TabKey.SYNO, listOf(photo), temp)
        assertEquals(
            listOf("home/SYNO", browserKeyOf(photo), browserKeyOf(temp)),
            chain
        )
    }

    @Test
    fun `进入算前进，逐级返回都算返回`() {
        val chain = jumpPageChain(TabKey.SYNO, listOf(photo), temp)
        val stack = PageStack()
        assertEquals(PageMotionKind.Forward, resolvePageMotion(stack, chain.last(), chain))
        // 返回上一目录（这一页此前没有真正打开过，全靠链补进栈）
        assertEquals(PageMotionKind.Back, resolvePageMotion(stack, browserKeyOf(photo)))
        // 再返回到群晖首页：收藏页不在链里，返回不会停在收藏上
        assertEquals(PageMotionKind.Back, resolvePageMotion(stack, "home/SYNO"))
    }

    /** 两个目录下各有一个「Temp」：path 按真实层级写，才不会算成同一个页面。 */
    @Test
    fun `同名目录在不同路径下是不同页面`() {
        val a = page("Temp", "/a/Temp", "群晖文件 / a / Temp")
        val b = page("Temp", "/b/Temp", "群晖文件 / b / Temp")
        assertNotEquals(browserKeyOf(a), browserKeyOf(b))
    }

    @Test
    fun `入栈时摘掉与目标同 key 的旧页面`() {
        val photo = page("photo", "/photo", "群晖文件 / photo")
        val temp = page("Temp", "/photo/Temp", "群晖文件 / photo / Temp")
        assertEquals(listOf(fav, photo), parentStackFor(listOf(fav, photo, temp), temp))
        assertEquals(emptyList<BrowserState>(), parentStackFor(emptyList(), temp))
    }

    /**
     * 反复「目录 → 收藏 → 目录」不能让返回链越堆越深：
     * 用户的实际感受就是「点了几次收藏，就要按几次返回才能回首页」。
     */
    @Test
    fun `反复经收藏进入同一目录，返回链深不增长`() {
        var stack = listOf<BrowserState>()
        // 进收藏 → 进 Temp → 更多里再点收藏 → 再进 Temp …… 来回 5 轮
        repeat(5) {
            stack = parentStackFor(stack, fav)
            // 从虚拟页跳转：原来的历史全部丢弃，只留目标目录的真实上层
            stack = parentStackFor(listOf(photo), temp)
        }
        assertEquals(listOf(browserKeyOf(photo)), stack.map { browserKeyOf(it) })
        // 从 Temp 回首页固定 2 次：photo → 首页
        assertEquals(2, stack.size + 1)
    }

    /** 目标就是根（共享文件夹 / 存储根）时没有上层页，返回直接回首页。 */
    @Test
    fun `跳进根目录时返回链为空`() {
        val share = page("photo", "/photo", "群晖文件 / photo")
        val chain = jumpPageChain(TabKey.SYNO, emptyList(), share)
        assertEquals(listOf("home/SYNO", browserKeyOf(share)), chain)
    }
}
