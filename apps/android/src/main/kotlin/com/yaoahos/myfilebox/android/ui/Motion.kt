package com.yaoahos.myfilebox.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Subtle Material-style press feedback. Compose's tween follows the system
 * animator duration scale, so accessibility settings remain respected.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "press-scale"
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Standard ripple + 150ms press scale for custom clickable rows. */
@Composable
fun Modifier.pressClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        onClick = onClick
    ).pressScale(interactionSource)
}

/**
 * 让一整块区域都能触发下拉刷新。
 *
 * PullToRefreshBox 本身只是个嵌套滚动的接收方：必须由子级滚动容器把「没被消费的位移」
 * 往上抛，它才动。页面内容比屏幕短时（比如只有两个文件夹的目录、只有一个连接的
 * 网络页），列表只占上方一小截，下半屏的空白不属于任何滚动容器——从那里下拉完全
 * 没有反应，用户会以为下半屏是「死区」。
 *
 * 这里补一个滚动范围为 0 的可滚动节点：它自己不会滚，只负责把位移继续往上传，
 * 于是标题栏、空白区、列表上任意位置下拉都能刷新，也不会和列表本身的滚动打架
 * （有内容可滚时内层先消费，剩下的一层才继续往上抛）。
 */
@Composable
fun Modifier.pullRefreshArea(): Modifier {
    // consumeScrollDelta 恒定返回 0：这条滚动链一点都不吃位移，只负责把位移原样
    // 交给上层。不能用 rememberScrollState()——它的最大滚动值是无穷大，会把位移
    // 全部吞掉自己「滚」进虚空，上层的下拉刷新反而收不到。
    val passThrough = rememberScrollableState { 0f }
    return scrollable(state = passThrough, orientation = Orientation.Vertical)
}
