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
