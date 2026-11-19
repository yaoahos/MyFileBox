package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「最近」展示规则：噪音名、备份名、缓存目录、同组去重、时间窗与合并。
 * 全部是纯函数，不依赖文件系统与 Android 运行时。
 */
class RecentPolicyTest {

    private val now = 1_760_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun file(
        name: String,
        dir: String = "/storage/emulated/0/Download",
        size: Long = 1024L,
        modified: Long = now - 1000L
    ) = FileEntry(
        id = "$dir/$name",
        name = name,
        kind = FileKind.UNKNOWN,
        path = "$dir/$name",
        sizeBytes = size,
        modified = modified
    )

    @Test
    fun noiseNamesAreFiltered() {
        listOf("a.tmp", "a.temp", "a.part", "a.crdownload", "~\$报告.docx", "#笔记#", "run.log", "db-wal", "db-shm")
            .forEach { assertTrue("应判为噪音：$it", RecentPolicy.isNoiseName(it)) }
        listOf("报告.docx", "照片.jpg", "archive.zip", "笔记.md").forEach {
            assertFalse("不应判为噪音：$it", RecentPolicy.isNoiseName(it))
        }
    }

    @Test
    fun backupNamesAreDetected() {
        listOf("报告.md.bak", "报告.orig", "笔记.md~", "论文.asd").forEach {
            assertTrue("应判为备份：$it", RecentPolicy.isBackupName(it))
        }
        // ~$ 是 Office 的锁文件，属于噪音而不是"可打开的副本"
        assertFalse(RecentPolicy.isBackupName("~\$报告.docx"))
        assertFalse(RecentPolicy.isBackupName("报告.docx"))
    }

    @Test
    fun originalNameRestoresSuffix() {
        assertEquals("报告.md", RecentPolicy.originalNameOf("报告.md.bak"))
        assertEquals("笔记.md", RecentPolicy.originalNameOf("笔记.md~"))
        assertEquals(null, RecentPolicy.originalNameOf("报告.docx"))
    }

    @Test
    fun cachePathsAreDetected() {
        assertTrue(RecentPolicy.isCachePath("/storage/emulated/0/tencent/MicroMsg/abc/image2/x.jpg"))
        assertTrue(RecentPolicy.isCachePath("/storage/emulated/0/Android/media/com.whatsapp/Media/x.jpg"))
        assertTrue(RecentPolicy.isCachePath("/storage/emulated/0/DCIM/.thumbnails/x.jpg"))
        assertFalse(RecentPolicy.isCachePath("/storage/emulated/0/DCIM/Camera/IMG_1.jpg"))
        assertFalse(RecentPolicy.isCachePath("/storage/emulated/0/Documents/合同.pdf"))
    }

    @Test
    fun filterKeepsWindowSortsAndDropsEmpty() {
        val files = listOf(
            file("new.jpg", modified = now - 1000L),
            file("mid.jpg", modified = now - 2 * day),
            file("old.jpg", modified = now - 8 * day),
            file("empty.txt", size = 0L),
            file("noise.tmp")
        )
        val kept = RecentPolicy.filter(files, now, hideBackups = true, hideCache = true)
        assertEquals(listOf("new.jpg", "mid.jpg"), kept.map { it.name })
    }

    @Test
    fun filterLimitCapsList() {
        val files = (1..(RecentPolicy.LIMIT + 20)).map {
            file("f$it.jpg", modified = now - it * 1000L)
        }
        assertEquals(RecentPolicy.LIMIT, RecentPolicy.filter(files, now).size)
    }

    @Test
    fun backupsCollapseWhenOriginalExists() {
        val files = listOf(
            file("报告.md", modified = now - 3 * day),
            file("报告.md.bak", modified = now - 1000L)
        )
        assertEquals(listOf("报告.md"), RecentPolicy.filter(files, now).map { it.name })
    }

    @Test
    fun backupSurvivesWhenOriginalGone() {
        val files = listOf(file("报告.md.bak", modified = now - 1000L))
        assertEquals(listOf("报告.md.bak"), RecentPolicy.filter(files, now).map { it.name })
    }

    @Test
    fun backupSwitchDisablesBothHideAndCollapse() {
        val files = listOf(
            file("报告.md", modified = now - 3 * day),
            file("报告.md.bak", modified = now - 1000L)
        )
        val kept = RecentPolicy.filter(files, now, hideBackups = false)
        assertEquals(listOf("报告.md.bak", "报告.md"), kept.map { it.name })
    }

    @Test
    fun cacheSwitchDisablesCacheFiltering() {
        val cached = file("x.jpg", dir = "/storage/emulated/0/tencent/MicroMsg/abc/image2")
        assertTrue(RecentPolicy.filter(listOf(cached), now).isEmpty())
        assertEquals(1, RecentPolicy.filter(listOf(cached), now, hideCache = false).size)
    }

    @Test
    fun filesInsideBackupDirectoryAreHidden() {
        // 遍历层不会进 Documents/Backup，但 MediaStore 索引仍会返回里面的文件，
        // 展示层必须用同一套口径再拦一次，否则两路边合并后又漏回来。
        val inBackup = file("旧稿.docx", dir = "/storage/emulated/0/Documents/Backup")
        val inDocuments = file("新稿.docx", dir = "/storage/emulated/0/Documents")
        assertTrue(RecentPolicy.isBackupPath(inBackup.path))
        assertFalse(RecentPolicy.isBackupPath(inDocuments.path))
        val kept = RecentPolicy.filter(listOf(inBackup, inDocuments), now)
        assertEquals(listOf("新稿.docx"), kept.map { it.name })
        // 开关关掉时照常显示
        assertEquals(2, RecentPolicy.filter(listOf(inBackup, inDocuments), now, hideBackups = false).size)
    }

    @Test
    fun mergeDedupesByPathAndKeepsNewerTimestamp() {
        val indexed = file("a.jpg", modified = now - 2 * day)
        val scanned = file("a.jpg", modified = now - 1000L)
        val merged = RecentPolicy.merge(listOf(indexed), listOf(scanned), now, true, true)
        assertEquals(1, merged.size)
        assertEquals(now - 1000L, merged.first().modified)
    }

    @Test
    fun mergeAddsFilesOnlySeenByWalk() {
        val indexed = file("a.jpg")
        val onlyLocal = file("b.jpg", dir = "/storage/emulated/0/Notes")
        val merged = RecentPolicy.merge(listOf(indexed), listOf(onlyLocal), now, true, true)
        assertEquals(setOf("a.jpg", "b.jpg"), merged.map { it.name }.toSet())
    }
}
