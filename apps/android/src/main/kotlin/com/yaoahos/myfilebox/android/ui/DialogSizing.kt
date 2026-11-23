package com.yaoahos.myfilebox.android.ui

import android.content.res.Configuration
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 弹窗内容区的最大高度。
 *
 * 这里有三层约束会打架，一次性说清楚：
 *  1 AlertDialog 的窗口在 adjustResize 下已经随键盘缩小，但 M3 的 text 槽不会自己变矮，
 *    内容一长就把标题挤上去、把按钮压到键盘下面；
 *  2 反过来补一层 imePadding，键盘高度会被算第二遍，键盘上方留出一大块空白；
 *  3 用屏高的固定比例（0.4 屏之类）在横屏会直接失真 —— 横屏屏高只有 360dp 上下，
 *    0.4 屏只剩 144dp，两三个字段就顶满，滚动条几乎看不到内容。
 *
 * 读到真实键盘高度是最理想的，但 Dialog 走 adjustResize 时 Compose 的 WindowInsets.ime
 * 已被系统消费成 0（实测：按「屏高 - ime」算出来的上限和没减一模一样），所以这里按
 * 常见键盘高度预留一段，再压一道屏高比例 —— 取二者较小值，竖屏小屏也不会溢到键盘下。
 * 横屏与分屏不走这个比例：FormDialog 在那种高度下会换成底部面板，
 * 由 imePadding 跟着键盘走。
 */
@Composable
fun rememberDialogContentMaxHeight(
    keyboardReserve: Dp = 300.dp,
    reserved: Dp = 112.dp,
    min: Dp = 88.dp
): Dp {
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenHeight = config.screenHeightDp.dp
    // 横屏只在极端情况（屏高刚好 480dp 以上）用到，留个够用的兜底。
    if (landscape) return (screenHeight * 0.34f).coerceAtLeast(min)
    val roomWithKeyboard = screenHeight - keyboardReserve - reserved
    val cap = screenHeight * 0.40f
    return roomWithKeyboard.coerceAtMost(cap).coerceAtLeast(min)
}

/** 屏高低于这个值（横屏、分屏、折叠屏内屏半开）就走紧凑形态。 */
private const val COMPACT_SCREEN_HEIGHT_DP = 480

/**
 * 长文本弹窗内容区：限高 + 滚动，内容没看完时底部渐隐并提示「上滑查看更多」。
 *
 * 渐隐叠在滚动区底部、渐变到弹窗自身的背景色（surfaceContainerHigh），
 * 被裁切的半行字自然淡出而不是被硬盖住；滑到底提示与渐隐一起消失。
 * 内容没超出时两者都不渲染，短日志弹窗外观保持原样。
 */
@Composable
fun ScrollableDialogContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    val hasMore = scrollState.maxValue > 0 && scrollState.value < scrollState.maxValue - 8
    Box(modifier) {
        Column(
            Modifier
                .heightIn(max = rememberDialogContentMaxHeight())
                .verticalScroll(scrollState),
            content = content
        )
        if (hasMore) {
            val surface = MaterialTheme.colorScheme.surfaceContainerHigh
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, surface.copy(alpha = 0.92f), surface)
                        )
                    )
            )
            Text(
                "上滑查看更多",
                fontSize = 11.sp,
                color = MutedText,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(surface)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * 表单类弹窗的统一外壳。
 *
 * - 屏高够用（竖屏常态）：走 AlertDialog，和删文件、重命名这些确认框是同一套外观；
 * - 屏高很低（横屏 / 分屏 / 小屏）：AlertDialog 的标题栏与按钮栏本身就要吃掉一百多 dp，
 *   横屏键盘再占七成高度，字段区只剩一条缝。这时换成底部面板（ModalBottomSheet）：
 *   它不是独立窗口，能拿到键盘 insets，imePadding 一垫就整体顶到键盘上方，
 *   操作按钮挪到标题行，保证任何时候都点得到。
 *
 * 竖屏分支不加 imePadding（窗口已随 adjustResize 缩过，再加会留空白）；
 * 底部面板分支必须加，面板长在主窗口里、insets 正常。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    dismissButton: @Composable () -> Unit = { TextButton(onClick = onDismiss) { Text("取消") } },
    /**
     * 固定在内容区下方的区域（不随内容滚动）。用于「测试结果」这类出现后必须
     * 立刻看到、又不能把表单顶走的反馈——放进滚动区里，键盘一弹就只剩一条缝。
     */
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val config = LocalConfiguration.current
    val compact = config.screenHeightDp < COMPACT_SCREEN_HEIGHT_DP
    // 有固定区时把内容区上限让出它的位置，否则两者相加会顶出可视范围
    // （小屏 + 键盘时尤其明显，底部按钮会被挤到屏幕外）。
    val contentMaxHeight = (rememberDialogContentMaxHeight() - if (footer != null) 96.dp else 0.dp)
        .coerceAtLeast(80.dp)
    if (!compact) {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = modifier,
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Column(
                        Modifier
                            .heightIn(max = contentMaxHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(spacing)
                    ) { content() }
                    // 固定区不进滚动：滚到哪它都在，键盘弹出也贴着按钮。
                    footer?.let {
                        Column(Modifier.fillMaxWidth().heightIn(max = 120.dp)) { it() }
                    }
                }
            },
            confirmButton = confirmButton,
            dismissButton = dismissButton
        )
        return
    }
    // 横屏 / 分屏：改用应用内的底部面板。
    //
    // 为什么不用 Dialog：Dialog 是独立窗口，横屏时它既不跟着键盘 resize，也拿不到
    // ime insets（Compose 的 WindowInsets.ime、零尺寸 View 的 insets 回调实测都是 0），
    // 键盘一弹就把输入框整块盖住，怎么调布局都救不回来。
    // ModalBottomSheet 长在主窗口里，insets 正常，imePadding 直接生效（传输选择器同款）。
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = null
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // 这里刻意不加 statusBarsPadding：横屏 + 键盘时面板只剩约 120dp，
                // 再扣掉状态栏后内容区会被挤没（实测只剩标题行，输入框看不到）。
                // 优先保证「按钮可点 + 聚焦输入框可见」，标题与时钟同高可接受
                // （状态栏本身由系统绘制在最上层，时钟仍然看得见）。
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 16.dp)
        ) {
            // 操作按钮跟标题同一行：横屏键盘弹出后可用高度只剩一百多 dp，
            // 底部再单独留一条按钮栏，字段区就没地方了。
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                dismissButton()
                confirmButton()
            }
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .heightIn(max = contentMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) { content() }
                footer?.let {
                    Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).padding(top = 8.dp)) { it() }
                }
            }
        }
    }
}
