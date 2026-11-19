package com.yaoahos.myfilebox.android.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKinds
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.NetworkConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbRepository {
    /**
     * smbj 默认 30 秒才判失败。建连与协商收到 4 秒（直连地址，见 NetTimeouts），
     * 读写各留 30 / 60 秒——超时对传输同样生效，一起压到 4 秒会让慢速网络下的大文件先于连接失败。
     */
    private val config: SmbConfig = SmbConfig.builder()
        .withTimeout(NetTimeouts.NET_CONNECT_SECONDS, TimeUnit.SECONDS)
        .withReadTimeout(NetTimeouts.READ_SECONDS, TimeUnit.SECONDS)
        .withWriteTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun base(connection: NetworkConnection): Pair<String, String> {
        val parts = connection.path.trim('/').split('/').filter { it.isNotBlank() }
        val share = parts.firstOrNull().orEmpty()
        val folder = parts.drop(1).joinToString("\\")
        return share to folder
    }

    private fun smbPath(connection: NetworkConnection, entryPath: String): Pair<String, String> {
        val share = base(connection).first
        val relative = entryPath.removePrefix("smb://").trim('/')
        val parts = relative.split('/').filter { it.isNotBlank() }
        val shareName = if (parts.firstOrNull() == share) parts.first() else share
        val rest = if (parts.firstOrNull() == share) parts.drop(1) else parts
        return shareName to rest.joinToString("\\")
    }

    private fun <T> withShare(connection: NetworkConnection, block: (DiskShare, String) -> T): T {
        val shareName = base(connection).first
        if (shareName.isBlank()) throw IllegalStateException("SMB 连接缺少共享名")
        val client = SMBClient(config)
        val smb = client.connect(connection.host, connection.port)
        try {
            val session = smb.authenticate(
                AuthenticationContext(
                    connection.username,
                    connection.password.toCharArray(),
                    null
                )
            )
            val share = session.connectShare(shareName) as DiskShare
            try {
                return block(share, shareName)
            } finally {
                runCatching { share.close() }
            }
        } finally {
            runCatching { smb.close() }
            runCatching { client.close() }
        }
    }

    /**
     * 测连。异常必须透出：吞成 false 之后界面只能说「连接失败」，
     * 用户看不出是地址错、端口错还是账号密码错。
     */
    fun test(connection: NetworkConnection): Boolean =
        withShare(connection) { share, _ -> share.list("").isNotEmpty() || share.folderExists("") }

    suspend fun list(connection: NetworkConnection, path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        withShare(connection) { share, _ ->
            val (_, relative) = smbPath(connection, path)
            share.list(relative).mapNotNull { it.toEntry(connection, path) }
        }
    }

    suspend fun download(
        connection: NetworkConnection,
        remote: String,
        target: File,
        abort: (() -> Boolean)? = null,
        expectedSize: Long = -1L
    ): Boolean = withContext(Dispatchers.IO) {
        // 记录实际写入的字节数：部分服务端在「读到文件尾」时回的包格式不规范，
        // smbj 解析会抛 BufferException: Underflow，异常路径上再读文件长度可能
        // 拿到 0（写入统计还没落定）。用计数器判定完整性最可靠。
        var written = 0L
        val ok = runCatching {
            withShare(connection) { share, _ ->
                val (_, relative) = smbPath(connection, remote)
                share.openFile(
                    relative,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { file ->
                    target.parentFile?.mkdirs()
                    file.inputStream.use { input ->
                        target.outputStream().use { raw ->
                            val counting = object : java.io.FilterOutputStream(raw) {
                                override fun write(b: Int) {
                                    out.write(b)
                                    written += 1
                                }

                                override fun write(b: ByteArray, off: Int, len: Int) {
                                    out.write(b, off, len)
                                    written += len
                                }
                            }
                            copyWithAbort(input, counting, abort)
                            counting.flush()
                        }
                    }
                }
                target.length() > 0
            }
        }.onFailure {
            android.util.Log.w("MFB-SMB", "download failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        // 数据完整收到才算失败之外的成功：宁可接受「尾包解析报错但字节齐」，
        // 也不要把完整文件报成失败；空文件（expectedSize=0）同样算成功。
        val sizeComplete = expectedSize >= 0 && written == expectedSize
        val complete = ok || sizeComplete
        android.util.Log.i(
            "MFB-SMB",
            "download done ok=$ok written=$written expect=$expectedSize complete=$complete"
        )
        complete
    }

    /** 打开 SMB 文件流式读取（供 StreamProxy 转发给播放器）。 */
    fun openStream(connection: NetworkConnection, remote: String, offset: Long = 0L): java.io.InputStream {
        // 建链失败时没有任何包装流可以 close，必须在这里按创建顺序逆序回收，
        // 否则播放器 seek / 重试几次就会累积 SMB 连接与 socket。
        var client: AutoCloseable? = null
        var conn: AutoCloseable? = null
        var session: AutoCloseable? = null
        var share: AutoCloseable? = null
        var file: AutoCloseable? = null
        var stream: AutoCloseable? = null
        try {
            val (shareName, _) = base(connection)
            val (_, relative) = smbPath(connection, remote)
            val smbClient = SMBClient(config).also { client = it }
            val smbConn = smbClient.connect(connection.host, connection.port).also { conn = it }
            val auth = AuthenticationContext(
                connection.username.ifBlank { "Guest" },
                connection.password.toCharArray(),
                null
            )
            val smbSession = smbConn.authenticate(auth).also { session = it }
            val diskShare = smbSession.connectShare(shareName) as DiskShare
            share = diskShare
            val diskFile = diskShare.openFile(
                relative,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).also { file = it }
            val baseStream = diskFile.inputStream.also { stream = it }
            if (offset > 0) skipFully(baseStream, offset)
            // 包装流：关闭时释放 SMB 资源。
            return object : java.io.FilterInputStream(baseStream) {
                override fun close() {
                    runCatching { super.close() }
                    runCatching { file?.close() }
                    runCatching { share?.close() }
                    runCatching { session?.close() }
                    runCatching { conn?.close() }
                    runCatching { client?.close() }
                }
            }
        } catch (t: Throwable) {
            stream?.let { runCatching { it.close() } }
            file?.let { runCatching { it.close() } }
            share?.let { runCatching { it.close() } }
            session?.let { runCatching { it.close() } }
            conn?.let { runCatching { it.close() } }
            client?.let { runCatching { it.close() } }
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
            withShare(connection) { share, _ ->
                val (_, relativeDir) = smbPath(connection, dir)
                val target = if (relativeDir.isBlank()) name else "$relativeDir\\$name"
                val parent = target.substringBeforeLast('\\', "")
                if (parent.isNotBlank()) ensureDirs(share, parent)
                share.openFile(
                    target,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                ).use { file ->
                    file.outputStream.use { output ->
                        CountingInputStream(local.inputStream(), onBytes, abort).use { input -> input.copyTo(output) }
                    }
                }
                true
            }
        }.getOrDefault(false)
    }

    suspend fun delete(connection: NetworkConnection, paths: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            withShare(connection) { share, _ ->
                var allDone = true
                // 逐个删、结果聚合，不能用 all{}：第一项失败（正在写的文件被服务端占用、
                // 目录里还有别人的东西）会短路，同批剩下的文件全部不删——上传中删记录
                // 时表现就是「远端一份都没清掉」。
                paths.forEach { path ->
                    val (_, relative) = smbPath(connection, path)
                    if (relative.isBlank()) return@forEach
                    val ok = runCatching {
                        when {
                            // 先判文件：folderExists 对文件返回 false，顺序反了会把文件当目录。
                            share.fileExists(relative) -> share.rm(relative)
                            // SMB 的 rmdir 是递归删，调用方已保证只对本次上传产生的目录用。
                            share.folderExists(relative) -> share.rmdir(relative, true)
                            // 本来就不在了：清理的目标达成，算成功。
                            else -> Unit
                        }
                        true
                    }.getOrDefault(false)
                    if (!ok) allDone = false
                }
                allDone
            }
        }.getOrDefault(false)
    }

    /** 在 dir 下新建文件夹；已存在也算成功。 */
    suspend fun createFolder(connection: NetworkConnection, dir: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                withShare(connection) { share, _ ->
                    val (_, relativeDir) = smbPath(connection, dir)
                    // 上传文件夹时会一次建多级目录，父级可能还不存在，先补齐再建自己。
                    if (relativeDir.isNotBlank()) runCatching { ensureDirs(share, relativeDir) }
                    val target = if (relativeDir.isBlank()) name else "$relativeDir\\$name"
                    runCatching { share.mkdir(target) }
                    share.folderExists(target)
                }
            }.getOrDefault(false)
        }

    /** 重命名文件（夹）：以 DELETE 权限打开句柄后改名（SMB 重命名必须带 DELETE 访问权）。 */
    suspend fun rename(connection: NetworkConnection, path: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                withShare(connection) { share, _ ->
                    val (_, relative) = smbPath(connection, path)
                    val parent = relative.substringBeforeLast('\\', "")
                    val target = if (parent.isBlank()) newName else "$parent\\$newName"
                    val entry: DiskEntry = if (share.folderExists(relative)) {
                        share.openDirectory(
                            relative,
                            EnumSet.of(AccessMask.DELETE),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null
                        )
                    } else {
                        share.openFile(
                            relative,
                            EnumSet.of(AccessMask.DELETE),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null
                        )
                    }
                    try {
                        entry.rename(target, true)
                        true
                    } finally {
                        runCatching { entry.close() }
                    }
                }
            }.getOrDefault(false)
        }

    suspend fun copyMove(connection: NetworkConnection, paths: List<String>, destDir: String, move: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                withShare(connection) { share, _ ->
                    val (_, relativeDir) = smbPath(connection, destDir)
                    var allDone = true
                    // 不能用 all{} 短路：同 delete，第一项失败会让同批其余条目全部跳过。
                    paths.forEach { path ->
                        val (_, relative) = smbPath(connection, path)
                        val name = relative.substringAfterLast('\\')
                        val target = if (relativeDir.isBlank()) name else "$relativeDir\\$name"
                        val ok = runCatching {
                            if (!copyEntry(share, relative, target)) return@runCatching false
                            if (!move) return@runCatching true
                            if (share.folderExists(relative)) share.rmdir(relative, true) else share.rm(relative)
                            true
                        }.getOrDefault(false)
                        if (!ok) allDone = false
                    }
                    allDone
                }
            }.getOrDefault(false)
        }

    private fun copyEntry(share: DiskShare, source: String, target: String): Boolean {
        return runCatching {
            if (share.folderExists(source)) {
                ensureDirs(share, target)
                share.list(source).forEach { info ->
                    val childSource = "$source\\${info.fileName}"
                    val childTarget = "$target\\${info.fileName}"
                    copyEntry(share, childSource, childTarget)
                }
                true
            } else {
                share.openFile(
                    source,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { inputFile ->
                    share.openFile(
                        target,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    ).use { outputFile ->
                        inputFile.inputStream.use { input ->
                            outputFile.outputStream.use { output -> input.copyTo(output) }
                        }
                    }
                }
                true
            }
        }.getOrDefault(false)
    }

    private fun ensureDirs(share: DiskShare, path: String) {
        var current = ""
        path.split('\\').filter { it.isNotBlank() }.forEach { part ->
            current = if (current.isBlank()) part else "$current\\$part"
            if (!share.folderExists(current)) share.mkdir(current)
        }
    }

    private fun FileIdBothDirectoryInformation.toEntry(connection: NetworkConnection, parent: String): FileEntry? {
        if (fileName == "." || fileName == "..") return null
        val isDir = (fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
        val path = "${parent.trimEnd('/')}/$fileName"
        val modified = lastWriteTime?.toEpochMillis() ?: 0L
        return FileEntry(
            id = path,
            name = fileName,
            kind = if (isDir) FileKind.FOLDER else FileKinds.of(fileName),
            sizeLabel = if (isDir) "" else LocalRepository.formatSize(endOfFile),
            timeLabel = LocalRepository.formatStamp(modified),
            folder = parent.trimEnd('/').substringAfterLast('/'),
            path = path,
            sizeBytes = if (isDir) 0 else endOfFile,
            modified = modified
        )
    }
}

/**
 * 可靠地跳过 n 个字节。
 *
 * InputStream.skip() 允许「少跳」，SMB 流上尤其容易只跳一部分；少跳的后果是
 * 播放器请求「从 1GB 开始的数据」，我们却从 800MB 开始发，解码直接出错。
 */
internal fun skipFully(stream: java.io.InputStream, n: Long) {
    var remaining = n
    val buffer = ByteArray(64 * 1024)
    while (remaining > 0) {
        val skipped = stream.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) return
        remaining -= read
    }
}
