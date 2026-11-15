package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.NetworkConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class NetworkRepository(
    private val webdav: WebDavRepository = WebDavRepository(),
    private val ftp: FtpRepository = FtpRepository(),
    private val smb: SmbRepository = SmbRepository(),
    private val sftp: SftpRepository = SftpRepository()
) {
    /** 目标目录树的根节点。WebDAV 必须是绝对 URL，其它协议是远端路径。 */
    fun rootPath(connection: NetworkConnection): String = when (connection.protocol.uppercase()) {
        "WEBDAV" -> webdav.rootPath(connection)
        else -> connection.path.ifBlank { "/" }
    }

    /** 最近一次 WebDAV 请求的具体错误，用于给「上传失败」提供可读原因。 */
    val lastError: String? get() = webdav.lastError

    fun clearError() { webdav.clearError() }

    suspend fun test(connection: NetworkConnection): Boolean = withContext(Dispatchers.IO) {
        when (connection.protocol.uppercase()) {
            "WEBDAV" -> webdav.test(connection)
            "FTP" -> ftp.test(connection)
            "SFTP" -> sftp.test(connection)
            "SMB" -> smb.test(connection)
            else -> throw IllegalStateException("不支持的协议：${connection.protocol}")
        }
    }

    suspend fun list(connection: NetworkConnection, path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        when (connection.protocol.uppercase()) {
            // WebDavRepository 内部会把 "/" 等相对路径补成绝对 URL。
            "WEBDAV" -> webdav.list(connection, path)
            "FTP" -> ftp.list(connection, path)
            "SFTP" -> sftp.list(connection, path)
            "SMB" -> smb.list(connection, path)
            else -> emptyList()
        }
    }

    suspend fun download(
        connection: NetworkConnection,
        remote: String,
        target: File,
        abort: (() -> Boolean)? = null,
        expectedSize: Long = -1L
    ): Boolean = withContext(Dispatchers.IO) {
        when (connection.protocol.uppercase()) {
            "WEBDAV" -> webdav.download(connection, remote, target, abort)
            "FTP" -> ftp.download(connection, remote, target, abort)
            "SFTP" -> sftp.download(connection, remote, target, abort)
            "SMB" -> smb.download(connection, remote, target, abort, expectedSize)
            else -> false
        }
    }

    /**
     * 能直接给播放器的流式源。
     * WebDAV 直接给 URL；SMB/FTP/SFTP 给 openStream 回调（StreamProxy 本地转发）。
     * 所有协议统一秒开，与群晖体验一致。
     */
    fun streamSource(connection: NetworkConnection, remote: String): StreamProxy.Source? =
        when (connection.protocol.uppercase()) {
            "WEBDAV" -> webdav.streamSource(connection, remote)
            "SMB" -> StreamProxy.Source(
                contentLength = remoteSize(connection, remote),
                openStream = { offset -> smb.openStream(connection, remote, offset) }
            )
            "FTP" -> StreamProxy.Source(
                contentLength = remoteSize(connection, remote),
                openStream = { offset -> ftp.openStream(connection, remote, offset) }
            )
            "SFTP" -> StreamProxy.Source(
                contentLength = remoteSize(connection, remote),
                openStream = { offset -> sftp.openStream(connection, remote, offset) }
            )
            else -> null
        }

    /** 从列表信息获取远端文件大小（用于 Content-Length）。 */
    private fun remoteSize(connection: NetworkConnection, remote: String): Long = -1L

    suspend fun upload(
        connection: NetworkConnection,
        local: File,
        dir: String,
        name: String = local.name,
        onBytes: ((Long) -> Unit)? = null,
        abort: (() -> Boolean)? = null
    ): Boolean =
        withContext(Dispatchers.IO) {
            when (connection.protocol.uppercase()) {
                "WEBDAV" -> webdav.upload(connection, local, dir, name, onBytes, abort)
                "FTP" -> ftp.upload(connection, local, dir, name, onBytes, abort)
                "SFTP" -> sftp.upload(connection, local, dir, name, onBytes, abort)
                "SMB" -> smb.upload(connection, local, dir, name, onBytes, abort)
                else -> false
            }
        }

    suspend fun createFolder(connection: NetworkConnection, dir: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            when (connection.protocol.uppercase()) {
                "WEBDAV" -> webdav.createFolder(connection, dir, name)
                "FTP" -> ftp.createFolder(connection, dir, name)
                "SFTP" -> sftp.createFolder(connection, dir, name)
                "SMB" -> smb.createFolder(connection, dir, name)
                else -> false
            }
        }

    suspend fun delete(connection: NetworkConnection, paths: List<String>): Boolean = withContext(Dispatchers.IO) {
        when (connection.protocol.uppercase()) {
            "WEBDAV" -> webdav.delete(connection, paths)
            "FTP" -> ftp.delete(connection, paths)
            "SFTP" -> sftp.delete(connection, paths)
            "SMB" -> smb.delete(connection, paths)
            else -> false
        }
    }

    suspend fun rename(connection: NetworkConnection, path: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            when (connection.protocol.uppercase()) {
                "WEBDAV" -> webdav.rename(connection, path, newName)
                "FTP" -> ftp.rename(connection, path, newName)
                "SFTP" -> sftp.rename(connection, path, newName)
                "SMB" -> smb.rename(connection, path, newName)
                else -> false
            }
        }

    suspend fun copyMove(connection: NetworkConnection, paths: List<String>, destDir: String, move: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            when (connection.protocol.uppercase()) {
                "WEBDAV" -> webdav.copyMove(connection, paths, destDir, move)
                "FTP" -> ftp.copyMove(connection, paths, destDir, move)
                "SFTP" -> sftp.copyMove(connection, paths, destDir, move)
                "SMB" -> smb.copyMove(connection, paths, destDir, move)
                else -> false
            }
        }
}
