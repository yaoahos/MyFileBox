package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动备份失败分类。
 *
 * 核心口径：只有「重试也没用」的确定性错误才放弃，其余一律重试。早期实现反过来
 * 用中文子串猜「像不像网络问题」，猜漏一次就得等下一个周期（可能 24 小时）才补上。
 */
class ConfigSyncFailureTest {

    @Test
    fun `网络类失败一律可重试`() {
        assertFalse(isPermanentSyncFailure("连接超时"))
        assertFalse(isPermanentSyncFailure("UnknownHostException: 无法解析主机"))
        assertFalse(isPermanentSyncFailure("Connection refused"))
        assertFalse(isPermanentSyncFailure("HTTP 500 Internal Server Error"))
        assertFalse(isPermanentSyncFailure("HTTP 503"))
        assertFalse(isPermanentSyncFailure("javax.net.ssl.SSLHandshakeException: timeout"))
        assertFalse(isPermanentSyncFailure("上传失败"))
    }

    @Test
    fun `凭据配置类失败不再重试`() {
        assertTrue(isPermanentSyncFailure("配置密码至少 8 位"))
        assertTrue(isPermanentSyncFailure("请先填写 WebDAV 地址"))
        assertTrue(isPermanentSyncFailure("配置加密失败"))
        assertTrue(isPermanentSyncFailure("HTTP 401 Unauthorized"))
        assertTrue(isPermanentSyncFailure("HTTP 403 Forbidden"))
        assertTrue(isPermanentSyncFailure("HTTP 404 Not Found"))
        assertTrue(isPermanentSyncFailure("服务器拒绝：认证失败"))
    }
}
