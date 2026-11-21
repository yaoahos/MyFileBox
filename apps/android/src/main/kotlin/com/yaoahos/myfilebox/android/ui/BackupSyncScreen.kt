package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.displayLeaf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaoahos.myfilebox.android.data.BackupCrypto
import com.yaoahos.myfilebox.android.data.CloudConfigFile
import com.yaoahos.myfilebox.android.data.ConfigBackup
import com.yaoahos.myfilebox.android.data.SyncInterval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 备份与同步。
 *
 * 版面顺序照用户要求：**备份在上、同步在下**。两块都只做「配置」的搬运，
 * 不涉及文件内容——备份文件本身就是密文，丢了密码谁也解不开。
 */
@Composable
fun BackupSyncScreen(state: MyFileBoxState, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 后台任务会写「上次同步」，每次进页面重读一次，别拿旧时间骗人。
    LaunchedEffect(Unit) { vm.refreshSyncState() }

    var backupDialog by remember { mutableStateOf(false) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importName by remember { mutableStateOf("") }
    // 恢复失败的文字留在弹窗里：弹窗是独立窗口，页面顶部提示会被它整块挡住。
    var importError by remember { mutableStateOf<String?>(null) }
    var importErrorKey by remember { mutableStateOf(0) }
    var syncDialog by remember { mutableStateOf(false) }
    var intervalDialog by remember { mutableStateOf(false) }
    var restoreDialog by remember { mutableStateOf(false) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    var cloudErrorKey by remember { mutableStateOf(0) }
    var deviceDialog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val pickBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 从网盘类 DocumentsProvider（OneDrive / Google Drive）读一份备份要先把整块
        // 内容拉下来，几十 KB 也可能几百毫秒。ActivityResult 回调在主线程，以前在这里
        // 同步 readBytes，表现就是「选完文件卡一下」——本机恢复「慢」的一半是这个。
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                vm.showMessage("无法读取该文件")
            } else {
                importBytes = bytes
                importName = displayName(context, uri) ?: "备份文件"
                importError = null
                importErrorKey = 0
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        SubPageHeader("备份与同步", vm::closeScreen)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 28.dp)) {

            // ---------------------------------------------------------- 备份（上）
            item {
                SettingsSection("备份") {
                    BackupRow(
                        icon = Icons.Outlined.Save,
                        title = "备份到本机",
                        subtitle = displayLeaf(state.prefs.downloadFolder, state.localRoot, "默认下载位置") +
                            " · 加密文件",
                        enabled = !state.backupBusy,
                        onClick = { backupDialog = true }
                    )
                    BackupRow(
                        icon = Icons.Outlined.Restore,
                        title = "从本机备份恢复",
                        subtitle = "支持 .mfbx 备份文件",
                        enabled = !state.backupBusy,
                        onClick = { pickBackup.launch(arrayOf("*/*")) }
                    )
                    // 两个恢复弹窗自己会显示「正在校验密码并恢复…」和错误文案。
                    // 这里再画一行，弹窗开着时它会在弹窗背后闪进闪出——用户看到的就是
                    // 「底下闪了一下什么提示」。所以只在没有弹窗时显示页面内进度。
                    val inlineBusy = state.backupBusy &&
                        importBytes == null && !restoreDialog && !backupDialog
                    if (inlineBusy) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("正在处理…", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                    Hint("备份 8 类设置，不含文件内容；以 AES-256-GCM 加密，密码丢失即无法解密。")
                }
            }

            // ---------------------------------------------------------- 同步（下）
            item {
                SettingsSection("同步") {
                    // 显示解析后的真实落点：地址栏允许粘整条 URL，直接回显原文会出现
                    // 「https://…/dav/:443」这种看不出到底传去哪儿的字符串。
                    val endpoint = remember(state.sync) { state.sync.connection() }
                    BackupRow(
                        icon = Icons.Outlined.Cloud,
                        title = "云同步（WebDAV）",
                        subtitle = if (state.sync.configured) {
                            "${endpoint.host}:${endpoint.port}${endpoint.path}"
                        } else "未配置",
                        onClick = { syncDialog = true }
                    )
                    if (state.sync.configured) {
                        BackupRow(
                            Icons.Outlined.Schedule,
                            "自动同步",
                            // 开关并入这里：选「关闭」即不同步，选任何周期即按该周期上传。
                            state.sync.interval.label,
                            onClick = { intervalDialog = true }
                        )
                        val lastSyncText =
                            if (state.sync.lastSyncAt <= 0L) state.sync.lastResult.ifBlank { "还没有同步过" }
                            else "${formatSyncTime(state.sync.lastSyncAt)} · ${state.sync.lastResult}"
                        // 失败要一眼能看出来：后台重试是静默的，若这里还和成功一个颜色，
                        // 用户会以为配置一直在正常上传。
                        val syncFailed = listOf("失败", "无法", "超时", "将自动重试", "没有完成")
                            .any { state.sync.lastResult.contains(it) }
                        BackupRow(
                            Icons.Outlined.Cloud,
                            "上次同步",
                            lastSyncText,
                            enabled = false,
                            subtitleColor = if (syncFailed) MaterialTheme.colorScheme.error else MutedText
                        )
                        BackupRow(
                            Icons.Outlined.Smartphone,
                            "本机配置名",
                            state.sync.deviceName.ifBlank { vm.deviceName() },
                            onClick = { deviceDialog = true }
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    // 没设过配置密码时上传必然失败（密钥无从派生）。
                                    // 直接引到设置对话框，而不是让用户先看一句「至少 8 位」再自己找入口。
                                    if (!state.sync.hasCipher) syncDialog = true else vm.uploadConfigNow()
                                },
                                enabled = !state.syncBusy && !state.backupBusy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Outlined.CloudUpload, null, modifier = Modifier.size(15.dp))
                                Text(
                                    if (state.sync.hasCipher) "立即上传" else "设置密码后上传",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 5.dp)
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                vm.refreshCloudFiles()
                                cloudError = null
                                cloudErrorKey = 0
                                restoreDialog = true
                            },
                                enabled = !state.syncBusy && !state.backupBusy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(15.dp))
                                Text("从云端恢复", fontSize = 12.sp, modifier = Modifier.padding(start = 5.dp))
                            }
                        }
                        if (!state.sync.hasCipher) {
                            Hint("还没有设置配置密码，上传前必须先设置一个。")
                        }
                        if (state.syncBusy) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("正在与云端通信…", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                        Hint(
                            "云端按设备各存一份，文件名带设备名与 4 位短码，同型号两台设备也不会互相覆盖；" +
                                "本机只覆盖自己那一份。换机或重装后，在新设备上从云端恢复即可。"
                        )
                        Text(
                            "清除云同步设置",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .clickable { confirmClear = true }
                        )
                    } else {
                        Hint("配置密码用于加密备份，丢失后无法恢复。")
                    }
                }
            }

            item {
                SettingsSection("安全") {
                    BackupRow(
                        Icons.Outlined.Lock,
                        "本地保存的凭据",
                        "加密保存在本机，仅用于同步",
                        enabled = false
                    )
                    BackupRow(
                        Icons.Outlined.Info,
                        "为什么密码不能丢",
                        "密钥只由口令派生，丢失即不可解密",
                        enabled = false
                    )
                }
            }
        }
    }

    if (backupDialog) {
        PasswordDialog(
            title = "设置备份密码",
            hint = "至少 ${BackupCrypto.MIN_PASSWORD_LENGTH} 位，忘记后这份备份无法恢复。",
            confirmLabel = "开始备份",
            confirmTwice = true,
            onDismiss = { backupDialog = false },
            onConfirm = { pwd ->
                backupDialog = false
                vm.backupToLocal(pwd)
            }
        )
    }

    importBytes?.let { bytes ->
        PasswordDialog(
            title = "输入配置密码",
            hint = "输入密码后点「恢复」，将用「$importName」覆盖本机 8 类设置；文件与缓存不受影响。",
            confirmLabel = "恢复",
            confirmTwice = false,
            error = importError,
            errorKey = importErrorKey,
            busy = state.backupBusy,
            busyText = "正在校验密码并恢复…",
            onDismiss = { importBytes = null },
            onConfirm = { pwd ->
                // 只在恢复成功后关掉弹窗；密码错误时保留文件和输入状态，用户可直接重输。
                vm.restoreFromLocal(bytes, pwd) { ok, err ->
                    if (ok) importBytes = null
                    else {
                        importError = err
                        importErrorKey += 1
                    }
                }
            }
        )
    }

    if (syncDialog) {
        SyncConfigDialog(
            state = state,
            onDismiss = { syncDialog = false },
            onSave = { host, port, path, user, pass, secure, cipher ->
                syncDialog = false
                vm.saveSyncConfig(host, port, path, user, pass, secure, cipher)
            }
        )
    }

    if (intervalDialog) {
        AlertDialog(
            onDismissRequest = { intervalDialog = false },
            title = { Text("自动同步", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    SyncInterval.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    intervalDialog = false
                                    vm.setSyncInterval(option.hours)
                                }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.sync.interval == option,
                                onClick = {
                                    intervalDialog = false
                                    vm.setSyncInterval(option.hours)
                                }
                            )
                            Text(option.label, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    Text(
                        "后台按周期上传本机配置，省电策略下可能延后。",
                        fontSize = 12.sp,
                        color = MutedText,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = { TextButton(onClick = { intervalDialog = false }) { Text("取消") } }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除云同步设置", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "会删除本机保存的连接信息与配置密码，并停止自动同步。" +
                        "云端备份不受影响；重新开启时需要输入原来的配置密码。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearSyncConfig()
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }

    if (restoreDialog) {
        CloudRestoreDialog(
            files = state.cloudFiles,
            loading = state.cloudFilesLoading,
            restoring = state.backupBusy,
            error = state.cloudFilesError,
            onRetry = { vm.refreshCloudFiles() },
            onDismiss = { restoreDialog = false },
            actionError = cloudError,
            actionErrorKey = cloudErrorKey,
            onRestore = { file, pwd ->
                // 云端文件同理：密码错误不关闭弹窗，避免重新选择文件和重新输入地址。
                vm.syncRestoreFrom(file.fileName, pwd) { ok, err ->
                    if (ok) restoreDialog = false
                    else {
                        cloudError = err
                        cloudErrorKey += 1
                    }
                }
            }
        )
    }

    if (deviceDialog) {
        DeviceNameDialog(
            current = state.sync.deviceName.ifBlank { vm.deviceName() },
            onDismiss = { deviceDialog = false },
            onConfirm = { name ->
                deviceDialog = false
                vm.setDeviceName(name)
            }
        )
    }
}

/** 需要单独输入密码时用它：备份（新建密码，两次确认）与恢复（输入密码）。 */
@Composable
private fun PasswordDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    confirmTwice: Boolean,
    error: String? = null,
    errorKey: Int = 0,
    busy: Boolean = false,
    busyText: String = "正在处理…",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val mismatch = confirmTwice && second.isNotEmpty() && first != second
    // 密码输入满 8 位就在后台预热固定盐校验值：用户还在读提示/点按钮的这几百毫秒里
    // 把唯一一次必要的 KDF 提前算掉，点恢复时校验值已在缓存，错密码零等待、正确密码只剩解密。
    // 注意只预热第一个密码框；确认框（备份）的值与第一个相同，无需重复。
    LaunchedEffect(first) {
        if (first.length >= BackupCrypto.MIN_PASSWORD_LENGTH) {
            // 去抖：还在打字时每多一个字符都会重启这个协程，先等一小段再预热。
            // 否则输入一个 12 位密码会白烧好几轮 21 万次 PBKDF2，把真正那次的
            // 预热拖到点击之后——本机恢复「慢」有一半是这么来的。
            delay(200)
            val pwd = first.toCharArray()
            withContext(Dispatchers.IO) { BackupCrypto.warmUp(pwd) }
            pwd.fill('\u0000')
        }
    }
    // 失败就清空：错误密码留在框里既容易让人以为已经填对，下一次点击也会带着旧值。
    // 同一口令的校验值已在内存缓存，重输相同口令仍是零 KDF，不会因此变慢。
    LaunchedEffect(errorKey) {
        if (errorKey > 0) {
            first = ""
            second = ""
        }
    }
    FormDialog(
        title = title,
        onDismiss = onDismiss,
        spacing = 9.dp,
        confirmButton = {
            Button(
                onClick = { onConfirm(first) },
                enabled = !busy && first.length >= BackupCrypto.MIN_PASSWORD_LENGTH && (!confirmTwice || first == second),
                shape = RoundedCornerShape(12.dp)
            ) { Text(confirmLabel) }
        }
    ) {
        // 高度与滚动由 FormDialog 统一处理：键盘把窗口压矮时内容自己滚动，
        // 这里再加 ime 内边距会把键盘高度算第二遍、留一块空白。
        PasswordField(first, { first = it }, label = "配置密码", modifier = Modifier.fillMaxWidth())
        if (confirmTwice) {
            PasswordField(
                second,
                { second = it },
                label = "再输入一次",
                isError = mismatch,
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, lineHeight = 16.sp) }
        Text(hint, fontSize = 12.sp, color = MutedText, lineHeight = 16.sp)
        if (mismatch) {
            Text("两次输入的密码不一致", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(busyText, fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SyncConfigDialog(
    state: MyFileBoxState,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, Boolean, String) -> Unit
) {
    val sync = state.sync
    var host by remember { mutableStateOf(sync.host) }
    var port by remember { mutableStateOf(sync.port.toString()) }
    var path by remember { mutableStateOf(sync.path) }
    var user by remember { mutableStateOf(sync.username) }
    var pass by remember { mutableStateOf(sync.password) }
    var secure by remember { mutableStateOf(sync.secure) }
    var cipher by remember { mutableStateOf("") }

    // 内容区自己限高并滚动。
    //
    // 这里有两层约束在打架：AlertDialog 的窗口在键盘弹出时会随 adjustResize 缩小，
    // 但 M3 的 text 槽不会主动让内容变矮，字段一多就顶出可用区域、按钮被键盘盖住；
    // 而再补一层 imePadding 又会把键盘高度重复算一遍，键盘上方留出一大块空白。
    // 限高改成「屏高 - 键盘 - 标题按钮开销」后，横屏与竖屏键盘态都不会溢出；
    // 屏高过低（横屏 + 键盘）时 FormDialog 自己切到紧凑形态，按钮始终可见。
    FormDialog(
        title = "云同步设置",
        onDismiss = onDismiss,
        spacing = 9.dp,
        confirmButton = {
            Button(
                onClick = { onSave(host, port, path, user, pass, secure, cipher) },
                enabled = host.isNotBlank() && (sync.hasCipher || cipher.length >= BackupCrypto.MIN_PASSWORD_LENGTH),
                shape = RoundedCornerShape(12.dp)
            ) { Text("保存并开启") }
        }
    ) {
        OutlinedTextField(
            host, { host = it },
            label = { Text("服务器地址或域名") },
            singleLine = true, shape = RoundedCornerShape(12.dp),
            colors = dialogFieldColors(), modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                port, { port = it.filter { c -> c.isDigit() } },
                label = { Text("端口") },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                colors = dialogFieldColors(),
                modifier = Modifier.weight(0.38f)
            )
            OutlinedTextField(
                path, { path = it },
                label = { Text("目录") },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                colors = dialogFieldColors(),
                modifier = Modifier.weight(0.62f)
            )
        }
        OutlinedTextField(
            user, { user = it },
            label = { Text("账号") },
            singleLine = true, shape = RoundedCornerShape(12.dp),
            colors = dialogFieldColors(), modifier = Modifier.fillMaxWidth()
        )
        PasswordField(pass, { pass = it }, label = "密码（留空则不修改）")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = secure, onCheckedChange = { secure = it })
            Text(
                "使用 HTTPS（自签名证书也可连接）",
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        PasswordField(
            cipher, { cipher = it },
            label = if (sync.hasCipher) "新配置密码（留空不修改）" else "配置密码（至少 8 位）"
        )
        Text(
            "用于加密备份文件，与 WebDAV 密码可以不同；忘记则云端备份无法恢复。",
            fontSize = 12.sp,
            color = MutedText,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun BackupRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    subtitleColor: Color = MutedText
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        Column(Modifier.weight(1f).padding(start = 11.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = subtitleColor, modifier = Modifier.padding(top = 1.dp))
        }
        trailing?.invoke()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MutedText,
        lineHeight = 17.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
    )
}

/** SAF 返回的 Uri 没有文件名，按 OpenableColumns 取一次显示名。 */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
}.getOrNull()

/**
 * 从云端恢复：先选哪台设备的配置，再输密码。
 *
 * 默认选中本机那份，但允许改选别的设备——换机、重装、误删之后，
 * 「另一台设备上那份旧配置」往往才是用户真正想拿回来的东西。
 */
@Composable
private fun CloudRestoreDialog(
    files: List<CloudConfigFile>,
    loading: Boolean,
    restoring: Boolean = false,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    actionError: String? = null,
    actionErrorKey: Int = 0,
    onRestore: (CloudConfigFile, String) -> Unit
) {
    // 列表刷新后尽量维持用户的选择；首次进入默认落在本机那份上。
    var selected by remember(files) {
        mutableStateOf(files.firstOrNull { it.isLocal }?.fileName ?: files.firstOrNull()?.fileName)
    }
    var cipher by remember { mutableStateOf("") }
    // 输入满 8 位后台预热固定盐校验值，点恢复时校验大概率已在缓存。
    // 这里同样去抖：每多敲一个字符都会重启协程，不等一下会白算好几轮 PBKDF2。
    LaunchedEffect(cipher) {
        if (cipher.length >= BackupCrypto.MIN_PASSWORD_LENGTH) {
            delay(200)
            val pwd = cipher.toCharArray()
            withContext(Dispatchers.IO) { BackupCrypto.warmUp(pwd) }
            pwd.fill('\u0000')
        }
    }
    // 密码错误就清空输入框；错误原因只显示在弹窗里（见下方 actionError）。
    // 之前这里只是传了 key 却没有消费，出错后旧密码一直留在框里。
    LaunchedEffect(actionErrorKey) {
        if (actionErrorKey > 0) cipher = ""
    }
    val chosen = files.firstOrNull { it.fileName == selected }
    val ready = !loading && !restoring && chosen != null && cipher.length >= BackupCrypto.MIN_PASSWORD_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从云端恢复", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "正在读取云端配置…",
                            fontSize = 12.sp,
                            color = MutedText,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                    error != null -> Column {
                        Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, lineHeight = 16.sp)
                        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                            Text("重试", fontSize = 12.sp)
                        }
                    }
                    files.isEmpty() -> Text(
                        "云端还没有配置备份。",
                        fontSize = 12.sp,
                        color = MutedText,
                        lineHeight = 16.sp
                    )
                    else -> {
                        Text("选择要恢复的配置：", fontSize = 12.sp, color = MutedText)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 208.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 4.dp)
                        ) {
                            files.forEach { file ->
                                val on = file.fileName == selected
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                            else Color.Transparent
                                        )
                                        .clickable { selected = file.fileName }
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = on, onClick = { selected = file.fileName })
                                    Column(Modifier.weight(1f).padding(start = 2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                // 设备名单独一行；短码挪到第二行。名字长的机型
                                                // （比如这台 Redmi K20 Pro Premium Edition）会把
                                                // 「· x1p6」挤到行尾被省略掉，而那正是同型号两台
                                                // 设备唯一能区分的信息。
                                                file.deviceName,
                                                fontSize = 13.sp,
                                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (file.isLocal) {
                                                Text(
                                                    "本机",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .padding(start = 6.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            listOfNotNull(
                                                file.deviceId.takeIf { it.isNotBlank() }?.let { "短码 $it" },
                                                if (file.modified > 0L) formatSyncTime(file.modified) else "时间未知"
                                            ).joinToString(" · "),
                                            fontSize = 11.sp,
                                            color = MutedText
                                        )
                                    }
                                }
                            }
                        }
                        // 密码框：统一用 PasswordField，眼睛在框内 trailingIcon。
                        PasswordField(
                            cipher,
                            { cipher = it },
                            label = "配置密码",
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        )
                        if (actionError != null) {
                            Text(
                                actionError,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        Text(
                            "会用所选配置覆盖本机 8 类设置；文件与缓存不受影响。" +
                                "密码错误或文件损坏都会给出具体原因。",
                            fontSize = 11.sp,
                            color = MutedText,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (restoring) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text("正在校验密码并恢复…", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let { onRestore(it, cipher) } }, enabled = ready) {
                Text("恢复")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 改本机在云端的配置名：只影响云端文件名，不动设备型号本身。 */
@Composable
private fun DeviceNameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    FormDialog(
        title = "本机配置名",
        onDismiss = onDismiss,
        spacing = 8.dp,
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("保存") }
        }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp),
            label = { Text("显示名", fontSize = 12.sp) },
            shape = RoundedCornerShape(12.dp),
            colors = dialogFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "这个名字会写进云端文件名，方便在列表里认出是哪台设备；" +
                "同型号的两台设备还会各带一个 4 位短码，名字一样也不会互相覆盖。",
            fontSize = 11.sp,
            color = MutedText,
            lineHeight = 15.sp
        )
    }
}
