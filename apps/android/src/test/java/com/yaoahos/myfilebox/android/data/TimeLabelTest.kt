package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

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
        // 「当天 / 更早」要看当前时间，必须把 now 钉在同一时刻：
        // 否则测试正好跨过零点时会拿前一秒的时间去比后一天的日期（假阳性）。
        val nowMs = System.currentTimeMillis()
        val label = LocalRepository.formatGridStamp(nowMs, nowMs)
        assertTrue("宫格当天应只有 HH:mm:ss，实际 $label", timeOnly.matches(label))
    }

    @Test
    fun `grid earlier day shows date only`() {
        val nowMs = System.currentTimeMillis()
        val label = LocalRepository.formatGridStamp(nowMs - 3L * 24 * 3600 * 1000, nowMs)
        assertTrue("宫格更早应只有 yyyy/MM/dd，实际 $label", dateOnly.matches(label))
    }

    @Test
    fun `grid text trims today to time part`() {
        val nowMs = System.currentTimeMillis()
        val now = LocalRepository.formatStamp(nowMs)
        assertEquals(now.substringAfter(" "), LocalRepository.formatGridText(now, nowMs))
    }

    @Test
    fun `grid text keeps date only for earlier day`() {
        val nowMs = System.currentTimeMillis()
        val earlier = LocalRepository.formatStamp(nowMs - 3L * 24 * 3600 * 1000)
        assertEquals(earlier.substringBefore(" "), LocalRepository.formatGridText(earlier, nowMs))
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

    @Test
    fun `formatter follows default time zone changes`() {
        // 格式化器按线程缓存，而 SimpleDateFormat 会把创建时的时区固化下来。
        // 用户改系统时区后必须立刻按新时区显示，不能沿用缓存里的旧口径。
        val original = TimeZone.getDefault()
        val ms = 1795107470273L // 2026/11/20 00:57:50 +08:00
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val shanghai = LocalRepository.formatStamp(ms)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utc = LocalRepository.formatStamp(ms)
            assertNotEquals("同一时刻换时区后时间显示应跟着变", shanghai, utc)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
