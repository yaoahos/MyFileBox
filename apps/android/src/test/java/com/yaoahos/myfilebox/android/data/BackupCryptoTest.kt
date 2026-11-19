package com.yaoahos.myfilebox.android.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份加密层的回归测试。
 *
 * 这一层是「配置丢了也不能泄密」的唯一保证，所以每条断言都对着一条设计承诺：
 * 口令错要说「密码错」、文件被改要说「文件坏」、密文里不能出现明文、
 * 同一份配置两次加密结果必须不同（随机 salt / iv）。
 */
class BackupCryptoTest {

    private val plain = """
        {"theme":"dark","loginServer":"nas.local","password":"s3cret-token"}
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun decryptError(data: ByteArray, password: String): BackupError = try {
        BackupCrypto.decrypt(data, password.toCharArray())
        fail("应当解密失败")
        error("unreachable")
    } catch (e: BackupCryptoException) {
        e.kind
    }

    @Test
    fun `往返加密后明文完全一致`() {
        val bytes = BackupCrypto.encrypt(plain, "a-good-password".toCharArray())
        assertArrayEquals(plain, BackupCrypto.decrypt(bytes, "a-good-password".toCharArray()))
    }

    @Test
    fun `文件头标识与版本可识别`() {
        val bytes = BackupCrypto.encrypt(plain, "a-good-password".toCharArray())
        assertTrue(BackupCrypto.isBackupFile(bytes))
        assertEquals(1, bytes[4].toInt())
        String(bytes.copyOfRange(0, 4), Charsets.US_ASCII).let { assertEquals("MFBX", it) }
    }

    @Test
    fun `密文里不出现任何配置明文`() {
        val bytes = BackupCrypto.encrypt(plain, "a-good-password".toCharArray())
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("nas.local"))
        assertFalse(text.contains("s3cret-token"))
        assertFalse(text.contains("theme"))
    }

    @Test
    fun `同一明文两次加密结果不同`() {
        val a = BackupCrypto.encrypt(plain, "a-good-password".toCharArray())
        val b = BackupCrypto.encrypt(plain, "a-good-password".toCharArray())
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `密码错误报 WRONG_PASSWORD 而不是损坏`() {
        val bytes = BackupCrypto.encrypt(plain, "a-good-password".toCharArray())
        assertEquals(BackupError.WRONG_PASSWORD, decryptError(bytes, "another-password"))
        // 第二次走内存校验缓存，仍须给出同样的判定。
        assertEquals(BackupError.WRONG_PASSWORD, decryptError(bytes, "another-password"))
    }

    @Test
    fun `输错密码后再输正确密码仍能恢复`() {
        val bytes = BackupCrypto.encrypt(plain, "a-good-password".toCharArray())
        assertEquals(BackupError.WRONG_PASSWORD, decryptError(bytes, "another-password"))
        assertArrayEquals(plain, BackupCrypto.decrypt(bytes, "a-good-password".toCharArray()))
    }

    @Test
    fun `未预热的错密码只跑校验那一路 KDF`() {
        // 关键性能承诺：判错只需要固定盐校验值，绝不该再跑文件盐派生。
        // 之前错密码要跑两路 21 万次 PBKDF2（并行抢核），这里断言降到一路。
        val bytes = BackupCrypto.encrypt(plain, "owner-pass-cold".toCharArray())
        val before = BackupCrypto.kdfCallCount.get()
        assertEquals(BackupError.WRONG_PASSWORD, decryptError(bytes, "cold-wrong-pass"))
        assertEquals("判错应只跑 1 次 KDF（固定盐校验），不跑文件盐派生", before + 1, BackupCrypto.kdfCallCount.get())
    }

    @Test
    fun `预热后的错密码校验不再跑任何 KDF`() {
        val bytes = BackupCrypto.encrypt(plain, "owner-pass-warm".toCharArray())
        val wrong = "warm-wrong-pass"
        BackupCrypto.warmUp(wrong.toCharArray())
        val before = BackupCrypto.kdfCallCount.get()
        assertEquals(BackupError.WRONG_PASSWORD, decryptError(bytes, wrong))
        assertEquals("校验值已预热，判错应为零 PBKDF2", before, BackupCrypto.kdfCallCount.get())
    }

    @Test
    fun `预热后的正确密码恢复只跑文件盐一路 KDF`() {
        val pwd = "warm-owner-pass"
        BackupCrypto.warmUp(pwd.toCharArray())
        val bytes = BackupCrypto.encrypt(plain, pwd.toCharArray())
        val before = BackupCrypto.kdfCallCount.get()
        assertArrayEquals(plain, BackupCrypto.decrypt(bytes, pwd.toCharArray()))
        assertEquals("校验值已缓存，正确恢复只剩文件盐派生 1 次", before + 1, BackupCrypto.kdfCallCount.get())
    }

    @Test
    fun `同一口令跨文件复用校验值`() {
        // 固定盐校验值只由口令决定，换一份 salt/iv 不同的备份仍应命中缓存。
        BackupCrypto.warmUp("cross-file-pass".toCharArray())
        val a = BackupCrypto.encrypt(plain, "cross-file-pass".toCharArray())
        val b = BackupCrypto.encrypt(plain, "cross-file-pass".toCharArray())
        assertNotEquals(a.toList(), b.toList())
        val before = BackupCrypto.kdfCallCount.get()
        assertEquals(BackupError.WRONG_PASSWORD, decryptError(a, "cross-file-wrong"))
        assertEquals(BackupError.WRONG_PASSWORD, decryptError(b, "cross-file-wrong"))
        // 两次判错共用同一份预热校验值：第一次错密码 warming 一次，第二次零次。
        assertTrue("跨文件应复用校验值，判错 KDF 增量为 0 或 1", BackupCrypto.kdfCallCount.get() - before <= 1)
    }

    @Test
    fun `老格式备份密码错误也报 WRONG_PASSWORD`() {
        val bytes = legacyEncrypt(plain, "a-good-password")
        assertEquals(BackupError.WRONG_PASSWORD, decryptError(bytes, "another-password"))
    }

    @Test
    fun `密文被改动报 CORRUPTED`() {
        val bytes = BackupCrypto.encrypt(plain, "a-good-password".toCharArray())
        val tampered = bytes.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x5A).toByte()
        assertEquals(BackupError.CORRUPTED, decryptError(tampered, "a-good-password"))
    }

    @Test
    fun `文件头被改动也报 CORRUPTED 而不是密码错误`() {
        val bytes = BackupCrypto.encrypt(plain, "a-good-password".toCharArray())
        // 改 salt（偏移 6 起）：口令校验值只依赖口令，所以能准确落到「文件坏了」。
        val tampered = bytes.copyOf()
        tampered[6] = (tampered[6].toInt() xor 0x01).toByte()
        assertEquals(BackupError.CORRUPTED, decryptError(tampered, "a-good-password"))
    }

    @Test
    fun `不是备份文件时报 BAD_FORMAT`() {
        assertEquals(BackupError.BAD_FORMAT, decryptError("hello world".toByteArray(), "a-good-password"))
    }

    @Test
    fun `版本号不受支持时报 UNSUPPORTED_VERSION`() {
        val bytes = BackupCrypto.encrypt(plain, "a-good-password".toCharArray())
        val tampered = bytes.copyOf()
        tampered[4] = 9
        assertEquals(BackupError.UNSUPPORTED_VERSION, decryptError(tampered, "a-good-password"))
    }

    @Test
    fun `口令过短拒绝加密`() {
        try {
            BackupCrypto.encrypt(plain, "short".toCharArray())
            fail("应当拒绝弱口令")
        } catch (e: BackupCryptoException) {
            assertEquals(BackupError.WEAK_PASSWORD, e.kind)
        }
    }

    @Test
    fun `空明文也能正常往返`() {
        val bytes = BackupCrypto.encrypt(ByteArray(0), "a-good-password".toCharArray())
        assertArrayEquals(ByteArray(0), BackupCrypto.decrypt(bytes, "a-good-password".toCharArray()))
    }

    /** 用改动前的 512bit 派生 + 无条件校验值手工造一份旧格式备份。 */
    private fun legacyEncrypt(plainText: ByteArray, password: String): ByteArray {
        val magic = "MFBX".toByteArray(Charsets.US_ASCII)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val algorithm = "PBKDF2WithHmacSHA256"
        // 旧 derive：请求 512bit，AES key 只用前 32 字节。
        val keySpec = PBEKeySpec(password.toCharArray(), salt, 210_000, 512)
        val raw = SecretKeyFactory.getInstance(algorithm).generateSecret(keySpec).encoded
        keySpec.clearPassword()
        val encKey = SecretKeySpec(raw.copyOfRange(0, 32), "AES")
        // 旧 checkValue：固定盐（magic + 1..12）+ 256bit。
        val verifySalt = magic + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val checkSpec = PBEKeySpec(password.toCharArray(), verifySalt, 210_000, 256)
        val checkRaw = SecretKeyFactory.getInstance(algorithm).generateSecret(checkSpec).encoded
        checkSpec.clearPassword()
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(checkRaw.copyOfRange(0, 32), "HmacSHA256"))
        }
        val check = mac.doFinal(magic).copyOf(8)
        val head = magic + byteArrayOf(1, 1) + salt + iv
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(head)
        return head + check + cipher.doFinal(plainText)
    }

    @Test
    fun `改动前的老格式备份仍能正常恢复`() {
        val bytes = legacyEncrypt(plain, "a-good-password")
        assertArrayEquals(plain, BackupCrypto.decrypt(bytes, "a-good-password".toCharArray()))
    }

    /**
     * 本机恢复「点了恢复还在转」的根因：密码框预热那一轮 PBKDF2 还没算完，
     * 用户已经点了恢复，旧实现查不到缓存就再起一轮，两条 21 万次的活抢同一个 CPU。
     * 现在同一口令只允许一轮在算，其余调用等它的结果。
     */
    @Test
    fun `并发校验同一口令只跑一次 KDF`() {
        val bytes = BackupCrypto.encrypt(plain, "owner-pass-concurrent".toCharArray())
        val wrong = "concurrent-wrong-pass"
        val before = BackupCrypto.kdfCallCount.get()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val futures = (1..4).map {
                pool.submit<BackupError> {
                    start.await()
                    decryptError(bytes, wrong)
                }
            }
            start.countDown()
            val kinds = futures.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue("四个线程都应判出密码错误", kinds.all { it == BackupError.WRONG_PASSWORD })
            assertEquals(
                "同一口令并发校验应合并成一次 PBKDF2，实际跑了 ${BackupCrypto.kdfCallCount.get() - before} 次",
                before + 1, BackupCrypto.kdfCallCount.get()
            )
        } finally {
            pool.shutdownNow()
        }
    }
}
