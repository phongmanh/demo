package secure.keystore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.InvalidKeySpecException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

class SecureProvider {

    enum class KeyLevel { SOFTWARE, TEE, STRONGBOX, SECURE_UNKNOWN, UNKNOWN }

    private val mutex = Mutex()

    companion object {
        const val KEY_ALIAS = "notes_key"
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_SIZE = 256
        const val CIPHER = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        const val IV_SIZE = 12
    }

    fun getOrCreateKey(alias: String = KEY_ALIAS): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(KEY_SIZE)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    suspend fun encrypt(
        plainTextValue: ByteArray, alias: String = KEY_ALIAS
    ): Pair<ByteArray, ByteArray> {

        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias = alias))
        return cipher.iv to cipher.doFinal(plainTextValue)

    }

    suspend fun decrypt(
        iv: ByteArray, cipherText: ByteArray, alias: String = KEY_ALIAS
    ): ByteArray {
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(alias = alias),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            return cipher.doFinal(cipherText)
        } catch (e: AEADBadTagException) {
            e.printStackTrace()
            throw e
        }
    }

    /**
     * KeyPair:
     *  - PrivateKey
     *  - PublicKey
     */
    fun createSigningKey(): KeyPair {
        val spec = KeyGenParameterSpec.Builder("payment_signing_key_v1", KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    /**
     * Read secret key information
     */
    fun readSecretKeyInfo(key: SecretKey): KeyInfo {
        try {
            val factory = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            return factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        } catch (e: InvalidKeySpecException) {
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Read private key information
     */
    fun readPrivateKeyInfo(key: PrivateKey): KeyInfo {
        try {
            val factory = KeyFactory.getInstance(key.algorithm, PROVIDER)
            return factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        } catch (e: InvalidKeySpecException) {
            e.printStackTrace()
            throw e
        }
    }

    suspend fun getOrCreateKey(alias: String, isBiometric: Boolean = false): SecretKey {
        mutex.withLock {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

            fun spec(useStrongBox: Boolean) = KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(KEY_SIZE)
                .apply {
                    if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                    if (isBiometric) {
                        setUserAuthenticationRequired(true)
                        setInvalidatedByBiometricEnrollment(true)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                        } else {
                            @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(-1)
                        }
                    }
                }.build()

            fun generate(useStrongBox: Boolean): SecretKey =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
                    init(spec(useStrongBox = useStrongBox))
                    generateKey()
                }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return generate(false)
            }

            return try {
                generate(true)
            } catch (e: StrongBoxUnavailableException) {
                e.printStackTrace()
                generate(false)
            }
        }

    }

    /**
     * Cipher bound to a biometric-protected key. It is only usable after
     * the user authenticates it through [secure.biometric.BiometricAuthenticator].
     *
     * @throws android.security.keystore.KeyPermanentlyInvalidatedException when biometric enrollment changed.
     */
    suspend fun biometricCipher(mode: Int, alias: String, iv: ByteArray? = null): Cipher {
        val key = getOrCreateKey(alias = alias, isBiometric = true)
        return Cipher.getInstance(CIPHER).apply {
            if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    fun deleteKey(alias: String) {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
    }

    fun getKeyLevel(key: SecretKey): KeyLevel {
        val factory = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyLevel.SOFTWARE
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyLevel.TEE
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeyLevel.SECURE_UNKNOWN
                else -> KeyLevel.UNKNOWN
            }
        }
        @Suppress("DEPRECATION") return if (info.isInsideSecureHardware) KeyLevel.SECURE_UNKNOWN else KeyLevel.SOFTWARE
    }
}
