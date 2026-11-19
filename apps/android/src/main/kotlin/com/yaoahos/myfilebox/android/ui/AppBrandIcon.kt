package com.yaoahos.myfilebox.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
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
 * null = 保持原始蓝色图标（开关关闭时的默认状态）；非 null = 用桌面图标
 * 当前档位的静态色铺底、叠加同一个白色文件夹图形。由 MyFileBoxTheme 统一提供，
 * 侧边栏 / 传输抽屉 / 群晖登录页 / 关于页四处只需换成 AppBrandIcon 即可，
 * 不用各自把 state 传进来。
 *
 * 用 compositionLocalOf（而非 static）：外观页的预览位于 LazyColumn 的 item 里，
 * 静态版本不跟踪读取点，换壁纸导致 iconTint 变化时这个图标有可能被跳过重组，
 * 表现就是「动态取色时图标颜色不变」。值由主题层一处提供，精确失效更可靠。
 */
val LocalAppIconTint = compositionLocalOf<Color?> { null }

/**
 * 品牌图标：与桌面自适应图标共用 ic_launcher_bg / ic_launcher_folder_fg。
 *
 * 以前关闭「跟随主题色」时读整张 ic_app_icon.png，开启时换成「底色 + 前景」，
 * 两张图里的白色文件夹比例不同，拨开关就会看到文件夹缩放一下。现在两种状态
 * 都画同一张前景，只换底色；外观页预览、关于页与桌面图标因此形状一致。
 * 圆角由调用方按尺寸给，保持各处原有观感。
 */
@Composable
fun AppBrandIcon(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(13.dp),
    contentDescription: String? = null
) {
    val tint = LocalAppIconTint.current
    Box(modifier.clip(shape)) {
        if (tint == null) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize().background(tint))
        }
        Image(
            painter = painterResource(R.drawable.ic_launcher_folder_fg),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}
