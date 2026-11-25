package com.yaoahos.myfilebox.android.data

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * 把底层网络异常翻译成普通用户能看懂的一句话。
 *
 * 直接把 OkHttp/JSch/jcifs 的英文异常抛到界面上（例如
 * “Failed to connect to /127.0.0.1:9999”）对用户毫无意义，
 * 所以所有面向用户的失败提示都统一走这里。
 *
 * 原则是「说清是哪一项不对」：地址解析失败 / 端口没开 / 账号密码错 /
 * 共享名不存在 / 没有权限 / 超时。只回一句「请检查地址、端口、账号、路径」
 * 等于把排查工作原样丢回给用户，等于没有提示。
 *
 * 但归因已经明确的那一类要一句话收住：超时就是网络不通，写「请检查网络」即可，
 * 再罗列地址、端口、开机状态只会把提示撑长，用户照样无从下手。
 *
 * ---- 设备侧状态优先于异常类型 ----
 *
 * 手机根本没联网时，底层异常与「地址填错」是完全一样的（UnknownHost / Connect / Timeout），
 * 照着异常翻译就会把用户引向错误方向：让他去改一个其实没错的地址。
 * 所以先看 [DeviceNet]（见 [NetStatus]），没网就直接说没网。
 *
 * 顺序上**先透出我们自己造的中文诊断**：那些是我们主动判定的结论
 * （缺共享名、HTTP 401、地址没填全），离线时也依然成立，且比「没联网」更具体。
 */
fun friendlyNetError(
    error: Throwable?,
    fallback: String = "操作失败，请稍后重试",
    device: DeviceNet = NetStatus.current(),
    host: String? = null
): String {
    if (error == null) return fallback
    val chain = generateSequence(error) { it.cause }.take(6).toList()
    fun has(predicate: (Throwable) -> Boolean) = chain.any(predicate)
    // smbj / JSch 这类库把原因（STATUS_LOGON_FAILURE / Auth fail / timeout）写在英文消息里，
    // 异常类型往往只是 RuntimeException；不看文本就只能落到「操作失败」。
    val text = (
        chain.joinToString(" ") { it.message.orEmpty() } + " " +
            chain.joinToString(" ") { it.javaClass.simpleName }
        ).lowercase(Locale.ROOT)
    // 我们自己抛出的 IllegalStateException 已经是给用户看的中文，直接透出
    val owned = chain.firstNotNullOfOrNull { (it as? IllegalStateException)?.message?.takeIf(String::isNotBlank) }

    return when {
        // ---- 我们自己的结论：最具体，优先级最高（离线时也成立）----
        // 例外：从别的网络错误二次包装出来的中文（「无法连接该服务器」这类兜底原文）
        // 不该挡住设备侧判断，交给下面按联网状态重判。
        owned != null && !owned.isWrapperText() -> owned

        // ---- 设备侧：没网 / 走错网络 ----
        // UNKNOWN 不参与判断：拿不到系统状态时不能替用户下「你没网」的结论。
        device == DeviceNet.OFFLINE -> offlineMessage(
            NetStatus.airplaneModeOn(),
            NetStatus.wifiEnabled(),
            NetStatus.mobileDataEnabled()
        )

        // 内网地址 + 只有移动数据：地址没错、服务器也没错，缺的是同一个 Wi-Fi。
        device == DeviceNet.CELLULAR && hostIsPrivate(host) ->
            "内网地址需 WiFi，当前为移动数据"

        // 公网地址 + 这个 Wi-Fi 到不了互联网：不是服务器的问题，是这个网络出不去。
        // 地址未知（host 为 null）时不下这个结论：目标可能就是台内网机器，
        // 而「上不了互联网的 Wi-Fi」访问内网恰好是通的，说错了会把用户引去换网络。
        device == DeviceNet.LAN_NO_INTERNET && host != null && !hostIsPrivate(host) ->
            "当前 WiFi 无法上网，请换一个网络"

        has { it is UnknownHostException } ->
            "地址无法解析，请检查拼写"

        has { it is SSLHandshakeException } ->
            "证书校验失败，可改用不加密连接"

        has { it is SSLException } ->
            "安全连接失败，请检查端口与加密开关"

        has { it is SocketTimeoutException } ->
            // 用户只需要一句可执行的结论：超时就是网络这边的问题。
            // 原先还跟着「请确认地址、端口，以及服务器是否已开机」，一句提示三种排查方向，
            // 既占满提示条又没法照做。
            "连接超时"

        has { it is NoRouteToHostException } ->
            "无法到达服务器，请检查是否同一网络"

        has { it is PortUnreachableException } ->
            "端口不可达，请检查服务是否已启动"

        has { it is ConnectException } && text.contains("refused") ->
            "端口未开放，请检查端口号"

        has { it is ConnectException } ->
            "无法连接服务器，请检查地址与端口"

        // ---- 第三方库写在消息里的原因 ----
        text.contains("status_logon_failure") ->
            "用户名或密码错误"

        text.contains("status_bad_network_name") ->
            "共享名不存在，请检查共享文件夹名"

        text.contains("status_access_denied") ->
            "没有访问权限"

        // SMB 的账号状态：与「密码错」不是一回事，改密码解决不了被锁的账号。
        text.contains("status_account_locked_out") ->
            "账户已被锁定，请先在服务器解锁"

        text.contains("status_account_disabled") ->
            "账户已被禁用，请先在服务器启用"

        text.contains("status_password_expired") ->
            "密码已过期，请先修改密码"

        text.contains("no such file") ->
            "路径不存在，请检查文件名"

        text.contains("permission denied") ->
            "没有访问权限"

        text.contains("no route to host") || text.contains("host is down") ->
            "无法到达服务器，请检查是否同一网络"

        text.contains("auth fail") || text.contains("authentication failed") || text.contains("530 login") ->
            "用户名或密码错误"

        text.contains("socket is not established") ->
            "连接超时"

        text.contains("connection refused") ->
            "端口未开放，请检查端口号"

        has { it is SocketException } ->
            "网络连接被中断，请重试"

        owned != null -> owned

        has { it is IOException } ->
            // 兜底：至少不要把整段英文原样抛给用户
            "网络读写失败，请稍后重试"

        else -> fallback
    }
}

/**
 * 设备完全没有网络时的那句话；有网时返回 null（调用方据此决定是否覆盖原有提示）。
 *
 * 界面也会用它：点下连接的那一刻就能确定结论，没必要让用户先对着转圈等 socket 超时。
 *
 * [wifiOn] / [dataOn] 只在「飞行模式 + 完全没有可用网络」时用来把话说细：
 * 飞行模式确实会把两路网络都关掉，但用户可以再单独打开 Wi-Fi（或移动数据）——
 * 打开了却没连上 AP，和两路都关着，要用户做的事完全不一样。
 */
fun offlineNetworkMessage(
    device: DeviceNet = NetStatus.current(),
    airplane: Boolean = NetStatus.airplaneModeOn(),
    wifiOn: Boolean = NetStatus.wifiEnabled(),
    dataOn: Boolean = NetStatus.mobileDataEnabled()
): String? =
    if (device == DeviceNet.OFFLINE) offlineMessage(airplane, wifiOn, dataOn) else null

private fun offlineMessage(airplane: Boolean, wifiOn: Boolean, dataOn: Boolean): String = if (airplane) {
    airplaneMessage(DeviceNet.OFFLINE, wifiOn, dataOn)
} else {
    "手机未联网，请打开 WiFi 或移动数据"
}

/**
 * 飞行模式开着时的说明。
 *
 * 必须区分三种情况，否则用户会被指到错的方向：
 * ① 两路网络都被飞行模式关掉 —— 要做的是关飞行模式；
 * ② 用户单独打开了 Wi-Fi（或移动数据）并已连上 —— 网络其实能用，关飞行模式不是必须的；
 * ③ 单独打开了开关但还没连上（无 AP / 无信号）—— 要做的是连一个网络。
 *
 * [DeviceNet] 已经能区分「连着 Wi-Fi」「连着移动数据」，剩下的 ③ 靠开关状态判断。
 */
private fun airplaneMessage(device: DeviceNet, wifiOn: Boolean, dataOn: Boolean): String = when (device) {
    DeviceNet.LAN -> "飞行模式已开启，当前仅 Wi-Fi 可用"
    DeviceNet.LAN_NO_INTERNET -> "飞行模式已开启，当前 Wi-Fi 无法上网，内网仍可访问"
    DeviceNet.CELLULAR -> "飞行模式已开启，当前仅移动数据可用"
    DeviceNet.OTHER -> "飞行模式已开启，当前仍有网络连接"
    else -> when {
        wifiOn -> "飞行模式已开启，Wi-Fi 已打开但未连接网络"
        dataOn -> "飞行模式已开启，移动数据已打开但未连接网络"
        else -> "飞行模式已开启，网络已全部关闭"
    }
}

/**
 * 页面顶部的设备联网状态说明。
 *
 * 与 [offlineNetworkMessage] 分工不同，别混用：
 * - [offlineNetworkMessage] 只在「完全没有 activeNetwork」时返回，用于发请求前快速失败；
 * - 这里还要覆盖两种「有网络对象但其实上不了公网」的情况：飞行模式下把 WiFi
 *   单独打开（activeNetwork 仍在，系统判 LAN / LAN_NO_INTERNET）、以及 WiFi 连着
 *   却通不了外网。此时内网 NAS 照样可达，绝不能拦请求，但界面必须说清状态——
 *   否则用户看到的是过期的「● 在线」卡片和一句「连接超时」。
 *
 * [airplane] 与两个开关单独入参是为了让这条判断在单元测试里可确定地覆盖（不依赖 Application）。
 */
fun networkStatusNotice(
    device: DeviceNet = NetStatus.current(),
    airplane: Boolean = NetStatus.airplaneModeOn(),
    wifiOn: Boolean = NetStatus.wifiEnabled(),
    dataOn: Boolean = NetStatus.mobileDataEnabled()
): String? = when {
    airplane -> airplaneMessage(device, wifiOn, dataOn)
    device == DeviceNet.OFFLINE -> offlineMessage(airplane = false, wifiOn = wifiOn, dataOn = dataOn)
    device == DeviceNet.LAN_NO_INTERNET -> "当前 WiFi 无法上网，内网仍可访问"
    else -> null
}

/**
 * 是否是「兜底原文」——各协议仓库在拿不到具体原因时包出来的一句话。
 * 这类文字在任何失败下都成立，因此不该挡住设备侧更准确的判断。
 */
private fun String.isWrapperText(): Boolean {
    val t = trim()
    return t == "无法连接该服务器" || t == "网络异常" || t == "操作失败，请稍后重试" ||
        t == "连接失败" || t == "读取失败"
}

/** 判断异常是否属于「证书 / TLS」类问题，用于决定是否追加自签名提示。 */
fun isTlsError(error: Throwable?): Boolean {
    if (error == null) return false
    return generateSequence(error) { it.cause }.take(6).any { it is SSLException }
}
