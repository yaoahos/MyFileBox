package com.yaoahos.myfilebox.android.data

/**
 * 各协议「连不上多久给结论」的统一预算。
 *
 * 全部协议都是阻塞式 IO（OkHttp execute / JSch / commons-net / smbj），
 * 协程取消打不断已经阻塞在 connect、read 上的调用（见 ProgressStream 的说明），
 * 所以等待时长只能在 socket 层定，改动都从这里走。
 */
internal object NetTimeouts {
    /** 群晖建立连接：QuickConnect 中继冷启动 2–5 秒，留 8 秒。 */
    const val CONNECT_SECONDS = 8L
    /** 普通请求的读超时：大目录在中继下可能偏慢，保留 30 秒。 */
    const val READ_SECONDS = 30L
    /** 切网窗口内的快速失败：宁可 4 秒报错让用户点刷新，也不干等原超时。 */
    const val QUICK_CONNECT_SECONDS = 4L
    const val QUICK_READ_SECONDS = 12L
    /** FTP / SFTP / SMB 的控制通道读超时（毫秒，供只接受 int 的库使用）。 */
    const val CONTROL_READ_MILLIS = 30_000

    /**
     * 网络文件（SMB / WebDAV / FTP / SFTP）建立连接：4 秒。
     *
     * 这几种都是用户自己填的直连地址，没有群晖那种中继冷启动：局域网与公网直连
     * 通常在 1 秒内完成 TCP（含 TLS）握手。原先和群晖一样给 8 秒，不可达时用户
     * 点一下连接就要干等 8 秒才看到「连接超时」，体感就是「没反应」。
     * 4 秒仍能覆盖移动网络下偏慢的 DNS 解析，同时把等待砍掉一半。
     */
    const val NET_CONNECT_SECONDS = 4L
    /** 同上，毫秒形式（FTP / SFTP / SMB 这类只收 int 的 API）。 */
    const val NET_CONNECT_MILLIS = 4_000
}
