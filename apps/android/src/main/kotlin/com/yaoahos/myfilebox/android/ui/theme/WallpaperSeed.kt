package com.yaoahos.myfilebox.android.ui.theme

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.yaoahos.myfilebox.core.IconTint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.pow

private const val TAG = "MFB-THEME"

/**
 * 动态取色开关、壁纸色读取、壁纸色监听共用的最低系统版本：Android 12（S / 31）。
 *
 * 以前这里按 34 分段，理由是「getPrimaryColor() 在 31–33 返回 int」——核实后
 * 该前提不成立：AOSP android-13.0.0_r1 是 `public @NonNull Color getPrimaryColor()`，
 * 官方文档标注 Added in API level 27，本项目 release 字节码的调用描述符也是
 * `()Landroid/graphics/Color;`，在 12/13 上能正确解析。getWallpaperColors(FLAG_SYSTEM)
 * 与 addOnColorsChangedListener 同为 27+，Color.toArgb() 是 26+（minSdk 26）。
 *
 * 门槛只在这里定义一次，Theme / 外观页 / Application 都通过 [wallpaperColorSupported]
 * 判断，避免再出现「开关 31+ 可用、取色却要 34+」这类双重口径。
 */
internal const val MIN_WALLPAPER_SDK = 31

internal fun wallpaperColorSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_WALLPAPER_SDK

/** 只响应桌面壁纸（FLAG_SYSTEM）变化；单独换锁屏壁纸不该改主色和桌面图标。 */
internal fun isSystemWallpaperChange(which: Int): Boolean =
    which and WallpaperManager.FLAG_SYSTEM != 0

/**
 * 壁纸色变化的应用内广播。
 *
 * 之前只在 Activity 回到前台时用 LifecycleResumeEffect 重读壁纸色：用户去系统
 * 设置换完壁纸直接回桌面，App 根本没 resume，主色和桌面启动图标都还是旧的；
 * 即便再进 App，桌面 alias 也要等下一次退到后台才切换。这里由 Application 注册
 * WallpaperManager.OnColorsChangedListener，回调一到就同时喂给主题层和桌面图标
 * 切换器，前后台都能立刻重算。
 */
internal object WallpaperColorBus {
    private val _seed = MutableStateFlow<Color?>(null)
    val seed: StateFlow<Color?> = _seed

    /** 优先用回调带来的颜色：部分 ROM 触发回调时 getWallpaperColors 缓存还没刷新。 */
    fun publish(context: Context, colors: WallpaperColors?) {
        val argb = colors?.let { runCatching { it.primaryColor.toArgb() }.getOrNull() }
        val next = if (argb != null) Color(argb) else wallpaperSeedColor(context)
        _seed.value = next
        Log.d(TAG, "壁纸色变化 #%06X".format((next?.toArgb() ?: 0) and 0xFFFFFF))
    }

    fun refresh(context: Context) {
        _seed.value = wallpaperSeedColor(context)
    }
}

/**
 * 取系统对当前壁纸的取色结果，作为动态配色的种子。
 *
 * 为什么不用 `dynamicLightColorScheme()`：它读的是 SystemUI 的
 * `com.android.systemui:accent` overlay。实测 HyperOS 3 上系统已经把壁纸算成
 * 蓝色（WallpaperColors = #3E5DA8），但 overlay 一直停在旧的绿色（#196B52），
 * 于是 App 的主色与壁纸完全无关，看起来像「动态取色坏了」。
 *
 * `WallpaperManager.getWallpaperColors()` 拿到的是系统对壁纸的真实提取结果，
 * 不经过 overlay，因此绕开了这个卡住的环节。读不到时返回 null，由调用方回落
 * 到系统方案（12 以下 / ROM 未缓存 / 动态壁纸未提供颜色 / 权限异常）。
 */
internal fun wallpaperSeedColor(context: Context): Color? {
    // 见 MIN_WALLPAPER_SDK：壁纸相关 API 从 27 起签名稳定，12/13 同样可用，
    // 不再按 34 分段。返回值可能为空（动态壁纸未实现 onComputeColors、系统仍在
    // 计算、IPC 异常），调用方已有回落到系统配色的兜底。
    if (!wallpaperColorSupported()) return null
    return try {
        val colors = WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        if (colors == null) {
            Log.d(TAG, "壁纸色缓存为空，回落系统方案")
            null
        } else {
            val argb = colors.primaryColor.toArgb()
            Log.d(TAG, "壁纸种子色 #%06X".format(argb and 0xFFFFFF))
            Color(argb)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读取壁纸色失败：${t.javaClass.simpleName}: ${t.message}")
        null
    }
}

/**
 * 把种子色规整成能当 primary 用的色调：色相不动，饱和度略收，明度压到与白字
 * （浅色）或深底（深色）保持 4.5:1 对比的位置。
 *
 * 只做「主色」这一层的换算，是为了让 surface / 容器层级继续沿用 Theme.kt 里
 * 既有的中性色——那里有明确约定：弹窗和菜单不能跟着主题色染色，否则会发灰发紫。
 * 若整套照搬 M3 生成的 surface，就会破坏这条约定。
 *
 * HSL 换算与对比度都用纯 Kotlin：androidx ColorUtils 的 HSLToColor 内部走
 * android.graphics.Color，在 JVM 单测里是恒返回 0 的桩方法，那样这条链路
 * （壁纸色 → 主色 → 桌面图标档位）就没法纳入回归。
 */
internal fun seedToPrimary(seed: Color, dark: Boolean): Color {
    val (hue, sourceSaturation, _) = hslOf(seed.toArgb())
    // 近乎灰的壁纸不做强着色，否则会得到一片浑浊的蓝灰。
    val saturation = if (sourceSaturation < 0.10f) {
        sourceSaturation
    } else {
        (sourceSaturation * 0.92f).coerceIn(0.22f, 0.68f)
    }
    var lightness = if (dark) 0.70f else 0.42f
    val step = if (dark) 0.025f else -0.025f
    var candidate = IconTint.hslToRgb(hue, saturation, lightness)
    val backdrop = if (dark) DarkBackground.toArgb() else Color.White.toArgb()
    repeat(24) {
        if (contrast(backdrop, candidate) >= 4.5) return Color(candidate)
        lightness = (lightness + step).coerceIn(0.06f, 0.96f)
        candidate = IconTint.hslToRgb(hue, saturation, lightness)
    }
    return Color(candidate)
}

private fun hslOf(argb: Int): Triple<Float, Float, Float> {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val lightness = (max + min) / 2f
    if (delta == 0f) return Triple(0f, 0f, lightness)
    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val raw = when (max) {
        r -> ((g - b) / delta) % 6f
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    }
    var hue = raw * 60f
    if (hue < 0f) hue += 360f
    return Triple(hue, saturation, lightness)
}

private fun contrast(a: Int, b: Int): Double {
    fun luminance(argb: Int): Double {
        fun linear(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(argb shr 16 and 0xFF) +
            0.7152 * linear(argb shr 8 and 0xFF) +
            0.0722 * linear(argb and 0xFF)
    }
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
