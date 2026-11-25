package com.yaoahos.myfilebox.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 面包屑层级：收藏 / 分类这类集合页不是目录，不能当成某一层上级。
 *
 * 从收藏点进 /photo/2026/旅行，曾经的显示是「群晖文件 › 收藏 › 2026 › 旅行」——
 * 文件实际不在「收藏」目录下，用户看到的层级是错的。
 */
class BreadcrumbTest {

    private fun page(
        title: String,
        favorites: Boolean = false,
        category: String? = null
    ) = BrowserState(
        title = title,
        path = title,
        ctx = FileContext.SYNO,
        items = emptyList(),
        favorites = favorites,
        localCategory = category
    )

    @Test
    fun `收藏页只留作返回目标不进面包屑`() {
        val trail = listOf(page("收藏", favorites = true), page("photo"), page("2026"), page("旅行"))
        assertEquals(listOf(1, 2, 3), breadcrumbIndices(trail))
    }

    @Test
    fun `停在收藏页时仍要显示收藏`() {
        val trail = listOf(page("收藏", favorites = true))
        assertEquals(listOf(0), breadcrumbIndices(trail))
    }

    @Test
    fun `分类页同样不进面包屑`() {
        val trail = listOf(page("图片", category = "图片"), page("DCIM"), page("Camera"))
        assertEquals(listOf(1, 2), breadcrumbIndices(trail))
    }

    @Test
    fun `普通目录链一层都不丢`() {
        val trail = listOf(page("共享文件夹"), page("DCIM"), page("Camera"))
        assertEquals(listOf(0, 1, 2), breadcrumbIndices(trail))
    }

    @Test
    fun `空栈不会越界`() {
        assertEquals(emptyList<Int>(), breadcrumbIndices(emptyList()))
    }
}
