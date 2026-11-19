package com.yaoahos.myfilebox.android.core

import com.yaoahos.myfilebox.core.FileSizeFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** 全局大小口径：三处旧实现统一后不能出现「210.3 KB / 210 KB」并存。 */
class FileSizeFormatTest {
    @Test
    fun `formats bytes with one decimal and fixed locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // 小数点习惯是逗号，验证输出不受地区影响
            assertEquals("0 B", FileSizeFormat.format(0L))
            assertEquals("999 B", FileSizeFormat.format(999L))
            assertEquals("1.0 KB", FileSizeFormat.format(1024L))
            assertEquals("210.3 KB", FileSizeFormat.format((210.3 * 1024).toLong()))
            assertEquals("1.5 MB", FileSizeFormat.format(1024L * 1536))
            assertEquals("1.0 GB", FileSizeFormat.format(1024L * 1024 * 1024))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
