package com.yaoahos.myfilebox.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 群晖回收站：`#recycle` 路径 → 删除前的原路径。
 *
 * 回收站里「这一层对应原目录的哪儿」全靠这个换算；算错会让还原提示里的位置
 * 和列表说明对不上，所以按典型层级各钉一条。
 */
class RecycleTrashPathTest {

    @Test
    fun `文件按原目录结构还原`() {
        assertEquals("/photo/trip/a.jpg", originalPathOfTrash("/photo/#recycle/trip/a.jpg"))
    }

    @Test
    fun `多层目录逐段保留`() {
        assertEquals("/Backup/Temp/Ces/0.log", originalPathOfTrash("/Backup/#recycle/Temp/Ces/0.log"))
    }

    @Test
    fun `删除单元本身就是共享根下的一级目录`() {
        assertEquals("/photo/trip", originalPathOfTrash("/photo/#recycle/trip"))
    }

    @Test
    fun `#recycle 根自身还原成共享根`() {
        assertEquals("/photo", originalPathOfTrash("/photo/#recycle"))
    }

    @Test
    fun `不含 recycle 标记的路径原样返回`() {
        assertEquals("/photo/a.jpg", originalPathOfTrash("/photo/a.jpg"))
        assertEquals("", originalPathOfTrash(""))
    }

    @Test
    fun `名字里带 recycle 字样的普通目录不被误判`() {
        // 只有「/#recycle」这一段才算标记，/photo/recycle_backup 是普通目录。
        assertEquals("/photo/recycle_backup/a.jpg", originalPathOfTrash("/photo/recycle_backup/a.jpg"))
    }
}
