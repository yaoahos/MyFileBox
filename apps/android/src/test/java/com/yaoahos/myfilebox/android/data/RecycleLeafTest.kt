package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.RecycleItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** 回收站列表只保留「真正被删的那一项」，不再把路径中间层目录并列展示。 */
class RecycleLeafTest {

    private fun item(path: String, folder: Boolean) = RecycleItem(
        id = path,
        name = path.substringAfterLast('/'),
        originalPath = path,
        trashPath = path,
        deletedAt = 0L,
        sizeBytes = 0L,
        isFolder = folder
    )

    @Test
    fun `intermediate folders are dropped, only deleted file remains`() {
        val all = listOf(
            item("/photo/#recycle", true),
            item("/photo/#recycle/2025", true),
            item("/photo/#recycle/2025/a.jpg", false)
        )
        assertEquals(listOf("/photo/#recycle/2025/a.jpg"), leafRecycleItems(all).map { it.trashPath })
    }

    @Test
    fun `empty deleted folder is kept`() {
        val all = listOf(
            item("/photo/#recycle/empty", true)
        )
        assertEquals(listOf("/photo/#recycle/empty"), leafRecycleItems(all).map { it.trashPath })
    }

    @Test
    fun `files at same level are all kept`() {
        val all = listOf(
            item("/photo/#recycle/2025/a.jpg", false),
            item("/photo/#recycle/2025/b.jpg", false)
        )
        assertEquals(2, leafRecycleItems(all).size)
    }

    @Test
    fun `folder deletion shows its files instead of every level`() {
        val all = listOf(
            item("/photo/#recycle/trip", true),
            item("/photo/#recycle/trip/day1", true),
            item("/photo/#recycle/trip/day1/x.jpg", false),
            item("/photo/#recycle/trip/day2/y.jpg", false)
        )
        val leaves = leafRecycleItems(all).map { it.trashPath }
        assertEquals(
            listOf("/photo/#recycle/trip/day1/x.jpg", "/photo/#recycle/trip/day2/y.jpg"),
            leaves
        )
    }
}
