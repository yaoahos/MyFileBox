package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 目录复制的两条硬要求：
 *
 * 1. 必须逐文件回调进度。粘贴一个大文件夹时外层只有「1 项」，只按项计数会一直停在
 *    「1/1」，用户依然以为卡住（这是「粘贴没有进度条」的直接原因）。
 * 2. 目标已存在同名文件时必须失败且不覆盖——与 `copyRecursively(overwrite = false)`
 *    的原语义一致，不能因为改成分段复制就把用户已有文件冲掉。
 */
class LocalRepositoryCopyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `复制目录保留层级并逐文件回报进度`() {
        val source = tmp.newFolder("src")
        File(source, "a.txt").writeText("A")
        File(source, "sub").mkdirs()
        File(source, "sub/b.txt").writeText("BB")
        File(source, "sub/deep").mkdirs()
        File(source, "sub/deep/c.txt").writeText("CCC")

        val target = File(tmp.newFolder("dst"), "src")
        val reports = mutableListOf<Pair<Int, Int>>()
        val ok = LocalRepository.copyDirectoryTree(source, target) { copied, total ->
            reports += copied to total
        }

        assertTrue(ok)
        assertEquals("A", File(target, "a.txt").readText())
        assertEquals("BB", File(target, "sub/b.txt").readText())
        assertEquals("CCC", File(target, "sub/deep/c.txt").readText())

        // 三个文件 → 三次回调，分母恒为 3，分子严格递增到 3。
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), reports)
    }

    @Test
    fun `目标已存在同名文件时不覆盖并判失败`() {
        val source = tmp.newFolder("src2")
        File(source, "keep.txt").writeText("new")
        val dest = tmp.newFolder("dst2")
        val target = File(dest, "src2")
        target.mkdirs()
        File(target, "keep.txt").writeText("old")

        val ok = LocalRepository.copyDirectoryTree(source, target)

        assertFalse(ok)
        assertEquals("old", File(target, "keep.txt").readText())
    }

    @Test
    fun `空目录也算一次进度避免停在零项`() {
        val source = tmp.newFolder("empty")
        File(source, "nested").mkdirs()
        val target = File(tmp.newFolder("dst3"), "empty")

        val reports = mutableListOf<Pair<Int, Int>>()
        val ok = LocalRepository.copyDirectoryTree(source, target) { copied, total ->
            reports += copied to total
        }

        assertTrue(ok)
        assertTrue(File(target, "nested").isDirectory)
        assertEquals(listOf(1 to 1), reports)
    }
}
