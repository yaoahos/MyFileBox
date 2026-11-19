package com.yaoahos.myfilebox.core

import java.util.Locale

/**
 * 全局唯一的文件大小显示口径。
 *
 * 之前 app 模块里有三份实现（LocalRepository.formatSize / TransferNotifications.formatBytes /
 * RecycleScreen.formatBytes），精度与 Locale 各不相同，同一个文件在不同页面会显示
 * 「210.3 KB」和「210 KB」。统一到这里，三个入口只做委托。
 */
object FileSizeFormat {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    fun format(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) "$bytes B"
        else String.format(Locale.US, "%.1f %s", value, units[index])
    }
}
