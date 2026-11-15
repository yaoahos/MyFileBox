package com.yaoahos.myfilebox.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新模块纯规则单测：版本归一化、版本大小比较、下载地址白名单。
 * 全部离线、确定性，不依赖网络与 Android 运行时。
 */
class GitHubUpdaterTest {

    // ---- normalize：去 v/V 前缀与首尾空白 ----

    @Test
    fun normalize_stripsVPrefix() {
        assertEquals("2.7.0", GitHubUpdater.normalize("v2.7.0"))
        assertEquals("2.7.0", GitHubUpdater.normalize("V2.7.0"))
        assertEquals("2.7.0", GitHubUpdater.normalize(" 2.7.0 "))
    }

    @Test
    fun normalize_keepsPlainVersion() {
        assertEquals("2.7.0", GitHubUpdater.normalize("2.7.0"))
        assertEquals("2.7.0-rc1", GitHubUpdater.normalize("v2.7.0-rc1"))
    }

    // ---- isNewer / compare：按数字段比较，不按字符串比较 ----

    @Test
    fun isNewer_basicOrder() {
        assertTrue(GitHubUpdater.isNewer("2.7.0", "2.6.0"))
        assertFalse(GitHubUpdater.isNewer("2.6.0", "2.7.0"))
        assertFalse(GitHubUpdater.isNewer("2.7.0", "2.7.0"))
    }

    @Test
    fun isNewer_numericSegmentNotLexicographic() {
        // 字符串比较会判 "2.10.0" < "2.9.0"，数字段比较必须反过来
        assertTrue(GitHubUpdater.isNewer("2.10.0", "2.9.0"))
        assertTrue(GitHubUpdater.isNewer("2.7.10", "2.7.9"))
    }

    @Test
    fun isNewer_vPrefixEquivalent() {
        assertTrue(GitHubUpdater.isNewer("v2.7.0", "V2.6.0"))
        assertFalse(GitHubUpdater.isNewer("v2.7.0", "2.7.0"))
    }

    @Test
    fun isNewer_missingSegmentsCountAsZero() {
        assertTrue(GitHubUpdater.isNewer("2.7.1", "2.7"))
        assertFalse(GitHubUpdater.isNewer("2.7", "2.7.0"))
        assertTrue(GitHubUpdater.isNewer("2.7.0.1", "2.7.0"))
    }

    @Test
    fun isNewer_prereleaseSuffixIgnoredForSegments() {
        // 预发布后缀不参与数字段比较：2.7.0-beta 与 2.7.0 的数字部分一致
        assertFalse(GitHubUpdater.isNewer("2.7.0-beta", "2.7.0"))
        assertTrue(GitHubUpdater.isNewer("3.0.0-beta", "2.9.9"))
    }

    // ---- isAllowedDownloadUrl：https + GitHub 域名白名单 ----

    @Test
    fun downloadUrl_allowsGithubHosts() {
        assertTrue(
            GitHubUpdater.isAllowedDownloadUrl(
                "https://github.com/yaoahos/MyFileBox/releases/download/v2.7.0/app-release.apk"
            )
        )
        assertTrue(GitHubUpdater.isAllowedDownloadUrl("https://objects.githubusercontent.com/x/y.apk"))
        assertTrue(GitHubUpdater.isAllowedDownloadUrl("https://api.github.com/a.apk"))
    }

    @Test
    fun downloadUrl_hostCaseInsensitive() {
        assertTrue(GitHubUpdater.isAllowedDownloadUrl("https://GITHUB.COM/a.apk"))
    }

    @Test
    fun downloadUrl_rejectsNonHttps() {
        assertFalse(GitHubUpdater.isAllowedDownloadUrl("http://github.com/a.apk"))
        assertFalse(GitHubUpdater.isAllowedDownloadUrl("ftp://github.com/a.apk"))
        assertFalse(GitHubUpdater.isAllowedDownloadUrl(""))
    }

    @Test
    fun downloadUrl_rejectsHostSpoofing() {
        // 后缀伪装：github.com.evil.com 不能因为 contains/前缀误放行
        assertFalse(GitHubUpdater.isAllowedDownloadUrl("https://github.com.evil.com/a.apk"))
        // 前缀伪装
        assertFalse(GitHubUpdater.isAllowedDownloadUrl("https://evil-github.com/a.apap"))
        // 完全无关域名
        assertFalse(GitHubUpdater.isAllowedDownloadUrl("https://evil.com/a.apk"))
    }

    @Test
    fun downloadUrl_rejectsUserInfoAndPortTricks() {
        // 用户信息伪装（java.net 会把它解析到 evil.com，白名单必须挡住）
        assertFalse(GitHubUpdater.isAllowedDownloadUrl("https://user:pass@github.com/a.apk"))
        // 非标准端口不属于 GitHub 下载域名形态
        assertFalse(GitHubUpdater.isAllowedDownloadUrl("https://github.com:8443/a.apk"))
    }

    @Test
    fun downloadUrl_toleratesQueryAndFragment() {
        assertTrue(GitHubUpdater.isAllowedDownloadUrl("https://github.com/a.apk?token=1"))
        assertTrue(GitHubUpdater.isAllowedDownloadUrl("https://github.com/a.apk#frag"))
    }

    // ---- releaseNotes：截断条数 ----

    @Test
    fun releaseNotes_truncatesToLimit() {
        val notes = (1..15).map {
            ReleaseNote(version = "2.$it.0", name = "n$it", notes = "", date = "2026/11/10")
        }
        assertEquals(10, GitHubUpdater.releaseNotes(notes).size)
        assertEquals(3, GitHubUpdater.releaseNotes(notes, limit = 3).size)
        assertEquals(15, GitHubUpdater.releaseNotes(notes, limit = 99).size)
    }
}
