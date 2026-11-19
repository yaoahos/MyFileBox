package com.yaoahos.myfilebox.android.core

import com.yaoahos.myfilebox.core.displayLeaf
import com.yaoahos.myfilebox.core.displayParent
import org.junit.Assert.assertEquals
import org.junit.Test

/** 路径末段显示名：内部存储根目录不能再露出末段「0」。 */
class PathDisplayTest {
    private val root = "/storage/emulated/0"

    @Test
    fun `根目录与带尾斜杠的根目录都归一`() {
        assertEquals("根目录", displayLeaf(root, root))
        assertEquals("根目录", displayLeaf("$root/", root))
    }

    @Test
    fun `末段恰好是0的历史路径也归一`() {
        assertEquals("根目录", displayLeaf("0", root))
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
        assertEquals("根目录", displayParent("$root/photo.jpg", root))
    }

    @Test
    fun `父目录是子目录时取末段`() {
        assertEquals("Avatar", displayParent("$root/Avatar/photo.jpg", root))
    }

    @Test
    fun `远端路径按自身层级取父目录名`() {
        assertEquals("sub", displayParent("/remote/sub/a.txt", root))
    }
}
