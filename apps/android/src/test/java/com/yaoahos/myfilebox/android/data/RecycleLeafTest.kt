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
    fun `deleting a file keeps its parent folder browsable`() {
        // 删文件后汇总层 topLevelOnly 只列第一层（2025 目录），tracked 是 a.jpg。
        // 2025 必须保留：点进去才能看到原目录里其它被删的东西。
        val raw = listOf(item("/photo/#recycle/2025", true))
        val tracked = listOf(item("/photo/#recycle/2025/a.jpg", false))
        val merged = mergeTrackedRecycle(raw, tracked).map { it.trashPath }
        assertEquals(
            listOf("/photo/#recycle/2025/a.jpg", "/photo/#recycle/2025"),
            merged
        )
    }

    @Test
    fun `deleting a folder still keeps the folder as one unit`() {
        // 删文件夹时反过来：祖先目录不该单独列出（文件夹本身就能点进去看内容）。
        val raw = listOf(
            item("/photo/#recycle/trip", true),
            item("/photo/#recycle/trip/day1", true),
            item("/photo/#recycle/trip/day1/x.jpg", false)
        )
        val tracked = listOf(item("/photo/#recycle/trip", true))
        val merged = mergeTrackedRecycle(raw, tracked).map { it.trashPath }
        assertEquals(listOf("/photo/#recycle/trip"), merged)
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

    @Test
    fun `parent folder stays when a sibling was also deleted`() {
        // 同名目录下另一个文件也被删（无 tracked 记录）：父目录仍须保留，
        // 用户才能点进去看到完整的同级被删项，而不是只列一个文件。
        val raw = listOf(
            item("/photo/#recycle/2025", true),
            item("/photo/#recycle/2025/b.jpg", false)
        )
        val tracked = listOf(item("/photo/#recycle/2025/a.jpg", false))
        val merged = mergeTrackedRecycle(raw, tracked).map { it.trashPath }
        assertEquals(
            listOf("/photo/#recycle/2025/a.jpg", "/photo/#recycle/2025/b.jpg"),
            merged
        )
    }

    @Test
    fun `summary hides deep deleted files but keeps folder entry`() {
        // 删 /backup/Temp/a.apk 后汇总层同时拿到 a.apk（tracked）与 Temp 目录。
        // 汇总页应只显示 Temp：a.apk 越级出现会让人以为「没有目录结构」。
        val shares = listOf("/backup")
        val items = listOf(
            item("/backup/#recycle/Temp", true),
            item("/backup/#recycle/Temp/a.apk", false),
            item("/backup/#recycle/root.txt", false)
        )
        val tracked = listOf(
            item("/backup/#recycle/Temp/a.apk", false),
            item("/backup/#recycle/root.txt", false)
        )
        val visible = com.yaoahos.myfilebox.android.ui.visibleSummary(items, tracked, shares)
        assertEquals(
            listOf("/backup/#recycle/Temp", "/backup/#recycle/root.txt"),
            visible.map { it.trashPath }
        )
    }

}
