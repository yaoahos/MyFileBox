package com.yaoahos.myfilebox.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class TransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TransferNotifications.ACTION_CANCEL) return
        val taskId = intent.getStringExtra(TransferNotifications.EXTRA_TASK_ID).orEmpty()
        if (taskId.isBlank()) return
        TransferCancellation.request(taskId)
        runCatching {
            NotificationManagerCompat.from(context).cancel(TransferNotifications.FOREGROUND_ID)
        }
        context.stopService(Intent(context, TransferService::class.java))
    }
}
