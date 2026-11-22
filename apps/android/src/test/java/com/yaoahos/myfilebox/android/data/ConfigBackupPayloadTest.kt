package com.yaoahos.myfilebox.android.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置载荷的结构测试：保证 8 个分区一个都不漏，且导出后能按原样解回来。
 *
 * 这里只验证「组装」这一半（[ConfigBackup.buildPayload]），导入那一半要写
 * SharedPreferences，属于仪器化范畴，由真机验收覆盖。
 */
class ConfigBackupPayloadTest {

    private val sample = mapOf<String, Any>(
        "theme_mode" to "DARK",
        "theme_accent" to 0xFF1F6BFF,
        "theme_dynamic" to false,
        "theme_icon_follows" to true,
        "tab_order" to "LOCAL,SYNO,NET",
        "show_local" to true,
        "show_syno" to true,
        "show_net" to false,
        "default_tab" to "SYNO",
        "module_order" to "local:categories,local:folders",
        "hidden_modules" to setOf("local:folders"),
        "category_order" to "图片,视频,文档",
        "view_mode" to "list",
        "sort_key" to "time",
        "sort_dir" to "desc",
        "download_folder" to "/storage/emulated/0/Download",
        "cache_keep_days" to 7,
        "recycle_days" to 30,
        "update_check_startup" to false,
        "login_server" to "nas.local",
        "login_account" to "yao",
        "allow_self_signed" to true
    )

    private fun payloadOf(map: Map<String, Any>): JSONObject =
        JSONObject(ConfigBackup.buildPayload(map, "1.0.1", 1_700_000_000_000L))

    @Test
    fun `载荷包含全部八个分区`() {
        val data = payloadOf(sample).getJSONObject("data")
        BackupSection.entries.forEach { section ->
            assertTrue("缺少分区 ${section.key}", data.has(section.key))
        }
        assertEquals(8, JSONObject(ConfigBackup.buildPayload(sample, "1.0.1", 0L))
            .getJSONObject("data").length())
    }

    @Test
    fun `根节点带应用标识与版本`() {
        val root = payloadOf(sample)
        assertEquals("MyFileBox", root.getString("app"))
        assertEquals(1, root.getInt("schema"))
        assertEquals("1.0.1", root.getString("appVersion"))
        assertEquals(1_700_000_000_000L, root.getLong("exportedAt"))
    }

    @Test
    fun `主题分区保留原始类型`() {
        val theme = payloadOf(sample).getJSONObject("data").getJSONObject("theme")
        assertEquals("DARK", theme.getString("theme_mode"))
        assertEquals(0xFF1F6BFF, theme.getLong("theme_accent"))
        assertEquals(true, theme.getBoolean("theme_icon_follows"))
    }

    @Test
    fun `集合类型导出为数组`() {
        val local = payloadOf(sample).getJSONObject("data").getJSONObject("local")
        val hidden = local.getJSONArray("hidden_modules")
        assertEquals(1, hidden.length())
        assertEquals("local:folders", hidden.getString(0))
    }

    @Test
    fun `缺失的键不会写进载荷`() {
        val data = payloadOf(emptyMap()).getJSONObject("data")
        val theme = data.getJSONObject("theme")
        assertEquals(0, theme.length())
    }

    @Test
    fun `整包加密后可解回同一份载荷`() {
        val json = ConfigBackup.buildPayload(sample, "1.0.1", 42L)
        val bytes = BackupCrypto.encrypt(json.toByteArray(Charsets.UTF_8), "backup-pass-2026".toCharArray())
        val restored = String(
            BackupCrypto.decrypt(bytes, "backup-pass-2026".toCharArray()),
            Charsets.UTF_8
        )
        assertEquals(json, restored)
    }

    @Test
    fun `载荷不含任何密码`() {
        // 安全约定：备份落在网盘或另一台设备上时，不能把一批密码集中放进
        // 一个只由「8 位口令」保护的文件里。群晖登录密码与每条连接的密码都不导出。
        val withSecrets = sample + mapOf(
            "login_password" to "syno-secret",
            "connections" to """[{"id":"a","name":"NAS","host":"h","username":"u","password":"conn-secret"}]"""
        )
        val data = payloadOf(withSecrets).getJSONObject("data")
        val syno = data.getJSONObject("syno")
        assertTrue("群晖分区不应包含登录密码", !syno.has("loginPassword"))
        val connections = data.getJSONObject("network").getString("connections")
        assertTrue("连接里不应出现密码字段", !connections.contains("password"))
        assertTrue("连接里不应出现密码值", !connections.contains("conn-secret"))
        assertTrue("地址等非敏感字段仍应保留", connections.contains("\"host\":\"h\""))
    }

    @Test
    fun `剥离密码后仍保留连接的其他字段`() {
        val raw = """[{"id":"a","name":"NAS","host":"10.0.0.5","port":445,"username":"u","password":"p","secure":true}]"""
        val out = ConfigBackup.stripConnectionPasswords(raw)
        val o = org.json.JSONArray(out).getJSONObject(0)
        assertEquals("a", o.getString("id"))
        assertEquals(445, o.getInt("port"))
        assertEquals("u", o.getString("username"))
        assertTrue("password 应被移除", !o.has("password"))
        assertTrue(o.getBoolean("secure"))
    }

    @Test
    fun `文件名带时间戳且可识别`() {
        val name = ConfigBackup.suggestedFileName(1_700_000_000_000L)
        assertTrue(name.startsWith("MyFileBox-config-"))
        assertTrue(name.endsWith(".mfbx"))
        assertTrue(ConfigBackup.looksLikeBackup(name))
    }
}
