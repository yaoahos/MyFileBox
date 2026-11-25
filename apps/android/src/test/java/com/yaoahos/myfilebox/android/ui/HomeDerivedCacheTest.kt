package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.AppPrefs
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FolderCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 本地首页派生缓存的回归测试。
 *
 * 这层缓存的唯一目的是「切底栏时不要再算一遍全盘数据」，所以断言集中在两点：
 * 输入没变时一次都不该重算；扫描结果或排序 / 筛选一变就必须重算。
 */
class HomeDerivedCacheTest {

    private fun file(name: String, path: String, kind: FileKind = FileKind.DOCUMENT) = FileEntry(
        id = path, name = name, kind = kind, path = path
    )

    private fun folder(name: String, path: String) = FolderCard(
        name = name, meta = "", kind = FileKind.FOLDER, path = path
    )

    private fun derived() = HomeDerived(
        rootFiles = emptyList(),
        folders = emptyList(),
        rootItems = emptyList(),
        byKind = emptyMap(),
        favorites = emptyList()
    )

    @Test
    fun `输入不变时反复取用只计算一次`() {
        val cache = HomeDerivedCache()
        val files = listOf(file("a.txt", "/sdcard/a.txt"))
        val folders = listOf(folder("DCIM", "/sdcard/DCIM"))
        val favs = emptyList<FileEntry>()
        val prefs = AppPrefs()
        var calls = 0

        repeat(5) {
            val value = cache.getOrCompute(files, folders, favs, prefs, "/sdcard", "", "") {
                calls++
                derived()
            }
            assertSame("同样的输入必须拿到同一个对象", value, cache.getOrCompute(files, folders, favs, prefs, "/sdcard", "", "") { calls++; derived() })
        }

        // 每次循环里取了两次：第一次算、第二次命中，5 轮总共只应算 1 次（第二轮起第一次也命中）。
        assertEquals("输入未变不应重算", 1, calls)
    }

    @Test
    fun `扫描结果变化后重新计算`() {
        val cache = HomeDerivedCache()
        val folders = emptyList<FolderCard>()
        val favs = emptyList<FileEntry>()
        val prefs = AppPrefs()
        var calls = 0
        fun get(files: List<FileEntry>) = cache.getOrCompute(files, folders, favs, prefs, "/sdcard", "", "") {
            calls++
            derived()
        }

        // 同一次扫描结果（同一个 List 实例）反复取用只算一次。
        val first = listOf(file("a.txt", "/sdcard/a.txt"))
        get(first)
        get(first)
        assertEquals(1, calls)

        // 新一次扫描返回新的列表实例：必须重算，否则新增文件不会出现在首页。
        get(listOf(file("a.txt", "/sdcard/a.txt"), file("b.txt", "/sdcard/b.txt")))
        assertEquals(2, calls)
    }

    @Test
    fun `排序与筛选偏好变化后重新计算`() {
        val cache = HomeDerivedCache()
        val files = listOf(file("a.txt", "/sdcard/a.txt"))
        val folders = emptyList<FolderCard>()
        val favs = emptyList<FileEntry>()
        var calls = 0
        fun get(prefs: AppPrefs, name: String, ext: String) =
            cache.getOrCompute(files, folders, favs, prefs, "/sdcard", name, ext) { calls++; derived() }

        val plain = AppPrefs()
        get(plain, "", "")
        get(plain, "", "")
        assertEquals(1, calls)

        // 改了排序：prefs 是新实例，必须重算。
        val byTime = AppPrefs(sortKey = "time")
        get(byTime, "", "")
        assertEquals(2, calls)

        // 只改筛选词同样要重算。
        get(byTime, "报告", "")
        assertEquals(3, calls)
        get(byTime, "报告", "pdf")
        assertEquals(4, calls)
    }

    /**
     * 深层收藏的反查结果是异步补进来的（`resolveLocalFavorites`）。
     *
     * 它到货时扫描结果和偏好都没变，如果缓存键里不含它，首页的收藏计数就会
     * 一直停在「少一个」的状态，直到下次扫描才纠正。
     */
    @Test
    fun `深层收藏解析结果变化后重新计算`() {
        val cache = HomeDerivedCache()
        val files = listOf(file("a.txt", "/sdcard/a.txt"))
        val folders = emptyList<FolderCard>()
        val prefs = AppPrefs()
        var calls = 0
        fun get(favs: List<FileEntry>) =
            cache.getOrCompute(files, folders, favs, prefs, "/sdcard", "", "") { calls++; derived() }

        val none = emptyList<FileEntry>()
        get(none)
        get(none)
        assertEquals(1, calls)

        // 反查完成：新的列表实例必须触发重算。
        get(listOf(file("Camera", "/sdcard/DCIM/Camera", FileKind.FOLDER)))
        assertEquals(2, calls)
    }
}
