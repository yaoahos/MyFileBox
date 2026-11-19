package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「备份目录」的目录名判定。
 *
 * 写作/办公软件把整份副本写进 Documents/Backup、`xxx 备份` 这类目录，
 * 副本的 mtime 每次保存都会刷新，会把「最近」的时间流挤满。
 *
 * 注意口径变化：这个判定现在只服务于「最近」的展示层过滤
 * （[RecentPolicy.isBackupPath]），**不再决定遍历是否进入**。早先遍历层整棵跳过，
 * 副作用是搜索和文件分类也搜不到这些目录里的文件——用户在那儿放了自己的文档，
 * 却怎么都找不到。遍历层现在照常收录。
 */
class ScanSkipRulesTest {

    @Test
    fun backupNamesAreSkipped() {
        listOf(
            "Backup",
            "backup",
            "BACKUPS",
            "Documents Backup",
            "auto-backup",
            "backup_2026",
            "MyBackup",
            "我的备份",
            "文档备份",
            "備份",
            "bak",
            "old.bak",
            "bak_2026",
            "project-bak"
        ).forEach { name ->
            assertTrue("应判为备份目录：$name", LocalRepository.isBackupDirName(name))
        }
    }

    @Test
    fun normalNamesAreNotSkipped() {
        listOf(
            "",
            "Documents",
            "Download",
            "Downloads",
            "DCIM",
            "Pictures",
            "MIUI",
            "tencent",
            "图片",
            "文档",
            "bank",
            "bake",
            "baking",
            "books",
            "kebab",
            "笔记"
        ).forEach { name ->
            assertFalse("不应判为备份目录：$name", LocalRepository.isBackupDirName(name))
        }
    }

    @Test
    fun bakOnlyMatchesAsWholeWord() {
        // `bak` 出现在词中间（bake / baking / kebab）不算备份标记
        assertFalse(LocalRepository.isBackupDirName("baking"))
        assertFalse(LocalRepository.isBackupDirName("kebab"))
        // 作为独立词或带分隔符才算
        assertTrue(LocalRepository.isBackupDirName("bak"))
        assertTrue(LocalRepository.isBackupDirName("notes.bak"))
        assertTrue(LocalRepository.isBackupDirName("bak.2026"))
    }
}
