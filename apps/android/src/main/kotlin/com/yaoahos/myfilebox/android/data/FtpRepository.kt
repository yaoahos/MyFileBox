package com.yaoahos.myfilebox.android.data

import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKinds
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.NetworkConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class FtpRepository {
    private fun open(connection: NetworkConnection): FTPClient {
        val client = FTPClient()
        // 不显式设置就是「无上限」：connect 交给 OS 的 SYN 重试（可达两分钟），
        // 控制通道读则永远不超时。地址打不通时用户只能一直等，必须自己压住。
        client.setConnectTimeout(NetTimeouts.NET_CONNECT_MILLIS)
        client.connect(connection.host, connection.port)
        // setSoTimeout 必须在 socket 建立后调用；之前放在 connect 前，JVM 下会
        // 因内部 socket 为空抛 NPE，控制通道读超时实际上从未生效。
        client.setSoTimeout(NetTimeouts.CONTROL_READ_MILLIS)
        client.enterLocalPassiveMode()
        client.setControlEncoding("UTF-8")
        if (!client.login(connection.username, connection.password)) {
            runCatching { client.logout(); client.disconnect() }
            throw IllegalStateException("FTP 登录失败：用户名或密码错误")
        }
        // 必须在登录之后设：RFC 959 的 TYPE 属于登录后命令，pyftpdlib / vsftpd 在未登录
        // 时直接拒绝。以前写在 login 前、返回值也没人看，于是全程停在服务端默认的 ASCII
        // 模式——二进制文件每遇到一个 0x0A 就被扩成 0x0D 0x0A，真机上传的 4 张 jpg 到远端
        // 各自大了 184~367 字节（图片直接打不开）。下载走同一条连接，同样必须二进制。
        if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
            throw IllegalStateException("FTP 无法切换到二进制传输模式")
        }
        return client
    }

    /** 测连：异常透出给上层翻成具体原因（DNS / 端口 / 认证 / 超时）。 */
    fun test(connection: NetworkConnection): Boolean = open(connection).use { client ->
        client.noop()
        true
    }

    suspend fun list(connection: NetworkConnection, path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        open(connection).use { client ->
            val dir = path.ifBlank { connection.path.ifBlank { "/" } }
            // FTPClient.listFiles 在目录不存在 / 无权限时返回 null；不能当成空目录，
            // 否则界面会把「连接失败」显示成「空文件夹」。
            val files = client.listFiles(dir)
                ?: throw java.io.IOException("FTP 无法读取目录 $dir")
            files.mapNotNull { it.toEntry(dir) }
        }
    }

    suspend fun download(
        connection: NetworkConnection,
        remote: String,
        target: File,
        abort: (() -> Boolean)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open(connection).use { client ->
                target.parentFile?.mkdirs()
                // retrieveFile() 会一口气写完，中途停不下来；改用文件流自己拷贝。
                val input = client.retrieveFileStream(remote)
                    ?: throw java.io.IOException("FTP 无法读取 $remote")
                try {
                    input.use { source ->
                        target.outputStream().use { output -> copyWithAbort(source, output, abort) }
                    }
                } finally {
                    runCatching { client.completePendingCommand() }
                }
                target.length() > 0
            }
        }.getOrDefault(false)
    }

    /** 打开 FTP 文件流式读取（供 StreamProxy 转发给播放器）。 */
    fun openStream(connection: NetworkConnection, remote: String, offset: Long = 0L): java.io.InputStream {
        val client = open(connection)
        try {
            if (offset > 0) client.setRestartOffset(offset)
            val stream = client.retrieveFileStream(remote)
                ?: throw java.io.IOException("FTP 无法读取 $remote")
            // 包装流：关闭时断开 FTP 连接。
            return object : java.io.FilterInputStream(stream) {
                override fun close() {
                    runCatching { super.close() }
                    runCatching { client.completePendingCommand() }
                    runCatching { client.logout(); client.disconnect() }
                }
            }
        } catch (t: Throwable) {
            // 拿不到数据流时不会有人调用包装流的 close，连接必须在这里收掉。
            runCatching { client.logout(); client.disconnect() }
            throw t
        }
    }

    suspend fun upload(
        connection: NetworkConnection,
        local: File,
        dir: String,
        name: String,
        onBytes: ((Long) -> Unit)? = null,
        abort: (() -> Boolean)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open(connection).use { client ->
                val dirPath = dir.ifBlank { "/" }
                // 上传本地文件夹时会生成多级子目录；只 makeDirectory(dir) 会在
                // 父目录不存在时失败，导致整个文件夹上传报「上传失败」。
                ensureDirectory(client, dirPath)
                val remote = "${dirPath.trimEnd('/')}/$name"
                local.inputStream().use { input ->
                    client.storeFile(remote, CountingInputStream(input, onBytes, abort))
                }
            }
        }.getOrDefault(false)
    }

    /** 逐级创建远端目录，已存在的层级直接跳过。 */
    /** 在 dir 下新建文件夹；已存在也算成功。 */
    suspend fun createFolder(connection: NetworkConnection, dir: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                open(connection).use { client ->
                    val parent = dir.trimEnd('/').ifBlank { "/" }
                    if (parent != "/") ensureDirectory(client, parent)
                    val target = if (parent == "/") "/$name" else "$parent/$name"
                    client.makeDirectory(target) || client.changeWorkingDirectory(target)
                }
            }.getOrDefault(false)
        }

    /** 重命名文件（夹）：RNFR/RNTO，同目录内改名。 */
    suspend fun rename(connection: NetworkConnection, path: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                open(connection).use { client ->
                    val parent = path.trimEnd('/').substringBeforeLast('/')
                    val target = if (parent.isBlank()) "/$newName" else "$parent/$newName"
                    client.rename(path, target)
                }
            }.getOrDefault(false)
        }

    private fun ensureDirectory(client: FTPClient, path: String) {
        val clean = path.trim().trimEnd('/')
        if (clean.isBlank() || clean == "/" || clean == ".") return
        val absolute = clean.startsWith("/")
        var current = if (absolute) "/" else ""
        clean.trim('/').split('/').filter { it.isNotBlank() && it != "." }.forEach { segment ->
            current = when {
                current == "/" -> "/$segment"
                current.isBlank() -> segment
                else -> "$current/$segment"
            }
            if (!client.changeWorkingDirectory(current)) {
                client.makeDirectory(current)
                client.changeWorkingDirectory(current)
            }
        }
    }

    suspend fun delete(connection: NetworkConnection, paths: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open(connection).use { client ->
                var allDone = true
                paths.forEach { path ->
                    val name = path.trimEnd('/').substringAfterLast('/')
                    val parent = path.trimEnd('/').substringBeforeLast('/')
                    val done = name.isNotBlank() && (
                        client.removeDirectory(path) || client.deleteFile(path) ||
                            client.deleteFile("$parent/$name")
                        )
                    // all{} 会在第一项失败时短路，导致同批剩余文件永远不被删除。
                    if (!done) allDone = false
                }
                allDone
            }
        }.getOrDefault(false)
    }

    suspend fun copyMove(connection: NetworkConnection, paths: List<String>, destDir: String, move: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                open(connection).use { client ->
                    var allDone = true
                    paths.forEach { path ->
                        val name = path.trimEnd('/').substringAfterLast('/')
                        val target = "${destDir.trimEnd('/')}/$name"
                        val done = if (move) {
                            client.rename(path, target)
                        } else {
                            val temp = File.createTempFile("ftp-copy-", ".tmp")
                            val ok = try {
                                temp.outputStream().use { client.retrieveFile(path, it) } &&
                                    temp.inputStream().use { client.storeFile(target, it) }
                            } finally {
                                temp.delete()
                            }
                            ok
                        }
                        if (!done) allDone = false
                    }
                    allDone
                }
            }.getOrDefault(false)
        }

    private fun FTPFile.toEntry(dir: String): FileEntry? {
        if (name == "." || name == "..") return null
        val path = "${dir.trimEnd('/')}/$name"
        val modifiedAt: Long = getTimestamp()?.timeInMillis ?: 0L
        val bytes: Long = this.getSize().toLong()
        return FileEntry(
            id = path,
            name = name,
            kind = if (isDirectory) FileKind.FOLDER else FileKinds.of(name),
            sizeLabel = if (isDirectory) "" else LocalRepository.formatSize(bytes),
            timeLabel = LocalRepository.formatStamp(modifiedAt),
            folder = dir.trimEnd('/').substringAfterLast('/'),
            path = path,
            sizeBytes = if (isDirectory) 0 else bytes,
            modified = modifiedAt
        )
    }

    private inline fun <T> FTPClient.use(block: (FTPClient) -> T): T {
        try {
            return block(this)
        } finally {
            runCatching { logout() }
            runCatching { disconnect() }
        }
    }
}
