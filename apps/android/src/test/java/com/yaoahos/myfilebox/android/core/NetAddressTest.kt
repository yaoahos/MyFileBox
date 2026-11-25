package com.yaoahos.myfilebox.android.core

import com.yaoahos.myfilebox.core.NetworkConnection
import com.yaoahos.myfilebox.core.connectionAddressLabel
import com.yaoahos.myfilebox.core.remoteFullPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网络地址展示：四种协议用同一种 `协议://主机:端口/路径` 写法。
 *
 * SMB 以前是 UNC（`\\host\share`），既和其它三种对不上，也表达不了自定义端口。
 */
class NetAddressTest {

    private fun conn(protocol: String, path: String, port: Int, secure: Boolean = false) =
        NetworkConnection(
            id = "c1",
            name = "测试",
            protocol = protocol,
            host = "192.168.1.10",
            port = port,
            path = path,
            online = true,
            latency = 0,
            secure = secure
        )

    @Test
    fun `smb address uses url form with port`() {
        assertEquals(
            "smb://192.168.1.10:445/Backup/照片",
            connectionAddressLabel(conn("SMB", "Backup/照片", 445))
        )
    }

    @Test
    fun `smb address without path has no trailing slash`() {
        assertEquals("smb://192.168.1.10:445", connectionAddressLabel(conn("SMB", "", 445)))
    }

    @Test
    fun `smb custom port is visible`() {
        assertEquals("smb://192.168.1.10:1445/Backup", connectionAddressLabel(conn("SMB", "Backup", 1445)))
    }

    @Test
    fun `other protocols keep their existing form`() {
        assertEquals("ftp://192.168.1.10:21/pub", connectionAddressLabel(conn("FTP", "/pub", 21)))
        assertEquals("sftp://192.168.1.10:2222/data", connectionAddressLabel(conn("SFTP", "/data", 2222)))
        assertEquals("https://192.168.1.10:443/dav", connectionAddressLabel(conn("WEBDAV", "/dav", 443, secure = true)))
        assertEquals("http://192.168.1.10:5005/dav", connectionAddressLabel(conn("WEBDAV", "/dav", 5005)))
    }

    @Test
    fun `smb file path becomes a full url`() {
        assertEquals(
            "smb://192.168.1.10:445/Backup/照片/a.jpg",
            remoteFullPath("Backup/照片/a.jpg", conn("SMB", "Backup", 445))
        )
    }

    @Test
    fun `ftp file path gains scheme and host`() {
        assertEquals(
            "ftp://192.168.1.10:21/pub/a.jpg",
            remoteFullPath("/pub/a.jpg", conn("FTP", "/pub", 21))
        )
    }

    @Test
    fun `webdav path is already a full url and stays untouched`() {
        val url = "https://192.168.1.10:443/dav/a.jpg"
        assertEquals(url, remoteFullPath(url, conn("WEBDAV", "/dav", 443, secure = true)))
    }

    @Test
    fun `path that already carries a scheme is not prefixed twice`() {
        assertEquals(
            "smb://other/share/a.jpg",
            remoteFullPath("smb://other/share/a.jpg", conn("SMB", "Backup", 445))
        )
    }

    @Test
    fun `no connection means path is shown as is`() {
        assertEquals("/pub/a.jpg", remoteFullPath("/pub/a.jpg", null))
    }
}
