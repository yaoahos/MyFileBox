package com.yaoahos.myfilebox.android.data

import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 密码校验必须在冲突选择之前完成。
 *
 * 用户选「跳过同名文件」且条目是 0 字节时，解压循环可能一次密文都不读，
 * 密码错误就不会暴露。这里锁死「0 字节加密包也能判出错密码」的行为。
 */
class ArchivePasswordVerifyTest {

    @JvmField
    @Rule
    val temp = TemporaryFolder()

    private val rightPassword = "correct123".toCharArray()
    private val wrongPassword = "wrongpass1".toCharArray()

    @Test
    fun `zero byte encrypted zip rejects wrong password`() {
        val archive = encryptedZip("zero.zip")
        assertTrue(LocalRepository.verifyArchivePasswordOf(archive.absolutePath, rightPassword))
        assertFalse(LocalRepository.verifyArchivePasswordOf(archive.absolutePath, wrongPassword))
    }

    @Test
    fun `encrypted 7z with zero byte entry rejects wrong password`() {
        // 7z 不加密文件头；若整个包只有 0 字节条目，确实没有密文可用于校验口令。
        // 这里放一个 1 字节条目加一个 0 字节条目，覆盖「跳过同名 + 0 字节条目」
        // 同时保证错密码有可判定的依据。
        val archive = encryptedSevenZ("zero.7z")
        assertTrue(LocalRepository.verifyArchivePasswordOf(archive.absolutePath, rightPassword))
        assertFalse(LocalRepository.verifyArchivePasswordOf(archive.absolutePath, wrongPassword))
    }

    private fun encryptedZip(name: String): File {
        val empty = temp.newFile("payload.txt")
        val archive = File(temp.root, name)
        net.lingala.zip4j.ZipFile(archive).use { zip ->
            zip.setPassword(rightPassword)
            val parameters = ZipParameters().apply {
                fileNameInZip = "empty.txt"
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
            }
            zip.addFile(empty, parameters)
        }
        return archive
    }

    private fun encryptedSevenZ(name: String): File {
        val small = temp.newFile("small7z.txt").apply { writeText("x") }
        val empty = temp.newFile("empty7z.txt")
        val archive = File(temp.root, name)
        SevenZOutputFile(archive, rightPassword).use { out ->
            out.putArchiveEntry(out.createArchiveEntry(small, "small.txt"))
            out.write(small.readBytes())
            out.closeArchiveEntry()
            out.putArchiveEntry(out.createArchiveEntry(empty, "empty.txt"))
            out.closeArchiveEntry()
        }
        return archive
    }
}
