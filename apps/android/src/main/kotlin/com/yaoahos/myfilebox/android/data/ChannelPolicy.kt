package com.yaoahos.myfilebox.android.data

import java.net.URI

/**
 * endpoint 的 host 是否内网地址。只看字面量，不做 DNS（避免在切网瞬间再引入一次阻塞解析）。
 */
internal fun hostLooksPrivate(endpoint: String): Boolean {
    val host = runCatching { URI(endpoint).host }.getOrNull().orEmpty()
    if (host.isBlank()) return false
    if (host == "localhost" || host.startsWith("127.")) return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    val second = parts[1].toIntOrNull() ?: return false
    return first == 10 ||
        (first == 192 && second == 168) ||
        (first == 172 && second in 16..31)
}

/**
 * 切网后是否必须先重建通道、再发业务请求。
 *
 * 判据只有一条：端点是内网地址、而当前不在局域网 —— 这个地址已经不可达，
 * 直接发请求只会把连接超时耗完。
 *
 * 中继端点（quickconnect）故意不在这里拦：它跨网络依然可达，直接请求就行；
 * 拦下来先重新解析，等于每次切网都白等十几秒（解析服务 + 直连/中继探测）。
 */
internal fun needsChannelRepair(
    endpoint: String,
    onLanTransport: Boolean
): Boolean {
    if (endpoint.isBlank()) return false
    return hostLooksPrivate(endpoint) && !onLanTransport
}
