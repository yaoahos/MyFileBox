package com.yaoahos.myfilebox.core

enum class FileKind { IMAGE, VIDEO, DOCUMENT, PDF, SHEET, AUDIO, ARCHIVE, APK, FOLDER, UNKNOWN }

enum class TabKey { LOCAL, SYNO, NET }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * A real file or folder. [path] is an absolute local path, a DSM path
 * ("/photo/2026") or a protocol path ("/team/design"). [id] is stable for
 * the current listing and can be the same path.
 */
data class FileEntry(
    val id: String,
    val name: String,
    val kind: FileKind,
    val sizeLabel: String = "",
    val timeLabel: String = "",
    val folder: String = "",
    val childCount: Int = 0,
    val path: String = "",
    val sizeBytes: Long = 0L,
    val modified: Long = 0L,
    val mime: String = ""
) {
    val isFolder: Boolean get() = kind == FileKind.FOLDER
}

data class ShareFolder(
    val key: String,
    val title: String,
    val subtitle: String,
    val count: Int,
    val items: List<FileEntry> = emptyList(),
    val path: String = ""
)

data class NetworkConnection(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val path: String,
    val online: Boolean,
    val latency: Int,
    val username: String = "",
    val password: String = "",
    val secure: Boolean = false
)

data class ThemePrefs(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Long = 0xFF1F6BFF,
    /**
     * Material You 动态取色：从桌面壁纸取一套配色（Android 12+）。
     *
     * 关闭时用 [accent] 自己算深浅色方案；开启时 [accent] 仍然保留，
     * 用户随时可以切回自选色，不用重新挑一遍。
     */
    val dynamic: Boolean = false,
    /**
     * 应用图标是否跟随主题色。false = 原始蓝色图标（应用内与桌面启动图标都用原图），
     * true = 图标底色由 [accent] / 动态取色的主色派生。桌面启动图标是静态资源，
     * 只能按色相档切换，见 android 模块的 AppIconSwitcher。
     */
    val iconFollowsTheme: Boolean = false
)

/** 群晖官方共享链接（SYNO.FileStation.Sharing list/create 的结果）。 */
data class ShareLink(
    val id: String,
    val path: String,
    val url: String,
    val hasPassword: Boolean = false,
    val expireAt: Long = 0L, // 0 = 永不失效；否则为到期时间戳（毫秒）
    val invalid: Boolean = false // 链接目标已不存在 / 未返回 url
)

data class AppPrefs(
    val defaultTab: TabKey = TabKey.LOCAL,
    val showLocal: Boolean = true,
    val showSyno: Boolean = true,
    val showNet: Boolean = true,
    val tabOrder: List<TabKey> = listOf(TabKey.LOCAL, TabKey.SYNO, TabKey.NET),
    val downloadFolder: String = "",
    /** 下载文件夹时同时传输的文件数，1~6；数值越大越快，但对 NAS / 弱网压力也越大。 */
    val downloadConcurrency: Int = 3,
    val theme: ThemePrefs = ThemePrefs(),
    val moduleOrder: List<String> = listOf(
        "local:categories", "local:folders",
        "syno:shares"
    ),
    val hiddenModules: Set<String> = emptySet(),
    val volumeId: String = "",
    val sectionViews: Map<String, String> = mapOf(
        "localRecent" to "list",
        "localFolders" to "grid",
        "synoShares" to "grid",
        "synoRecent" to "list"
    ),
    val viewMode: String = "grid",
    val tabViews: Map<String, String> = mapOf("local" to "grid", "syno" to "grid", "net" to "grid"),
    val favorites: Set<String> = emptySet(),
    val sortKey: String = "name",
    val sortDir: String = "asc",
    /** 每个底栏独立的排序：key = "local_key"/"local_dir"/"syno_key"/…，空 = 用全局 sortKey/sortDir。 */
    val tabSorts: Map<String, String> = emptyMap(),
    val recycleDays: Int = 30,
    /**
     * 在线打开 / 预览产生的本机缓存保留天数：0 = 永不自动清理。
     * 缓存目录是 cacheDir 下的 open-* / preview-* / thumbs / uploads。
     */
    val cacheKeepDays: Int = 7,
    /** 本地文件 8 个分类的展示顺序（清单 4）：分类名列表，空 = 默认顺序。 */
    val categoryOrder: List<String> = emptyList(),
    /**
     * 「始终用某个应用打开」的记忆：key = audio / video / image / 具体 mime，value = 包名。
     * 空 = 每次都弹「打开方式」让用户选。
     */
    val openDefaults: Map<String, String> = emptyMap()
)

/**
 * 有效启动页：用户设置的默认页若仍可见就用它，否则退到第一个可见页，
 * 再退到本地文件。冷启动、导入配置、返回到首页三条路径必须共用这一口径，
 * 否则会出现「从群晖一路返回却落到本地文件」这类与默认启动页不符的行为。
 */
fun effectiveDefaultTab(prefs: AppPrefs): TabKey {
    val visible = prefs.tabOrder.filter {
        when (it) {
            TabKey.LOCAL -> prefs.showLocal
            TabKey.SYNO -> prefs.showSyno
            TabKey.NET -> prefs.showNet
        }
    }
    return if (prefs.defaultTab in visible) prefs.defaultTab
    else visible.firstOrNull() ?: TabKey.LOCAL
}

data class RecycleItem(
    val id: String,
    val name: String,
    val originalPath: String,
    val trashPath: String,
    val deletedAt: Long,
    val sizeBytes: Long,
    val isFolder: Boolean,
    val childCount: Int = 0
)

data class Volume(
    val id: String,
    val name: String,
    val usedTb: Double,
    val totalTb: Double,
    val percent: Int,
    val type: String
)

data class Session(
    val server: String,
    val account: String,
    val name: String,
    val nas: String,
    val mode: String,
    val sid: String = "",
    /** DSM 7 的 CSRF 令牌：登录时带 enable_syno_token=yes 获得，写操作（上传/新建/删除）必须带。 */
    val synoToken: String = "",
    val https: Boolean = true,
    /** Resolved base URL used for API calls (QuickConnect may resolve to a LAN/DDNS host). */
    val endpoint: String? = null
)

data class FolderCard(
    val name: String,
    val meta: String,
    val kind: FileKind,
    val path: String = "",
    val date: String = "",
    val count: Int = 0
)

/** Helpers shared by the UI and data layer. */
object FileKinds {
    val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
    val videoExt = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "3gp")
    val audioExt = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "ape")
    val docExt = setOf("doc", "docx", "txt", "md", "rtf", "pages", "odt")
    val sheetExt = setOf("xls", "xlsx", "csv", "numbers", "ods")
    val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")
    val apkExt = setOf("apk", "apks", "xapk")

    fun of(name: String): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in imageExt -> FileKind.IMAGE
            in videoExt -> FileKind.VIDEO
            in audioExt -> FileKind.AUDIO
            in docExt -> FileKind.DOCUMENT
            in sheetExt -> FileKind.SHEET
            in archiveExt -> FileKind.ARCHIVE
            in apkExt -> FileKind.APK
            "pdf" -> FileKind.PDF
            else -> FileKind.UNKNOWN
        }
    }
}
