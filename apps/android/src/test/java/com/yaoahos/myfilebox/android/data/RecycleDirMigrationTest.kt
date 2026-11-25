package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 回收站目录改名（`.myfilebox_recycle` → `.trash`）时的迁移。
 *
 * 这段代码挪动的是用户真实文件，出错就是数据丢失；冲突改名和孤儿清单这两个分支
 * 真机上很难造出来，所以用临时目录直接测。
 */
class RecycleDirMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun legacy(): File = tmp.newFolder(".myfilebox_recycle")

    private fun target(): File = File(tmp.root, ".trash").apply { mkdirs() }

    /** 造一个完整条目：数据文件 + 同名清单。 */
    private fun entry(dir: File, id: String, original: String = "原名.txt") {
        File(dir, id).writeText("payload-$id")
        File(dir, "$id.meta").writeText("/sdcard/DCIM/$original\n$original\n1\nfalse\n6")
    }

    @Test
    fun `moves data and meta together and removes legacy dir`() {
        val src = legacy()
        entry(src, "1_0_a.txt")

        val dst = target()
        migrateLegacyRecycleDir(src, dst)

        assertFalse("旧目录应被删掉，否则卸载后仍残留", src.exists())
        assertEquals(listOf("1_0_a.txt", "1_0_a.txt.meta"), dst.list()?.sorted())
        assertEquals("payload-1_0_a.txt", File(dst, "1_0_a.txt").readText())
    }

    @Test
    fun `renames both files when id already exists in target`() {
        val src = legacy()
        entry(src, "1_0_a.txt")

        val dst = target()
        // 目标里已有同一 id（上次迁移只搬了一半）
        File(dst, "1_0_a.txt").writeText("new")
        File(dst, "1_0_a.txt.meta").writeText("new-meta")

        migrateLegacyRecycleDir(src, dst)

        assertFalse(src.exists())
        assertEquals("new", File(dst, "1_0_a.txt").readText())
        assertEquals("payload-1_0_a.txt", File(dst, "1_0_a.txt (1)").readText())
        // 清单必须跟着数据文件一起换名字，否则这条在回收站里读不出原名
        assertEquals("new-meta", File(dst, "1_0_a.txt.meta").readText())
        assertTrue(File(dst, "1_0_a.txt (1).meta").exists())
        assertEquals(4, dst.list()?.size ?: -1)
    }

    @Test
    fun `removes empty legacy dir`() {
        val src = legacy()
        migrateLegacyRecycleDir(src, target())
        assertFalse(src.exists())
    }

    @Test
    fun `keeps orphan meta instead of dropping it`() {
        val src = legacy()
        // 数据文件早已丢失、只剩清单：也要搬走，否则旧目录永远删不掉
        File(src, "9_0_gone.txt.meta").writeText("/x\nx\n1\nfalse\n1")

        val dst = target()
        migrateLegacyRecycleDir(src, dst)

        assertFalse(src.exists())
        assertTrue(File(dst, "9_0_gone.txt.meta").exists())
    }

    @Test
    fun `removes legacy dir even when it holds a nomedia marker`() {
        val src = legacy()
        entry(src, "1_0_a.txt")
        // 用户手动往回收站目录里放过 .nomedia：不能因为它导致旧目录永远删不掉
        File(src, ".nomedia").writeText("")

        val dst = target()
        migrateLegacyRecycleDir(src, dst)

        assertFalse("旧目录必须能被删掉", src.exists())
        assertEquals(listOf("1_0_a.txt", "1_0_a.txt.meta"), dst.list()?.sorted())
    }

    @Test
    fun `no-op when legacy missing`() {
        val dst = target()
        migrateLegacyRecycleDir(File(tmp.root, "nope"), dst)
        assertEquals(0, dst.list()?.size ?: -1)
    }

    @Test
    fun `no-op when legacy equals target`() {
        val dir = legacy()
        entry(dir, "1_0_a.txt")
        migrateLegacyRecycleDir(dir, dir)
        assertTrue(dir.exists())
        assertEquals(2, dir.list()?.size ?: -1)
    }
}
