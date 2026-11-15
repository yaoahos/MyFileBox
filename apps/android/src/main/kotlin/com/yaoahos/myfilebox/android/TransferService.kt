package com.yaoahos.myfilebox.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.yaoahos.myfilebox.android.ui.TransferMode
import com.yaoahos.myfilebox.android.ui.TransferStatus
import com.yaoahos.myfilebox.android.ui.TransferTask

/**
 * 上传 / 下载进行中时保活的前台服务。进度本身由 AppViewModel 通过
 * TransferNotifications.update() 持续推送，服务只负责「先挂一条合法通知」。
 */
class TransferService : Service() {
    companion object {
        /** 停止前台服务但把通知留在通知栏（用于「完成」通知继续显示）。 */
        const val ACTION_DETACH = "com.yaoahos.myfilebox.DETACH_FOREGROUND"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DETACH) {
            // 只把「前台」这个身份摘掉，通知本身留着，用户点它就能打开刚传完的文件。
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
            return START_NOT_STICKY
        }
        val taskId = intent?.getStringExtra(TransferNotifications.EXTRA_TASK_ID).orEmpty()
        if (taskId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        TransferNotifications.ensureChannel(this)
        val title = intent?.getStringExtra(TransferNotifications.EXTRA_TASK_TITLE).orEmpty()
        val total = intent?.getLongExtra(TransferNotifications.EXTRA_TASK_TOTAL, 0L) ?: 0L
        val mode = runCatching {
            TransferMode.valueOf(intent?.getStringExtra(TransferNotifications.EXTRA_TASK_MODE) ?: "DOWNLOAD")
        }.getOrDefault(TransferMode.DOWNLOAD)
        val placeholder = TransferTask(
            id = taskId,
            mode = mode,
            title = title,
            detail = "",
            total = 1,
            totalBytes = total,
            status = TransferStatus.RUNNING
        )
        ServiceCompat.startForeground(
            this,
            TransferNotifications.FOREGROUND_ID,
            TransferNotifications.build(this, placeholder),
            if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        return START_NOT_STICKY
    }
}
