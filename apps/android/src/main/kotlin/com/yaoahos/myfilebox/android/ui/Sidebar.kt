package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.android.BuildConfig

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val APP_VERSION = BuildConfig.VERSION_NAME

@Composable
fun AppSidebar(
    open: Boolean,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onTransfers: () -> Unit,
    onAbout: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = open, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = .35f))
                    .clickable(onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(120))
        ) {
            Surface(
                modifier = Modifier.fillMaxHeight().width(292.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
                    Row(Modifier.padding(top = 18.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppBrandIcon(
                            modifier = Modifier.size(46.dp),
                            shape = RoundedCornerShape(13.dp)
                        )
                        Column(Modifier.padding(start = 11.dp)) {
                            Text("MyFileBox", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            Text("本地 · 群晖 · 网络", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SidebarItem(Icons.Outlined.Settings, "设置") { onDismiss(); onSettings() }
                    SidebarItem(Icons.Outlined.SwapVert, "传输中心") { onDismiss(); onTransfers() }
                    SidebarItem(Icons.Outlined.Info, "关于") { onDismiss(); onAbout() }
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .5f))
                    Text(
                        "MyFileBox  $APP_VERSION",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                    Text(
                        "群晖 NAS 文件管理客户端",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    TextButton(onClick = onCheckUpdate, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp)) {
                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(15.dp))
                        Text("检查更新", fontSize = 12.sp, modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
    }
}
