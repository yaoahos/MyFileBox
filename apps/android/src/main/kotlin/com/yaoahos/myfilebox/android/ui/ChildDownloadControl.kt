package com.yaoahos.myfilebox.android.ui

/**
 * 文件夹下载的子项调度器。
 *
 * 把「一次性把所有文件丢给 Semaphore」换成可抢占的队列：worker 从 [nextKey] 领取
 * 一个未暂停、未在开始中的子项；用户可以单独暂停某个正在下载 / 等待中的文件，
 * 恢复时它回到队首并从 .part 续传，也可以把一个等待中的文件插到队首提前下载。
 *
 * 所有方法都在下载协程 / UI 线程间共享，故用 synchronized 保护内部集合。
 * 这里只管「谁该下载」，不碰文件 IO 与进度统计（那部分留在 downloadToLocal）。
 */
internal class ChildDownloadControl {
    /** 等待下载的子项 key（队首先取）。暂停的项不在这里。 */
    private val pending = ArrayDeque<String>()
    /** 用户暂停的子项：既不取、也不算完成，直到 resume。 */
    private val paused = LinkedHashSet<String>()
    /** 已被 worker 领走、正在下载的子项。 */
    private val inFlight = LinkedHashSet<String>()
    /** 需要从上次的 .part 续传的子项（暂停中断后恢复时置位）。 */
    private val needResume = LinkedHashSet<String>()
    /** true 后 worker 不再领取新任务，等现有 inFlight 收尾即退出。 */
    @Volatile
    private var closed = false

    @Synchronized
    fun enqueueAll(keys: Collection<String>) {
        keys.forEach { if (it !in paused && it !in inFlight && it !in pending) pending.addLast(it) }
    }

    /** 领取下一个可下载子项；没有就返回 null（worker 自行 delay 重试）。 */
    @Synchronized
    fun nextKey(): String? {
        if (closed || pending.isEmpty()) return null
        val key = pending.removeFirst()
        inFlight += key
        return key
    }

    @Synchronized
    fun isPaused(key: String): Boolean = key in paused

    @Synchronized
    fun shouldResume(key: String): Boolean = key in needResume

    /** 领取后消费续传标记：只对这一次生效。 */
    @Synchronized
    fun consumeResume(key: String): Boolean = needResume.remove(key)

    /**
     * 暂停：等待中的项移出队列、下载中的项等其 abortCheck 自行中断。
     * 两种方法最终都会把 key 放进 [paused]，worker 不会再取。
     */
    @Synchronized
    fun pause(key: String) {
        pending.remove(key)
        if (key !in inFlight) {
            paused += key
        } else {
            // 下载中：先记暂停，downloadOne 的 abortCheck 读到后中断并调用 abortPaused。
            paused += key
        }
    }

    /** 下载协程检测到「本项被暂停」后调用：把 inFlight 交还到暂停区。 */
    @Synchronized
    fun abortPaused(key: String) {
        inFlight -= key
        paused += key
        needResume += key
    }

    /** 恢复：回到队首，并从 .part 续传。 */
    @Synchronized
    fun resume(key: String): Boolean {
        if (key !in paused && key !in inFlight) return false
        paused -= key
        inFlight -= key
        needResume += key
        pending.addFirst(key)
        return true
    }

    /** 提前开始：把等待 / 暂停中的项插到队首；暂停的项同时被恢复。 */
    @Synchronized
    fun promote(key: String): Boolean {
        val wasPaused = key in paused
        if (key in inFlight) return false
        pending.remove(key)
        paused -= key
        pending.addFirst(key)
        if (wasPaused) needResume += key
        return true
    }

    /** 下载成功 / 失败：从在途集合移除，不再参与调度。 */
    @Synchronized
    fun finish(key: String) {
        inFlight -= key
        paused -= key
        needResume -= key
    }

    /**
     * 没有等待项、也没有在途项时 worker 即可退出。暂停项不阻塞退出：
     * 任务会据此停在 PAUSED 态，用户恢复暂停项时控制器已清理，走单文件重新下载。
     */
    @Synchronized
    fun allFinished(): Boolean = pending.isEmpty() && inFlight.isEmpty()

    /** 暂停中的数量，供 UI / 提示使用。 */
    @Synchronized
    fun pausedCount(): Int = paused.size

    @Synchronized
    fun close() {
        closed = true
        pending.clear()
    }
}
