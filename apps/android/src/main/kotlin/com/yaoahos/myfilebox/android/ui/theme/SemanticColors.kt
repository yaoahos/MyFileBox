package com.yaoahos.myfilebox.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 语义色统一入口。
 *
 * 之前「成功绿 0xFF15A46B」「暂停橙 0xFFE67E22」在 MainShell / Components /
 * TransferCenter / AppRoot 各写一遍，深色主题下这些固定色不跟随主题，容易出现
 * 对比度不足。这里按明暗两套给出，页面只引用语义，不再散写色值。
 */
object SemanticColors {
    val SUCCESS_LIGHT = Color(0xFF15A46B)
    val SUCCESS_DARK = Color(0xFF5BE3B0)
    val WARNING_LIGHT = Color(0xFFE67E22)
    val WARNING_DARK = Color(0xFFFFB84D)
    val DANGER_LIGHT = Color(0xFFD94848)
    val DANGER_DARK = Color(0xFFFF8A8A)
    val ONLINE_LIGHT = Color(0xFF1AA874)
    val ONLINE_DARK = Color(0xFF5BE3B0)
    val STAR_LIGHT = Color(0xFFFFB020)
    val STAR_DARK = Color(0xFFFFD166)

    val LIGHT = Palette(SUCCESS_LIGHT, WARNING_LIGHT, DANGER_LIGHT, ONLINE_LIGHT, STAR_LIGHT)
    val DARK = Palette(SUCCESS_DARK, WARNING_DARK, DANGER_DARK, ONLINE_DARK, STAR_DARK)

    data class Palette(
        val success: Color,
        val warning: Color,
        val danger: Color,
        val online: Color,
        val star: Color
    )
}

/** 按当前配色方案取语义色。用 surface 亮度判断，跟随应用主题而非系统主题。 */
@Composable
fun semanticColors(): SemanticColors.Palette =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) SemanticColors.DARK
    else SemanticColors.LIGHT
