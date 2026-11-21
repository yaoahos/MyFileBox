package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.MODULE_LABEL_LOCAL
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yaoahos.myfilebox.android.ui.theme.semanticColors

@Composable
fun CompressDialog(
    defaultName: String,
    onConfirm: (name: String, format: String, password: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    var format by remember { mutableStateOf("zip") }
    var password by remember { mutableStateOf("") }
    // 高度与滚动交给 FormDialog：窗口已随键盘缩小，再叠 imePadding 会把键盘高度算两遍。
    FormDialog(
        title = "压缩",
        onDismiss = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(name.trim().ifBlank { defaultName }, format, password)
            }) { Text("开始压缩") }
        }
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("压缩包名称") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = dialogFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Text("格式", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("zip" to "ZIP", "7z" to "7Z").forEach { (value, label) ->
                val active = format == value
                Row(
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clickable { format = value },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (active) {
                        Icon(
                            Icons.Outlined.Check,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp).size(15.dp)
                        )
                    }
                }
            }
        }
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = "压缩密码（可选）",
            supportingText = "留空则不加密；ZIP 用 AES-256，7Z 用原生加密",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 解压对话框：解压到当前文件夹，或挑一个指定文件夹。
 * 指定文件夹用轻量目录浏览器（listSubfolders 返回 path→(path,name)）。
 */
@Composable
fun ExtractDialog(
    rootPath: String,
    listSubfolders: (String) -> List<Pair<String, String>>,
    onConfirm: (destinationPath: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var pickMode by remember { mutableStateOf("here") } // here | pick
    var pickPath by remember { mutableStateOf(rootPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解压", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { pickMode = "here" }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = pickMode == "here", onClick = { pickMode = "here" })
                    Text("解压到当前文件夹", fontSize = 12.sp)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { pickMode = "pick" }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = pickMode == "pick", onClick = { pickMode = "pick" })
                    Text("解压到指定文件夹", fontSize = 12.sp)
                }
                if (pickMode == "pick") {
                    // 面包屑（清单 12/19）：点哪层回哪层；无「上一级」。
                    val segments = buildList {
                        add(MODULE_LABEL_LOCAL to rootPath.trimEnd('/'))
                        val rel = pickPath.trimEnd('/').removePrefix(rootPath.trimEnd('/')).trim('/')
                        if (rel.isNotBlank()) {
                            var acc = rootPath.trimEnd('/')
                            rel.split('/').forEach { seg ->
                                acc = "$acc/$seg"
                                add(seg to acc)
                            }
                        }
                    }
                    PathBreadcrumbs(
                        labels = segments.map { it.first },
                        modifier = Modifier.padding(top = 6.dp),
                        onCrumbClick = { index -> pickPath = segments[index].second }
                    )
                    // 列目录要读盘：放在组合里同步列，目录条目一多就会卡住这一帧。
                    // 改成 remember + LaunchedEffect，在 IO 线程列完再重组。
                    var subfolders by remember(pickPath) {
                        mutableStateOf(emptyList<Pair<String, String>>())
                    }
                    LaunchedEffect(pickPath) {
                        subfolders = withContext(Dispatchers.IO) { listSubfolders(pickPath) }
                    }
                    // 同上：目录里子文件夹可能有几百个，Column + forEach 会一次全组合。
                    // 换成定高 LazyColumn，只组合看得见的几行。
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                    ) {
                        items(subfolders) { (path, name) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { pickPath = path }.padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(name, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(if (pickMode == "here") null else pickPath)
            }) { Text("开始解压") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 解压前发现同名文件，让用户决定怎么处理。
 *
 * 用单选 + 「开始解压」，而不是三个动作按钮直接执行：三个按钮并排会挤成一排小字，
 * 而且「覆盖」是唯一真正会丢数据的选项，不该让它在手指下和「取消」同样容易点到。
 */
@Composable
fun ExtractConflictDialog(
    prompt: ExtractConflictPrompt,
    onChoose: (ExtractConflict) -> Unit,
    onDismiss: () -> Unit
) {
    var choice by remember(prompt) { mutableStateOf(ExtractConflict.OVERWRITE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("有同名文件", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "「${prompt.destinationName}」里已有 ${prompt.conflicts.size} 个同名文件：",
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Column(Modifier.padding(top = 6.dp, start = 4.dp, bottom = 8.dp)) {
                    prompt.conflicts.take(5).forEach {
                        Text(
                            "· $it",
                            fontSize = 11.sp,
                            color = MutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (prompt.conflicts.size > 5) {
                        Text("等 ${prompt.conflicts.size} 个", fontSize = 11.sp, color = MutedText)
                    }
                }
                ConflictOption("覆盖已有文件", "用压缩包里的版本替换，原文件会丢", ExtractConflict.OVERWRITE, choice) { choice = it }
                ConflictOption("跳过同名文件", "只解压其余文件，已有的不动", ExtractConflict.SKIP, choice) { choice = it }
                ConflictOption("两份都保留", "新解出的自动改名，加 (1)", ExtractConflict.KEEP_BOTH, choice) { choice = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(choice) }) { Text("开始解压") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 加密压缩包的密码输入。密码不写进 State（只在 ViewModel 内存里留到解压结束），
 * 弹窗自己用 remember 保存输入，关掉即丢。
 *
 * revision 自增时（密码输错）remember 的 key 变化，输入框自动清空；
 * 弹窗本身不卸载，所以不会出现「闪一下又重新弹出」。
 */
@Composable
fun ExtractPasswordDialog(
    prompt: ExtractPasswordPrompt,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember(prompt) { mutableStateOf("") }
    FormDialog(
        title = "需要密码",
        onDismiss = onDismiss,
        spacing = 8.dp,
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text("解压") } }
    ) {
        Text("「${prompt.archiveName}」已加密，请输入解压密码。", fontSize = 12.sp, lineHeight = 18.sp)
        if (prompt.message != DEFAULT_EXTRACT_PASSWORD_MESSAGE) {
            Text(prompt.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = "解压密码",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private const val DEFAULT_EXTRACT_PASSWORD_MESSAGE = "该压缩包已加密，请输入密码"

@Composable
private fun ConflictOption(
    title: String,
    subtitle: String,
    value: ExtractConflict,
    selected: ExtractConflict,
    onSelect: (ExtractConflict) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onSelect(value) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Column(Modifier.padding(start = 2.dp)) {
            Text(title, fontSize = 12.sp)
            Text(subtitle, fontSize = 11.sp, color = MutedText, lineHeight = 15.sp)
        }
    }
}

/**
 * 单文件 / 文件夹重命名对话框（清单 8：移除批量模板，只保留单个重命名）。
 * 预填原名，改完点保存。
 */
@Composable
fun RenameDialog(
    currentName: String,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    // 高度与滚动交给 FormDialog：窗口已随键盘缩小，再叠 imePadding 会把键盘高度算两遍。
    FormDialog(
        title = "重命名",
        onDismiss = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name != currentName,
                onClick = { onConfirm(name.trim()) }
            ) { Text("保存") }
        }
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("新名称") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = dialogFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 群晖共享设置（清单 4）：先选有效期 / 访问密码，点「生成并复制链接」才真正生成。
 * 返回 (password, expireMinutes)；expireMinutes 0 = 永不失效。
 */
@Composable
fun ShareLinkDialog(
    onConfirm: (password: String, expireMinutes: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var expireChoice by remember { mutableStateOf(0) } // minutes; 0 = never
    val expiryOptions = listOf(
        0 to "永不",
        1440 to "1 天",
        10080 to "7 天",
        43200 to "30 天"
    )
    FormDialog(
        title = "共享设置",
        onDismiss = onDismiss,
        confirmButton = {
            Button(
                onClick = { onConfirm(password.trim(), expireChoice) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("生成并复制链接") }
        }
    ) {
        Text("有效期", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MutedText)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            expiryOptions.forEach { (minutes, label) ->
                FilterChip(
                    selected = expireChoice == minutes,
                    onClick = { expireChoice = minutes },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = "访问密码（可选）",
            supportingText = "留空表示不设密码；设置后访客需输入密码才能打开链接。",
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )
    }
}

/** 文件树选择器的两种模式：只选文件夹（上传文件夹 / 下载位置）、选文件（上传文件）。 */
enum class TreePickerMode { FOLDER, FILE }

/**
 * 全软件统一的「本机文件树」选择器（清单 12 / 13）。
 *
 * 交互统一为三步，所有入口一模一样：
 *  1 点一行 = 进入该文件夹（文件行则切换勾选）；
 *  2 点右侧圆圈 = 选中它（单选，选中另一个会自动取消上一个；文件模式可多选）；
 *  3 点底部「选择」= 确认。
 *
 * 之前上传文件走系统选择器、上传文件夹走应用内选择器，两套风格并存；
 * 现在统一成这一套，避免同一件事出现两种操作习惯。
 */
// produceState 的首帧是 null 分支，先 return@produceState 再赋值，lint 只看前者。
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
fun LocalTreePickerDialog(
    title: String,
    rootPath: String,
    mode: TreePickerMode = TreePickerMode.FOLDER,
    multiSelect: Boolean = false,
    initialPath: String = rootPath,
    /** 允许在当前目录下新建子文件夹：下载位置、上传文件夹都需要，缺了就得来回切页面。 */
    allowCreateFolder: Boolean = mode == TreePickerMode.FOLDER,
    hint: String = if (mode == TreePickerMode.FOLDER) {
        "点文件夹进入下一层，勾选右侧圆圈选中，再点「选择」"
    } else {
        "点文件夹进入下一层，勾选文件后点「选择」"
    },
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val root = rootPath.trimEnd('/')
    var path by remember(root, initialPath) { mutableStateOf(initialPath.ifBlank { root }) }
    var selected by remember(root) { mutableStateOf<Set<String>>(emptySet()) }
    var showNewFolder by remember(path) { mutableStateOf(false) }
    var newFolderName by remember(path) { mutableStateOf("") }
    var createError by remember(path) { mutableStateOf("") }
    var reloadToken by remember(path) { mutableIntStateOf(0) }

    // 目录扫描在 IO 线程执行：目录文件多时 listFiles() 在主线程会 ANR（等待超时没响应）。
    // 用 loading 标记区分「加载中」和「真的是空目录」。
    var loading by remember(path) { mutableStateOf(true) }
    val entries by produceState<List<java.io.File>>(initialValue = emptyList(), path, reloadToken) {
        loading = true
        value = emptyList() // 切目录时先清掉上一屏残留
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(path)
                if (!dir.isDirectory) emptyList() else dir.listFiles().orEmpty()
                    .filter { !it.name.startsWith(".") }
                    .filter { it.isDirectory || mode == TreePickerMode.FILE }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }.getOrDefault(emptyList())
        }
        loading = false
    }
    val segments = buildList {
        add(MODULE_LABEL_LOCAL to root)
        val rel = path.removePrefix(root).trim('/')
        if (rel.isNotBlank()) {
            var acc = root
            rel.split('/').forEach { seg ->
                acc = "$acc/$seg"
                add(seg to acc)
            }
        }
    }

    fun pick(target: String) {
        selected = if (multiSelect) {
            if (target in selected) selected - target else selected + target
        } else {
            if (target in selected) emptySet() else setOf(target)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 面包屑：每一层都能点回去。
                PathBreadcrumbs(
                    labels = segments.map { it.first },
                    modifier = Modifier.padding(bottom = 6.dp),
                    onCrumbClick = { index -> path = segments[index].second }
                )
                // LazyColumn 懒加载：文件夹里成千上万个文件时只组合可见行，
                // 旧实现 Column+forEach 一次性组合全部行，必然卡顿。
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    if (loading) {
                        item {
                            Text(
                                "正在读取文件夹…",
                                fontSize = 12.sp,
                                color = MutedText,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                            )
                        }
                    } else if (entries.isEmpty()) {
                        item {
                            Text(
                                if (mode == TreePickerMode.FILE) "这个文件夹里没有可上传的文件" else "这个文件夹里没有子文件夹",
                                fontSize = 12.sp,
                                color = MutedText,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                            )
                        }
                    }
                    items(entries, key = { it.absolutePath }) { entry ->
                        val checked = entry.absolutePath in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    if (entry.isDirectory) path = entry.absolutePath
                                    else pick(entry.absolutePath)
                                }
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                                null,
                                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MutedText,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(
                                    entry.name,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (entry.isFile) {
                                    Text(
                                        com.yaoahos.myfilebox.android.data.LocalRepository.formatSize(entry.length()),
                                        fontSize = 11.sp,
                                        color = MutedText,
                                        maxLines = 1
                                    )
                                }
                            }
                            // 右侧只有一个「勾选」圆圈：选中 ≠ 确认，确认在底部按钮。
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable { pick(entry.absolutePath) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (checked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    if (checked) "已选中" else "选中",
                                    tint = if (checked) MaterialTheme.colorScheme.primary else MutedText.copy(alpha = 0.45f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                if (allowCreateFolder) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "在当前文件夹下新建子文件夹",
                            fontSize = 12.sp,
                            color = MutedText,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { showNewFolder = !showNewFolder; createError = "" },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Outlined.CreateNewFolder, null, modifier = Modifier.size(17.dp))
                            Text(
                                if (showNewFolder) "收起" else "新建",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                    if (showNewFolder) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newFolderName,
                                onValueChange = { newFolderName = it; createError = "" },
                                singleLine = true,
                                placeholder = { Text("子文件夹名称", fontSize = 13.sp) },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                shape = RoundedCornerShape(12.dp),
                                colors = dialogFieldColors(),
                                modifier = Modifier.weight(1f).height(52.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val name = newFolderName.trim()
                                    if (name.isNotBlank()) {
                                        val dir = java.io.File(path, name)
                                        val ok = runCatching {
                                            if (dir.isDirectory) false else dir.mkdirs()
                                        }.getOrDefault(false)
                                        if (ok) {
                                            // 新建后直接选中，少点一次「选择」。
                                            selected = setOf(dir.absolutePath)
                                            newFolderName = ""
                                            showNewFolder = false
                                            createError = ""
                                            reloadToken++
                                        } else {
                                            createError = "无法在这里新建文件夹，可能没有写入权限"
                                        }
                                    }
                                },
                                enabled = newFolderName.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(52.dp)
                            ) { Text("新建", fontSize = 13.sp) }
                        }
                        if (createError.isNotBlank()) {
                            Text(
                                createError,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                Text(
                    hint,
                    fontSize = 12.sp,
                    color = MutedText,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            // 文件夹模式没勾选任何行时，默认选中「当前所在文件夹」。
            val ready = selected.isNotEmpty() || mode == TreePickerMode.FOLDER
            TextButton(
                enabled = ready,
                onClick = {
                    if (selected.isNotEmpty()) onConfirm(selected.toList()) else onConfirm(listOf(path))
                }
            ) {
                Text(
                    if (mode == TreePickerMode.FILE && selected.size > 1) "选择 ${selected.size} 个" else "选择"
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 全局统一的下拉刷新容器。
 *
 * 指示器用 Material3 默认的浅色圆底（`surfaceContainerHigh`），只把里面的箭头 /
 * 转圈换成主题色：圆底跟着主题色的「面」走，不会像纯主色圆底那样在浅色页面上
 * 抢眼；箭头跟着 primary，换主题配色时同步变，视觉上是一套。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val pullState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    // 兜底收圈：拉完松手后，指示器有时会一直停在标题栏上方不动。
    //
    // 实测（logcat MFB-PULL）：手指松开时 M3 会把位置动画到「阈值」（fraction 1.0）
    // 并调用 onRefresh；本地目录刷新只要 9ms，isRefreshing 从 true 落回 false 全部发生在
    // 同一帧之内，Compose 根本没观测到 true，于是它那条「刷新结束 → animateToHidden()」
    // 永远不会触发，位置就永久停在 1.0：圆圈和箭头明明白白留在屏幕顶部
    // （用户反馈的「刷新完卡住」）。网络慢的时候反而正常，所以只在本地目录容易撞上。
    //
    // 这里不再依赖「观测到 isRefreshing 变化」：由我们自己发起的那次刷新结束后，
    // 等阈值动画走完（isAnimating 落回 false）就把残余位置收回去。只有真的拉过才会跑，
    // 不会跟手指抢——手指在拉的时候 onRefresh 还没被调用过。
    val refreshingNow by rememberUpdatedState(isRefreshing)
    val startRefresh: () -> Unit = {
        onRefresh()
        scope.launch {
            // 最多看两分钟：刷新再慢（切网自愈重登）也该结束了，不会真跑满。
            repeat(1000) {
                kotlinx.coroutines.delay(120L)
                if (refreshingNow || pullState.isAnimating) return@repeat
                settlePullIndicator(pullState)
                return@launch
            }
        }
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = startRefresh,
        state = pullState,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isRefreshing,
                // 页面内容都避开了状态栏，指示器同样避开，不要压在时间和信号上面。
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                // containerColor 不传 = 用 Material3 默认的浅色圆底（之前的观感）。
                color = MaterialTheme.colorScheme.primary
            )
        },
        content = content
    )
}

/**
 * 把下拉指示器彻底收回起点。
 *
 * 为什么不能只调一次 [PullToRefreshState.animateToHidden]：它和指示器自己那条
 * 「松手后动画到阈值」共用同一个 Animatable，两者会互相取消。取消发生在动画中途时
 * 位置就停在半路——正是「刷新完了，圆圈箭头还挂在标题栏上方」的最终形态。
 *
 * 所以先跑 M3 的收起动画，再盯一小段时间：只要还有残余位置、而且此刻没有动画在跑，
 * 就 [PullToRefreshState.snapTo] 一口气写死到 0。snapTo 是这里唯一不依赖
 * 「另一条协程配不配合」的动作，取消当前动画并立刻落位。
 */
@OptIn(ExperimentalMaterial3Api::class)
private suspend fun settlePullIndicator(state: PullToRefreshState) {
    if (state.distanceFraction > 0f) runCatching { state.animateToHidden() }
    repeat(3) {
        if (state.distanceFraction <= 0f) return
        kotlinx.coroutines.delay(60L)
        if (state.distanceFraction > 0f && !state.isAnimating) runCatching { state.snapTo(0f) }
    }
}
