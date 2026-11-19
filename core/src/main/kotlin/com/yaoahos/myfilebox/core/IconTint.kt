package com.yaoahos.myfilebox.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 应用图标的主题色染色与桌面分档。
 *
 * [themeTone] 是唯一的染色口径，与界面主色 seedToPrimary 完全同源：
 * 色相不动（用户选紫还是能看出是紫），饱和度 ×0.92 夹到 0.22~0.68，
 * 明度收敛到与白底（浅色）/ 深底（深色）4.5:1 的位置。这样桌面图标底色
 * 与界面主色是同一个色系，不会再出现「主题深绿、图标亮绿」的偏差。
 *
 * 早先桌面档位另走一套 soft()（饱和 ×0.78、明度锚 0.60），与界面主色口径
 * 不同，同一个主题在两处看着不是一个色系；现已统一到 [themeTone]。
 *
 * 桌面启动图标无法在运行时上色，因此另有 [bucket] 把主题色归到有限色相档，
 * 对应 res/values/colors.xml 的静态资源；应用内图标直接复用同一档位色，
 * 保证预览、桌面、关于页三处颜色一致。纯 Kotlin 实现（不碰 android.graphics /
 * androidx），既能给 UI 用，也能在 JVM 单测里直接断言。
 */
object IconTint {
    /** 16 个色相档（每 22.5°）+ 1 个近灰中性档。 */
    const val BUCKET_COUNT = 17
    const val NEUTRAL_BUCKET = 16

    private const val SLATE = 0xFF64748B.toInt()       // 近灰主题色的中性档种子
    private const val NEUTRAL_THRESHOLD = 0.20f

    /**
     * 桌面色档索引：色相 0° 起每 22.5° 一档，低饱和的近灰色走中性档。
     * 索引 N 对应资源 `ic_launcher_tint_N` / alias `IconTintN`。
     *
     * 8 档（45°）时最大色相误差 22.5°，蓝紫交界处肉眼就能看出图标和界面主色
     * 不是一回事；加密到 16 档后误差减半到 11.25°。
     */
    fun bucket(argb: Int): Int {
        val (hue, saturation, _) = rgbToHsl(argb)
        // 0.20 要能盖住中性档种子色 #64748B（饱和度约 0.16）与预置的石板 #475569
        // （约 0.19），否则「中性档」自己会被分去蓝档，与应用内染色结果对不上。
        if (saturation < NEUTRAL_THRESHOLD) return NEUTRAL_BUCKET
        val step = 360f / (BUCKET_COUNT - 1)  // 22.5°
        return (((hue + step / 2f) / step).toInt()) % (BUCKET_COUNT - 1)
    }

    /**
     * 主题色口径的染色：与界面主色 seedToPrimary 完全同一套规则——
     * 色相不动，饱和度 ×0.92 夹到 0.22~0.68，明度从 0.42 起每步 -0.025，
     * 直到对白底达到 4.5:1。中性色（饱和 <0.10）保留原低饱和。
     *
     * 桌面图标底色必须和界面主色同色系：早先用 soft() 生成档位色，饱和 ×0.78、
     * 明度锚 0.60，结果同一个绿主题（如 #1F6B00）界面是深绿、图标却是亮绿，
     * 看着「不是一个色系」。改用本函数后，档位色与主色同源，只剩色相分档
     * 带来的最多 11.25° 误差，不再有明度/饱和口径的分歧。
     */
    fun themeTone(argb: Int, dark: Boolean = false): Int {
        val (hue, saturation, _) = rgbToHsl(argb)
        // 阈值与 [bucket] 的中性判定保持一致：近灰不走强着色，否则壁纸近灰时
        // 界面会变浑浊蓝灰，中性档自己也会被上成有色档。
        val sat = if (saturation < NEUTRAL_THRESHOLD) {
            saturation
        } else {
            (saturation * 0.92f).coerceIn(0.22f, 0.68f)
        }
        var lightness = if (dark) 0.70f else 0.42f
        val step = if (dark) 0.025f else -0.025f
        val backdrop = if (dark) 0xFF0B1220.toInt() else 0xFFFFFFFF.toInt()
        var candidate = hslToRgb(hue, sat, lightness)
        repeat(24) {
            if (contrastWith(backdrop, candidate) >= 4.5) return candidate
            lightness = (lightness + step).coerceIn(0.06f, 0.96f)
            candidate = hslToRgb(hue, sat, lightness)
        }
        return candidate
    }

    /** 两色的 WCAG 对比度（1:1 ~ 21:1）。公开供主题层与单测复用。 */
    fun contrastWith(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * 桌面静态资源使用的固定色值，与 `colors.xml` 的 `icon_tint_0..16` 一一对应。
     * 生成资源时以此为准，避免手写十六进制与运行时算法慢慢走偏。
     *
     * 用 [themeTone] 而非 soft()：桌面图标底色要和界面主色同色系。
     */
    val buckets: IntArray = IntArray(BUCKET_COUNT) { index ->
        if (index == NEUTRAL_BUCKET) themeTone(SLATE)
        else themeTone(hslToRgb(index * (360f / (BUCKET_COUNT - 1)), 1f, 0.5f))
    }

    /** HSL → 0xAARRGGBB。色相单位为度，允许超出 [0,360)。公开供桌面色档生成与单测复用。 */
    fun hslToRgb(hue: Float, saturation: Float, lightness: Float): Int {
        val chroma = (1f - abs(2f * lightness - 1f)) * saturation
        val sector = (((hue % 360f) + 360f) % 360f) / 60f
        val x = chroma * (1f - abs(sector % 2f - 1f))
        val (r1, g1, b1) = when (sector.toInt()) {
            0 -> Triple(chroma, x, 0f)
            1 -> Triple(x, chroma, 0f)
            2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma)
            4 -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val m = lightness - chroma / 2f
        val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    /** 0xAARRGGBB → HSL，色相单位为度。 */
    private fun rgbToHsl(argb: Int): Triple<Float, Float, Float> {
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

    /** sRGB 相对亮度，用于判断浅蓝白文件夹在底色上还剩多少对比。 */
    private fun relativeLuminance(argb: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(argb shr 16 and 0xFF) +
            0.7152 * linear(argb shr 8 and 0xFF) +
            0.0722 * linear(argb and 0xFF)
    }

}
