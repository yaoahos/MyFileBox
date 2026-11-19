package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名称筛选：一处判定，三处列表（首页 / 内层目录 / 连接列表）共用。
 * 判错的代价是「明明有文件却搜不出来」，比多做一次过滤难受得多。
 */
class NameFilterTest {

    @Test
    fun `blank query matches everything`() {
        assertTrue(nameMatchesFilter("照片", ""))
        assertTrue(nameMatchesFilter("照片", "   "))
        assertTrue(nameMatchesFilter("", ""))
    }

    @Test
    fun `substring match anywhere in the name`() {
        assertTrue(nameMatchesFilter("2026-11-13 备份.zip", "备份"))
        assertTrue(nameMatchesFilter("2026-11-13 备份.zip", "2026"))
        assertTrue(nameMatchesFilter("2026-11-13 备份.zip", "13 备"))
    }

    @Test
    fun `case insensitive for latin names`() {
        assertTrue(nameMatchesFilter("Movie.MKV", "movie"))
        assertTrue(nameMatchesFilter("movie.mkv", "MKV"))
    }

    @Test
    fun `non matching name is filtered out`() {
        assertFalse(nameMatchesFilter("照片", "视频"))
        assertFalse(nameMatchesFilter("a.txt", "a.txt.bak"))
    }

    @Test
    fun `query is trimmed before matching`() {
        assertTrue(nameMatchesFilter("报告.docx", "  报告  "))
    }

    // ---------------------------------------------------------------- 后缀名筛选

    @Test
    fun `blank extension matches every file`() {
        assertTrue(extensionMatchesFilter("photo.jpg", false, ""))
        assertTrue(extensionMatchesFilter("noext", false, "   "))
    }

    @Test
    fun `folders are hidden while an extension filter is active`() {
        // 文件夹没有后缀可言：只筛 jpg 时把一堆无关文件夹一起列出来就是噪音。
        assertFalse(extensionMatchesFilter("Photos", true, "jpg"))
        assertFalse(extensionMatchesFilter("Photos.jpg", true, "jpg"))
        // 没有后缀条件时，文件夹照旧全部显示。
        assertTrue(extensionMatchesFilter("Photos", true, ""))
    }

    @Test
    fun `commas spaces and full width commas all separate extensions`() {
        listOf("jpg,png", "jpg png", "jpg，png", ".jpg;.png").forEach { query ->
            assertTrue("query=$query", extensionMatchesFilter("a.jpg", false, query))
            assertTrue("query=$query", extensionMatchesFilter("b.PNG", false, query))
            assertFalse("query=$query", extensionMatchesFilter("c.mp4", false, query))
        }
    }

    @Test
    fun `extension match is case insensitive`() {
        assertTrue(extensionMatchesFilter("Movie.MKV", false, "mkv"))
        assertTrue(extensionMatchesFilter("movie.mkv", false, "MKV"))
    }

    @Test
    fun `files without a real extension never match`() {
        assertFalse(extensionMatchesFilter("README", false, "txt"))
        assertFalse(extensionMatchesFilter(".gitignore", false, "gitignore"))
        assertFalse(extensionMatchesFilter("trailing.", false, "txt"))
    }

    @Test
    fun `name and extension must both match`() {
        assertTrue(itemMatchesFilter("假期照片.jpg", false, "照片", "jpg"))
        assertFalse(itemMatchesFilter("假期照片.jpg", false, "工作", "jpg"))
        assertFalse(itemMatchesFilter("假期照片.mp4", false, "照片", "jpg"))
        // 任一条为空表示不限。
        assertTrue(itemMatchesFilter("假期照片.mp4", false, "照片", ""))
        assertTrue(itemMatchesFilter("假期照片.mp4", false, "", "mp4"))
    }

    @Test
    fun `filter summary spells out both conditions`() {
        assertEquals("", filterSummary("", ""))
        assertEquals("名称「假期」", filterSummary("假期", ""))
        assertEquals("后缀「jpg」", filterSummary("", "jpg"))
        assertEquals("名称「假期」 + 后缀「jpg」", filterSummary("假期", "jpg"))
    }

    // ---------------------------------------------------------------- 可见条目（全选口径）

    private data class Row(val name: String, val dir: Boolean)

    private val rows = listOf(
        Row("照片", true),
        Row("假期照片.jpg", false),
        Row("假期照片.mp4", false),
        Row("工作报告.docx", false)
    )

    private fun visible(name: String, ext: String): List<String> =
        filterVisible(rows, name, ext, { it.name }, { it.dir }).map { it.name }

    @Test
    fun `no filter keeps every row`() {
        // 这条是「不筛选时点全选一个都选不上」的回归锁：两个条件都空必须原样返回全部。
        assertEquals(rows.map { it.name }, visible("", ""))
        assertEquals(rows.map { it.name }, visible("  ", "   "))
    }

    @Test
    fun `name filter narrows select all scope`() {
        assertEquals(listOf("假期照片.jpg", "假期照片.mp4"), visible("假期", ""))
        // 名称筛选不区分文件夹：名字对上了，目录也照样留在可见集合里。
        assertEquals(listOf("照片", "假期照片.jpg", "假期照片.mp4"), visible("照片", ""))
    }

    @Test
    fun `extension filter drops folders too`() {
        assertEquals(listOf("假期照片.jpg"), visible("", "jpg"))
        assertEquals(listOf("假期照片.jpg"), visible("照片", "jpg"))
    }

    @Test
    fun `nothing matching yields empty list`() {
        assertTrue(visible("不存在", "").isEmpty())
        assertTrue(visible("", "7z").isEmpty())
    }
}
