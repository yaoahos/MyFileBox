package com.yaoahos.myfilebox.android.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small Android Keystore-backed AES/GCM helper for credentials at rest.
 *
 * Values are stored as `v1:<base64(iv || ciphertext)>`. Existing plaintext values
 * are returned unchanged so upgrades do not lose an existing session.
 */
object SecretVault {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val ALIAS = "myfilebox_credentials_v1"
    private const val PREFIX = "v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Never silently fall back to plaintext. Callers must handle encryption failure. */
    @Volatile var lastFailure: String? = null

    fun encrypt(value: String): String {
        if (value.isEmpty()) return value
        lastFailure = null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + encrypted
            PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        }.getOrElse {
            lastFailure = "凭据加密失败：${it.message ?: "Keystore 不可用"}"
            ""
        }
    }

    fun decrypt(value: String): String {
        if (value.isEmpty()) return value
        // Legacy plaintext values are read once for migration, but never written again.
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val payload = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            if (payload.size <= IV_BYTES) return ""
            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse {
            lastFailure = "凭据解密失败：${it.message ?: "密钥已变更"}"
            ""
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
