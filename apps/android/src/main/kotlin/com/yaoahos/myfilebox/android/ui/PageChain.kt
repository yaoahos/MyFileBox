package com.yaoahos.myfilebox.android.ui

/**
 * 消费一次性的「页面层级链」。
 *
 * 传输记录跳进深页时，ViewModel 把首页/父级/目标的 pageKey 整条写进
 * [MyFileBoxState.pendingPageChain]，转场宿主读它重建页面栈（保证进/出方向一致）
 * 后调用这里清掉，避免同一条链被后续页面变化重复使用。
 */
internal fun AppViewModel.consumePageChain() {
    if (state.pendingPageChain.isNotEmpty()) {
        state = state.copy(pendingPageChain = emptyList())
    }
}
