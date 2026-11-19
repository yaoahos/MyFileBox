package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 解压同名冲突的落盘路径规则。
 *
 * 以前这里没有规则可用：`outputStream()` 直接盖掉同名文件，用户既没被问过，
 * 也没法要一份「两份都留」。三个策略各自的行为都在这里锁死。
 *
 * `exists` 由调用方注入，所以不用真造文件。
 */
class ExtractConflictTest {

    private fun file(name: String) = File("/storage/emulated/0/Download/$name")

    @Test
    fun overwriteReturnsSameTargetEvenWhenFileExists() {
        val target = file("报告.docx")
        val actual = LocalRepository.conflictTarget(target, ExtractConflict.OVERWRITE) { true }
        assertEquals(target, actual)
    }

    @Test
    fun noConflictReturnsTargetUnderEveryPolicy() {
        val target = file("报告.docx")
        ExtractConflict.entries.forEach { policy ->
            assertEquals("策略 $policy 无冲突时应原样返回", target, LocalRepository.conflictTarget(target, policy) { false })
        }
    }

    @Test
    fun skipReturnsNullWhenTargetExists() {
        assertNull(LocalRepository.conflictTarget(file("报告.docx"), ExtractConflict.SKIP) { true })
    }

    @Test
    fun keepBothAppendsIndexBeforeExtension() {
        val actual = LocalRepository.conflictTarget(file("报告.docx"), ExtractConflict.KEEP_BOTH) {
            it.name == "报告.docx"
        }
        assertEquals("报告 (1).docx", actual?.name)
    }

    @Test
    fun keepBothWalksPastTakenIndexes() {
        val taken = setOf("报告.docx", "报告 (1).docx", "报告 (2).docx")
        val actual = LocalRepository.conflictTarget(file("报告.docx"), ExtractConflict.KEEP_BOTH) {
            it.name in taken
        }
        assertEquals("报告 (3).docx", actual?.name)
    }

    @Test
    fun keepBothTreatsLeadingDotAsNameNotExtension() {
        // `.gitignore` 的点不是扩展名，拼出来应该是 `.gitignore (1)` 而不是 ` (1).gitignore`
        val actual = LocalRepository.conflictTarget(file(".gitignore"), ExtractConflict.KEEP_BOTH) {
            it.name == ".gitignore"
        }
        assertEquals(".gitignore (1)", actual?.name)
    }

    @Test
    fun keepBothHandlesFileWithoutExtension() {
        val actual = LocalRepository.conflictTarget(file("README"), ExtractConflict.KEEP_BOTH) {
            it.name == "README"
        }
        assertEquals("README (1)", actual?.name)
    }

    @Test
    fun keepBothSplitsAtLastDotOnly() {
        // 多段扩展名只在最后一段前插入序号（`a.tar.gz` → `a.tar (1).gz`）。
        // 解压出来的条目几乎不会是多段扩展名，为它特判不值得。
        val actual = LocalRepository.conflictTarget(file("a.tar.gz"), ExtractConflict.KEEP_BOTH) {
            it.name == "a.tar.gz"
        }
        assertEquals("a.tar (1).gz", actual?.name)
        assertEquals(file("x").parent, actual?.parent)
    }
}
