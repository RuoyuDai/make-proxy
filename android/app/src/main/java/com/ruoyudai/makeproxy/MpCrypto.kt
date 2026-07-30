package com.ruoyudai.makeproxy

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-GCM with per-message random IV, mirroring the Erlang mp_crypto module.
 * Wire layout: <<IV:12, Tag:16, CipherText>>.
 * Key derived from password with PBKDF2-HMAC-SHA256, 10000 iterations.
 * Note: use an ASCII password - Erlang list_to_binary and Java PBEKeySpec
 * treat non-ASCII characters differently.
 */
object MpCrypto {
    private const val IV_LEN = 12
    private const val TAG_LEN = 16
    private const val ITERATIONS = 10000
    private val SALT = "make-proxy".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun deriveKey(password: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), SALT, ITERATIONS, 128)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LEN * 8, iv)
        )
        val ctAndTag = cipher.doFinal(plain) // Java appends tag at the end
        val ct = ctAndTag.copyOfRange(0, ctAndTag.size - TAG_LEN)
        val tag = ctAndTag.copyOfRange(ctAndTag.size - TAG_LEN, ctAndTag.size)
        return iv + tag + ct
    }

    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size >= IV_LEN + TAG_LEN) { "invalid data" }
        val iv = data.copyOfRange(0, IV_LEN)
        val tag = data.copyOfRange(IV_LEN, IV_LEN + TAG_LEN)
        val ct = data.copyOfRange(IV_LEN + TAG_LEN, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LEN * 8, iv)
        )
        return cipher.doFinal(ct + tag)
    }
}
