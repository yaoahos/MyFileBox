package com.yaoahos.myfilebox.android.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import com.yaoahos.myfilebox.android.ui.theme.WallpaperColorBus
import com.yaoahos.myfilebox.android.ui.theme.seedToPrimary
import com.yaoahos.myfilebox.android.ui.theme.wallpaperSeedColor
import com.yaoahos.myfilebox.core.IconTint

private const val TAG = "MFB-ICON"

/**
 * 只有「目标确实启用」且「所有旧入口都禁用成功」才算这次切换完成。
 *
 * 以前无论成败都写入已应用档位，而 [AppIconSwitcher.request] 见到目标等于已应用
 * 档位就不再登记待办——一次失败会让桌面图标永久停在旧档，再也不会重试。
 * 任一失败都必须保留待办，下次退到后台重跑一遍。
 */
internal fun shouldMarkAliasApplied(targetEnabled: Boolean, disableFailures: Int): Boolean =
    targetEnabled && disableFailures == 0

/**
 * 桌面启动图标的主题色切换。
 *
 * Android 的 launcher icon 是清单里的静态资源，无法在运行时染成用户输入的
 * 任意 #RRGGBB。可行做法是预置按色相分档的图标（[IconTint.bucket]），
 * 每个档位对应一个 activity-alias，再用 PackageManager 只启用其中一个。
 *
 * 两条约束：
 * 1. **只在 App 退到后台时真正切换**。setComponentEnabledSetting 会重建当前
 *    task，实测 MIUI 上「在设置页拨一下开关」会直接把界面踢回桌面、连页面栈
 *    都丢掉。所以前台只登记目标（[request]），等 onStop 再执行（[flush]）；
 *    用户按 Home 回桌面时看到的刚好就是新图标。顺带把多次改色合并成最后一次。
 * 2. 先启用目标组件、再禁用其它组件。反过来会有一瞬间所有 LAUNCHER 入口
 *    都是 disabled，部分桌面会直接把图标从桌面移除。
 *
 * 切换启动组件本身会让部分 ROM 重排桌面图标、使旧快捷方式失效，
 * 这是系统行为，设置页里已明确告知用户。MainActivity 始终 enabled，
 * 只有 alias 参与切换，因此 targetActivity 不存在被禁掉而起不来的风险。
 */
object AppIconSwitcher {
    private const val PREFS_NAME = "myfilebox_prefs"
    private const val KEY_APPLIED_ALIAS = "app_icon_alias"
    private const val KEY_PENDING_ALIAS = "app_icon_alias_pending"
    private const val KEY_ICON_FOLLOWS = "theme_icon_follows"
    private const val KEY_DYNAMIC = "theme_dynamic"
    private const val CLASS_PREFIX = "com.yaoahos.myfilebox.android.Icon"
    private const val ORIGINAL = CLASS_PREFIX + "Original"

    private val TINTS = (0 until IconTint.BUCKET_COUNT).map { CLASS_PREFIX + "Tint$it" }
    private val ALL = listOf(ORIGINAL) + TINTS

    /**
     * 登记这次应该使用的启动组件，不立即动系统组件状态。
     *
     * @param primary 当前真正在用的主色（动态取色时来自壁纸，不是 prefs.accent）
     */
    fun request(context: Context, followsTheme: Boolean, primary: Int) {
        val target = if (!followsTheme) ORIGINAL else TINTS[IconTint.bucket(primary)]
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, 0)
        val applied = prefs.getString(KEY_APPLIED_ALIAS, null)
        val editor = prefs.edit()
        if (target == applied) editor.remove(KEY_PENDING_ALIAS)
        else editor.putString(KEY_PENDING_ALIAS, target)
        editor.apply()
    }

    /**
     * 壁纸色变化：动态取色下桌面图标要跟着换档。
     *
     * 这条路径不能只依赖 AppRoot 的 syncAppIcon——那是 Compose 在前台算出主色后
     * 才登记的，用户去系统设置换完壁纸直接回桌面时 App 根本没 resume，pending
     * 永远停在旧档。这里由 Application 的 OnColorsChangedListener 直接调用，
     * 后台就立刻切组件，前台则只登记、等 onStop 再切。
     */
    fun onWallpaperChanged(context: Context, inForeground: Boolean) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, 0)
        if (!prefs.getBoolean(KEY_ICON_FOLLOWS, false)) return
        if (!prefs.getBoolean(KEY_DYNAMIC, false)) return
        val seed = WallpaperColorBus.seed.value ?: wallpaperSeedColor(app) ?: return
        // seedToPrimary 的 dark 只改明度、不改色相，分档结果不受深浅模式影响。
        val primary = seedToPrimary(seed, false).toArgb()
        val bucket = IconTint.bucket(primary)
        Log.d(TAG, "壁纸色变化 -> 桌面图标档位 $bucket / ${TINTS[bucket]}")
        request(app, true, primary)
        if (!inForeground) flush(app)
    }

    /** 由 MainActivity.onStop 调用：此时界面已不在前台，切换组件不会打断用户。 */
    fun flush(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, 0)
        val target = prefs.getString(KEY_PENDING_ALIAS, null) ?: return
        if (prefs.getString(KEY_APPLIED_ALIAS, null) == target) {
            prefs.edit().remove(KEY_PENDING_ALIAS).apply()
            return
        }

        val pm = app.packageManager
        val targetComponent = ComponentName(app, target)
        // 先开新的，再关旧的：中间不会出现「一个 LAUNCHER 入口都没有」的窗口。
        val targetEnabled = runCatching {
            pm.setComponentEnabledSetting(
                targetComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            // 部分 ROM 不抛异常但静默拒绝；回读实际状态才算数。
            pm.getComponentEnabledSetting(targetComponent) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }.getOrDefault(false)
        if (!targetEnabled) {
            Log.w(TAG, "启用 $target 失败，保留待切换档位等待下次重试")
            return
        }
        var disableFailures = 0
        ALL.filter { it != target }.forEach { name ->
            val ok = runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(app, name),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }.isSuccess
            if (!ok) {
                disableFailures++
                Log.w(TAG, "禁用 $name 失败")
            }
        }
        if (!shouldMarkAliasApplied(targetEnabled, disableFailures)) {
            // 不写已应用、也不清待办：下次退到后台会重跑，把没关掉的旧入口补齐。
            // 目标已启用，此时至少有一个可用入口，桌面不会丢图标。
            Log.w(TAG, "有 $disableFailures 个旧入口未禁用，保留待切换档位等待重试")
            return
        }
        prefs.edit()
            .putString(KEY_APPLIED_ALIAS, target)
            .remove(KEY_PENDING_ALIAS)
            .apply()
    }
}
