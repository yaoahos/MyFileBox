package com.yaoahos.myfilebox.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.yaoahos.myfilebox.android.data.AppIconSwitcher
import com.yaoahos.myfilebox.android.ui.AppViewModel
import com.yaoahos.myfilebox.android.ui.MyFileBoxApp

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 点传输通知进来：进行中 → 传输页；已完成 → 直接打开那个文件。
        consumeTransferIntent(intent)
        setContent {
            MyFileBoxApp(viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeTransferIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // 桌面启动图标换 alias 会重建当前 task，前台做会把界面踢回桌面。
        // 等 App 真正退到后台再切换，用户按 Home 时刚好看到新图标。
        AppIconSwitcher.flush(this)
    }

    private fun consumeTransferIntent(intent: Intent?) {
        val taskId = intent?.getStringExtra(TransferNotifications.EXTRA_OPEN_TASK) ?: return
        intent.removeExtra(TransferNotifications.EXTRA_OPEN_TASK)
        viewModel.handleTransferNotification(taskId)
    }
}
