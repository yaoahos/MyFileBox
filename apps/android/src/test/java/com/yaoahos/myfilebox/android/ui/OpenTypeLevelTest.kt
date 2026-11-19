package com.yaoahos.myfilebox.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「打开方式」候选应用的声明级别判定。
 *
 * 关键回归：WPS Office 这类办公套件在 intent-filter 里只声明任意类型通配 `*`，
 * 早期实现把「通配」当成「凑数应用」整批丢弃，导致打开 pptx 时列表里没有 WPS。
 * 真机 probe 日志为 `drop cn.wps.moffice_eng types=[*]`。
 */
class OpenTypeLevelTest {

    private val pptx = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    @Test
    fun `只声明通配的应用算通配档而不是丢弃`() {
        // typesIterator() 对 `*/*` 吐出的是裸星号。
        assertEquals(2, declaredTypeLevel(listOf("*"), listOf(pptx), generic = false))
        assertEquals(2, declaredTypeLevel(listOf("*/*"), listOf(pptx), generic = false))
    }

    @Test
    fun `精确命中具体类型算最高档`() {
        assertEquals(3, declaredTypeLevel(listOf(pptx), listOf(pptx), generic = false))
    }

    @Test
    fun `具体类型命中大类目标也算最高档`() {
        assertEquals(
            3,
            declaredTypeLevel(listOf("audio/mpeg"), listOf("audio/*"), generic = false)
        )
    }

    @Test
    fun `大类声明命中具体目标算最高档`() {
        assertEquals(
            3,
            declaredTypeLevel(listOf("text"), listOf("text/plain"), generic = false)
        )
    }

    @Test
    fun `与目标无关的类型声明被丢弃`() {
        assertEquals(
            0,
            declaredTypeLevel(
                listOf("audio", "application/flac", "application/ogg"),
                listOf(pptx),
                generic = false
            )
        )
    }

    @Test
    fun `同时声明通配与具体类型时取具体`() {
        assertEquals(3, declaredTypeLevel(listOf("*", pptx), listOf(pptx), generic = false))
    }

    @Test
    fun `既有无关具体类型又挂通配的排最后但保留`() {
        // 汽水音乐这类：audio/flac/ogg 都不匹配文档，只靠 `*` 兜底。
        assertEquals(
            1,
            declaredTypeLevel(listOf("audio", "application/flac", "*"), listOf(pptx), generic = false)
        )
    }

    @Test
    fun `拿不到 filter 时不误伤`() {
        assertEquals(3, declaredTypeLevel(null, listOf(pptx), generic = false))
        assertEquals(
            3,
            declaredTypeLevel(emptyList(), listOf(pptx), generic = false, filterMissing = true)
        )
    }

    /**
     * 网易云音乐的 VIEW 入口只写了 `scheme=file`，一个 MIME 都没声明，
     * 于是 `typesIterator()` 返回空。这类应用能「接收」任意文件却打不开文档
     * （真机：网易云、腾讯混元、WorkBuddy 都靠这条路混进 pptx 列表），必须丢弃。
     */
    @Test
    fun `filter 存在但没声明任何类型时丢弃`() {
        assertEquals(
            0,
            declaredTypeLevel(emptyList(), listOf(pptx), generic = false, filterMissing = false)
        )
    }

    /**
     * 分享 / 发送 / 导入 / 打印 / 跳转入口不是「打开」。
     * 这些名字都来自真机 probe（包名 → Activity 名）。
     */
    @Test
    fun `分享发送打印类入口不算查看器`() {
        assertEquals(true, isNonViewerActivity("com.tencent.mm.ui.tools.ShareScreenImgUI"))
        assertEquals(true, isNonViewerActivity("com.xiaomi.smarthome.printer.SendPrintActivity"))
        assertEquals(true, isNonViewerActivity("com.mutangtech.qianji.dataimport.home.ImportHomeAct"))
        assertEquals(true, isNonViewerActivity("nutstore.android.SendToNutstoreIndex"))
        assertEquals(true, isNonViewerActivity("com.netease.cloudmusic.activity.RedirectActivity"))

        // 真正的查看器入口不能被误伤。
        assertEquals(false, isNonViewerActivity("cn.wps.moffice.plugin.app.entrance.PPTEntranceActivity"))
        assertEquals(false, isNonViewerActivity("com.android.fileexplorer.activity.ExtractFileActivity"))
        assertEquals(false, isNonViewerActivity("com.tencent.weread.LaunchExternalFile"))
        assertEquals(false, isNonViewerActivity("io.legado.app.ui.association.FileAssociationActivity"))
    }

    /**
     * 浏览器不该出现在「打开方式」里：它能开 PDF / 图片，但用户要的是专门的应用。
     * 系统浏览器常常查不到 https 入口（只声明自建 scheme），所以还要有包名兜底。
     */
    @Test
    fun `浏览器不参与打开方式`() {
        val httpsHosts = setOf("com.microsoft.emmx")
        assertEquals(true, isBrowserPackage("com.microsoft.emmx", httpsHosts))
        assertEquals(true, isBrowserPackage("com.quark.browser", emptySet()))
        assertEquals(true, isBrowserPackage("com.android.browser", emptySet()))

        // 系统文件管理、办公套件、阅读器不能被「browser / edge」这类词误伤。
        assertEquals(false, isBrowserPackage("com.android.fileexplorer", emptySet()))
        assertEquals(false, isBrowserPackage("cn.wps.moffice_eng", emptySet()))
        assertEquals(false, isBrowserPackage("com.legado.app.release", emptySet()))
    }

    /**
     * 把文件当「附件收下」的应用不是查看器：AI 助手、社交、支付、输入法、清理工具。
     * 真机上 GKD、WorkBuddy、微信、支付宝都靠「任意类型通配」混进过打开方式。
     */
    @Test
    fun `AI社交支付类不算查看器`() {
        assertEquals(true, isNonViewerPackage("com.tencent.workbuddy.app"))
        assertEquals(true, isNonViewerPackage("li.songe.gkd"))
        assertEquals(true, isNonViewerPackage("com.tencent.mm"))
        assertEquals(true, isNonViewerPackage("com.eg.android.AlipayGphone"))

        assertEquals(false, isNonViewerPackage("cn.wps.moffice_eng"))
        assertEquals(false, isNonViewerPackage("com.legado.app.release"))
        assertEquals(false, isNonViewerPackage("com.android.fileexplorer"))
        assertEquals(false, isNonViewerPackage("com.fongmi.android.tv"))
    }

    @Test
    fun `裸前缀补成大类通配`() {
        assertEquals("audio/*", normalizeDeclaredType("audio"))
        assertEquals("*/*", normalizeDeclaredType(""))
        assertEquals(pptx, normalizeDeclaredType(pptx))
    }
}
