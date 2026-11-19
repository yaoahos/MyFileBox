package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * 带密码压缩 / 解压的往返测试。
 *
 * LocalRepository 的构造器依赖 Android Context，但这里覆盖的四个方法只碰文件系统；
 * JVM 单测用 Unsafe 分配实例，省掉为几个文件操作引入 Robolectric。
 */
class ArchivePasswordTest {

    private fun repository(): LocalRepository {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(field.get(null), LocalRepository::class.java) as LocalRepository
    }

    private fun entry(file: File) = FileEntry(
        id = file.absolutePath,
        name = file.name,
        kind = FileKinds.of(file.name),
        path = file.absolutePath
    )

    private fun tempDir(prefix: String) = createTempDirectory(prefix).toFile()

    @Test
    fun sevenZEncryptedRoundTrip() {
        val repo = repository()
        val dir = tempDir("mfb-7z-enc")
        val source = File(dir, "报告.txt").apply { writeText("机密内容") }
        val archive = repo.createArchive(
            listOf(entry(source)), dir.absolutePath, "加密包", "7z", null, "s3cr3t".toCharArray()
        ) ?: throw AssertionError("7z 加密压缩失败")

        assertTrue("加密包应被探测为需要密码", repo.archiveNeedsPassword(archive.absolutePath))
        val output = File(dir, "out").apply { mkdirs() }
        val result = repo.extractArchive(
            archive.absolutePath, output, password = "s3cr3t".toCharArray()
        )
        assertEquals("机密内容", File(result, "报告.txt").readText())
    }

    @Test
    fun zipEncryptedRoundTrip() {
        val repo = repository()
        val dir = tempDir("mfb-zip-enc")
        val source = File(dir, "报告.txt").apply { writeText("机密内容") }
        val archive = repo.createArchive(
            listOf(entry(source)), dir.absolutePath, "加密包", "zip", null, "s3cr3t".toCharArray()
        ) ?: throw AssertionError("zip 加密压缩失败")

        assertTrue("加密 zip 应被探测为需要密码", repo.archiveNeedsPassword(archive.absolutePath))
        val output = File(dir, "out").apply { mkdirs() }
        val result = repo.extractArchive(
            archive.absolutePath, output, password = "s3cr3t".toCharArray()
        )
        assertEquals("机密内容", File(result, "报告.txt").readText())
    }

    @Test
    fun wrongPasswordAsksAgainForBothFormats() {
        val repo = repository()
        listOf("zip", "7z").forEach { format ->
            val dir = tempDir("mfb-wrong-$format")
            val source = File(dir, "秘密.txt").apply { writeText("内容") }
            val archive = repo.createArchive(
                listOf(entry(source)), dir.absolutePath, "加密包", format, null, "right".toCharArray()
            ) ?: throw AssertionError("$format 加密压缩失败")
            val output = File(dir, "out").apply { mkdirs() }
            try {
                repo.extractArchive(archive.absolutePath, output, password = "wrong".toCharArray())
                throw AssertionError("$format 用错密码应要求重新输入")
            } catch (e: ArchivePasswordRequired) {
                assertEquals("密码错误，请重新输入", e.message)
            }
        }
    }

    @Test
    fun plainArchiveNeedsNoPassword() {
        val repo = repository()
        val dir = tempDir("mfb-plain")
        val source = File(dir, "普通.txt").apply { writeText("公开") }
        listOf("zip", "7z").forEach { format ->
            val archive = repo.createArchive(
                listOf(entry(source)), dir.absolutePath, "普通包$format", format
            ) ?: throw AssertionError("$format 无密码压缩失败")
            assertFalse("$format 明文包不应弹密码框", repo.archiveNeedsPassword(archive.absolutePath))
            val output = File(dir, "out-$format").apply { mkdirs() }
            val result = repo.extractArchive(archive.absolutePath, output)
            assertEquals("公开", File(result, "普通.txt").readText())
        }
    }

    @Test
    fun encryptedZipKeepsChineseEntryName() {
        val repo = repository()
        val dir = tempDir("mfb-cn")
        val source = File(dir, "中文 报告.txt").apply { writeText("内容") }
        val archive = repo.createArchive(
            listOf(entry(source)), dir.absolutePath, "中文包", "zip", null, "pw".toCharArray()
        ) ?: throw AssertionError("zip 加密压缩失败")
        val output = File(dir, "out").apply { mkdirs() }
        val result = repo.extractArchive(archive.absolutePath, output, password = "pw".toCharArray())
        assertEquals("内容", File(result, "中文 报告.txt").readText())
    }

    @Test
    fun encryptedZipConflictScanUsesPassword() {
        val repo = repository()
        val dir = tempDir("mfb-conflict")
        val source = File(dir, "报告.txt").apply { writeText("包内版本") }
        val archive = repo.createArchive(
            listOf(entry(source)), dir.absolutePath, "加密包", "zip", null, "pw".toCharArray()
        ) ?: throw AssertionError("zip 加密压缩失败")
        val output = File(dir, "out").apply { mkdirs() }
        File(output, "报告.txt").writeText("已存在")
        assertEquals(
            listOf("报告.txt"),
            repo.archiveConflictNames(archive.absolutePath, output, password = "pw".toCharArray())
        )
    }
}
