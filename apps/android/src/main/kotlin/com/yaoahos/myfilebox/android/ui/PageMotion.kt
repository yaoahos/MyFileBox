package com.yaoahos.myfilebox.android.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
 * 也不区分层级。前进与返回严格互为逆过程（怎么来的就怎么回）。
 */
object PageMotion {
    /** 前进与返回都是 300ms，tween 缓动。 */
    const val DURATION_MS = 300
    val Easing = FastOutSlowInEasing

    /** 左边缘手势返回的可响应区域。 */
    const val EDGE_DP = 20f

    /** 松手提交阈值：位移 > 30% 屏宽，或速度 > 800px/s。 */
    const val COMMIT_RATIO = 0.30f
    const val COMMIT_VELOCITY = 800f
}

/**
 * 页面转场规格 —— 与 Navigation Compose 的四个参数一一对应：
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
 * 页面回退栈：只记录「页面 key」，不管它是首页、底栏还是文件夹。
 * 用真实栈判定方向：新 key 在栈里 = pop，否则 = 前进，
 * 因此不依赖 ViewModel 里可能过期的 navDir，多级返回也不会判错。
 */
class PageStack {
    val keys = mutableListOf<String>()
    var pop: Boolean = false
        private set

    fun update(key: String) {
        if (keys.isEmpty()) {
            keys.add(key)
            pop = false
            return
        }
        if (keys.last() == key) return
        val index = keys.indexOfLast { it == key }
        pop = index >= 0
        if (index >= 0) {
            while (keys.size > index + 1) keys.removeAt(keys.size - 1)
        } else {
            keys.add(key)
        }
    }

    /** 当前页之下那一页（返回时要露出来的真实页面）。 */
    fun under(): String? = keys.getOrNull(keys.size - 2)
}
