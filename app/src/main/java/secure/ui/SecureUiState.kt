package secure

import android.util.Base64
import androidx.compose.runtime.Immutable
import secure.keystore.SecureProvider


fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)


sealed interface SecureUiStatus {
    data object Idle : SecureUiStatus
    data object Processing : SecureUiStatus
    data object Success : SecureUiStatus
    data class Failure(val error: String) : SecureUiStatus
}

@Immutable
data class SecureResult(
    val iv: String? = null,
    val cipherText: String? = null,
    val isBiometric: Boolean = false,
) {
    val isValid get() = iv != null && cipherText != null
}

@Immutable
data class SecureUiState(
    val status: SecureUiStatus = SecureUiStatus.Idle,
    val input: String? = null,
    val result: SecureResult = SecureResult(),
    /** Result of the last decrypt check; null until the current ciphertext is decrypted. */
    val isMatched: Boolean? = null,
    val secureLevel: SecureProvider.KeyLevel = SecureProvider.KeyLevel.UNKNOWN,
) {
    val isBusy: Boolean get() = status == SecureUiStatus.Processing
}
