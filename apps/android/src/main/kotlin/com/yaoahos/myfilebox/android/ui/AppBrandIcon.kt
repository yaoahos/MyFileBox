package com.yaoahos.myfilebox.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yaoahos.myfilebox.android.R

/**
 * 应用内品牌图标的底色。
 *
 * null = 保持原始蓝色图标（开关关闭时的默认状态）；非 null = 用柔化后的
 * 主题色铺底、叠加原来的白色文件夹图形。由 MyFileBoxTheme 统一提供，
 * 侧边栏 / 传输抽屉 / 群晖登录页 / 关于页四处只需换成 AppBrandIcon 即可，
 * 不用各自把 state 传进来。
 *
 * 用 compositionLocalOf（而非 static）：外观页的预览位于 LazyColumn 的 item 里，
 * 静态版本不跟踪读取点，换壁纸导致 iconTint 变化时这个图标有可能被跳过重组，
 * 表现就是「动态取色时图标颜色不变」。值由主题层一处提供，精确失效更可靠。
 */
val LocalAppIconTint = compositionLocalOf<Color?> { null }

/**
 * 品牌图标：关闭「跟随主题色」时读原始 ic_app_icon.png；开启时改成
 * 「柔和底色 + ic_launcher_folder_fg.png」，文件夹形状与原始图标完全一致，
 * 只是底色跟主题走。圆角由调用方按尺寸给，保持各处原有观感。
 */
@Composable
fun AppBrandIcon(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(13.dp),
    contentDescription: String? = null
) {
    val tint = LocalAppIconTint.current
    if (tint == null) {
        Image(
            painter = painterResource(R.drawable.ic_app_icon),
            contentDescription = contentDescription,
            modifier = modifier.clip(shape)
        )
    } else {
        Box(
            modifier = modifier.clip(shape).background(tint),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_folder_fg),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
