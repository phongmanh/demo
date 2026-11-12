package secure.envelope

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import secure.keystore.SecureProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DbManager(context: Context) {
    private val alias = "db_dek_v1"
    private val store = AtomicFile(File(context.noBackupFilesDir, "db_dek.bin"))
    private val keyStore = KeyStore.getInstance(SecureProvider.PROVIDER).apply { load(null) }


    @Synchronized
    fun passphrase(): ByteArray {
        val wrapped = try {
            store.readFully()
        } catch (_: FileNotFoundException) {
            null
        }
        val dek = if (wrapped != null) {
            unwrap(wrapped)
        } else {
            createAndStoreDek()
        }
        return try {
            toRawKey(dek)
        } finally {
            dek.fill(0)
        }
    }

    private fun createAndStoreDek(): ByteArray {
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(SecureProvider.CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKek())
        val blob = cipher.iv + cipher.doFinal(dek)

        val out = store.startWrite()
        try {
            out.write(blob)
            store.finishWrite(out)
        } catch (e: IOException) {
            store.finishWrite(out)
            throw e
        }
        return dek
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val kek = keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("KEK missing")
        val cipher = Cipher.getInstance(SecureProvider.CIPHER)
        cipher.init(
            Cipher.DECRYPT_MODE,
            kek,
            GCMParameterSpec(SecureProvider.GCM_TAG_BITS, blob, 0, SecureProvider.IV_SIZE)
        )
        return cipher.doFinal(blob, SecureProvider.IV_SIZE, blob.size - SecureProvider.IV_SIZE)
    }

    private fun getOrCreateKek(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(SecureProvider.KEY_SIZE)
                .apply {
                    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(
                        true
                    )
                }.build()
            return KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                SecureProvider.PROVIDER
            )
                .run {
                    init(spec)
                    generateKey()
                }

        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generate(true)
            } catch (_: StrongBoxUnavailableException) {
                generate(false)
            }
        } else {
            generate(false)
        }


    }

    // Produces x'hex', which tells SQLCipher to skip PBKDF2
    private fun toRawKey(dek: ByteArray): ByteArray {
        val hex = "0123456789abcdef"
        val out = ByteArray(dek.size * 2 + 3)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        dek.forEachIndexed { i, byte ->
            val value = byte.toInt() and 0xFF
            out[2 + i * 2] = hex[value ushr 4].code.toByte()
            out[3 + i * 2] = hex[value and 0x0F].code.toByte()
        }
        out[out.lastIndex] = '\''.code.toByte()
        return out
    }

}
