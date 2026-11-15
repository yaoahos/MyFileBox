package com.yaoahos.myfilebox.android.data

import java.io.IOException
import java.io.InputStream

/**
 * 上传进度用的包装流：按读取字节数回调，节流到 200ms 一次，
 * 避免大文件逐个缓冲区回调把主线程刷爆。
 *
 * SMB / FTP / SFTP / WebDAV / 群晖各自的上传实现都读同一个本机文件，
 * 统一用这个流就能拿到「已经发出多少字节」，进度条不再是 0% 直接跳 100%。
 *
 * [abort] 用于「暂停上传」：上传是阻塞 IO，协程 cancel() 打断不了正在 send 的
 * socket，只有让读文件的流自己抛异常才能真正停下。返回 true 时立刻抛
 * [TransferAbortedException]，各协议的 runCatching 会把它当成「上传未完成」，
 * 上层再按取消标记判定成「已暂停」而不是「失败」。
 */
internal class CountingInputStream(
    private val delegate: InputStream,
    private val onBytes: ((Long) -> Unit)?,
    private val abort: (() -> Boolean)? = null
) : InputStream() {
    private var total = 0L
    private var lastReportAt = 0L

    override fun read(): Int {
        checkAbort()
        val value = delegate.read()
        if (value >= 0) report(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkAbort()
        val count = delegate.read(b, off, len)
        if (count > 0) report(count.toLong())
        return count
    }

    override fun available(): Int = delegate.available()

    override fun close() {
        delegate.close()
        onBytes?.invoke(total)
    }

    private fun checkAbort() {
        if (abort?.invoke() == true) throw TransferAbortedException()
    }

    private fun report(delta: Long) {
        total += delta
        val callback = onBytes ?: return
        val now = System.currentTimeMillis()
        if (now - lastReportAt >= 200L) {
            lastReportAt = now
            callback(total)
        }
    }
}

/** 用户主动暂停上传：不是网络错误，提示文案不能写成「上传失败」。 */
internal class TransferAbortedException : IOException("transfer-aborted")

/**
 * 下载用的可取消拷贝。
 *
 * 下载同样是阻塞 IO：`job.cancel()` 只能在下个挂起点生效，`copyTo` 里一个挂起点
 * 都没有，于是「删掉任务后后台还在往已删除的 .part 里写」——界面干净了，
 * 带宽和磁盘却继续被吃掉。用这个循环，每次读之前看一次取消标记。
 */
internal fun copyWithAbort(
    input: InputStream,
    output: java.io.OutputStream,
    abort: (() -> Boolean)?
) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        if (abort?.invoke() == true) throw TransferAbortedException()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
    }
}

