package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本地收藏「深层条目」解析的路径筛选。
 *
 * 首页快照只有存储根一层的文件夹，深层文件夹收藏后要去按路径反查条目；这里
 * 只测「哪些 key 该反查」——反查本身是文件系统调用，由真机与手工验证覆盖。
 */
class LocalFavoriteResolveTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `存储根之下的深层路径都要反查`() {
        val keys = setOf(
            "$root/DCIM/Camera",
            "$root/Download",
            "$root/DCIM/Camera/2026"
        )
        assertEquals(
            listOf("$root/DCIM/Camera", "$root/Download", "$root/DCIM/Camera/2026"),
            favoritePathsToResolve(keys, root)
        )
    }

    @Test
    fun `folder 前缀的首页卡片写法同样反查`() {
        assertEquals(
            listOf("$root/Movies"),
            favoritePathsToResolve(setOf("folder:$root/Movies"), root)
        )
    }

    @Test
    fun `群晖路径不反查`() {
        // 群晖收藏是服务器上的 /photo/2026，与本机文件系统无关。
        assertEquals(
            emptyList<String>(),
            favoritePathsToResolve(setOf("/photo/2026", "/home/yao"), root)
        )
    }

    @Test
    fun `网络位置的 key 不反查`() {
        val keys = setOf(
            "net:abc:$root/Backup",              // 带 net: 前缀
            "smb://192.168.1.10:445/Backup/a.txt",
            "https://nas.local/dav/photo.jpg"
        )
        assertEquals(emptyList<String>(), favoritePathsToResolve(keys, root))
    }

    @Test
    fun `存储根本身与其它卷不反查`() {
        val keys = setOf(
            root,                       // 根目录不可收藏
            "/storage/1A2B-3C4D/DCIM",  // 外置卡不在本地首页快照范围内
            "/storage/emulated/1/DCIM"  // 另一卷的更不在范围内
        )
        assertEquals(emptyList<String>(), favoritePathsToResolve(keys, root))
    }

    @Test
    fun `重复 key 与尾斜杠只反查一次`() {
        val keys = setOf("$root/DCIM/", "$root/DCIM", "folder:$root/DCIM")
        assertEquals(listOf("$root/DCIM"), favoritePathsToResolve(keys, root))
    }

    @Test
    fun `存储根未知时不做任何反查`() {
        assertEquals(emptyList<String>(), favoritePathsToResolve(setOf("/sdcard/DCIM"), ""))
    }

    // ---- 合并三个来源 ----

    private fun entry(name: String, path: String, folder: Boolean = false) = FileEntry(
        id = path,
        name = name,
        kind = if (folder) FileKind.FOLDER else FileKind.DOCUMENT,
        path = path
    )

    @Test
    fun `深层反查出来的文件夹能进收藏列表`() {
        val deep = entry("Camera", "$root/DCIM/Camera", folder = true)
        val merged = mergeLocalFavorites(
            folders = emptyList(),
            files = emptyList(),
            resolved = listOf(deep),
            favoriteKeys = setOf("$root/DCIM/Camera")
        )
        assertEquals(listOf("Camera"), merged.map { it.name })
    }

    @Test
    fun `folder 前缀的 key 同样认`() {
        val deep = entry("Camera", "$root/DCIM/Camera", folder = true)
        val merged = mergeLocalFavorites(
            emptyList(), emptyList(), listOf(deep), setOf("folder:$root/DCIM/Camera")
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `取消收藏后反查结果不再显示`() {
        val deep = entry("Camera", "$root/DCIM/Camera", folder = true)
        assertEquals(
            emptyList<FileEntry>(),
            mergeLocalFavorites(emptyList(), emptyList(), listOf(deep), emptySet())
        )
    }

    @Test
    fun `快照与反查重叠时同一路径只留一条`() {
        val fromSnapshot = entry("Camera", "$root/DCIM/Camera", folder = true)
        val fromResolve = entry("Camera", "$root/DCIM/Camera/", folder = true)
        val merged = mergeLocalFavorites(
            emptyList(),
            listOf(fromSnapshot),
            listOf(fromResolve),
            setOf("$root/DCIM/Camera")
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `首页文件夹与深层结果按路径去重`() {
        val home = entry("DCIM", "$root/DCIM", folder = true)
        val resolved = entry("DCIM", "$root/DCIM", folder = true)
        val merged = mergeLocalFavorites(
            listOf(home), emptyList(), listOf(resolved), setOf("$root/DCIM")
        )
        assertEquals(1, merged.size)
    }
}
