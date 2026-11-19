package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址栏的容错：用户粘整条 URL 进「服务器地址」是最常见的填法，
 * 必须拆成 scheme / 主机 / 端口 / 路径，否则 base() 会拼出非法 URL。
 */
class SyncSettingsConnectionTest {

    @Test
    fun `plain host keeps port and default path`() {
        val c = SyncSettings(host = "nas.local", port = 5006).connection()
        assertEquals("nas.local", c.host)
        assertEquals(5006, c.port)
        assertEquals("/MyFileBox", c.path)
        assertTrue(c.secure)
    }

    @Test
    fun `full https url is split into host port and path`() {
        val c = SyncSettings(host = "https://nas.local:5007/dav").connection()
        assertEquals("nas.local", c.host)
        assertEquals(5007, c.port)
        assertEquals("/dav/MyFileBox", c.path)
        assertTrue(c.secure)
    }

    @Test
    fun `http url also turns off tls`() {
        val c = SyncSettings(host = "http://192.168.1.9/dav").connection()
        assertEquals("192.168.1.9", c.host)
        assertEquals("/dav/MyFileBox", c.path)
        assertFalse(c.secure)
    }

    @Test
    fun `explicit path wins over the one inside the url`() {
        val c = SyncSettings(host = "https://nas.local/dav", path = "/backup").connection()
        assertEquals("/backup", c.path)
    }

    @Test
    fun `trailing slash on host does not leak into base`() {
        val c = SyncSettings(host = "nas.local/").connection()
        assertEquals("nas.local", c.host)
    }

    @Test
    fun `auto sync follows the interval only`() {
        // 没有单独的总开关：配好连接 + 周期非关闭 = 在跑；周期为 0 = 关闭。
        assertTrue(SyncSettings(host = "nas.local", autoHours = 24).autoSyncOn)
        assertFalse(SyncSettings(host = "nas.local", autoHours = 0).autoSyncOn)
        assertFalse(SyncSettings(host = "", autoHours = 24).autoSyncOn)
    }
}
