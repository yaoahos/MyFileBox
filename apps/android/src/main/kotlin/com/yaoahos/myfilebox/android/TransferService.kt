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
        /**
         * 停止前台服务并撤掉它那条进度通知。
         *
         * 不要「摘掉前台身份、把通知留在栏里」：前台服务名下的通知用户划不掉，
         * 系统的「清除全部」也清不掉，只能变成一条赖着不走的旧进度。
         * 完成结果由 TransferNotifications.notifyFinal() 单独发一条可清除的通知。
         */
        const val ACTION_STOP = "com.yaoahos.myfilebox.STOP_FOREGROUND"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
