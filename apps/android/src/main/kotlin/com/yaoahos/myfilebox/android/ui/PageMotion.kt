package com.yaoahos.myfilebox.android.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * 全软件唯一的页面转场参数。
 *
 * 所有「页面」一视同仁：不区分底栏、首页、里层文件夹、覆盖页，
 * 也不区分层级。前进与返回严格互为逆过程（怎么来的就怎么回）；
 * 底栏之间例外，方向只跟底栏的左右顺序走——见 [PageMotionKind.Tab]。
 */
object PageMotion {
    /** 前进与返回都是 300ms，tween 缓动。 */
    const val DURATION_MS = 300
    /**
     * 底栏切换单独用更短的时长。底栏是平级入口，用户常连续快速点击：
     * 300ms 期间状态已切到目标栏、画面还在滑动，重复点击会被
     * selectTab 的「已是当前栏」挡掉，主观上就像「卡在上一栏 / 切不过去」。
     * 缩短到 180ms 让画面尽快追上状态，压缩这段不同步窗口。
     */
    const val TAB_DURATION_MS = 180
    val Easing = FastOutSlowInEasing

    /** 左边缘手势返回的可响应区域。 */
    const val EDGE_DP = 20f

    /** 松手提交阈值：位移 > 30% 屏宽，或速度 > 800px/s。 */
    const val COMMIT_RATIO = 0.30f
    const val COMMIT_VELOCITY = 800f
}

/** 页面转场规格 —— 与 Navigation Compose 的四个参数一一对应：
 *
 * | 方向 | enter (enterTransition)                  | exit (exitTransition)                     |
 * |------|------------------------------------------|-------------------------------------------|
 * | 前进 | slideInHorizontally { it }               | slideOutHorizontally { -it / 3 }           |
 * | 返回 | slideInHorizontally { -it / 3 } (popEnter) | slideOutHorizontally { it } (popExit)      |
 *
 * 只有水平位移：不做缩放、不做变暗/淡入淡出、不用共享元素，避免文字模糊和花哨特效。
 * zIndex：前进时新页覆盖旧页；返回时当前页覆盖即将回来的上一页（把目标页压到下层）。
 */
fun pageTransition(pop: Boolean): ContentTransform {
    val spec = tween<IntOffset>(PageMotion.DURATION_MS, easing = PageMotion.Easing)
    val enter = if (pop) {
        slideInHorizontally(animationSpec = spec) { -it / 3 }
    } else {
        slideInHorizontally(animationSpec = spec) { it }
    }
    val exit = if (pop) {
        slideOutHorizontally(animationSpec = spec) { it }
    } else {
        slideOutHorizontally(animationSpec = spec) { -it / 3 }
    }
    return (enter togetherWith exit).apply {
        targetContentZIndex = if (pop) -1f else 1f
    }
}

/**
 * 底栏之间的转场：整屏左右平移，像翻相邻的两页。
 *
 * 方向由底栏在底栏条里的左右顺序决定（往右切 = 新页从右边进来、旧页往左走出），
 * 与「这是第几次切换、以前去过哪一栏」无关。之前复用前进/返回栈判断，
 * 从右往左切和往右切会得到完全相反的方向，快速来回切就成了「一会左一会右」。
 */
fun tabTransition(toRight: Boolean): ContentTransform {
    val spec = tween<IntOffset>(PageMotion.TAB_DURATION_MS, easing = PageMotion.Easing)
    val enter = slideInHorizontally(animationSpec = spec) { if (toRight) it else -it }
    val exit = slideOutHorizontally(animationSpec = spec) { if (toRight) -it else it }
    return (enter togetherWith exit).apply { targetContentZIndex = 1f }
}

/** 由 [PageMotionKind] 取得实际转场。 */
internal fun motionTransition(kind: PageMotionKind): ContentTransform = when (kind) {
    PageMotionKind.Forward -> pageTransition(false)
    PageMotionKind.Back -> pageTransition(true)
    is PageMotionKind.Tab -> tabTransition(kind.toRight)
}
