package com.yaoahos.myfilebox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.android.data.accentToHex
import com.yaoahos.myfilebox.android.data.parseAccentHex
import com.yaoahos.myfilebox.android.ui.theme.DynamicAccentState
import com.yaoahos.myfilebox.android.ui.theme.LocalDynamicAccentState
import com.yaoahos.myfilebox.android.ui.theme.wallpaperColorSupported
import com.yaoahos.myfilebox.core.ThemeMode
import java.util.Locale

/**
 * 20 个预置主题色。
 *
 * 按色相环绕一圈（蓝 → 青 → 绿 → 黄 → 橙 → 红 → 粉 → 紫 → 靛），每种再给一个
 * 明度差一档的邻居，保证相邻两格一眼能分辨，也不至于只有「蓝的黑的红的」三档。
 * 都是 Tailwind 500/600 级的中明度色，配白字或深字都够对比度——
 * 具体用哪个由 Theme.kt 的 readableOn() 按亮度自动判断。
 */
internal val AccentPresets = listOf(
    0xFF1F6BFF, // 品牌蓝
    0xFF2563EB, // 深蓝
    0xFF0EA5E9, // 天蓝
    0xFF06B6D4, // 青
    0xFF0D9488, // 蓝绿
    0xFF10B981, // 翡翠
    0xFF22C55E, // 绿
    0xFF84CC16, // 青柠
    0xFFEAB308, // 黄
    0xFFF59E0B, // 琥珀
    0xFFF97316, // 橙
    0xFFEA580C, // 深橙
    0xFFEF4444, // 红
    0xFFE11D48, // 玫瑰
    0xFFEC4899, // 粉
    0xFFD946EF, // 品红
    0xFFA855F7, // 紫
    0xFF7C3AED, // 紫罗兰
    0xFF4F46E5, // 靛
    0xFF475569  // 石板
)

/** 动态取色失败时，回落的到底是预置色块还是自定义色值。 */
internal fun isPresetAccent(accent: Long): Boolean = accent in AccentPresets

/**
 * 色块统一用圆角方形，预置格和自定义颜色的预览块是同一种形状。
 *
 * 圆角按边长约 0.3 取：预览块 32dp → 10dp，预置格 44dp → 13dp。固定成同一个 dp
 * 会让大格看着偏方、小格偏圆，两者摆在同一页里对不上。
 */
private val AccentSwatchShape = RoundedCornerShape(10.dp)
private val AccentTileShape = RoundedCornerShape(13.dp)

/** 选中描边占掉 3~4dp 之后的内层圆角，保持内外圆角同心。 */
private val AccentTileInnerShape = RoundedCornerShape(10.dp)

/**
 * 设置 → 主题与外观（二级页）。
 *
 * 三块内容从上到下：外观模式、主题配色、自定义颜色。改动即时生效并立即落盘——
 * 主题在 AppRoot 顶层，改 prefs 会整体重组，用户点一下就能看到整套界面换色，
 * 不需要「保存」按钮，也不存在「改了没生效」。
 */
@Composable
fun AppearanceScreen(state: MyFileBoxState, vm: AppViewModel) {
    val theme = state.prefs.theme
    // 跟随当前主题色：选了预置色之后，输入框应该显示「现在的颜色」而不是进页面时的旧值，
    // 否则用户会看到「明明是紫色，输入框还写着蓝色」。用户自己输入时 theme.accent 不变，
    // 不会被这行打断。
    var customHex by remember(theme.accent) {
        mutableStateOf(accentToHex(theme.accent))
    }
    // 用户动过输入框没有。动过之后色块先跟输入走（见下面的 preview），
    // 否则动态取色开启时「边输边看」永远是壁纸色，等于没有预览。
    var customDirty by remember(theme.accent) { mutableStateOf(false) }
    var customError by remember { mutableStateOf<String?>(null) }
    // 12 以下没有系统取色接口，直接告诉用户为什么点不了，而不是给个灰色的谜语开关。
    // 门槛与 Theme / 壁纸监听共用 WallpaperSeed.kt 的 MIN_WALLPAPER_SDK，别再各写一份。
    val dynamicSupported = wallpaperColorSupported()
    val dynamicAccentState = LocalDynamicAccentState.current
    val followingWallpaper = theme.dynamic && dynamicSupported &&
        dynamicAccentState == DynamicAccentState.FOLLOWING
    // PENDING 还在读壁纸，不算「取不到」；只有明确 FALLBACK 才提示用户正在用哪种颜色。
    val dynamicFallback = theme.dynamic && dynamicSupported &&
        dynamicAccentState == DynamicAccentState.FALLBACK
    val fallbackOnPreset = isPresetAccent(theme.accent)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            // 底部有输入框：不加这个，键盘弹起会盖住正在编辑的格子。
            .imePadding()
    ) {
        SubPageHeader("主题与外观", vm::closeScreen)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            // ---------------------------------------------------------- 外观模式
            item {
                SettingsSection("外观模式") {
                    Column(Modifier.padding(12.dp)) {
                        Text("界面深浅", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        SegmentedThemeMode(
                            current = theme.mode,
                            onSelect = vm::setThemeMode,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                        Text(
                            "随系统深色模式自动切换。",
                            fontSize = 12.sp,
                            color = MutedText,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 9.dp)
                        )
                    }
                }
            }

            // ---------------------------------------------------------- 动态取色
            item {
                SettingsSection("动态取色") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = dynamicSupported) { vm.setDynamicColor(!theme.dynamic) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Colorize, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(Modifier.weight(1f).padding(start = 11.dp)) {
                            Text("跟随桌面壁纸", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (dynamicSupported) "取壁纸主色"
                                else "需 Android 12 及以上",
                                fontSize = 12.sp,
                                color = MutedText,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                        Switch(
                            checked = theme.dynamic && dynamicSupported,
                            onCheckedChange = { vm.setDynamicColor(it) },
                            enabled = dynamicSupported
                        )
                    }
                    Text(
                        "开启后主色随壁纸变化；系统未提供壁纸取色时，沿用下方自选配色。",
                        fontSize = 12.sp,
                        color = MutedText,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                            .padding(bottom = 8.dp)
                    )
                }
            }

            // ---------------------------------------------------------- 应用图标
            item {
                SettingsSection("应用图标") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.setIconFollowsTheme(!theme.iconFollowsTheme) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 拿应用内品牌图标本身当预览：拨一下开关就能看到底色换没换。
                        AppBrandIcon(
                            modifier = Modifier.size(34.dp),
                            shape = RoundedCornerShape(11.dp)
                        )
                        Column(Modifier.weight(1f).padding(start = 11.dp)) {
                            Text("图标跟随主题色", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (theme.iconFollowsTheme) "底色跟随当前色档" else "保持原始蓝色图标",
                                fontSize = 12.sp,
                                color = MutedText,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                        Switch(
                            checked = theme.iconFollowsTheme,
                            onCheckedChange = vm::setIconFollowsTheme
                        )
                    }
                    Text(
                        "桌面图标按色相分档；切换后可能重排图标或使旧快捷方式失效。",
                        fontSize = 12.sp,
                        color = MutedText,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                            .padding(bottom = 8.dp)
                    )
                }
            }

            // ---------------------------------------------------------- 预置配色
            item {
                SettingsSection("主题配色") {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        // 只有「真的在跟随壁纸」时才不标记任何色块。开着动态取色却取不到
                        // 壁纸色时，正在用哪个就在哪一组下面说明：预置色要标出那一块，
                        // 自定义色则在下面的输入框区提示，别在这里说「选中的配色」。
                        AccentPresetGrid(
                            selected = if (followingWallpaper) -1L else theme.accent,
                            onPick = vm::setAccent
                        )
                        Text(
                            when {
                                followingWallpaper -> "选择色块即时生效，并关闭动态取色。"
                                dynamicFallback && fallbackOnPreset ->
                                    "当前壁纸取不到可用的颜色，正在沿用这个预置配色。"
                                else -> "点选即时生效。"
                            },
                            fontSize = 12.sp,
                            color = MutedText,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }

            // ---------------------------------------------------------- 自定义颜色
            item {
                SettingsSection("自定义颜色") {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 优先级：输入框里合法的新色值 > 当前真正生效的主色。
                            // 动过输入框就按输入里的颜色显示；输到一半（不足 6 位）退回生效色，
                            // 不会闪成一块灰。动态取色开着也一样——否则「输一个色看一眼」这个
                            // 最直接的动作失效，必须点「应用」才知道是什么颜色。
                            val dynamicNow = theme.dynamic && dynamicSupported
                            val typed = if (customDirty) parseAccentHex(customHex)?.let { Color(it) } else null
                            val preview = typed
                                ?: if (dynamicNow) MaterialTheme.colorScheme.primary else Color(theme.accent)
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(AccentSwatchShape)
                                    .background(preview)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, AccentSwatchShape)
                            )
                            // 一行只放一个色值，输入框跟着压到 48dp：默认 56dp 的 Material 规格
                            // 在这一行里比旁边的色块高出一大截，看着像根柱子。
                            OutlinedTextField(
                                value = customHex,
                                onValueChange = {
                                    // 只留 # 和最多 6 位十六进制：粘贴一堆脏字符时输入框不会失控。
                                    val cleaned = it.uppercase(Locale.US)
                                        .filter { ch -> ch == '#' || ch in '0'..'9' || ch in 'A'..'F' }
                                        .let { s -> if (s.startsWith("#")) s else "#$s" }
                                        .take(7)
                                    customHex = cleaned
                                    // 动过输入框 → 色块立刻跟着输入走（含动态取色开启时）。
                                    customDirty = true
                                    customError = null
                                },
                                singleLine = true,
                                isError = customError != null,
                                placeholder = { Text("#1F6BFF", fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters
                                ),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                shape = RoundedCornerShape(11.dp),
                                colors = dialogFieldColors(),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                                    .height(48.dp)
                            )
                            // 「应用」跟输入框同一行、右侧对齐：填完色值直接点，
                            // 不用再把视线移到下一行找按钮。
                            Button(
                                onClick = {
                                    val parsed = parseAccentHex(customHex)
                                    if (parsed == null) {
                                        customError = "请输入 6 位十六进制颜色，例如 #1F6BFF"
                                    } else {
                                        customError = null
                                        vm.setAccent(parsed)
                                    }
                                },
                                shape = RoundedCornerShape(11.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                modifier = Modifier.padding(start = 8.dp).height(40.dp)
                            ) {
                                Text("应用", fontSize = 13.sp)
                            }
                        }
                        if (customError != null) {
                            Text(
                                customError!!,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        val customNote = if (theme.dynamic && dynamicSupported && !dynamicFallback) {
                            // 色块默认显示壁纸色，一旦输入就切成输入值的预览；
                            // 真正生效仍要点「应用」（写偏好 + 关掉动态取色）。
                            "输入色值可即时预览；点「应用」生效，并关闭动态取色。"
                        } else {
                            "支持 #RRGGBB 与 RRGGBB，点「应用」生效。"
                        }
                        Text(
                            // 回落到自定义色时，把「为什么色块没跟着壁纸走」说明放在这里，
                            // 跟它解释的对象（下面的输入框）待在一起。
                            if (dynamicFallback && !fallbackOnPreset) {
                                "当前壁纸取不到可用的颜色，正在沿用这个自选颜色。\n$customNote"
                            } else customNote,
                            fontSize = 12.sp,
                            color = MutedText,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            item {
                Text(
                    "主题色用于按钮、开关、选中态与底栏高亮；页面底色随深浅模式自动重算。",
                    fontSize = 12.sp,
                    color = MutedText,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** 5 列 × 4 行色块。用固定分块而不是 FlowRow：列宽稳定，手指不容易点错邻格。 */
@Composable
private fun AccentPresetGrid(selected: Long, onPick: (Long) -> Unit) {    val columnCount = 5
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AccentPresets.chunked(columnCount).forEach { rowColors ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowColors.forEach { value ->
                    val color = Color(value)
                    // dynamic 开启时传 -1L：没有「当前选中」的预置色，不该硬标一个。
                    val active = selected > 0L && sameRgb(selected, value)
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(AccentTileShape)
                                // 选中时外面套一圈主色描边：浅色格也能看出是哪个被选中了。
                                .then(
                                    if (active) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, AccentTileShape
                                    ) else Modifier
                                )
                                .padding(if (active) 4.dp else 3.dp)
                                .clip(AccentTileInnerShape)
                                .background(color)
                                .clickable { onPick(value) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (active) {
                                Icon(
                                    Icons.Outlined.Check, null,
                                    // 勾的颜色跟字一样按亮度反着来，浅黄格上不会出现白勾看不见。
                                    tint = if (color.luminance() > 0.55f) Color(0xFF101828) else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                // 不足 5 个的末行补空位，保持每格宽度与其它行一致。
                repeat(columnCount - rowColors.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SegmentedThemeMode(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(3.dp)
    ) {
        listOf(
            ThemeMode.SYSTEM to "跟随系统",
            ThemeMode.LIGHT to "日间",
            ThemeMode.DARK to "夜间"
        ).forEach { (mode, label) ->
            val active = current == mode
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary else MutedText
                )
            }
        }
    }
}

private fun sameRgb(a: Long, b: Long): Boolean = (a and 0xFFFFFF) == (b and 0xFFFFFF)
