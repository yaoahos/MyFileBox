package com.yaoahos.myfilebox.android.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * LocalRepository 伴随对象里的纯规则单测：
 * 解压路径校验（zip-slip 防护）与日期格式化。
 * 日期断言固定默认时区/地区，保证在任何机器上确定性通过。
 */
class LocalRepositoryRulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var savedTimeZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before
    fun fixDefaults() {
        savedTimeZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreDefaults() {
        TimeZone.setDefault(savedTimeZone)
        Locale.setDefault(savedLocale)
    }

    // ---- safeExtractTarget：zip-slip 防护 ----

    @Test
    fun safeExtractTarget_allowsEntryInsideRoot() {
        val root = tmp.newFolder("root")
        val target = LocalRepository.safeExtractTarget(root, "ok.txt")
        assertNotNull(target)
        assertEquals(File(root, "ok.txt").canonicalFile, target)

        val nested = LocalRepository.safeExtractTarget(root, "sub/dir/ok.txt")
        assertNotNull(nested)
        assertEquals(File(root, "sub/dir/ok.txt").canonicalFile, nested)
    }

    @Test
    fun safeExtractTarget_allowsDotDotThatStaysInside() {
        val root = tmp.newFolder("root")
        // a/../ok.txt 规范化后仍是 root 内部，应放行
        val target = LocalRepository.safeExtractTarget(root, "a/../ok.txt")
        assertNotNull(target)
        assertEquals(File(root, "ok.txt").canonicalFile, target)
    }

    @Test
    fun safeExtractTarget_rejectsParentEscapes() {
        val root = tmp.newFolder("root")
        assertNull(LocalRepository.safeExtractTarget(root, "../evil.txt"))
        assertNull(LocalRepository.safeExtractTarget(root, "sub/../../evil.txt"))
        assertNull(LocalRepository.safeExtractTarget(root, "../../../../etc/passwd"))
    }

    @Test
    fun safeExtractTarget_rejectsSiblingPrefixTrick() {
        // root=.../r、越界到兄弟目录 .../rx：字符串前缀会误判成内部路径
        val root = File(tmp.root, "r").apply { mkdirs() }
        assertNull(LocalRepository.safeExtractTarget(root, "../${root.name}x/evil.txt"))
    }

    @Test
    fun safeExtractTarget_rejectsRootItself() {
        val root = tmp.newFolder("root")
        // 目标恰好等于 root（空条目 / "."）不算"内部"，必须拒绝
        assertNull(LocalRepository.safeExtractTarget(root, ""))
        assertNull(LocalRepository.safeExtractTarget(root, "."))
    }

    // ---- formatDate：固定 yyyy/MM/dd，非法时间戳为空 ----

    @Test
    fun formatDate_fixedPattern() {
        val t = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 10, 8, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("2026/11/10", LocalRepository.formatDate(t))
    }

    @Test
    fun formatDate_invalidTimestampEmpty() {
        assertEquals("", LocalRepository.formatDate(0L))
        assertEquals("", LocalRepository.formatDate(-5L))
    }

    // ---- formatDateShort：本年省略年份，跨年带年份 ----

    @Test
    fun formatDateShort_omitsYearInSameYear() {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val t = Calendar.getInstance().apply {
            set(year, Calendar.NOVEMBER, 10, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("11/10", LocalRepository.formatDateShort(t))
    }

    @Test
    fun formatDateShort_keepsYearWhenCrossYear() {
        val year = Calendar.getInstance().get(Calendar.YEAR) - 2
        val t = Calendar.getInstance().apply {
            set(year, Calendar.MAY, 6, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(String.format("%d/05/06", year), LocalRepository.formatDateShort(t))
    }

    @Test
    fun formatDateShort_invalidTimestampEmpty() {
        assertEquals("", LocalRepository.formatDateShort(0L))
    }

    // ---- formatSize：量级分档 ----

    @Test
    fun formatSize_scalesByMagnitude() {
        assertEquals("0 B", LocalRepository.formatSize(0L))
        assertEquals("512 B", LocalRepository.formatSize(512L))
        assertEquals("1.0 KB", LocalRepository.formatSize(1024L))
        assertEquals("1.5 KB", LocalRepository.formatSize(1536L))
        assertEquals("1.0 MB", LocalRepository.formatSize(1024L * 1024L))
        // 与通知 / 回收站统一为一位小数，不再单独用两位
        assertEquals("1.0 GB", LocalRepository.formatSize(1024L * 1024L * 1024L))
        // 小于 1 KB 保持字节整数
        assertTrue(LocalRepository.formatSize(2000L).endsWith("KB"))
    }
}
