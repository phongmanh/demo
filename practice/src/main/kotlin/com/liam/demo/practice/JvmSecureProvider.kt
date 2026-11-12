package com.liam.demo.practice

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Desktop-JVM stand-in for the app's `secure.SecureProvider`.
 *
 * Same AES/GCM encrypt/decrypt API, but the key is generated in memory
 * because AndroidKeyStore only exists on an Android device.
 */
class JvmSecureProvider(
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(KEY_SIZE) }.generateKey(),
) {
    fun encrypt(plainTextValue: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv to cipher.doFinal(plainTextValue)
    }

    fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    private companion object {
        const val KEY_SIZE = 256
        const val TAG_BITS = 128
        const val CIPHER = "AES/GCM/NoPadding"
    }
}
