package com.yaoahos.myfilebox.android.update

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 通知里的安装包路径来自外部 intent，只能放行自家 cacheDir/updates 下的真实 apk。 */
class UpdateNotificationsTest {

    @Test
    fun onlyAcceptsApkInsideUpdateCache() {
        val cache = Files.createTempDirectory("mfb-cache").toFile()
        val dir = File(cache, "updates").apply { mkdirs() }
        val apk = File(dir, "MyFileBox-1.1.1.apk").apply { writeText("x") }
        val upper = File(dir, "MyFileBox-1.2.0.APK").apply { writeText("x") }
        val notApk = File(dir, "readme.txt").apply { writeText("x") }
        val dirNamedApk = File(dir, "fake.apk").apply { mkdirs() }
        val outside = Files.createTempDirectory("mfb-outside").toFile()
        val outsideApk = File(outside, "evil.apk").apply { writeText("x") }

        assertEquals(apk.canonicalFile, UpdateNotifications.resolveUpdateApk(cache, apk.absolutePath))
        assertEquals(upper.canonicalFile, UpdateNotifications.resolveUpdateApk(cache, upper.absolutePath))
        assertNull(UpdateNotifications.resolveUpdateApk(cache, notApk.absolutePath))
        assertNull(UpdateNotifications.resolveUpdateApk(cache, dirNamedApk.absolutePath))
        assertNull(UpdateNotifications.resolveUpdateApk(cache, outsideApk.absolutePath))
        assertNull(
            UpdateNotifications.resolveUpdateApk(
                cache,
                File(cache, "updates/../../evil.apk").absolutePath
            )
        )
        assertNull(UpdateNotifications.resolveUpdateApk(cache, ""))
    }
}
