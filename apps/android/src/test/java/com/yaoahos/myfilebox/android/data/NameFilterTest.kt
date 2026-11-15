package com.yaoahos.myfilebox.android.data

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
}
