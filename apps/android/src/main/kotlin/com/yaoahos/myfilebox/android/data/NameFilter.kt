package com.yaoahos.myfilebox.android.data

/**
 * 名称筛选：只比名称，空词恒命中。
 *
 * 抽成纯函数是为了让「首页 / 内层目录 / 共享列表 / 连接列表」共用同一套判定，
 * 免得一处改成子串匹配、另一处还是前缀匹配。
 */
internal fun nameMatchesFilter(name: String, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return name.contains(q, ignoreCase = true)
}

/**
 * 后缀名筛选：`jpg,png` / `jpg png` / `.jpg` 三种写法等价，大小写不敏感。
 *
 * 两条规则：
 * - **筛后缀时文件夹一律不显示**。文件夹本身没有后缀，早先的「目录恒命中」
 *   会让「只筛 jpg」的结果里塞满一堆无关文件夹（用户反馈就是这条）。
 *   想进目录先点「清除」——筛选条上一直有这个按钮。
 * - **无扩展名的文件不命中**（条件是空时除外）。`.gitignore` 这种点开头的
 *   视为无后缀，不做「后缀是 gitignore」的荒谬匹配。
 */
internal fun extensionMatchesFilter(name: String, isDir: Boolean, query: String): Boolean {
    val tokens = query.split(',', ' ', '，', ';', '；')
        .map { it.trim().trimStart('.').lowercase() }
        .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true
    if (isDir) return false
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return false
    val ext = name.substring(dot + 1).lowercase()
    return tokens.any { it == ext }
}

/** 名称与后缀两个条件同时满足才算命中（任一条为空表示不限）。 */
internal fun itemMatchesFilter(
    name: String,
    isDir: Boolean,
    nameQuery: String,
    extQuery: String
): Boolean =
    nameMatchesFilter(name, nameQuery) && extensionMatchesFilter(name, isDir, extQuery)

/**
 * 一屏条目里筛选后真正可见的那些。
 *
 * 全选 / 取消全选、列表展示、计数行全都读这一份：三处各写一遍
 * 「没筛选就全返回、有筛选就逐条判定」，迟早会出现「列表筛了、全选没筛」。
 * 两个条件都为空时必须原样返回**全部**，不能返回空表——否则不筛选时点全选会一个都选不上。
 */
internal fun <T> filterVisible(
    items: List<T>,
    nameQuery: String,
    extQuery: String,
    nameOf: (T) -> String,
    isDirOf: (T) -> Boolean
): List<T> {
    if (nameQuery.isBlank() && extQuery.isBlank()) return items
    return items.filter { itemMatchesFilter(nameOf(it), isDirOf(it), nameQuery, extQuery) }
}

/**
 * 筛选条件的人话描述：筛选条、空态、计数行都读它，避免三处各拼一句、
 * 文案还对不上。两个条件都为空时返回空串。
 */
internal fun filterSummary(nameQuery: String, extQuery: String): String {
    val parts = buildList {
        if (nameQuery.isNotBlank()) add("名称「${nameQuery.trim()}」")
        if (extQuery.isNotBlank()) add("后缀「${extQuery.trim()}」")
    }
    return parts.joinToString(" + ")
}
