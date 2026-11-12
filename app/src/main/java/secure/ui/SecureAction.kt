package secure

import javax.crypto.Cipher

sealed interface SecureAction {
    data class Encrypt(val input: ByteArray) : SecureAction
    data object Decrypt : SecureAction

    data object GetSecretKey: SecureAction

    data class BiometricEncrypt(val input: ByteArray) : SecureAction
    data object BiometricDecrypt : SecureAction
    data class BiometricSucceeded(val cipher: Cipher) : SecureAction
    data class BiometricFailed(val error: String) : SecureAction

    data object Reset: SecureAction
}
