package com.yaoahos.myfilebox.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 应用图标的柔化染色。
 *
 * 主题色直接铺满图标底色时，亮黄、品红、荧光绿会刺眼，白色文件夹也会被
 * 高亮背景吃掉。这里按「保色相、提饱和、按感知亮度收敛」处理：
 *
 * - 色相不动，用户选紫还是能看出是紫；
 * - 饱和度乘 0.78 后夹到 0.30~0.68（旧版 0.16~0.42 太灰），近灰色保持低饱和；
 * - 明度从 0.60 起步，只有白色文件夹对比不足 3.2:1 时才逐步下调：
 *   黄/绿/青天生亮，会自动压到 0.36~0.41；蓝/紫留在 0.60；
 * - 不再混深色石板蓝。旧版混 12% #40506B 后蓝紫档与白字的对比高达
 *   6~7:1，比原始图标底色 #1B6FD2（4.94:1）还暗，正是「深沉暗淡」的根因。
 *
 * 各色相档的白色前景对比因此落在 3.2~4.6:1：比旧版明快，又不比原始
 * 蓝底更刺眼。纯 Kotlin 实现（不碰 android.graphics / androidx），既能给
 * UI 用，也能在 JVM 单测里直接断言。桌面启动图标无法运行时上色，因此另有
 * [bucket] 把主题色归到有限色相档，对应 res/values/colors.xml 的静态资源。
 */
object IconTint {
    /** 8 个色相档（每 45°）+ 1 个近灰中性档。 */
    const val BUCKET_COUNT = 9
    const val NEUTRAL_BUCKET = 8

    private const val SLATE = 0xFF64748B.toInt()       // 近灰主题色的中性档种子
    private const val NEUTRAL_THRESHOLD = 0.20f

    private const val SATURATION_SCALE = 0.78f
    private const val SATURATION_MIN = 0.30f
    private const val SATURATION_MAX = 0.68f
    private const val NEUTRAL_SATURATION_SCALE = 0.85f

    private const val LIGHTNESS_START = 0.60f
    private const val LIGHTNESS_STEP = 0.015f
    private const val LIGHTNESS_STEPS = 24
    private const val MIN_WHITE_CONTRAST = 3.2

    /** 把任意主题色转成适合做图标底色的明快色。 */
    fun soft(argb: Int): Int {
        val (hue, saturation, _) = rgbToHsl(argb)
        val softenedSaturation = if (saturation < NEUTRAL_THRESHOLD) {
            // 中性色不做强行上色，否则「灰」会变成一档明显的蓝灰。
            saturation * NEUTRAL_SATURATION_SCALE
        } else {
            (saturation * SATURATION_SCALE).coerceIn(SATURATION_MIN, SATURATION_MAX)
        }
        var lightness = LIGHTNESS_START
        var candidate = hslToRgb(hue, softenedSaturation, lightness)
        repeat(LIGHTNESS_STEPS) {
            if (whiteContrast(candidate) >= MIN_WHITE_CONTRAST) return candidate
            lightness -= LIGHTNESS_STEP
            candidate = hslToRgb(hue, softenedSaturation, lightness)
        }
        return candidate
    }

    /**
     * 桌面色档索引：色相 0° 起每 45° 一档，低饱和的近灰色走中性档。
     * 索引 N 对应资源 `ic_launcher_tint_N` / alias `IconTintN`。
     */
    fun bucket(argb: Int): Int {
        val (hue, saturation, _) = rgbToHsl(argb)
        // 0.20 要能盖住中性档种子色 #64748B（饱和度约 0.16）与预置的石板 #475569
        // （约 0.19），否则「中性档」自己会被分去蓝档，与应用内染色结果对不上。
        if (saturation < NEUTRAL_THRESHOLD) return NEUTRAL_BUCKET
        return (((hue + 22.5f) / 45f).toInt()) % (BUCKET_COUNT - 1)
    }

    /**
     * 桌面静态资源使用的固定色值，与 `colors.xml` 的 `icon_tint_0..8` 一一对应。
     * 生成资源时以此为准，避免手写十六进制与运行时算法慢慢走偏。
     */
    val buckets: IntArray = IntArray(BUCKET_COUNT) { index ->
        if (index == NEUTRAL_BUCKET) soft(SLATE)
        else soft(hslToRgb(index * 45f, 1f, 0.5f))
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

    private fun whiteContrast(argb: Int): Double = 1.05 / (relativeLuminance(argb) + 0.05)
}
