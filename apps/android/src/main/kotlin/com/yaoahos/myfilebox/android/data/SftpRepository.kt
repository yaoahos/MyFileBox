package com.yaoahos.myfilebox.android.data

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FileKinds
import com.yaoahos.myfilebox.core.NetworkConnection
import java.io.File

class SftpRepository(private val knownHostsFile: File? = null) {
    private fun <T> withChannel(connection: NetworkConnection, block: (ChannelSftp) -> T): T {
        var session: Session? = null
        return try {
            val jsch = JSch()
            session = jsch.getSession(
                connection.username.ifBlank { "anonymous" },
                connection.host,
                if (connection.port > 0) connection.port else 22
            )
            session.setPassword(connection.password)
            // Trust-on-first-use: the first host key is persisted, every later
            // connection is verified against it instead of skipping the check.
            val known = knownHostsFile
            val alreadyKnown = known?.exists() == true
            if (alreadyKnown) {
                runCatching { jsch.setKnownHosts(known!!.absolutePath) }
                session.setConfig("StrictHostKeyChecking", "yes")
            } else {
                session.setConfig("StrictHostKeyChecking", "no")
            }
            session.connect(NetTimeouts.NET_CONNECT_MILLIS)
            if (!alreadyKnown) rememberHostKey(session, connection, known)
            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(NetTimeouts.NET_CONNECT_MILLIS)
            try {
                block(channel)
            } finally {
                channel.disconnect()
            }
        } finally {
            session?.disconnect()
        }
    }

    /** 测连：JSch 把原因写在异常消息里（Auth fail / timeout），必须透出。 */
    fun test(connection: NetworkConnection): Boolean = withChannel(connection) { it.pwd(); true }

    fun list(connection: NetworkConnection, path: String): List<FileEntry> = runCatching {
        withChannel(connection) { channel ->
            val dir = path.ifBlank { connection.path.ifBlank { "/" } }
            channel.ls(dir).mapNotNull { raw ->
                val item = raw as? ChannelSftp.LsEntry ?: return@mapNotNull null
                if (item.filename == "." || item.filename == "..") return@mapNotNull null
                val attrs = item.attrs
                val kind = if (attrs.isDir) FileKind.FOLDER else FileKinds.of(item.filename)
                FileEntry(
                    id = "$dir/${item.filename}",
                    name = item.filename,
                    kind = kind,
                    sizeLabel = if (attrs.isDir) "" else LocalRepository.formatSize(attrs.size),
                    timeLabel = LocalRepository.formatStamp(attrs.mTime * 1000L),
                    folder = dir,
                    path = "$dir/${item.filename}",
                    sizeBytes = if (attrs.isDir) 0L else attrs.size,
                    modified = attrs.mTime * 1000L
                )
            }.sortedWith(compareBy<FileEntry> { !it.isFolder }.thenByDescending { it.modified })
        }
    }.getOrDefault(emptyList())

    fun download(
        connection: NetworkConnection,
        remote: String,
        target: File,
        abort: (() -> Boolean)? = null
    ): Boolean = runCatching {
        target.parentFile?.mkdirs()
        withChannel(connection) { channel ->
            channel.get(remote).use { input ->
                target.outputStream().use { output -> copyWithAbort(input, output, abort) }
            }
        }
        target.exists()
    }.getOrDefault(false)

    /** 打开 SFTP 文件流式读取（供 StreamProxy 转发给播放器）。 */
    fun openStream(connection: NetworkConnection, remote: String, offset: Long = 0L): java.io.InputStream {
        val jsch = JSch()
        val session = jsch.getSession(
            connection.username.ifBlank { "anonymous" },
            connection.host,
            if (connection.port > 0) connection.port else 22
        )
        session.setPassword(connection.password)
        // Trust-on-first-use：与 withChannel 同口径——首次记住主机密钥，之后严格校验。
        val known = knownHostsFile
        val alreadyKnown = known?.exists() == true
        if (alreadyKnown) {
            runCatching { jsch.setKnownHosts(known!!.absolutePath) }
        }
        session.setConfig("StrictHostKeyChecking", if (alreadyKnown) "yes" else "no")
        session.connect(NetTimeouts.NET_CONNECT_MILLIS)
        if (!alreadyKnown) rememberHostKey(session, connection, known)
        val channel = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
        channel.connect(NetTimeouts.NET_CONNECT_MILLIS)
        // JSch 的 ChannelSftp.get(src, monitor, skip) 走的是协议层的偏移读取，
        // 直接向服务端要 offset 之后的数据；用 InputStream.skip() 则是一个字节一个字节
        // 读过去丢掉——拖动进度到 60% 时等于先把前面几十 GB 全下一遍。
        val stream = channel.get(remote, null, offset.coerceAtLeast(0L))
        return object : java.io.FilterInputStream(stream) {
            override fun close() {
                runCatching { super.close() }
                runCatching { channel.disconnect() }
                runCatching { session.disconnect() }
            }
        }
    }

    fun upload(
        connection: NetworkConnection,
        local: File,
        dir: String,
        name: String = local.name,
        onBytes: ((Long) -> Unit)? = null,
        abort: (() -> Boolean)? = null
    ): Boolean = runCatching {
        withChannel(connection) { channel ->
            ensureDirs(channel, dir)
            local.inputStream().use { input ->
                channel.put(CountingInputStream(input, onBytes, abort), "$dir/$name")
            }
        }
        true
    }.getOrDefault(false)

    /** 在 dir 下新建文件夹；已存在也算成功（SFTP 会抛异常，这里吞掉）。 */
    fun createFolder(connection: NetworkConnection, dir: String, name: String): Boolean =
        withChannel(connection) { channel ->
            val parent = dir.trimEnd('/').ifBlank { "/" }
            val target = parent.trimEnd('/') + "/" + name
            runCatching { channel.mkdir(target) }
            runCatching { channel.stat(target) }.isSuccess
        }

    fun delete(connection: NetworkConnection, paths: List<String>): Boolean = runCatching {
        withChannel(connection) { channel ->
            var allDone = true
            // 逐项 try/catch：以前整批包在一个 runCatching 里，前面某个文件 stat 抛异常
            // （权限、路径刚被改名）会让循环直接中断，排在它后面的一个都不删。
            paths.forEach { path ->
                val ok = runCatching {
                    val attrs = channel.stat(path)
                    if (attrs.isDir) channel.rmdir(path) else channel.rm(path)
                    true
                }.getOrDefault(false)
                if (!ok) allDone = false
            }
            allDone
        }
    }.getOrDefault(false)

    /** 重命名文件（夹）：同目录内改名。 */
    fun rename(connection: NetworkConnection, path: String, newName: String): Boolean = runCatching {
        withChannel(connection) { channel ->
            val parent = path.substringBeforeLast('/', "")
            val target = if (parent.isEmpty()) newName else "$parent/$newName"
            channel.rename(path, target)
        }
        true
    }.getOrDefault(false)

    fun copyMove(
        connection: NetworkConnection,
        paths: List<String>,
        destDir: String,
        move: Boolean
    ): Boolean = runCatching {
        withChannel(connection) { channel ->
            ensureDirs(channel, destDir)
            var allDone = true
            // 逐项 try/catch：同 delete，单项 stat/权限失败不能中断同批其余条目。
            paths.forEach { path ->
                val ok = runCatching {
                    val name = path.trimEnd('/').substringAfterLast('/')
                    val target = "${destDir.trimEnd('/')}/$name"
                    // 移动优先走服务端 rename：目录也支持，也不用先在目标端落一份副本。
                    // 失败（跨挂载点 / 服务端不允许）再退回「递归复制 + 删源」。
                    if (move && runCatching { channel.rename(path, target) }.isSuccess) {
                        true
                    } else {
                        copyTree(channel, path, target)
                        if (move) removeTree(channel, path)
                        true
                    }
                }.getOrDefault(false)
                if (!ok) allDone = false
            }
            allDone
        }
    }.getOrDefault(false)

    /**
     * 递归复制一个文件或整个目录。
     *
     * 这里原来只有 `if (!attrs.isDir)` 一个分支：复制 / 移动文件夹时整项被静默跳过，
     * 却仍返回 true——界面上提示「移动成功」，实际源和目标都还在，一个文件都没动。
     */
    private fun copyTree(channel: ChannelSftp, source: String, target: String) {
        val attrs = channel.stat(source)
        if (!attrs.isDir) {
            // 同一个 channel 上不能同时开 get / put 两条流，先落临时文件再上传。
            val tmp = File.createTempFile("myfilebox-sftp-", ".tmp")
            try {
                channel.get(source).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.inputStream().use { input -> channel.put(input, target) }
            } finally {
                tmp.delete()
            }
            return
        }
        // 目标目录可能已存在（覆盖粘贴），mkdir 失败不当错误；随后的 put 会暴露真实问题。
        runCatching { channel.stat(target) }.getOrNull() ?: runCatching { channel.mkdir(target) }
        for (entry in channel.ls(source)) {
            val name = entry.filename
            if (name == "." || name == "..") continue
            copyTree(channel, "$source/$name", "$target/$name")
        }
    }

    /** 递归删除（仅当 rename 不可用、需要「复制后再删源」时走这里）。 */
    private fun removeTree(channel: ChannelSftp, path: String) {
        val attrs = channel.stat(path)
        if (!attrs.isDir) {
            channel.rm(path)
            return
        }
        for (entry in channel.ls(path)) {
            val name = entry.filename
            if (name == "." || name == "..") continue
            removeTree(channel, "$path/$name")
        }
        channel.rmdir(path)
    }

    /** 首次连接后记住主机密钥，下次起 StrictHostKeyChecking=yes 严格校验。 */
    private fun rememberHostKey(session: Session?, connection: NetworkConnection, known: File?) {
        if (known == null) return
        runCatching {
            val hostKey = session?.hostKey ?: return
            val entry = "${connection.host} ${hostKey.type} ${hostKey.key}\n"
            val portEntry = if (connection.port > 0 && connection.port != 22)
                "[${connection.host}]:${connection.port} ${hostKey.type} ${hostKey.key}\n" else ""
            known.appendText(entry + portEntry)
        }
    }

    private fun ensureDirs(channel: ChannelSftp, path: String) {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        var current = if (path.startsWith("/")) "/" else ""
        parts.forEach { part ->
            current = if (current.endsWith("/")) "$current$part" else "$current/$part"
            runCatching { channel.stat(current) }.getOrNull() ?: runCatching { channel.mkdir(current) }
        }
    }
}
