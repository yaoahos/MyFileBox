package com.yaoahos.myfilebox.android.core

import com.yaoahos.myfilebox.core.STORAGE_ROOT_LABEL
import com.yaoahos.myfilebox.core.ancestorLevels
import com.yaoahos.myfilebox.core.displayLeaf
import com.yaoahos.myfilebox.core.displayParent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 路径末段显示名：内部存储根目录不能再露出末段「0」，且全应用只用一个名字。 */
class PathDisplayTest {
    private val root = "/storage/emulated/0"

    @Test
    fun `根目录与带尾斜杠的根目录都归一`() {
        assertEquals(STORAGE_ROOT_LABEL, displayLeaf(root, root))
        assertEquals(STORAGE_ROOT_LABEL, displayLeaf("$root/", root))
    }

    @Test
    fun `末段恰好是0的历史路径也归一`() {
        assertEquals(STORAGE_ROOT_LABEL, displayLeaf("0", root))
    }

    @Test
    fun `子目录仍取末段`() {
        assertEquals("Avatar", displayLeaf("$root/Avatar", root))
    }

    @Test
    fun `空路径用调用方给定的兜底文案`() {
        assertEquals("未设置", displayLeaf("", root, "未设置"))
    }

    @Test
    fun `父目录是根目录时归一`() {
        assertEquals(STORAGE_ROOT_LABEL, displayParent("$root/photo.jpg", root))
    }

    @Test
    fun `父目录是子目录时取末段`() {
        assertEquals("Avatar", displayParent("$root/Avatar/photo.jpg", root))
    }

    @Test
    fun `远端路径按自身层级取父目录名`() {
        assertEquals("sub", displayParent("/remote/sub/a.txt", root))
    }

    /**
     * 全应用只有这一个叫法，不允许再出现「根目录」。
     * 以后谁改回字面量，这条会先红。
     */
    @Test
    fun `根目录统一叫内部存储`() {
        assertEquals("内部存储", STORAGE_ROOT_LABEL)
        assertEquals("内部存储", displayLeaf(root, root))
        assertEquals("内部存储", displayParent("$root/a.txt", root))
    }

    // ---- 真实路径的上级链（从收藏跳进目录时用来拼面包屑） ----

    @Test
    fun `群晖按共享名逐级拆出上级`() {
        val levels = ancestorLevels("/photo/2026/旅行")
        assertEquals(listOf("photo", "2026"), levels.map { it.name })
        assertEquals(listOf("/photo", "/photo/2026"), levels.map { it.path })
    }

    @Test
    fun `共享文件夹根没有上级`() {
        assertTrue(ancestorLevels("/photo").isEmpty())
    }

    @Test
    fun `本地先摘掉存储根再拆`() {
        val levels = ancestorLevels("$root/DCIM/Camera", root)
        assertEquals(listOf("DCIM"), levels.map { it.name })
        assertEquals(listOf("$root/DCIM"), levels.map { it.path })
    }

    @Test
    fun `存储根自身与根下一层都没有上级`() {
        assertTrue(ancestorLevels(root, root).isEmpty())
        assertTrue(ancestorLevels("$root/DCIM", root).isEmpty())
    }

    /** 不在存储根下（换卷、指到别处）不编造层级，按绝对路径拆。 */
    @Test
    fun `存储根之外的路径按自身层级拆`() {
        val levels = ancestorLevels("/sdcard/DCIM/Camera", root)
        assertEquals(listOf("sdcard", "DCIM"), levels.map { it.name })
        assertEquals(listOf("/sdcard", "/sdcard/DCIM"), levels.map { it.path })
    }

    /** WebDAV 条目路径是完整 URL：主机名不能被当成一级目录。 */
    @Test
    fun `WebDAV 的 URL 路径摘掉协议与主机后按连接根拆`() {
        val levels = ancestorLevels("https://nas.local:5006/dav/照片/2026", "/dav")
        assertEquals(listOf("照片"), levels.map { it.name })
        assertEquals(listOf("https://nas.local:5006/dav/照片"), levels.map { it.path })
    }

    @Test
    fun `只有主机没有目录时不给上级`() {
        assertTrue(ancestorLevels("https://nas.local:5006", "/dav").isEmpty())
    }
}
