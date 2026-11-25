package com.yaoahos.myfilebox.android.data

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络位置的失败提示要「说清哪一项不对」。
 *
 * 之前的形态是各协议 test 把异常吞成 false，界面只能说「请检查地址 / 端口 / 路径」，
 * 用户拿到这句话没有任何可执行的下一步。这里锁住每类原因的文案落点。
 */
class NetErrorsTest {

    @Test
    fun `unknown host points at the address`() {
        val msg = friendlyNetError(UnknownHostException("nas.local"))
        assertTrue("应指出地址解析失败，实际：$msg", msg.contains("地址无法解析"))
    }

    @Test
    fun `timeout says check the network only`() {
        // 超时的归因已经明确，提示就一句话；罗列「地址、端口、是否开机」只会把提示条撑满。
        val msg = friendlyNetError(SocketTimeoutException("connect timed out"))
        assertEquals("连接超时", msg)
    }

    @Test
    fun `sftp socket not established maps to the same short timeout`() {
        val msg = friendlyNetError(RuntimeException("java.net.SocketException: socket is not established"))
        assertEquals("连接超时", msg)
    }

    @Test
    fun `refused connection points at the port`() {
        val msg = friendlyNetError(ConnectException("Connection refused"))
        assertTrue("应指出端口未开放，实际：$msg", msg.contains("端口未开放"))
    }

    @Test
    fun `smb logon failure maps to credentials`() {
        // smbj 抛的是运行时异常，原因只在消息里。
        val msg = friendlyNetError(RuntimeException("STATUS_LOGON_FAILURE: The attempted logon is invalid"))
        assertTrue("应指出账号密码错误，实际：$msg", msg.contains("用户名或密码错误"))
    }

    @Test
    fun `smb bad share maps to share name`() {
        val msg = friendlyNetError(RuntimeException("STATUS_BAD_NETWORK_NAME"))
        assertTrue("应指出共享名不存在，实际：$msg", msg.contains("共享名不存在"))
    }

    @Test
    fun `sftp auth fail maps to credentials`() {
        val msg = friendlyNetError(RuntimeException("Auth fail"))
        assertTrue("应指出认证失败，实际：$msg", msg.contains("用户名或密码错误"))
    }

    @Test
    fun `our own chinese message is passed through`() {
        val msg = friendlyNetError(IllegalStateException("FTP 登录失败：用户名或密码错误"))
        assertEquals("FTP 登录失败：用户名或密码错误", msg)
    }

    @Test
    fun `unknown error uses fallback`() {
        assertEquals("操作失败，请稍后重试", friendlyNetError(null))
        assertEquals("操作失败，请稍后重试", friendlyNetError(IllegalArgumentException("boom")))
    }

    // ---- 设备侧状态：没网 / 走错网络 ----

    @Test
    fun `offline device beats the dns wording`() {
        // 没网时 UnknownHost 与「地址填错」长得一模一样，照异常翻译会把用户引去改地址。
        val msg = friendlyNetError(UnknownHostException("nas.local"), device = DeviceNet.OFFLINE)
        assertTrue("应指出手机没联网，实际：$msg", msg.contains("未联网"))
    }

    @Test
    fun `offline device tells the user what to do`() {
        val msg = friendlyNetError(SocketTimeoutException("connect timed out"), device = DeviceNet.OFFLINE)
        assertTrue("应给出打开 WiFi / 移动数据的动作，实际：$msg", msg.contains("请打开 WiFi 或移动数据"))
    }

    @Test
    fun `our own precise diagnosis survives an offline device`() {
        // 「缺少共享名」是我们主动判定的配置问题，离线时也成立，不该被「没联网」盖掉。
        val msg = friendlyNetError(IllegalStateException("SMB 连接缺少共享名"), device = DeviceNet.OFFLINE)
        assertEquals("SMB 连接缺少共享名", msg)
    }

    @Test
    fun `cellular plus a private host asks for the same wifi`() {
        val msg = friendlyNetError(
            SocketTimeoutException("timeout"),
            device = DeviceNet.CELLULAR,
            host = "192.168.1.10"
        )
        assertTrue("应提示连回 WiFi，实际：$msg", msg.contains("内网地址需 WiFi"))
    }

    @Test
    fun `cellular plus a public host keeps the plain timeout wording`() {
        val msg = friendlyNetError(
            SocketTimeoutException("timeout"),
            device = DeviceNet.CELLULAR,
            host = "dav.example.com"
        )
        assertEquals("连接超时", msg)
    }

    @Test
    fun `wifi without internet points at the wifi for public hosts`() {
        val msg = friendlyNetError(
            UnknownHostException("dav.example.com"),
            device = DeviceNet.LAN_NO_INTERNET,
            host = "dav.example.com"
        )
        assertTrue("应指出这个 WiFi 出不去，实际：$msg", msg.contains("WiFi 无法上网"))
    }

    @Test
    fun `wifi without internet stays quiet when the host is unknown`() {
        // 目标可能就是内网机器，而「上不了互联网的 Wi-Fi」访问内网是通的：
        // 地址不明时不能替用户下「是网络出不去」的结论。
        val msg = friendlyNetError(SocketTimeoutException("timeout"), device = DeviceNet.LAN_NO_INTERNET)
        assertEquals("连接超时", msg)
    }

    @Test
    fun `wifi without internet keeps lan hosts on the original reason`() {
        // 内网地址在「上不了网」的 Wi-Fi 上其实照样能连，不能反过来劝用户换网络。
        val msg = friendlyNetError(
            ConnectException("Connection refused"),
            device = DeviceNet.LAN_NO_INTERNET,
            host = "192.168.1.10"
        )
        assertTrue("应保留端口未开放的判断，实际：$msg", msg.contains("端口未开放"))
    }

    @Test
    fun `bare ip and url forms are both recognised as private`() {
        assertTrue(hostIsPrivate("192.168.1.10"))
        assertTrue(hostIsPrivate("http://192.168.1.10:5255/dav"))
        assertTrue(hostIsPrivate("10.0.0.5"))
        assertTrue(!hostIsPrivate("dav.example.com"))
        assertTrue(!hostIsPrivate(""))
        assertTrue(!hostIsPrivate(null))
    }

    // ---- HTTP 状态码 ----

    @Test
    fun `http status codes are spelled out`() {
        assertEquals(
            "WebDAV 上传失败：认证失败，请检查账号密码",
            httpStatusHint("WebDAV 上传失败", 401)
        )
        assertTrue(httpStatusHint("WebDAV 上传失败", 507).contains("空间不足"))
        assertTrue(httpStatusHint("WebDAV 上传失败", 503).contains("服务端错误"))
        assertEquals("WebDAV 上传失败：HTTP 418", httpStatusHint("WebDAV 上传失败", 418))
    }

    // ---- 设备状态说明：只用于展示，不参与是否发请求 ----

    @Test
    fun `device notice reports airplane mode even while wifi stays on`() {
        // 用户实测漏掉的正是这一格：飞行模式开着、WiFi 单独打开时 activeNetwork 还在，
        // 旧的 offlineNotice 返回 null，界面一句话都没有。
        // 文案要说明「网络其实还能用」，否则用户会被指去关飞行模式。
        assertEquals(
            "飞行模式已开启，当前仅 Wi-Fi 可用",
            networkStatusNotice(DeviceNet.LAN, airplane = true)
        )
    }

    @Test
    fun `airplane mode with wifi that cannot reach the internet`() {
        assertEquals(
            "飞行模式已开启，当前 Wi-Fi 无法上网，内网仍可访问",
            networkStatusNotice(DeviceNet.LAN_NO_INTERNET, airplane = true)
        )
    }

    @Test
    fun `airplane mode with only mobile data on`() {
        assertEquals(
            "飞行模式已开启，当前仅移动数据可用",
            networkStatusNotice(DeviceNet.CELLULAR, airplane = true)
        )
    }

    @Test
    fun `device notice reports airplane mode with no active network`() {
        // 两路网络都被飞行模式关掉：这时才该让用户去关飞行模式。
        assertEquals(
            "飞行模式已开启，网络已全部关闭",
            networkStatusNotice(DeviceNet.OFFLINE, airplane = true)
        )
    }

    @Test
    fun `airplane mode with wifi switched on but not connected yet`() {
        // 打开 Wi-Fi 但还没连上 AP：要做的是连一个网络，不是关飞行模式。
        assertEquals(
            "飞行模式已开启，Wi-Fi 已打开但未连接网络",
            networkStatusNotice(DeviceNet.OFFLINE, airplane = true, wifiOn = true)
        )
    }

    @Test
    fun `airplane mode with mobile data switched on but not connected yet`() {
        assertEquals(
            "飞行模式已开启，移动数据已打开但未连接网络",
            networkStatusNotice(DeviceNet.OFFLINE, airplane = true, dataOn = true)
        )
    }

    @Test
    fun `wifi switched on wins over mobile data when both are on`() {
        // 两个开关都开着但都没连上：优先说 Wi-Fi，这台设备上它更可能是用户刚动的那个。
        assertEquals(
            "飞行模式已开启，Wi-Fi 已打开但未连接网络",
            networkStatusNotice(DeviceNet.OFFLINE, airplane = true, wifiOn = true, dataOn = true)
        )
    }

    @Test
    fun `device notice reports a completely offline device`() {
        assertEquals(
            "手机未联网，请打开 WiFi 或移动数据",
            networkStatusNotice(DeviceNet.OFFLINE, airplane = false)
        )
    }

    @Test
    fun `device notice explains wifi that cannot reach the internet`() {
        assertEquals(
            "当前 WiFi 无法上网，内网仍可访问",
            networkStatusNotice(DeviceNet.LAN_NO_INTERNET, airplane = false)
        )
    }

    @Test
    fun `device notice stays quiet on a healthy network`() {
        assertTrue(networkStatusNotice(DeviceNet.LAN, airplane = false) == null)
        assertTrue(networkStatusNotice(DeviceNet.CELLULAR, airplane = false) == null)
    }

    @Test
    fun `fast failure still only triggers with no active network`() {
        // 飞行模式下手动开 WiFi 连内网 NAS 是合法场景：说明行要有，但请求不能被拦。
        assertTrue(offlineNetworkMessage(DeviceNet.LAN) == null)
        assertTrue(offlineNetworkMessage(DeviceNet.LAN_NO_INTERNET) == null)
        assertTrue(offlineNetworkMessage(DeviceNet.CELLULAR) == null)
    }

    @Test
    fun `fast failure message distinguishes airplane mode from switched off networks`() {
        // 完全没有可用网络时，这句同样要说清是「全关」还是「打开了某一路但没连上」，
        // 否则请求被提前拦下后，用户还是不知道该动哪儿。
        assertEquals(
            "飞行模式已开启，网络已全部关闭",
            offlineNetworkMessage(DeviceNet.OFFLINE, airplane = true)
        )
        assertEquals(
            "飞行模式已开启，Wi-Fi 已打开但未连接网络",
            offlineNetworkMessage(DeviceNet.OFFLINE, airplane = true, wifiOn = true)
        )
        assertEquals(
            "手机未联网，请打开 WiFi 或移动数据",
            offlineNetworkMessage(DeviceNet.OFFLINE, airplane = false)
        )
    }
}
