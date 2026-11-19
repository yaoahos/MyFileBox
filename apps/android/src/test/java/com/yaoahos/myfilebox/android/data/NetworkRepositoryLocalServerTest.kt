package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.NetworkConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket

/**
 * FTP / SFTP 仓库对接本机测试服务的集成验证。
 *
 * 服务未启动时会跳过，不影响常规单测；启动 `_process/test_ftp_sftp_server.py`
 * 后执行，可真实覆盖连接、列目录与失败路径。
 */
class NetworkRepositoryLocalServerTest {

    private fun portOpen(port: Int): Boolean = try {
        Socket("127.0.0.1", port).use { true }
    } catch (_: Exception) {
        false
    }

    private fun connection(protocol: String, port: Int, user: String, password: String) = NetworkConnection(
        id = "local-$protocol",
        name = "Local${protocol.uppercase()}",
        protocol = protocol,
        host = "127.0.0.1",
        port = port,
        path = "/",
        online = true,
        latency = 0,
        username = user,
        password = password
    )

    @Test
    fun `ftp connects and lists local server`() {
        assumeTrue("需要本机 FTP 测试服务", portOpen(2121))
        val repo = FtpRepository()
        val conn = connection("ftp", 2121, "ftptest", "test1234")
        assertTrue(repo.test(conn))
        val entries = runBlocking { repo.list(conn, "/") }
        assertTrue(entries.any { it.name == "hello.txt" })
        assertTrue(entries.any { it.name == "sub" })
    }

    @Test
    fun `ftp wrong port fails instead of returning empty list`() {
        assumeTrue("需要本机 FTP 测试服务", portOpen(2121))
        val conn = connection("ftp", 1, "ftptest", "test1234")
        assertThrows(Exception::class.java) { FtpRepository().test(conn) }
    }

    /**
     * 二进制往返：载荷里塞满 0x0A / 0x0D / 0x0D0A。
     *
     * FTP 的 TYPE 是登录后命令，曾经写在 login 之前、返回值又没人看，连接实际一直
     * 停在服务端默认的 ASCII 模式——每遇到 0x0A 就被扩成 0x0D0A，真机上传的 jpg
     * 到远端各自大出几百字节、图片打不开。这条用例专门盯住这个回归。
     */
    @Test
    fun `ftp keeps binary payload byte for byte`() {
        assumeTrue("需要本机 FTP 测试服务", portOpen(2121))
        val repo = FtpRepository()
        val conn = connection("ftp", 2121, "ftptest", "test1234")
        val payload = ByteArray(4096) { i ->
            when (i % 5) {
                0 -> 0x0A
                1 -> 0x0D
                2 -> 0x0D
                3 -> 0x00
                else -> (i % 251).toByte()
            }
        }
        val source = File.createTempFile("ftp-binary-", ".bin").apply { writeBytes(payload) }
        val sink = File.createTempFile("ftp-binary-out-", ".bin")
        val remote = "binary-probe-${System.nanoTime()}.bin"
        try {
            assertTrue("上传失败", runBlocking { repo.upload(conn, source, "/", remote) })
            assertTrue("下载失败", runBlocking { repo.download(conn, "/$remote", sink) })
            assertTrue("二进制内容被改写过（很可能是 ASCII 模式）", payload.contentEquals(sink.readBytes()))
        } finally {
            runCatching { runBlocking { repo.delete(conn, listOf("/$remote")) } }
            source.delete()
            sink.delete()
        }
    }

    @Test
    fun `sftp connects and lists local server`() {
        assumeTrue("需要本机 SFTP 测试服务", portOpen(2222))
        val repo = SftpRepository()
        val conn = connection("sftp", 2222, "sftptest", "test1234")
        assertTrue(repo.test(conn))
        val entries = repo.list(conn, "/")
        assertTrue(entries.any { it.name == "hello.txt" })
        assertTrue(entries.any { it.name == "sub" })
    }
}
