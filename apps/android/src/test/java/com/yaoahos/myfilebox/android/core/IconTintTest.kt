package com.yaoahos.myfilebox.android.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.yaoahos.myfilebox.android.data.shouldMarkAliasApplied
import com.yaoahos.myfilebox.android.ui.theme.MIN_WALLPAPER_SDK
import com.yaoahos.myfilebox.android.ui.theme.isSystemWallpaperChange
import com.yaoahos.myfilebox.android.ui.theme.seedToPrimary
import com.yaoahos.myfilebox.core.IconTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class IconTintTest {

    private fun hueOf(argb: Int): Float = hsl(argb).first
    private fun saturationOf(argb: Int): Float = hsl(argb).second
    private fun lightnessOf(argb: Int): Float = hsl(argb).third

    private fun hsl(argb: Int): Triple<Float, Float, Float> {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val lightness = (max + min) / 2f
        if (delta == 0f) return Triple(0f, 0f, lightness)
        val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
        val raw = when (max) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        var hue = raw * 60f
        if (hue < 0f) hue += 360f
        return Triple(hue, saturation, lightness)
    }

    private fun contrastWithWhite(argb: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        fun luminance(color: Int): Double =
            0.2126 * channel(color shr 16 and 0xFF) +
                0.7152 * channel(color shr 8 and 0xFF) +
                0.0722 * channel(color and 0xFF)
        val a = luminance(argb)
        val b = luminance(0xFFFFFFFF.toInt())
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test
    fun `柔化保留主题色的色相`() {
        val brandBlue = 0xFF1F6BFF.toInt()
        val purple = 0xFFA855F7.toInt()
        val orange = 0xFFF97316.toInt()
        listOf(brandBlue, purple, orange).forEach { source ->
            val softened = IconTint.soft(source)
            val delta = kotlin.math.abs(hueOf(softened) - hueOf(source))
            assertTrue("色相不应明显偏移：$source", min(delta, 360f - delta) < 12f)
        }
    }

    @Test
    fun `高饱和亮色收敛成明快但不刺眼的底色`() {
        val loud = listOf(
            0xFFFFFF00.toInt(), // 亮黄
            0xFFD946EF.toInt(), // 品红
            0xFF22C55E.toInt(), // 绿
            0xFF1F6BFF.toInt()  // 品牌蓝
        )
        loud.forEach { source ->
            val softened = IconTint.soft(source)
            assertTrue(
                "饱和度过高：#%06X -> #%06X".format(source and 0xFFFFFF, softened and 0xFFFFFF),
                saturationOf(softened) <= 0.70f
            )
            assertTrue(
                "明度超出可用带：#%06X".format(source and 0xFFFFFF),
                lightnessOf(softened) in 0.30f..0.62f
            )
            assertTrue(
                "白色文件夹对比不足：#%06X".format(softened and 0xFFFFFF),
                contrastWithWhite(softened) >= 3.2
            )
        }
    }

    @Test
    fun `底色明快且白色文件夹仍清晰`() {
        IconTint.buckets.forEach { color ->
            val contrast = contrastWithWhite(color)
            // 上限防止调回旧版暗调：旧蓝/紫档分别达到 6.46 / 6.92，比原始
            // 图标底色 #1B6FD2（4.94）还暗，用户反馈「深沉暗淡」。
            assertTrue(
                "底色过暗：#%06X 与白色对比 %.2f".format(color and 0xFFFFFF, contrast),
                contrast <= 4.6
            )
            assertTrue(
                "底色与白色对比不足：#%06X 对比 %.2f".format(color and 0xFFFFFF, contrast),
                contrast >= 3.0
            )
        }
    }

    @Test
    fun `色相按 45 度分档`() {
        assertEquals(0, IconTint.bucket(0xFFFF0000.toInt()))   // 红
        assertEquals(3, IconTint.bucket(0xFF00FF00.toInt()))   // 绿 120°
        assertEquals(5, IconTint.bucket(0xFF0000FF.toInt()))   // 蓝 240°
        assertEquals(7, IconTint.bucket(0xFF800080.toInt()))   // 紫 300°
        assertEquals(5, IconTint.bucket(0xFF1F6BFF.toInt()))   // 品牌蓝
    }

    @Test
    fun `近灰主题色走中性档`() {
        assertEquals(IconTint.NEUTRAL_BUCKET, IconTint.bucket(0xFF808080.toInt()))
        assertEquals(IconTint.NEUTRAL_BUCKET, IconTint.bucket(0xFF475569.toInt()))
        // 中性档的种子色自己必须落回中性档，否则桌面档位会自相矛盾。
        assertEquals(IconTint.NEUTRAL_BUCKET, IconTint.bucket(0xFF64748B.toInt()))
        assertTrue(
            "中性档不应被强行上色",
            saturationOf(IconTint.buckets[IconTint.NEUTRAL_BUCKET]) < 0.20f
        )
    }

    @Test
    fun `桌面色档与柔化算法一致且索引在范围内`() {
        assertEquals(IconTint.BUCKET_COUNT, IconTint.buckets.size)
        repeat(8) { index ->
            val color = IconTint.buckets[index]
            val expected = IconTint.soft(IconTint.hslToRgb(index * 45f, 1f, 0.5f))
            assertEquals("第 $index 档应与 soft() 一致", expected, color)
            assertTrue("色档索引越界", index in 0 until IconTint.BUCKET_COUNT)
        }
        assertEquals(
            IconTint.soft(0xFF64748B.toInt()),
            IconTint.buckets[IconTint.NEUTRAL_BUCKET]
        )
    }

    @Test
    fun `动态取色的壁纸主色保留色相`() {
        listOf(
            0xFF53A4D9.toInt(), // K20Pro 实测的浅蓝壁纸
            0xFFE0683C.toInt(), // 橙
            0xFF3FA67B.toInt(), // 绿
            0xFF7B5AD8.toInt()  // 紫
        ).forEach { raw ->
            val primary = seedToPrimary(Color(raw), false).toArgb()
            val delta = kotlin.math.abs(hueOf(primary) - hueOf(raw))
            assertTrue(
                "壁纸主色色相被改掉：#%06X -> #%06X".format(
                    raw and 0xFFFFFF, primary and 0xFFFFFF
                ),
                min(delta, 360f - delta) < 3f
            )
        }
    }

    @Test
    fun `换壁纸会切到不同的桌面图标档位`() {
        // 动态取色下桌面图标走 seedToPrimary -> bucket，若这条链退化成固定档，
        // 用户换壁纸就会看到「图标颜色不会变」。
        val buckets = listOf(
            0xFFE0683C, 0xFFE8C13B, 0xFF3FA67B,
            0xFF53A4D9, 0xFF7B5AD8, 0xFFD85A9C
        ).map { raw ->
            IconTint.bucket(seedToPrimary(Color(raw.toInt()), false).toArgb())
        }
        assertTrue(
            "不同壁纸色相应落在不同色档，实际：$buckets",
            buckets.distinct().size >= 5
        )
    }

    @Test
    fun `动态取色门槛与壁纸 API 起点一致且只有一处定义`() {
        // Android 12 / S = 31。壁纸相关 API（getWallpaperColors、颜色监听、
        // Color getPrimaryColor）从 27 起就稳定；若有人把门槛改回 34，
        // 12/13 又会落回「开关开着但走系统 overlay」的旧问题。
        assertEquals(31, MIN_WALLPAPER_SDK)
        assertTrue("门槛不能高于 34", MIN_WALLPAPER_SDK <= 34)
        assertTrue("门槛不能低于壁纸 API 起点 27", MIN_WALLPAPER_SDK >= 27)
    }

    @Test
    fun `只响应桌面壁纸的颜色变化`() {
        assertTrue("仅桌面壁纸", isSystemWallpaperChange(1))   // FLAG_SYSTEM
        assertFalse("仅锁屏壁纸", isSystemWallpaperChange(2))  // FLAG_LOCK
        assertTrue("两者同时变化", isSystemWallpaperChange(3))
        assertFalse("无标志位", isSystemWallpaperChange(0))
    }

    @Test
    fun `桌面档位只有切换全部成功才算应用完成`() {
        assertTrue("目标启用且旧入口全禁用", shouldMarkAliasApplied(true, 0))
        assertFalse("目标启用失败要保留待办", shouldMarkAliasApplied(false, 0))
        assertFalse("有旧入口没关掉要保留待办", shouldMarkAliasApplied(true, 1))
        assertFalse("两者都失败更要保留待办", shouldMarkAliasApplied(false, 3))
    }

    @Test
    fun `桌面资源色值与柔化算法一致`() {
        val cwd = System.getProperty("user.dir")
        val file = listOf(
            File("src/main/res/values/colors.xml"),
            File("apps/android/src/main/res/values/colors.xml")
        ).firstOrNull { it.isFile } ?: error("找不到 colors.xml，当前目录：$cwd")
        val text = file.readText()
        IconTint.buckets.forEachIndexed { index, color ->
            val expected = "#FF%06X".format(color and 0xFFFFFF)
            assertTrue(
                "第 $index 档资源应为 $expected（${file.absolutePath}）",
                text.contains("<color name=\"icon_tint_$index\">$expected</color>")
            )
        }
    }
}
