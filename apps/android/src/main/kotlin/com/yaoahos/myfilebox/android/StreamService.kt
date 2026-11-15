package com.yaoahos.myfilebox.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * 流式播放期间的保活前台服务。
 *
 * 为什么必须有它：外部播放器接管屏幕后本应用退到后台，MIUI 等 ROM 会立刻限制
 * 后台应用的网络（真机实测：后台吞吐降到 1/4，且新连接会在几秒内被静默掐断），
 * 表现为「视频播十几秒就 Source error」。挂一条 mediaPlayback 类型的前台服务后，
 * 系统按「正在播放媒体」对待本进程，网络不再被限制，播放恢复正常。
 *
 * 生命周期由 StreamProxy 控制：有连接在传就保持前台，空闲超过 90 秒自动退出。
 */
class StreamService : Service() {

    companion object {
        const val ACTION_START = "com.yaoahos.myfilebox.STREAM_START"
        const val ACTION_STOP = "com.yaoahos.myfilebox.STREAM_STOP"
        private const val CHANNEL_ID = "stream_keepalive"
        private const val NOTIF_ID = 40712

        fun start(context: Context) {
            val intent = Intent(context, StreamService::class.java).setAction(ACTION_START)
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在传输媒体流")
            .setContentText("保持网络畅通以继续播放")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else 0
        )
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "媒体传输",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "流式播放媒体文件时保持网络连接"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
