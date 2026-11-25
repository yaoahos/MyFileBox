package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 回收站条目的命名、认领与删除一致性。
 *
 * 这些分支（meta 写失败、被人删了 meta、数据文件被清理软件删掉、旧命名遗留）
 * 真机上造不出来，但每一条都直接关系到“用户的文件还在、界面上却看不见”，
 * 所以用临时目录把逻辑单独测。
 */
class RecycleEntryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun trash(): File = tmp.newFolder(".trash")

    @Test
    fun `random id is twelve lowercase hex`() {
        repeat(200) {
            val id = randomRecycleId()
            assertEquals(12, id.length)
            assertTrue("只允许 0-9a-f：$id", id.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun `random id does not repeat`() {
        val seen = HashSet<String>()
        repeat(5_000) { assertTrue("生成 ${seen.size} 个后撞了", seen.add(randomRecycleId())) }
    }

    @Test
    fun `unique target keeps extension and walks index`() {
        val dir = trash()
        File(dir, "报告.docx").writeText("a")
        assertEquals("报告 (1).docx", uniqueTargetFile(dir, "报告.docx").name)
        File(dir, "报告 (1).docx").writeText("b")
        assertEquals("报告 (2).docx", uniqueTargetFile(dir, "报告.docx").name)
    }

    @Test
    fun `unique target handles name without extension`() {
        val dir = trash()
        File(dir, "README").writeText("a")
        assertEquals("README (1)", uniqueTargetFile(dir, "README").name)
    }

    @Test
    fun `unique target returns original name when free`() {
        assertEquals("x.txt", uniqueTargetFile(trash(), "x.txt").name)
    }

    @Test
    fun `reads indexed entry`() {
        val dir = trash()
        File(dir, "a1b2c3d4e5f6").writeText("payload")
        writeRecycleMeta(dir, "a1b2c3d4e5f6", "/sdcard/DCIM/旅行.mp4", "旅行.mp4", false, 7L, 1_000L)

        val item = readRecycleRoot(dir).single()
        assertEquals("a1b2c3d4e5f6", item.id)
        assertEquals("旅行.mp4", item.name)
        assertEquals("/sdcard/DCIM/旅行.mp4", item.originalPath)
        assertEquals(1_000L, item.deletedAt)
        assertEquals(7L, item.sizeBytes)
        assertFalse(item.isFolder)
    }

    @Test
    fun `adopts data without meta and writes one back`() {
        val dir = trash()
        File(dir, "deadbeef0001").writeText("payload")

        val item = readRecycleRoot(dir).single()
        assertEquals("deadbeef0001", item.id)
        assertTrue("占位名应带删除日期：${item.name}", item.name.startsWith("恢复的文件_"))
        assertEquals("原路径无从得知应留空", "", item.originalPath)
        assertTrue("应补一份 meta，下次直接按索引读", File(dir, "deadbeef0001.meta").exists())
        // 再读一次走正常索引路径，名字不能变
        assertEquals(item.name, readRecycleRoot(dir).single().name)
    }

    @Test
    fun `adopts legacy name and recovers original name and time`() {
        val dir = trash()
        val stamp = 1_700_000_000_000L
        File(dir, "${stamp}_0_旅行视频.mp4").writeText("payload")

        val item = readRecycleRoot(dir).single()
        assertEquals("旅行视频.mp4", item.name)
        assertEquals(stamp, item.deletedAt)
        assertEquals("", item.originalPath)
    }

    @Test
    fun `drops meta whose data is gone`() {
        val dir = trash()
        File(dir, "gone.meta").writeText("/x\nx\n1\nfalse\n1")

        assertTrue(readRecycleRoot(dir).isEmpty())
        assertFalse("无主索引要清掉，否则越攒越多", File(dir, "gone.meta").exists())
    }

    @Test
    fun `ignores nomedia marker`() {
        val dir = trash()
        File(dir, ".nomedia").writeText("")

        assertTrue(readRecycleRoot(dir).isEmpty())
        assertTrue("标记文件本身不能被当成条目删掉", File(dir, ".nomedia").exists())
    }

    @Test
    fun `folder child count excludes nomedia`() {
        val dir = trash()
        val folder = File(dir, "abc123abc123").apply { mkdirs() }
        File(folder, "a.txt").writeText("a")
        File(folder, ".nomedia").writeText("")
        writeRecycleMeta(dir, "abc123abc123", "/sdcard/DCIM/相册", "相册", true, 0L, 1L)

        val item = readRecycleRoot(dir).single()
        assertTrue(item.isFolder)
        assertEquals(1, item.childCount)
    }

    @Test
    fun `adopts folder without meta`() {
        val dir = trash()
        File(dir, "ffffffffffff").mkdirs()

        val item = readRecycleRoot(dir).single()
        assertTrue(item.isFolder)
        assertTrue("占位名应带删除日期：${item.name}", item.name.startsWith("恢复的文件夹_"))
    }

    @Test
    fun `broken meta still surfaces the data`() {
        val dir = trash()
        File(dir, "bad0bad0bad0").writeText("payload")
        File(dir, "bad0bad0bad0.meta").writeText("garbage")

        val item = readRecycleRoot(dir).single()
        assertEquals("bad0bad0bad0", item.id)
        assertTrue("坏索引应退回占位名而不是让条目消失：${item.name}", item.name.startsWith("恢复的文件_"))
        assertEquals("坏索引要被重写成合法内容", 5, File(dir, "bad0bad0bad0.meta").readLines().size)
    }

    @Test
    fun `data file whose own name ends with meta is not treated as an index`() {
        val dir = trash()
        // 旧版落盘名 = 时间戳_下标_原名；用户删的文件本身就叫「报告.meta」，
        // 于是数据文件名也以 .meta 结尾，旁边还有一份 …meta.meta
        val id = "1700000000000_0_报告.meta"
        File(dir, id).writeText("payload")
        File(dir, "$id.meta").writeText("/sdcard/Doc/报告.meta\n报告.meta\n1700000000000\nfalse\n7")

        val item = readRecycleRoot(dir).single()
        assertEquals(id, item.id)
        assertEquals("报告.meta", item.name)
        assertTrue("数据文件不能被当成无主索引删掉", File(dir, id).exists())
        assertEquals("payload", File(dir, id).readText())
    }

    @Test
    fun `move refuses to overwrite an existing target`() {
        val dir = trash()
        val src = File(tmp.root, "src.txt").apply { writeText("src") }
        val dest = File(dir, "dest.txt").apply { writeText("keep") }

        moveRecycleEntry(src, dest)

        assertEquals("已存在的文件不能被顶掉", "keep", dest.readText())
        assertEquals("源文件要留着，交给调用方报失败", "src", src.readText())
    }
}
