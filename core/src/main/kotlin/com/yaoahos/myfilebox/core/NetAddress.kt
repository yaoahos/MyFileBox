package com.yaoahos.myfilebox.core

/**
 * 网络连接的地址展示，四种协议统一成 `协议://主机:端口/路径`。
 *
 * SMB 以前用 Windows 的 UNC（`\\host\share`），是四种协议里唯一的例外：
 * 既和旁边三种对不上，也表达不了自定义端口（UNC 没有端口这个概念，用户把端口
 * 改成 1445 之后界面上完全看不出来）。换成 `smb://` 之后一眼就能看出是同一类东西，
 * 复制出去在 VLC / Kodi / macOS 访达里也能直接打开。
 */
fun connectionAddressLabel(c: NetworkConnection): String {
    val p = c.path.trim()
        .let { if (it.startsWith("/")) it else if (it.isBlank()) "" else "/$it" }
        .trimEnd('/')
    return when (c.protocol.uppercase()) {
        "SMB" -> "smb://${c.host}:${c.port}$p"
        "SFTP" -> "sftp://${c.host}:${c.port}$p"
        "FTP" -> "ftp://${c.host}:${c.port}$p"
        else -> {
            val scheme = if (c.secure || c.port == 443) "https" else "http"
            "$scheme://${c.host}:${c.port}$p"
        }
    }
}

/**
 * 网络文件的完整地址，用于详情里的「完整路径」。
 *
 * WebDAV 的 `FileEntry.path` 本身就是完整 URL，原样返回；SMB / FTP / SFTP 的 path
 * 只有相对路径（SMB 连主机名都没有），不补协议和主机的话看不出这是哪台机器上的文件，
 * 复制出去也没法直接用。
 */
fun remoteFullPath(path: String, connection: NetworkConnection?): String {
    if (connection == null || path.isBlank()) return path
    val lower = path.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") ||
        lower.startsWith("smb://") || lower.startsWith("ftp://") || lower.startsWith("sftp://")
    ) {
        return path
    }
    val clean = path.trimStart('/')
    return when (connection.protocol.uppercase()) {
        "SMB" -> "smb://${connection.host}:${connection.port}/$clean"
        "SFTP" -> "sftp://${connection.host}:${connection.port}/$clean"
        "FTP" -> "ftp://${connection.host}:${connection.port}/$clean"
        else -> path
    }
}
