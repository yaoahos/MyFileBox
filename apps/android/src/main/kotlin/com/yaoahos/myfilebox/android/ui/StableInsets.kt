package com.yaoahos.myfilebox.android.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 稳定的状态栏顶部高度：不随窗口瞬时 insets 抖动。
 *
 * 背景（真机录屏实测）：点传输记录里的文件会启动外部应用，MIUI 在 Activity 失焦那一瞬
 * 会把 statusBars 的 insets 临时算小，`statusBarsPadding()` 立即跟着变小，整个页面内容
 * 上移约 40px，约 0.35 秒后 insets 恢复又跳回来——用户看到的就是「点文件打开界面往上跳」。
 * 底部导航手势条不动，说明不是整窗平移，就是这一处 padding 在抖。
 *
 * 做法：只在读到有效值（> 0）时更新缓存，抖到更小的值时沿用已缓存的高度。
 * 状态栏高度在运行期不会真的变小（最多因分屏/折叠变化），因此缓存是安全的。
 */
@Composable
internal fun rememberStableStatusBarTop(): Dp {
    val density = LocalDensity.current
    val live = WindowInsets.statusBars.getTop(density)
    var cached by remember { mutableIntStateOf(0) }
    if (live > cached) cached = live
    val px = if (cached > 0) cached else live
    return with(density) { px.toDp() }
}

/** 稳定的状态栏垫片 + 一个基础顶部间距，供全屏页面替换 `statusBarsPadding()` 使用。 */
@Composable
internal fun stableStatusBarsPadding(extraTop: Dp = 0.dp): Dp =
    rememberStableStatusBarTop() + extraTop
