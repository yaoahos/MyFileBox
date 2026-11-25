package com.yaoahos.myfilebox.android.ui

import com.yaoahos.myfilebox.core.AppPrefs
import com.yaoahos.myfilebox.core.FileEntry
import com.yaoahos.myfilebox.core.FileKind
import com.yaoahos.myfilebox.core.FolderCard

/**
 * 本地首页首帧需要的全部派生数据。
 *
 * 这四份结果都从「全盘扫描结果 + 排序 / 筛选偏好」算出来，其中 [rootFiles] 要过滤
 * 全部文件、[byKind] 要做一次分组、[folders] / [rootItems] 各排一次序。
 * 数据量大时单次计算可达数十万次字符串操作。
 */
internal class HomeDerived(
    /** 根目录直属文件（未排序、未筛选），用于展示「命中 / 总数」。 */
    val rootFiles: List<FileEntry>,
    /** 根目录文件夹，已按当前偏好排序并按筛选过滤。 */
    val folders: List<FolderCard>,
    /** 根目录文件，已按当前偏好排序并按筛选过滤。 */
    val rootItems: List<FileEntry>,
    /** 全盘文件按类型分组，供分类卡直接取用。 */
    val byKind: Map<FileKind, List<FileEntry>>,
    /** 本地收藏（文件 + 文件夹）。 */
    val favorites: List<FileEntry>
)

/**
 * [HomeDerived] 的跨页面缓存。
 *
 * 为什么放在 ViewModel 而不是页面里的 `remember`：切底栏时旧页面被移出组合、新页面
 * 重新组合，页面内的 `remember` 全部失效，于是每次切回本地都要把上面四份结果重算
 * 一遍——这正是「切到本地文件顿一下」的来源。缓存挂在 ViewModel 上，只要扫描结果与
 * 排序 / 筛选偏好没变（引用相同），切多少次都直接命中。
 *
 * 只在主线程调用；`compute` 也是主线程执行，故只用最简单的一致性比较，不加锁。
 */
internal class HomeDerivedCache {
    private var filesRef: List<FileEntry>? = null
    private var foldersRef: List<FolderCard>? = null
    /** 深层收藏的反查结果：变了就说明收藏页条目变了，缓存必须重算。 */
    private var favoritesRef: List<FileEntry>? = null
    private var prefsRef: AppPrefs? = null
    private var rootPath: String = ""
    private var nameFilter: String = ""
    private var extensionFilter: String = ""
    private var value: HomeDerived? = null

    fun getOrCompute(
        files: List<FileEntry>,
        folders: List<FolderCard>,
        favorites: List<FileEntry>,
        prefs: AppPrefs,
        rootPath: String,
        nameFilter: String,
        extensionFilter: String,
        compute: () -> HomeDerived
    ): HomeDerived {
        val cached = value
        if (cached != null &&
            files === filesRef &&
            folders === foldersRef &&
            favorites === favoritesRef &&
            prefs === prefsRef &&
            rootPath == this.rootPath &&
            nameFilter == this.nameFilter &&
            extensionFilter == this.extensionFilter
        ) {
            return cached
        }
        val fresh = compute()
        filesRef = files
        foldersRef = folders
        favoritesRef = favorites
        prefsRef = prefs
        this.rootPath = rootPath
        this.nameFilter = nameFilter
        this.extensionFilter = extensionFilter
        value = fresh
        return fresh
    }
}
