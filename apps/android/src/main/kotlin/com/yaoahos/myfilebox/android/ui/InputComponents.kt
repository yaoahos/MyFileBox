package com.yaoahos.myfilebox.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FolderCard
import com.yaoahos.myfilebox.core.TabKey
import com.yaoahos.myfilebox.android.R
import com.yaoahos.myfilebox.android.data.ExtractConflict
import com.yaoahos.myfilebox.android.data.LocalRepository
import kotlinx.coroutines.launch
import com.yaoahos.myfilebox.android.ui.theme.semanticColors

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    corner: Dp = 12.dp
) {
    var visible by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.replace("\n", "").replace("\r", "")) },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            isError = isError,
            // 固定单行、固定字号：点眼睛只切掩码，输入框高度不跳变，登录页几个框
            // 始终等高，眼睛位置也不上下移动。长密码明文时靠 TextField 自身横向滚动
            // 查看（光标移到末尾即显示末尾），比折行撑高整个框更稳。
            singleLine = true,
            maxLines = 1,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            shape = RoundedCornerShape(corner),
            colors = dialogFieldColors(),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            // 眼睛放进 trailingIcon：这是 Material3 唯一能保证与输入文字行垂直对齐的位置。
            // 之前用 Box 叠放 + CenterEnd 对齐的是整个组件几何中心，而带浮动 label 的
            // 单行框文字行中心比组件中心低，图标因此始终偏高（用户两次截图确认）。
            // IconButton 自己消费点击，不会当成点输入框而抢焦点 / 拉起输入法。
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "隐藏密码" else "显示密码",
                        tint = MutedText,
                        modifier = Modifier.size(19.dp)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        // supportingText 放框外单独渲染：留在 TextField 内会撑高输入框、破坏多个密码框等高。
        if (supportingText != null) {
            Text(
                supportingText,
                fontSize = 12.sp,
                color = if (isError) MaterialTheme.colorScheme.error else MutedText,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

/**
 * 第二行统一小字号；清单 15：日期一行，数量/大小一行（文件夹显示 N 项、文件显示大小）。
 *
 * [grid] 区分两种展示口径（同一份数据，两种场景要求不同）：
 * - 列表（默认）：时间给全 `yyyy/MM/dd HH:mm:ss`，左对齐——列表一行放得下，
 *   而且用户要在一行里看全「哪天几点」；
 * - 宫格：当天只给 `HH:mm:ss`、更早只给 `yyyy/MM/dd`，居中——一格只有 4 列宽，
 *   完整时间会截断；同一天靠时间分、跨天靠日期分，信息不丢。
 */
@Composable
fun FileMetaLine(
    file: FileEntry,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 10.sp,
    grid: Boolean = false
) {
    val date = when {
        file.modified > 0 ->
            if (grid) LocalRepository.formatGridStamp(file.modified)
            else LocalRepository.formatStamp(file.modified)
        grid -> LocalRepository.formatGridText(file.timeLabel)
        else -> file.timeLabel
    }
    val info = when {
        file.isFolder -> "${file.childCount} 项"
        file.sizeLabel.isNotBlank() -> file.sizeLabel
        else -> ""
    }
    Column(
        modifier,
        horizontalAlignment = if (grid) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(
            date,
            fontSize = fontSize,
            lineHeight = 12.sp,
            color = MutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (grid) TextAlign.Center else TextAlign.Start
        )
        if (info.isNotBlank()) {
            Text(
                info,
                fontSize = fontSize,
                lineHeight = 12.sp,
                color = MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (grid) TextAlign.Center else TextAlign.Start
            )
        }
    }
}

