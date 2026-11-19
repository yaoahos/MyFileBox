package com.yaoahos.myfilebox.android.update

import android.Manifest
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
import com.yaoahos.myfilebox.android.MainActivity
import java.io.File

/**
 * 下载更新安装包时的通知栏进度与「已下载」入口。
 *
 * 与文件传输分渠道：更新是一次性的应用自身行为，混进传输进度会被当成一条文件任务。
 * 渠道用 DEFAULT + 关振动（参照 TransferNotifications 的结论：IMPORTANCE_LOW 在
 * MIUI 等 ROM 会被塞进「不重要通知」，下拉看不到），进度条本身 setSilent 不打扰。
 */
object UpdateNotifications {
    const val EXTRA_INSTALL_APK = "extra_install_update_apk"

    private const val CHANNEL_ID = "app_updates"
    private const val PROGRESS_ID = 4401
    private const val DONE_ID = 4402

    /** 上次发进度通知的 (百分比, 时刻)：国产 ROM 对高频通知敏感，限制 1s 且百分比变化才发。 */
    private var lastSent = -2 to 0L

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "下载新版本安装包时显示进度"
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    /** 进度通知；[percent] 小于 0 表示还没拿到总大小。 */
    // 权限由入口的 canPost() 检查，未授予就静默跳过；lint 只认内联的 checkSelfPermission。
    @Suppress("MissingPermission")
    fun progress(context: Context, percent: Int) {
        if (!canPost(context)) return
        val now = android.os.SystemClock.elapsedRealtime()
        val (lastPercent, lastAt) = lastSent
        if (percent == lastPercent && now - lastAt < 1_000L) return
        lastSent = percent to now
        ensureChannel(context)
        val indeterminate = percent < 0
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载 MyFileBox 更新")
            .setContentText(if (indeterminate) "正在连接下载地址…" else "已完成 $percent%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        runCatching { NotificationManagerCompat.from(context).notify(PROGRESS_ID, builder.build()) }
    }

    /** 下载完成：撤掉进度条，另发一条可点击直接安装的通知。 */
    @Suppress("MissingPermission")
    fun completed(context: Context, info: UpdateInfo, apkPath: String) {
        if (!canPost(context)) return
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.cancel(PROGRESS_ID) }
        ensureChannel(context)
        val pending = PendingIntent.getActivity(
            context,
            DONE_ID,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_INSTALL_APK, apkPath)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MyFileBox ${info.version} 已下载")
            .setContentText("点按安装新版本")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        runCatching { manager.notify(DONE_ID, builder.build()) }
    }

    /** 撤掉进度与已下载两条通知（下载失败、从通知进入安装时调用）。 */
    fun clear(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.cancel(PROGRESS_ID) }
        runCatching { manager.cancel(DONE_ID) }
    }

    /** 冷启动时清掉上个进程残留的进度条：下载不可能跨进程存活，留着就是一条骗人的假进度。 */
    fun clearStaleProgress(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(PROGRESS_ID) }
    }

    /**
     * 通知携带的路径任何应用都能伪造（MainActivity 必须 exported），
     * 只放行 cacheDir/updates 下的真实 .apk。
     */
    fun resolveUpdateApk(cacheDir: File, raw: String): File? = runCatching {
        val dir = File(cacheDir, "updates").canonicalFile
        val file = File(raw).canonicalFile
        val inside = file.path.startsWith(dir.path + File.separator)
        if (!inside || !file.name.endsWith(".apk", ignoreCase = true) || !file.isFile) null else file
    }.getOrNull()
}
