package com.yaoahos.myfilebox.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/**
 * 手机当前的联网形态。
 *
 * 只用来**解释**「为什么连不上」，不参与任何连接决策（该不该发请求、走直连还是中继，
 * 仍然由 [ChannelPolicy] 与各仓自己判断）。分开的理由：过去的失败提示只能看异常，
 * 而异常在「手机没网」和「地址填错」两种情况下长得一模一样——都是
 * `UnknownHostException` / `ConnectException` / `SocketTimeoutException`。
 * 照着异常翻译，用户会被告知「请检查地址是否拼写正确」，而真正要做的只是打开 Wi-Fi。
 */
enum class DeviceNet {
    /** 拿不到系统状态（单元测试、启动早期）。此时不做任何覆盖，沿用原有异常翻译。 */
    UNKNOWN,

    /** 没有任何可用网络：飞行模式、Wi-Fi 与移动数据都没开。 */
    OFFLINE,

    /** 接在 Wi-Fi / 以太网上，且这个网络能上互联网。 */
    LAN,

    /**
     * 接在 Wi-Fi / 以太网上，但这个网络到不了互联网。
     *
     * 典型场景：公司内网 Wi-Fi、随身路由未插卡、需要网页登录的公共 Wi-Fi。
     * 注意它**不等于**没网——此时访问 192.168.x 的 NAS 完全正常，
     * 所以不能一句话判定「没有联网」，只能对公网地址给出针对性提示。
     */
    LAN_NO_INTERNET,

    /** 只有移动数据。 */
    CELLULAR,

    /** 其它传输（VPN 独占、蓝牙共享等）。 */
    OTHER;

    val hasNetwork: Boolean get() = this != OFFLINE && this != UNKNOWN
    val onLan: Boolean get() = this == LAN || this == LAN_NO_INTERNET
}

/**
 * 全局的「手机现在怎么联网」查询入口。
 *
 * 用全局持有 Application Context 而不是层层传参：失败提示是在 20 多处分散抛出的
 * （各协议仓库、传输任务、复制移动），要求每个调用点自己把联网状态带下来，
 * 一定会漏。这里集中读，[friendlyNetError] 直接取用。
 */
object NetStatus {

    @Volatile
    private var appContext: Context? = null

    /** 在 Application.onCreate 里调用一次；重复调用无害。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 读一次当前联网形态。每次都是真的去问系统（一次 binder 调用，量级 0.1ms），
     * 不做缓存：用户刚打开 Wi-Fi 就点重试的场景，读旧值会给出相反的结论。
     * 界面侧如果要在重组里用，自己在 `remember` 里收一下。
     */
    fun current(): DeviceNet {
        val context = appContext ?: return DeviceNet.UNKNOWN
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return DeviceNet.UNKNOWN
            val network = cm.activeNetwork ?: return DeviceNet.OFFLINE
            val caps = cm.getNetworkCapabilities(network) ?: return DeviceNet.OFFLINE
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            // VALIDATED 才是「真的通到互联网」：DHCP 拿到地址但网关不通、需要认证的
            // 公共 Wi-Fi 都会停在 false。INTERNET 只说明这张网声称能上网，不够。
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            when {
                lan -> if (online) DeviceNet.LAN else DeviceNet.LAN_NO_INTERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DeviceNet.CELLULAR
                online -> DeviceNet.OTHER
                // 有 activeNetwork 却既非局域网也不通互联网：VPN 断开中 / 网络刚起来还没配好。
                // 对用户而言就是一个字——没网。
                else -> DeviceNet.OFFLINE
            }
        }.getOrDefault(DeviceNet.UNKNOWN)
    }

    /** 是否开着飞行模式。只用于把「没网」说得更具体。 */
    fun airplaneModeOn(): Boolean {
        val context = appContext ?: return false
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        }.getOrDefault(false)
    }
}

/**
 * 主机（裸 IP / 域名，可能带 scheme 与端口）是否内网地址。
 *
 * 与 [hostLooksPrivate] 的区别：那边的入参是完整 endpoint，这里收的是用户填在
 * 「服务器地址」里的原文（`192.168.1.10`、`http://nas.local:5000`），
 * 没有 scheme 的裸 IP 交给 URI 解析会得不到 host，所以统一补一个 scheme 再判。
 */
internal fun hostIsPrivate(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    val raw = host.trim()
    val schemeEnd = raw.indexOf("//").let { if (it >= 0) it + 2 else 0 }
    val bare = raw.substring(schemeEnd).substringBefore('/')
    if (bare.isBlank()) return false
    return hostLooksPrivate("http://$bare")
}
