package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切网后「要不要先重建通道」的判定。判错的代价很直接：
 * 该修没修 → 用户白等连接超时；不该修却修 → 平白重解析通道、多等十几秒。
 */
class ChannelPolicyTest {

    @Test
    fun `lan ipv4 ranges are private`() {
        assertTrue(hostLooksPrivate("https://10.0.0.5:5001"))
        assertTrue(hostLooksPrivate("https://192.168.1.10:5001"))
        assertTrue(hostLooksPrivate("https://172.16.0.1"))
        assertTrue(hostLooksPrivate("https://172.31.255.254"))
    }

    @Test
    fun `public and out of range addresses are not private`() {
        assertFalse(hostLooksPrivate("https://172.32.0.1"))
        assertFalse(hostLooksPrivate("https://172.15.0.1"))
        assertFalse(hostLooksPrivate("https://8.8.8.8"))
        assertFalse(hostLooksPrivate("https://example.cn6.quickconnect.cn"))
    }

    @Test
    fun `localhost and malformed input`() {
        assertTrue(hostLooksPrivate("http://localhost:5000"))
        assertTrue(hostLooksPrivate("http://127.0.0.1:5000"))
        assertFalse(hostLooksPrivate(""))
        assertFalse(hostLooksPrivate("not a url"))
    }

    @Test
    fun `private endpoint while off lan needs repair`() {
        assertTrue(needsChannelRepair("https://192.168.1.10:5001", onLanTransport = false))
        // 还在同一局域网里，内网地址正常可用，不该多此一举。
        assertFalse(needsChannelRepair("https://192.168.1.10:5001", onLanTransport = true))
    }

    @Test
    fun `relay endpoint never forces a repair`() {
        // 中继地址跨网络可达：直接请求即可，先重解析等于每次切网白等十几秒。
        val relay = "https://example.cn6.quickconnect.cn"
        assertFalse(needsChannelRepair(relay, onLanTransport = false))
        assertFalse(needsChannelRepair(relay, onLanTransport = true))
    }

    @Test
    fun `stable public endpoint never forces a repair`() {
        val ddns = "https://nas.example.com:5001"
        assertFalse(needsChannelRepair(ddns, onLanTransport = true))
        assertFalse(needsChannelRepair(ddns, onLanTransport = false))
    }

    @Test
    fun `blank endpoint is skipped`() {
        assertFalse(needsChannelRepair("", onLanTransport = false))
    }
}
