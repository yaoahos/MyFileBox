package com.yaoahos.myfilebox.android

import android.app.Activity
import android.app.Application
import android.app.WallpaperManager
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.yaoahos.myfilebox.android.data.AppIconSwitcher
import com.yaoahos.myfilebox.android.ui.theme.WallpaperColorBus
import com.yaoahos.myfilebox.android.ui.theme.isSystemWallpaperChange
import com.yaoahos.myfilebox.android.ui.theme.wallpaperColorSupported
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把 WorkManager 的初始化从启动路径里摘出来。
 *
 * manifest 里移除了 `WorkManagerInitializer`（它会在 ContentProvider 阶段打开
 * Room 数据库并重排所有任务，冷启动实测占掉可观的固定开销），改为由本类提供
 * [androidx.work.Configuration]。这样 `WorkManager.getInstance()` 第一次被调用时
 * 才真正初始化——目前只有「自动同步」用到它，而那条路径本来就在后台协程里。
 *
 * 注意：必须实现 Provider，否则移除 initializer 后 `getInstance()` 会抛
 * IllegalStateException，自动同步会静默失效。
 */
class MyFileBoxApplication : Application(), androidx.work.Configuration.Provider {

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(Log.WARN)
            .build()

    companion object {
        private const val TAG = "MFB-THEME"

        @Volatile
        private var startedActivities = 0

        /**
         * 桌面 alias 只能在后台切，前台切换会把当前页面踢回桌面。
         * 用 started/stopped 而非 resumed/paused：分屏、对话框样式 Activity 等场景里
         * pause 可以单独发生，而 started 始终对应「有界面可见」，计数不会残留成
         * 永远的前台（那样壁纸变化就只登记不切 alias，回到老问题）。
         */
        fun isInForeground(): Boolean = startedActivities > 0
    }

    override fun onCreate() {
        super.onCreate()
        // 流代理需要 Context 启停保活前台服务（MIUI 后台网络限制）。
        com.yaoahos.myfilebox.android.data.StreamProxy.attach(this)
        // 失败提示要能区分「手机没网」和「地址填错」：这两者的底层异常完全一样，
        // 只能另外问系统当前联网状态。集中在这里装一次，各调用点不必层层传参。
        com.yaoahos.myfilebox.android.data.NetStatus.attach(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        attachWallpaperWatcher()
        // 上次改色后若进程被回收或用户强制停止，待切换的档位还留在偏好里。
        // 此刻没有任何 Activity 可见，切 alias 不会打断界面；pending 为空时直接返回。
        AppIconSwitcher.flush(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrashLog(error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * 换壁纸时系统会把新的 WallpaperColors 推给已注册的监听器。这里同时更新
     * 应用内主题（前台也生效）和桌面启动图标（后台直接切 alias）。
     * 门槛与动态取色共用 MIN_WALLPAPER_SDK（Android 12 / 31）；回调的 which 必须
     * 包含 FLAG_SYSTEM，否则只换锁屏壁纸也会把主色和桌面图标带偏。
     */
    private fun attachWallpaperWatcher() {
        if (!wallpaperColorSupported()) return
        val app = this
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (!isSystemWallpaperChange(which)) {
                Log.d(TAG, "忽略非桌面壁纸的颜色变化，which=$which")
                return@OnColorsChangedListener
            }
            WallpaperColorBus.publish(app, colors)
            AppIconSwitcher.onWallpaperChanged(app, isInForeground())
        }
        runCatching {
            WallpaperManager.getInstance(app)
                .addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            Log.d(TAG, "壁纸色监听已注册")
        }.onFailure {
            Log.w(TAG, "壁纸色监听注册失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun writeCrashLog(error: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val text = buildString {
            appendLine("MyFileBox crash $stamp")
            appendLine("version: ${BuildConfig.VERSION_NAME}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(Log.getStackTraceString(error))
        }
        Log.e("MyFileBox", text)
        runCatching {
            File(getExternalFilesDir(null), "crash-$stamp.txt").writeText(text)
        }
        // 堆栈里可能带服务器地址/路径，只留应用私有目录，不再写公共 Downloads。
    }
}
