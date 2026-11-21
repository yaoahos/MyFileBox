package com.yaoahos.myfilebox.core

/**
 * 内部存储根目录（`/storage/emulated/0`）唯一的用户可见名。
 *
 * 全应用只认这一个叫法：面包屑、文件夹选择器、搜索位置、详情「所在位置」、提示文案
 * 全部用它。以前有的地方叫「根目录」、有的叫「内部存储」，同一个位置出现两个名字，
 * 用户会以为是两个不同的目录。
 */
const val STORAGE_ROOT_LABEL = "内部存储"

/**
 * 面包屑第一层：模块名，三个模块（底栏三个 Tab）对称。
 *
 * 第一层只说「这是哪个模块」，第二层起才是用户真正走过的东西——本地是首页里的
 * 文件夹、网络是连接名、群晖是共享文件夹。不要再往中间插「网络存储」「群晖 NAS」
 * 「内部存储」这类结构层：它们既不是页面，点了也回不去，只会把面包屑挤成
 * 「网络存储 › 网络文件 › 我的群晖 › WebDAV › Backup」这种谁也读不懂的长串。
 *
 * [STORAGE_ROOT_LABEL] 是**根目录**的名字（用在「所在位置」「已解压到」），
 * 不是模块名；两者别混用。
 */
const val MODULE_LABEL_LOCAL = "本地文件"
const val MODULE_LABEL_SYNO = "群晖文件"
const val MODULE_LABEL_NET = "网络文件"

/**
 * 路径末段的用户可见名。
 *
 * 内部存储根目录是 `/storage/emulated/0`，末段正好是「0」。各处直接取末段当名字，
 * 就会在详情、提示、设置项里冒出「已解压到0」「所在位置 0」这类机器路径。
 * 这里统一把根目录归一成 [rootLabel]，其余情况仍取末段。
 *
 * [root] 传调用方的根目录（UI 层 `state.localRoot`，数据层 `root().absolutePath`）。
 */
fun displayLeaf(path: String, root: String, rootLabel: String = STORAGE_ROOT_LABEL): String {
    val trimmed = path.trim().trimEnd('/')
    if (trimmed.isBlank() || trimmed == "0") return rootLabel
    if (trimmed == root.trimEnd('/')) return rootLabel
    return trimmed.substringAfterLast('/').ifBlank { rootLabel }
}

/**
 * 文件所在父目录的用户可见名，供「所在位置 / 已还原到 / 已解压到」复用。
 *
 * 例如 `/storage/emulated/0/photo.jpg` → 「内部存储」，`/sdcard/A/B/x.txt` → 「B」。
 */
fun displayParent(path: String, root: String, rootLabel: String = STORAGE_ROOT_LABEL): String =
    displayLeaf(path.trim().trimEnd('/').substringBeforeLast('/'), root, rootLabel)
