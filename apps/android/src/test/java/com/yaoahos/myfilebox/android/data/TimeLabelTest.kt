package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两套时间列口径（同一份数据，两种场景）：
 * - 列表 [LocalRepository.formatStamp]：一律 `yyyy/MM/dd HH:mm:ss`，当天也不例外；
 * - 宫格 [LocalRepository.formatGridStamp]：当天只给 `HH:mm:ss`，更早只给 `yyyy/MM/dd`。
 *
 * 这里只校验格式形状，具体日期随系统时区与当前时间变化。
 */
class TimeLabelTest {

    private val timeOnly = Regex("""\d{2}:\d{2}:\d{2}""")
    private val dateOnly = Regex("""\d{4}/\d{2}/\d{2}""")
    private val dateTime = Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}""")

    @Test
    fun `today shows date and time`() {
        val label = LocalRepository.formatStamp(System.currentTimeMillis())
        assertTrue("今天也应是 yyyy/MM/dd HH:mm:ss，实际 $label", dateTime.matches(label))
    }

    @Test
    fun `earlier day shows date and time`() {
        val label = LocalRepository.formatStamp(System.currentTimeMillis() - 3L * 24 * 3600 * 1000)
        assertTrue("更早也应是 yyyy/MM/dd HH:mm:ss，实际 $label", dateTime.matches(label))
    }

    @Test
    fun `full format keeps both date and time`() {
        val label = LocalRepository.formatFull(System.currentTimeMillis() - 3L * 24 * 3600 * 1000)
        assertTrue("详情应是 yyyy/MM/dd HH:mm:ss，实际 $label", dateTime.matches(label))
    }

    @Test
    fun `formatDate never carries time`() {
        val label = LocalRepository.formatDate(System.currentTimeMillis())
        assertTrue("分区键应是 yyyy/MM/dd，实际 $label", dateOnly.matches(label))
    }

    @Test
    fun `grid today shows time only`() {
        val label = LocalRepository.formatGridStamp(System.currentTimeMillis())
        assertTrue("宫格当天应只有 HH:mm:ss，实际 $label", timeOnly.matches(label))
    }

    @Test
    fun `grid earlier day shows date only`() {
        val label = LocalRepository.formatGridStamp(System.currentTimeMillis() - 3L * 24 * 3600 * 1000)
        assertTrue("宫格更早应只有 yyyy/MM/dd，实际 $label", dateOnly.matches(label))
    }

    @Test
    fun `grid text trims today to time part`() {
        val now = LocalRepository.formatStamp(System.currentTimeMillis())
        assertEquals(now.substringAfter(" "), LocalRepository.formatGridText(now))
    }

    @Test
    fun `grid text keeps date only for earlier day`() {
        val earlier = LocalRepository.formatStamp(System.currentTimeMillis() - 3L * 24 * 3600 * 1000)
        assertEquals(earlier.substringBefore(" "), LocalRepository.formatGridText(earlier))
    }

    @Test
    fun `grid text leaves plain subtitle untouched`() {
        // 群晖共享卡的副标题没有日期时间，宫格不能把它的空格当分隔符切坏。
        assertEquals("2 天前更新", LocalRepository.formatGridText("2 天前更新"))
        assertEquals("", LocalRepository.formatGridText(""))
    }

    @Test
    fun `unknown timestamp renders empty`() {
        assertEquals("", LocalRepository.formatStamp(0))
        assertEquals("", LocalRepository.formatFull(0))
        assertEquals("", LocalRepository.formatDate(0))
        assertEquals("", LocalRepository.formatGridStamp(0))
    }
}
