package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 云端配置「一设备一份」的文件名规则。
 *
 * 这里只测纯函数（slug / parse）：名字里能不能看出是哪台设备、同型号能不能分开，
 * 全靠这两步。真实读写 WebDAV 的部分不在这里验证。
 */
class DeviceProfileNamingTest {

    @Test
    fun `slug keeps readable words and drops path hostile characters`() {
        assertEquals("Xiaomi_14", DeviceProfile.slug("Xiaomi 14"))
        assertEquals("Pixel_8_Pro", DeviceProfile.slug("  Pixel   8 Pro  "))
        // 斜杠、冒号这类字符在 WebDAV 路径里会拼出别的层级，必须替换掉。
        assertEquals("a_b_c", DeviceProfile.slug("a/b:c"))
        // 中文保留：文件名要能直接看出是哪台设备。
        assertEquals("办公室平板", DeviceProfile.slug("办公室平板"))
        assertEquals("device", DeviceProfile.slug("   "))
    }

    @Test
    fun `parse recovers device name and short id`() {
        val parsed = DeviceProfile.parse("myfilebox-config-Xiaomi_14-3f9a.mfbx")
        assertEquals("Xiaomi 14", parsed?.first)
        assertEquals("3f9a", parsed?.second)
    }

    @Test
    fun `parse rejects files that are not device configs`() {
        assertNull(DeviceProfile.parse("myfilebox-config.mfbx"))
        assertNull(DeviceProfile.parse("myfilebox-config-.mfbx"))
        assertNull(DeviceProfile.parse("myfilebox-config-abc.mfbx"))
        assertNull(DeviceProfile.parse("notes.txt"))
    }

    @Test
    fun `same model two devices stay distinguishable`() {
        // 同型号两台设备：显示名一样，短码不同 → 文件名不同，不会互相覆盖。
        val a = DeviceProfile.parse("myfilebox-config-Pixel_8_Pro-1a2b.mfbx")
        val b = DeviceProfile.parse("myfilebox-config-Pixel_8_Pro-9z0y.mfbx")
        assertEquals(a?.first, b?.first)
        assertEquals("1a2b", a?.second)
        assertEquals("9z0y", b?.second)
    }
}
