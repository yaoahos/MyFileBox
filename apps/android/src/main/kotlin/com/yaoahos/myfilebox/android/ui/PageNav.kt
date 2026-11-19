package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.TabKey

/** 一次页面切换该用哪种转场。 */
sealed interface PageMotionKind {
    /** 进入下一层页面。 */
    data object Forward : PageMotionKind
    /** 回到上一层页面。 */
    data object Back : PageMotionKind
    /** 底栏切换：[toRight] 为 true 表示切到右边那一栏。 */
    data class Tab(val toRight: Boolean) : PageMotionKind
}

/**
 * 判定这次页面切换的方向。
 *
 * - 两次都是底栏页（首页 / 登录页）：按底栏左右顺序决定方向，并且**不进入**前进/返回栈。
 *   底栏是平级的三个入口，不是层级；把它们压进栈会让方向随历史反转，
 *   于是同一排底栏「一会左一会右」。
 * - 其它情况仍按真实回退栈判断：新 key 在栈里 = 返回，否则 = 前进。
 */
internal fun resolvePageMotion(
    stack: PageStack,
    key: String,
    /** 非空表示这是一次跳转：按这条完整层级链重建页面栈，保证进/出方向一致。 */
    chain: List<String>? = null
): PageMotionKind {
    if (!chain.isNullOrEmpty()) {
        stack.replaceChain(chain)
        return PageMotionKind.Forward
    }
    val previous = stack.current
    val fromTab = tabKeyOf(previous)
    val toTab = tabKeyOf(key)
    if (previous != null && previous != key && fromTab != null && toTab != null) {
        stack.reset(key)
        return PageMotionKind.Tab(toRight = toTab.ordinal > fromTab.ordinal)
    }
    stack.update(key)
    return if (stack.pop) PageMotionKind.Back else PageMotionKind.Forward
}

/** 底栏页面的 key 里取底栏；不是底栏页返回 null。 */
private fun tabKeyOf(key: String?): TabKey? {
    if (key == null) return null
    val name = when {
        key.startsWith(PAGE_HOME) -> key.removePrefix(PAGE_HOME)
        key.startsWith(PAGE_LOGIN) -> key.removePrefix(PAGE_LOGIN)
        else -> return null
    }
    return runCatching { TabKey.valueOf(name) }.getOrNull()
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

    /** 栈顶（当前页面）；尚未入栈时为空。 */
    val current: String?
        get() = keys.lastOrNull()

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

    /**
     * 重设栈：底栏切换是平级跳转，切完只留当前这一栏当基准，
     * 否则以前去过的栏目会一直躺在栈里影响后面方向判断。
     */
    fun reset(key: String) {
        keys.clear()
        keys.add(key)
        pop = false
    }

    /**
     * 一次「跳转」直接落到一个深页，中间层没有真正打开过。
     *
     * 把这些中间层的 key 一并补齐，页面栈才和真实的层级链一致：
     * 进去是前进，退回每一层都命中栈里的 key、判成返回。否则目标 key 若恰好
     * 以前访问过会被误判成返回（从左边进），返回的父级又因没入栈被判成前进
     * （从右边进），表现就是「一会左一会右」。
     *
     * [keysIn] 从最外层到目标页（含目标），最后一个必须等于当前 pageKey。
     */
    fun replaceChain(keysIn: List<String>) {
        keys.clear()
        keys.addAll(keysIn)
        pop = false
    }

    /** 当前页之下那一页（返回时要露出来的真实页面）。 */
    fun under(): String? = keys.getOrNull(keys.size - 2)
}
