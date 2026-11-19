package com.yaoahos.myfilebox.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yaoahos.myfilebox.android.ui.LocalAppIconTint
import com.yaoahos.myfilebox.core.IconTint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LightBackground = Color(0xFFF1F5FC)
internal val DarkBackground = Color(0xFF0B1220)
private val DarkSurface = Color(0xFF121C2C)

/**
 * 动态取色此刻的实际结果，供外观页决定「预置配色该不该显示为选中」。
 *
 * 关键是 [FALLBACK]：开关是打开的，但壁纸色取不到（ROM 只给黑色 / 动态壁纸没实现
 * 取色 / IPC 异常），界面实际用的是自选配色。这时如果预置色块全都不选中，用户会
 * 以为「配色丢了」——把正在生效的那一块标出来才对得上眼睛看到的东西。
 */
enum class DynamicAccentState {
    /** 首帧还没读完壁纸色，先不做任何标记，避免闪一下再跳。 */
    PENDING,

    /** 真的在跟随壁纸，预置色块全部不选中。 */
    FOLLOWING,

    /** 取不到壁纸色，已回落自选配色；预置色块按 accent 选中。 */
    FALLBACK
}

val LocalDynamicAccentState = staticCompositionLocalOf { DynamicAccentState.FALLBACK }

/** 壁纸色的三种状态：读到了 / 读不到 / 还没读完。 */
private sealed interface SeedState {
    data object Pending : SeedState
    data object Unusable : SeedState
    data class Ready(val color: Color) : SeedState
}

private fun seedStateOf(seed: Color?): SeedState =
    if (seed == null) SeedState.Unusable else SeedState.Ready(seed)

// lint 认为下面的 produceState 没有赋值：它在 `return@produceState` 之后才赋值，
// 而前置分支（不支持动态取色）也确实提前 return 了。两种写法都赋过值，属于误报。
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
fun MyFileBoxTheme(
    accent: Long = 0xFF1F6BFF,
    dynamic: Boolean = false,
    darkTheme: Boolean = false,
    iconFollowsTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val accentColor = Color(accent)
    val context = LocalContext.current
    // 动态取色与壁纸取色共用 MIN_WALLPAPER_SDK（Android 12 / 31）：低版本
    // 自动回落到自选色，不报错、也不把界面画成灰色（入口处会说明需要 12 以上）。
    val dynamicSupported = dynamic && wallpaperColorSupported()
    val refreshScope = rememberCoroutineScope()
    // 订阅壁纸色变化：前台换壁纸（分屏 / 悬浮窗）也能立刻更新，不用等 Activity resume。
    // key 里带 dynamicSupported，拨开关的那一帧就重新取初值，不会出现「开了动态
    // 取色却还拿着关闭时算出的 null」。
    val seedState by produceState<SeedState>(SeedState.Pending, context, dynamicSupported) {
        if (!dynamicSupported) {
            value = SeedState.Unusable
            return@produceState
        }
        // getWallpaperColors 会走 IPC，AOSP 明确不建议在 UI 线程调用；首帧的两次
        // 读取统一放 IO，避免放宽到 12/13 后拖慢低端机启动。
        value = seedStateOf(withContext(Dispatchers.IO) { wallpaperSeedColor(context) })
        withContext(Dispatchers.IO) { WallpaperColorBus.refresh(context) }
        WallpaperColorBus.seed.collect { latest ->
            value = seedStateOf(
                latest ?: withContext(Dispatchers.IO) { wallpaperSeedColor(context) }
            )
        }
    }
    // 部分 ROM 不发 OnColorsChanged 回调，回前台再兜底重读一次。
    if (dynamicSupported) {
        LifecycleResumeEffect(Unit) {
            // 读取幂等且很短，交给 rememberCoroutineScope，离开组合时自动取消。
            refreshScope.launch(Dispatchers.IO) { WallpaperColorBus.refresh(context) }
            onPauseOrDispose { }
        }
    }
    // 优先用系统对壁纸的取色结果自己算主色；拿不到才交给系统 overlay 方案。
    // 这样即便 ROM 算出壁纸色却不更新 accent overlay（HyperOS 3 实测），
    // 主色也仍然跟着壁纸走。
    // 委托属性不能智能转非空，先取一次快照再进 when。
    val effectiveSeed = (seedState as? SeedState.Ready)?.color
    // 只有真正拿到可用的壁纸色才跟随壁纸。读不到时（部分 ROM 的 WallpaperColors
    // 直接返回黑色，系统 overlay 也随之派生出一片灰蓝）就老老实实用自选配色——
    // 之前会退到 dynamicLightColorScheme，结果整屏是"来路不明的灰"，
    // 用户看到的是"深蓝壁纸却是灰界面"，比不跟随更糟。
    //
    // 派生配色是纯计算（种子 → 主色 → 30 多个 scheme 槽位），但它挂在整棵 UI 的根上，
    // 任何一次重组都会重跑。用 remember 绑住三个输入，滚动/展开这类重组不再白算一遍。
    val scheme = remember(effectiveSeed, darkTheme, accentColor) {
        when {
            effectiveSeed != null -> {
                val wallpaperPrimary = seedToPrimary(effectiveSeed, darkTheme)
                if (darkTheme) darkScheme(wallpaperPrimary) else lightScheme(wallpaperPrimary)
            }
            darkTheme -> darkScheme(accentColor)
            else -> lightScheme(accentColor)
        }
    }
    // 外观页据此标记预置配色：跟随壁纸时不选，回落自选色时选中正在用的那一块。
    val dynamicAccentState = when {
        seedState is SeedState.Ready -> DynamicAccentState.FOLLOWING
        seedState is SeedState.Pending -> DynamicAccentState.PENDING
        else -> DynamicAccentState.FALLBACK
    }
    val density = LocalDensity.current
    // Mature file managers keep body copy around 14-16sp. Several screens use
    // compact labels, so lift the whole type scale while still respecting the
    // system font-size setting.
    val readableDensity = Density(
        density = density.density,
        fontScale = (density.fontScale * 1.1f).coerceIn(1.0f, 1.35f)
    )
    // 应用内品牌图标必须与桌面 alias 的颜色一模一样：桌面只能用静态档位色，
    // 直接用 scheme.primary 会让外观页预览和关于页比桌面图标更鲜艳/更暗。
    // 放在 scheme 之后算，动态取色时拿到的是壁纸派生色而不是旧的 accent。
    val iconTint = if (iconFollowsTheme) {
        Color(IconTint.buckets[IconTint.bucket(scheme.primary.toArgb())])
    } else {
        null
    }
    CompositionLocalProvider(
        LocalDensity provides readableDensity,
        LocalAppIconTint provides iconTint,
        LocalDynamicAccentState provides dynamicAccentState
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(),
            content = content
        )
    }
}

private fun lightScheme(primary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = readableOn(primary),
    primaryContainer = primary.copy(alpha = 0.12f).compositeOver(Color.White),
    onPrimaryContainer = primary.darken(0.3f),
    secondary = Color(0xFF5E6C84),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0FC),
    onSecondaryContainer = Color(0xFF14233D),
    tertiary = primary,
    onTertiary = readableOn(primary),
    background = LightBackground,
    onBackground = Color(0xFF101828),
    surface = Color.White,
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFEAF0FA),
    onSurfaceVariant = Color(0xFF7B879C),
    error = Color(0xFFEF4444),
    outline = Color(0xFFE2E9F5),
    // 不写 outlineVariant 会拿到 M3 基线值（浅色 #CAC4D0，带一层淡紫），
    // 与这套蓝色系描边不是一回事，分隔线 / 边框在浅色界面里会显脏。
    outlineVariant = Color(0xFFD3DEEF),
    // 下面这一组是「容器层」色，M3 里对话框（AlertDialog / 添加网络位置）、
    // 菜单、下拉刷新圆底用的都是它们。
    //
    // 不写的话拿到的是 lightColorScheme() 的基线值：surfaceContainerHigh = #ECE6F0，
    // 自带一层淡紫。放在这个浅蓝底、白卡片的界面里，对话框会显得"脏"，和主题色一换
    // 更明显（用户反馈的「添加/编辑网络连接的背景偏色」）。这里统一成中性近白灰，
    // 不参与任何按主题色推导的计算。
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFE),
    surfaceContainer = Color(0xFFF5F8FD),
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFEFF4FB)
)

private fun darkScheme(primary: Color) = darkColorScheme(
    primary = primary,
    onPrimary = readableOn(primary),
    primaryContainer = primary.copy(alpha = 0.22f).compositeOver(DarkSurface),
    onPrimaryContainer = Color(0xFFDCE9FF),
    secondary = Color(0xFFB6C4DA),
    onSecondary = Color(0xFF101828),
    secondaryContainer = Color(0xFF1B2A42),
    onSecondaryContainer = Color(0xFFDCE9FF),
    tertiary = primary,
    onTertiary = readableOn(primary),
    background = DarkBackground,
    onBackground = Color(0xFFF2F6FD),
    surface = DarkSurface,
    onSurface = Color(0xFFF2F6FD),
    surfaceVariant = Color(0xFF1B2A42),
    onSurfaceVariant = Color(0xFF9FB0C7),
    error = Color(0xFFFF7A70),
    outline = Color(0xFF24344C),
    outlineVariant = Color(0xFF2A3A53),
    // 同 lightScheme：深色基线值是 #2B2930（偏紫），换成这套偏蓝的中性层级。
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF070D18),
    surfaceContainerLow = Color(0xFF101A2A),
    surfaceContainer = Color(0xFF152033),
    surfaceContainerHigh = Color(0xFF1A2639),
    surfaceContainerHighest = Color(0xFF22304A)
)

private fun readableOn(color: Color): Color =
    if (color.luminance() > 0.55f) Color(0xFF101828) else Color.White

private fun Color.darken(amount: Float): Color = Color(
    red = (red * (1f - amount)).coerceIn(0f, 1f),
    green = (green * (1f - amount)).coerceIn(0f, 1f),
    blue = (blue * (1f - amount)).coerceIn(0f, 1f),
    alpha = alpha
)
