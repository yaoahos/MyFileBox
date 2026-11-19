package com.yaoahos.myfilebox.core

/**
 * 路径末段的用户可见名。
 *
 * 内部存储根目录是 `/storage/emulated/0`，末段正好是「0」。各处直接取末段当名字，
 * 就会在详情、提示、设置项里冒出「已解压到0」「所在位置 0」这类机器路径。
 * 这里统一把根目录归一成 [rootLabel]，其余情况仍取末段。
 *
 * [root] 传调用方的根目录（UI 层 `state.localRoot`，数据层 `root().absolutePath`）。
 */
fun displayLeaf(path: String, root: String, rootLabel: String = "根目录"): String {
    val trimmed = path.trim().trimEnd('/')
    if (trimmed.isBlank() || trimmed == "0") return rootLabel
    if (trimmed == root.trimEnd('/')) return rootLabel
    return trimmed.substringAfterLast('/').ifBlank { rootLabel }
}

/**
 * 文件所在父目录的用户可见名，供「所在位置 / 已还原到 / 已解压到」复用。
 *
 * 例如 `/storage/emulated/0/photo.jpg` → 「根目录」，`/sdcard/A/B/x.txt` → 「B」。
 */
fun displayParent(path: String, root: String, rootLabel: String = "根目录"): String =
    displayLeaf(path.trim().trimEnd('/').substringBeforeLast('/'), root, rootLabel)
