package com.yaoahos.myfilebox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.android.data.LocalRepository
import com.yaoahos.myfilebox.core.ShareLink

/**
 * 群晖共享管理（清单 4）：统一查看 / 取消全部共享链接，并清除无效链接。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShareManagerScreen(state: MyFileBoxState, vm: AppViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        // 整页下拉刷新：标题区和列表在同一个容器里，从任意位置下拉都能刷新；
        // 右上角的刷新按钮已去掉，风格与其它文件列表页保持一致。
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        AppPullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refreshShareLinks(notice = true) },
            modifier = Modifier.fillMaxSize()
        ) {
            // 标题与返回固定（3.9.16），不随共享链接列表滚走。
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        // 与回收 / 浏览页同一口径：稳定状态栏高度，别用 statusBarsPadding()
                        // （启动外部应用时 MIUI 的 insets 会变小，整页会往上跳一下）。
                        .padding(top = rememberStableStatusBarTop())
                        .padding(horizontal = 14.dp)
                ) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircleBackButton(onClick = vm::closeShareManager)
                            // 与传输 / 设置二级页一致：标题离箭头 12dp。
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("共享管理", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                            if (state.shareLinks.any { it.invalid }) {
                                TextButton(onClick = { vm.clearInvalidShareLinks() }) {
                                    Text("清除无效链接", fontSize = 12.sp)
                                }
                            }
                        }
                        // 说明独占整行：与「回收」页同一口径，避免被右侧按钮挤成两行断句。
                        // 与传输页副标题同款：单独一行、左起 20dp。
                        Text(
                            "群晖官方共享链接",
                            fontSize = 13.sp,
                            color = MutedText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 2.dp)
                        )
                    }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    // 标题固定后，首张卡片要留出与原来 item 间距一致的空白（3.9.16）。
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                when {
                    state.shareLinksLoading && state.shareLinks.isEmpty() -> item {
                        Box(
                            Modifier.fillMaxWidth().height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    }
                    state.shareLinks.isEmpty() -> item {
                        Column(
                            Modifier.fillMaxWidth().height(320.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Outlined.Link,
                                null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                "还没有共享链接",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            Text(
                                "在文件上点「共享」生成的链接会列在这里",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    else -> {
                        items(state.shareLinks, key = { it.id }) { link ->
                            ShareLinkCard(
                                link = link,
                                onCopy = {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(link.url))
                                    vm.showMessage("共享链接已复制")
                                },
                                onCancel = { vm.cancelShareLink(link) }
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ShareLinkCard(link: ShareLink, onCopy: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 路径图标落在彩色圆角底上，与设置页图标风格一致（清单 9 美化）。
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Link, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Text(
                link.path.ifBlank { "(未知路径)" },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp)
            )
            if (link.hasPassword) {
                Badge("有密码")
            }
            if (link.invalid) {
                Badge("已失效", danger = true)
            } else if (link.expireAt > 0) {
                Badge("到期 ${expireLabel(link.expireAt)}")
            } else {
                Badge("永久")
            }
        }
        // 链接放在浅底圆角框里，可读性更好。
        Text(
            link.url.ifBlank { "链接已不可用" },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            // 复制按钮（清单 9）：一键复制链接到剪贴板。
            TextButton(onClick = onCopy, enabled = link.url.isNotBlank()) {
                Icon(
                    Icons.Outlined.ContentCopy, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    "复制",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            TextButton(onClick = onCancel) {
                Icon(
                    Icons.Outlined.Block,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    "取消共享",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

private fun expireLabel(ms: Long): String =
    LocalRepository.formatWith("yyyy/MM/dd", ms)
