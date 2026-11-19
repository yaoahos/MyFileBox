package com.yaoahos.myfilebox.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yaoahos.myfilebox.android.ui.TransferMode
import com.yaoahos.myfilebox.android.ui.TransferStatus
import com.yaoahos.myfilebox.android.ui.TransferTask

/**
 * 传输通知：上传与下载共用一条「进行中」的前台通知（可取消），
 * 每个任务结束后单独发一条完成通知，点击直接打开那个文件。
 *
 * 点击语义（清单 4）：
 *  - 进行中：进入传输页看进度；
 *  - 已完成：和文件列表里点一下完全一样（图片进相册、apk 安装、视频交给播放器）。
 */
object TransferNotifications {
    /**
     * v2：早期版本用 IMPORTANCE_LOW，国产 ROM（MIUI 等）会把它收进「不重要通知」分组，
     * 用户下拉根本看不到进度。改成 DEFAULT 但显式关掉声音 / 振动，
     * 既不打扰又能出现在通知栏正常位置。
     */
    const val CHANNEL_ID = "myfilebox_transfers_v3"
    const val FOREGROUND_ID = 4101
    const val ACTION_CANCEL = "com.yaoahos.myfilebox.android.CANCEL_TRANSFER"
    const val EXTRA_TASK_ID = "transfer_task_id"
    const val EXTRA_TASK_TITLE = "transfer_task_title"
    const val EXTRA_TASK_TOTAL = "transfer_task_total"
    const val EXTRA_TASK_MODE = "transfer_task_mode"
    /** MainActivity 收到这个 extra 后交给 ViewModel 处理「点通知」。 */
    const val EXTRA_OPEN_TASK = "open_transfer_task"

    private const val FINAL_ID_BASE = 4300

    /** 每个任务上一次发通知的 (进度百分比, 时间)，用于节流。 */
    private val lastPosted = HashMap<String, Pair<Int, Long>>()

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "文件传输",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "后台上传与下载进度"
                    setShowBadge(false)
                    // 必须保留默认提示音：MIUI 等 ROM 会把「无声渠道」的通知归到
                    // 「不重要通知」分组，用户下拉根本看不到完成通知。
                    // 进行中的那条用 setSilent(true) 单独静音，只保留完成时一声提示。
                    enableVibration(false)
                }
            )
        }
    }

    /** 点击通知：带上任务 id 回到 MainActivity，由 ViewModel 决定进传输页还是打开文件。 */
    private fun contentIntent(context: Context, taskId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = "com.yaoahos.myfilebox.android.OPEN_TASK_$taskId"
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_TASK, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancelIntent(context: Context, taskId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            Intent(context, TransferCancelReceiver::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun verb(mode: TransferMode, status: TransferStatus): String {
        val action = if (mode == TransferMode.UPLOAD) "上传" else "下载"
        return when (status) {
            TransferStatus.QUEUED -> "等待$action"
            TransferStatus.RUNNING -> "正在$action"
            TransferStatus.PAUSED -> "$action 已暂停"
            TransferStatus.SUCCESS -> "$action 完成"
            TransferStatus.FAILED -> "$action 失败"
            TransferStatus.CANCELLED -> "$action 已取消"
            TransferStatus.MISSING -> "文件丢失"
        }
    }

    private fun iconOf(mode: TransferMode, status: TransferStatus): Int = when {
        mode == TransferMode.UPLOAD && status == TransferStatus.RUNNING -> android.R.drawable.stat_sys_upload
        mode == TransferMode.UPLOAD -> android.R.drawable.stat_sys_upload_done
        status == TransferStatus.RUNNING -> android.R.drawable.stat_sys_download
        else -> android.R.drawable.stat_sys_download_done
    }

    fun build(context: Context, task: TransferTask): Notification {
        val running = task.status == TransferStatus.RUNNING
        val totalBytes = task.totalBytes
        val percent = if (totalBytes > 0L) {
            ((task.completedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
        } else if (task.total > 0) {
            ((task.completed.toDouble() / task.total) * 100).toInt().coerceIn(0, 100)
        } else 0
        val stateText = when (task.status) {
            TransferStatus.QUEUED -> "排队中…"
            TransferStatus.RUNNING -> when {
                totalBytes > 0 -> "$percent% · ${formatBytes(task.completedBytes)} / ${formatBytes(totalBytes)}"
                task.total > 1 -> "${task.completed} / ${task.total} 项"
                else -> "准备中…"
            }
            TransferStatus.PAUSED -> "已暂停"
            TransferStatus.SUCCESS -> buildString {
                append("已完成")
                if (totalBytes > 0) append(" · ${formatBytes(totalBytes)}")
                if (task.total > 1) append(" · ${task.total} 项")
            }
            TransferStatus.FAILED -> "失败" + (task.error?.let { " · $it" } ?: "")
            TransferStatus.CANCELLED -> "已取消"
            TransferStatus.MISSING -> "文件丢失"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconOf(task.mode, task.status))
            .setContentTitle(verb(task.mode, task.status))
            .setContentText("${task.title} · $stateText")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${task.title}\n$stateText"))
            .setContentIntent(contentIntent(context, task.id))
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setAutoCancel(!running)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (running) {
            builder.setSilent(true)
                .setProgress(100, percent, totalBytes <= 0L && task.total <= 1)
                .addAction(0, "暂停", cancelIntent(context, task.id))
        }
        return builder.build()
    }

    /**
     * 进行中：始终复用前台通知 id，多个任务只保留最新一条进度。
     *
     * 必须节流：下载进度每 350ms 回调一次、上传每 200ms 一次，如果每次都 notify()，
     * 一分钟后就是上百条更新，国产 ROM（MIUI）会直接把该应用的通知判为「打扰」并静音，
     * 表现就是「通知栏里根本看不到进度」。这里限制 1s 且进度百分比变化才发。
     */
    // 通知权限已由 canPost() 在入口处查过（POST_NOTIFICATIONS 未授予就直接返回），
    // lint 不认识这个自定义封装，只看到 notify 前没有内联的 checkSelfPermission。
    @Suppress("MissingPermission")
    @Synchronized
    fun update(context: Context, task: TransferTask) {
        if (task.quiet || !canPost(context)) return
        val percent = progressOf(task)
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastPosted[task.id]
        if (last != null && now - last.second < 1_000L && last.first == percent) return
        lastPosted[task.id] = percent to now
        runCatching {
            NotificationManagerCompat.from(context).notify(FOREGROUND_ID, build(context, task))
        }
    }

    private fun progressOf(task: TransferTask): Int = when {
        task.totalBytes > 0L ->
            ((task.completedBytes.toDouble() / task.totalBytes) * 100).toInt().coerceIn(0, 100)
        task.total > 0 -> ((task.completed.toDouble() / task.total) * 100).toInt().coerceIn(0, 100)
        else -> 0
    }

    /**
     * 结束：发一条独立的「完成 / 失败」通知，点开就是打开那个文件。
     *
     * 绝不复用前台通知那条（FOREGROUND_ID）：前台服务名下的通知用户划不掉、
     * 「清除全部」也清不掉，完成通知挂在那个 id 上就会永远赖在通知栏。
     * 所以结束通知一律用自己的 id，只把「进行中」保持常驻。
     *
     * [replacesProgress] = true 表示没有别的任务在跑，那条进度通知已经没用了：
     * 顺手撤掉它，否则前台服务摘掉后它还会以「已完成」的样子留在栏里。
     */
    // 同 update()：权限判断在 canPost()，lint 看不到封装内部的 checkSelfPermission。
    @Suppress("MissingPermission")
    fun notifyFinal(context: Context, task: TransferTask, replacesProgress: Boolean = false) {
        lastPosted.remove(task.id)
        if (task.quiet || !canPost(context)) return
        val id = FINAL_ID_BASE + (task.id.hashCode() and 0x0FFF)
        runCatching {
            NotificationManagerCompat.from(context).notify(id, build(context, task))
            if (replacesProgress) NotificationManagerCompat.from(context).cancel(FOREGROUND_ID)
        }
    }

    /** 直接移除通知（删除任务时用，不需要显示结果）。 */
    fun dismiss(context: Context, taskId: String) {
        lastPosted.remove(taskId)
        runCatching {
            NotificationManagerCompat.from(context).cancel(FOREGROUND_ID)
            NotificationManagerCompat.from(context).cancel(FINAL_ID_BASE + (taskId.hashCode() and 0x0FFF))
        }
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 委托 core 的唯一口径，避免通知与列表/详情显示精度不一致。 */
    fun formatBytes(bytes: Long): String = com.yaoahos.myfilebox.core.FileSizeFormat.format(bytes)
}
