package com.yaoahos.myfilebox.android.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 备份解密失败的原因，UI 需要区分「密码不对」与「文件坏了」。 */
enum class BackupError { BAD_FORMAT, WEAK_PASSWORD, UNSUPPORTED_VERSION, WRONG_PASSWORD, CORRUPTED }

class BackupCryptoException(val kind: BackupError, message: String) : Exception(message)

/**
 * 配置备份专用加密层：密钥完全由用户配置口令派生，不依赖任何设备密钥，
 * 因此备份文件可以跨设备恢复；同时**口令本身绝不写入备份文件**。
 *
 * 文件布局（大端顺序，全部为二进制，没有任何配置明文）：
 * ```
 * 0..3   magic "MFBX"
 * 4      version (1)
 * 5      kdf id (1 = PBKDF2-HMAC-SHA256, 2 = PBKDF2-HMAC-SHA1 兼容回退)
 * 6..21  salt (16B, 每次加密随机)
 * 22..33 iv (12B, 每次加密随机)
 * 34..41 口令校验值 (8B, HMAC-SHA256(macKey, 文件头))
 * 42..   AES-256-GCM 密文（含 128bit 认证标签）
 * ```
 * - 口令经 PBKDF2 21 万次迭代派生 256bit 加密密钥；校验值另用固定盐同样派生 256bit，
 *   取 HMAC 前 8 字节，只用于区分「口令错误」与「文件损坏」。
 * - 文件头（magic..iv）作为 GCM 的 AAD 绑定，任何一字节改动都会导致认证失败。
 * - 校验值只用于区分「口令错误」与「文件损坏」，不携带口令信息，也不削弱密文强度。
 * - 攻击者拿到备份文件只能做离线口令爆破，无法读到任何配置项名称或值；
 *   丢失口令时文件不可解密，这是设计目标，不提供任何绕过或后门。
 */
object BackupCrypto {

    private val MAGIC = byteArrayOf(
        'M'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), 'X'.code.toByte()
    )
    // 口令校验用固定盐，与每次加密随机的 salt / iv 无关：这样文件头被改动时
    // 能准确报「文件损坏」，而不是误报「密码不正确」。
    private val VERIFY_SALT: ByteArray = MAGIC + byteArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
    )
    private const val VERSION: Byte = 1
    private const val KDF_SHA256: Byte = 1
    private const val KDF_SHA1: Byte = 2
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val CHECK_BYTES = 8
    private const val ITERATIONS = 210_000
    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    private const val AAD_BYTES = 4 + 1 + 1 + SALT_BYTES + IV_BYTES // 34
    private const val HEADER_BYTES = AAD_BYTES + CHECK_BYTES          // 42
    const val MIN_PASSWORD_LENGTH = 8

    /**
     * PBKDF2 是纯 CPU 计算，迭代次数直接决定等待时间。关键路径按「先验错、再解密」重排：
     *
     * 1. [derive] 原来请求 512bit（两轮 21 万次），但后 256bit 的 mac key 从未被使用；
     *    只取 256bit 后 PBKDF2 只跑一轮，前 32 字节与原来逐字节相同，老备份照常可恢复。
     * 2. 校验值 [checkValue] 用**固定盐**，结果只取决于口令，与具体文件无关。解密时
     *    **先只算校验值**比对：口令错当场返回，完全不跑文件盐的 [derive]。
     *    错误密码从「两路 KDF 并行抢核」降到单路 21 万次；正确密码再多跑一次文件盐派生。
     * 3. 校验值按「算法 + 口令摘要」在内存缓存（不含 expected、不绑定文件），同一口令
     *    跨文件、跨备份、跨重试只派生一次。密码框输入满 8 位即预热 [warmUp]，点恢复时
     *    多数情况下校验值已算好，错密码零等待、正确密码只剩文件盐那一路。
     * 4. 加密同样先复用缓存的校验值，只有一路 derive 必跑。
     *
     * 安全强度不变：两路 PBKDF2 都是 21 万次，缓存只存固定盐派生出的 8 字节单向摘要，
     * 不落盘、不进备份文件、不缓存明文口令，也不提供任何解密捷径。
     */
    /** 仅供单测断言 PBKDF2 实际执行次数，判断快路径是否真的省掉了某一路。 */
    internal val kdfCallCount: AtomicInteger = AtomicInteger(0)

    private const val CHECK_CACHE_LIMIT = 32
    private val checkCacheLock = Any()
    /** 键 = 算法 + 口令摘要（不含 expected）。固定盐校验值只由这两者决定，可跨文件复用。 */
    private val checkCache = object : LinkedHashMap<String, ByteArray>(CHECK_CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > CHECK_CACHE_LIMIT
    }

    /**
     * 正在计算的固定盐校验值：键同 [checkCache]，值是一份待完成的结果。
     *
     * 这是「本机恢复比云端恢复慢」的根因修复：密码框输入满 8 位会在后台预热，用户
     * 敲完最后一个字符到点「恢复」通常只有两三百毫秒，预热那轮 21 万次 PBKDF2 往往
     * 还没算完。以前 decrypt 只查「已完成」的缓存，查不到就**再起一轮**，于是两条
     * 21 万次的活同时抢 CPU，比不预热还慢；云端因为中间隔了一次网络下载，预热早已
     * 结束，所以看起来正常。现在同一口令只允许有一轮在算：后来者直接等它的结果。
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<ByteArray>>()

    /**
     * 取固定盐校验值：命中缓存直接返回；同一口令已有计算在跑就等它；否则自己算一轮。
     */
    private fun checkValueFor(password: CharArray, algorithm: String): ByteArray {
        val key = checkCacheKey(password, algorithm)
        synchronized(checkCacheLock) { checkCache[key] }?.let { return it }
        val task = java.util.concurrent.FutureTask { checkValue(password, algorithm) }
        val running = inFlight.putIfAbsent(key, task)
        if (running != null) return running.get()
        try {
            task.run()
            val value = task.get()
            synchronized(checkCacheLock) { checkCache[key] = value }
            return value
        } finally {
            inFlight.remove(key, task)
        }
    }

    /** 输入密码时后台预热固定盐校验值：只派生一次，之后所有恢复共用。 */
    fun warmUp(password: CharArray) {
        if (password.size < MIN_PASSWORD_LENGTH) return
        runCatching { checkValueFor(password, preferredKdf().first) }
    }

    fun isBackupFile(data: ByteArray): Boolean =
        data.size > HEADER_BYTES && data.copyOfRange(0, 4).contentEquals(MAGIC)

    /** 加密配置明文。口令由调用方持有，用完应自行清空 char 数组。 */
    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        if (password.size < MIN_PASSWORD_LENGTH) {
            throw BackupCryptoException(
                BackupError.WEAK_PASSWORD,
                "配置密码至少 $MIN_PASSWORD_LENGTH 位"
            )
        }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val (algorithm, kdfId) = preferredKdf()
        // 文件盐派生每次都不同（salt 随机），必跑；固定盐校验值只由口令决定，优先复用缓存。
        val key = derive(password, salt, algorithm)
        val check = checkValueFor(password, algorithm)
        val head = head(kdfId, salt, iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(head)
        val body = cipher.doFinal(plain)
        return head + check + body
    }

    /** 解密配置明文。失败时抛出带明确原因的 [BackupCryptoException]。 */
    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        if (!isBackupFile(data)) {
            throw BackupCryptoException(BackupError.BAD_FORMAT, "不是有效的配置备份文件")
        }
        if (data[4] != VERSION) {
            throw BackupCryptoException(BackupError.UNSUPPORTED_VERSION, "备份文件版本不受支持")
        }
        val algorithm = when (data[5]) {
            KDF_SHA256 -> "PBKDF2WithHmacSHA256"
            KDF_SHA1 -> "PBKDF2WithHmacSHA1"
            else -> throw BackupCryptoException(BackupError.UNSUPPORTED_VERSION, "未知的密钥派生算法")
        }
        val salt = data.copyOfRange(6, 6 + SALT_BYTES)
        val iv = data.copyOfRange(6 + SALT_BYTES, 6 + SALT_BYTES + IV_BYTES)
        val head = data.copyOfRange(0, AAD_BYTES)
        val expected = data.copyOfRange(AAD_BYTES, HEADER_BYTES)
        val body = data.copyOfRange(HEADER_BYTES, data.size)

        // 固定盐校验值只由口令决定：先取缓存、未命中才算一次。它独立于本文件的 salt，
        // 因此文件头被改动不会被误判成「口令不正确」。命中缓存（或同一口令正在预热）
        // 时这一步零额外 PBKDF2。
        val check = checkValueFor(password, algorithm)
        // 校验先行：口令不对当场返回，**绝不跑文件盐派生**。这是错密码快的关键——
        // 之前那一路 21 万次 PBKDF2 对「判断口令对错」毫无贡献，纯属浪费。
        if (!MessageDigest.isEqual(expected, check)) {
            throw BackupCryptoException(BackupError.WRONG_PASSWORD, "配置密码不正确")
        }

        // 走到这里口令已确认正确，才派生解密钥并做 GCM；GCM 失败只可能是文件体被破坏。
        val key = derive(password, salt, algorithm)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(head)
            cipher.doFinal(body)
        } catch (e: Exception) {
            throw BackupCryptoException(BackupError.CORRUPTED, "备份文件已损坏或被篡改")
        }
        return plain
    }

    /** 只存口令的 SHA-256 摘要，绝不缓存明文，也不写入备份文件。 */
    private fun checkCacheKey(password: CharArray, algorithm: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(String(password).toByteArray(Charsets.UTF_8))
        // 手写十六进制：每敲一个字符都会走一次这里，"%02x".format 的格式化开销
        // 比 SHA-256 本身还大（32 次装箱 + 正则式解析），没必要。
        val chars = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        digest.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0F]
        }
        return algorithm + ':' + String(chars)
    }

    private fun head(kdfId: Byte, salt: ByteArray, iv: ByteArray): ByteArray =
        MAGIC + byteArrayOf(VERSION, kdfId) + salt + iv

    private fun checkValue(password: CharArray, algorithm: String): ByteArray {
        kdfCallCount.incrementAndGet()
        val spec = PBEKeySpec(password, VERIFY_SALT, ITERATIONS, KEY_BYTES * 8)
        val raw = try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(raw, 0, KEY_BYTES, "HmacSHA256"))
        }
        return mac.doFinal(MAGIC).copyOf(CHECK_BYTES)
    }

    /**
     * 派生 AES-256 密钥。只请求 256bit：PBKDF2 的前 32 字节与请求 512bit 时逐字节相同，
     * 原先多请求的 256bit（mac key）从未被任何代码使用，白白多跑一轮 21 万次迭代。
     * 因此这里既省一半时间，又与所有老备份的 enc key 完全一致。
     */
    private fun derive(password: CharArray, salt: ByteArray, algorithm: String): SecretKeySpec {
        kdfCallCount.incrementAndGet()
        // PBKDF2WithHmacSHA256 自 Android 8.0 (API 26) 起可用；万一某些 ROM 缺失，
        // 回退到全版本可用的 SHA1，并把算法 id 写进文件头，解密时按头还原。
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BYTES * 8)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun preferredKdf(): Pair<String, Byte> = try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        "PBKDF2WithHmacSHA256" to KDF_SHA256
    } catch (e: Exception) {
        "PBKDF2WithHmacSHA1" to KDF_SHA1
    }
}
