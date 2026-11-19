package com.yaoahos.myfilebox.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新日志清洗规则单测：界面只渲染「· 条目」，
 * 因此 Markdown 记号、与标题重复的日期行、发布信息噪音都必须在这里被清掉。
 * 全部为纯函数、离线确定性。
 */
class ReleaseNotesTest {

    /** GitHub Release 正文：首行标题 + `- ` 列表，正是 1.0.0 实际发布时的形态。 */
    private val releaseBody = """
        **2026/11/13 · 1.0.0**
        - 本地文件、群晖 NAS 与 WebDAV / SMB / FTP / SFTP 网络位置统一管理
        - 目录浏览、搜索、排序与多选批量操作，分类入口快速找文件
        - 跨端上传与下载、同端复制与移动，任务支持暂停、恢复与取消
        - 音视频在线流式播放，拖动进度不必下载整个文件
    """.trimIndent()

    @Test
    fun stripsBulletPrefixAndBold() {
        val notes = conciseNotes(releaseBody)
        assertEquals(4, notes.size)
        assertEquals("本地文件、群晖 NAS 与 WebDAV / SMB / FTP / SFTP 网络位置统一管理", notes[0])
        assertTrue(notes.none { it.startsWith("- ") })
        assertTrue(notes.none { it.contains("**") })
    }

    @Test
    fun dropsHeadingLineThatDuplicatesDialogTitle() {
        // 对话框自己会渲染「2026/11/13    v1.0.0」，正文里再来一行就是重复。
        val notes = conciseNotes("**2026/11/13 · 1.0.0**\n- 修复切换网络后连接失败\n")
        assertEquals(listOf("修复切换网络后连接失败"), notes)
    }

    @Test
    fun dropsPlainDateAndVersionHeadings() {
        val notes = conciseNotes("2026/11/13\n2026-11-13 1.2.0\n- 新增功能一\n")
        assertEquals(listOf("新增功能一"), notes)
    }

    @Test
    fun keepsPlainBulletsFromLocalLog() {
        // 内置 updateLog.md 解析出来的 notes 已是纯文本，清洗不能改动它。
        val notes = conciseNotes("新增功能一\n修复问题二")
        assertEquals(listOf("新增功能一", "修复问题二"), notes)
    }

    @Test
    fun stripsOtherListMarkersAndInlineCode() {
        val notes = conciseNotes("* 星号条目\n+ 加号条目\n• 圆点条目\n· 中点条目\n使用 `adb` 调试")
        assertEquals(listOf("星号条目", "加号条目", "圆点条目", "中点条目", "使用 adb 调试"), notes)
    }

    @Test
    fun dropsReleaseInfoNoise() {
        val raw = """
            - 正常功能介绍
            - 包名：com.yaoahos.myfilebox
            - versionCode 67 / versionName 1.0.0
            - SHA-256：7d3713af1069b331
            - 支持系统：Android 8.0 及以上
            大小：5.9 MB
            certutil -hashfile app.apk SHA256
            下载地址：https://example.com/a.apk
        """.trimIndent()
        assertEquals(listOf("正常功能介绍"), conciseNotes(raw))
    }

    @Test
    fun dropsMarkdownStructureLines() {
        val raw = """
            ### 安装说明
            ---
            | 版本 | 日期 |
            ![截图](a.png)
            > 引用块
            - 真正的条目
        """.trimIndent()
        assertEquals(listOf("真正的条目"), conciseNotes(raw))
    }

    @Test
    fun truncatesLongEntryTo80Chars() {
        val long = "很长的条目".repeat(30)
        val notes = conciseNotes("- $long")
        assertEquals(1, notes.size)
        assertEquals(80, notes[0].length)
        assertTrue(notes[0].endsWith("…"))
    }

    @Test
    fun blankInputYieldsEmpty() {
        assertTrue(conciseNotes("").isEmpty())
        assertTrue(conciseNotes("   \n\n  ").isEmpty())
        // 只剩标题与噪音行时也要为空，界面才有「问题修复与稳定性优化」的兜底。
        assertTrue(conciseNotes("**2026/11/13 · 1.0.0**\nSHA-256：abcd").isEmpty())
    }

    @Test
    fun honorsLimit() {
        val raw = (1..20).joinToString("\n") { "- 条目 $it" }
        assertEquals(20, conciseNotes(raw).size)
        assertEquals(5, conciseNotes(raw, limit = 5).size)
    }

    /** 1.1.0 实际发布正文：发现新版本弹窗必须和「更新日志」弹窗一样只显示纯条目。 */
    @Test
    fun releaseBody1110ShowsPlainEntriesOnly() {
        val raw = """
            MyFileBox 1.1.0

            **2026/11/19 · 1.1.0**
            - 新增：配置备份与 WebDAV 云同步，支持定时上传及跨设备恢复
            - 修复：FTP 图片损坏、断点续传重复、重名覆盖与暂停后中断
            - 修复：传输记录按文件逐条展示，点击文件夹进入目录、点击文件直接打开，支持按层级返回；超长路径自动省略，完成通知支持手动清除
        """.trimIndent()
        val notes = conciseNotes(raw)
        assertEquals(3, notes.size)
        assertTrue(notes.none { it.contains("**") || it.startsWith("- ") || it.contains("MyFileBox 1.1.0") })
        // 每条都不超过上限；超长条目会被截断并带省略号。
        assertTrue(notes.all { it.length <= 80 })
        assertTrue(conciseNotes("- ${"长".repeat(100)}").last().endsWith("…"))
    }

    /** 「产品名 + 版本号」裸标题要丢弃，但不能误伤「适配 Android 15」这类普通条目。 */
    @Test
    fun productVersionTitleOnlyDropsAsciiTitle() {
        assertTrue(conciseNotes("MyFileBox 1.1.0").isEmpty())
        assertTrue(conciseNotes("MyFileBox v1.1.0").isEmpty())
        assertEquals(listOf("适配 Android 15"), conciseNotes("适配 Android 15"))
        assertEquals(listOf("兼容 Android 8.0"), conciseNotes("兼容 Android 8.0"))
    }
}
