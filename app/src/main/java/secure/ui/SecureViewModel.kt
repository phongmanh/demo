package secure

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import secure.keystore.SecureProvider
import secure.ui.SecureEvent
import javax.crypto.Cipher

class SecureViewModel : ViewModel() {

    companion object {
        const val STRONGBOX_KEY = "STRONGBOX_KEY2"
        const val BIOMETRIC_KEY = "BIOMETRIC_KEY"
    }

    /** What to do with the cipher once the biometric prompt unlocks it. */
    private sealed interface PendingBiometric {
        data class Encrypt(val input: String) : PendingBiometric
        data object Decrypt : PendingBiometric
    }

    private var pendingBiometric: PendingBiometric? = null

    val uiState: StateFlow<SecureUiState>
        field = MutableStateFlow(SecureUiState())

    private val _event = Channel<SecureEvent>(Channel.BUFFERED)
    val uiEvent = _event.receiveAsFlow()

    private val secureProvider = SecureProvider()

    fun onAction(secureAction: SecureAction): Unit = when (secureAction) {
        is SecureAction.Encrypt -> encrypt(secureAction.input.toBase64())
        is SecureAction.Decrypt -> decrypt()
        SecureAction.GetSecretKey -> getKeySecureLevel()
        is SecureAction.BiometricEncrypt ->
            requestBiometric(PendingBiometric.Encrypt(secureAction.input.toBase64()))

        SecureAction.BiometricDecrypt -> requestBiometric(PendingBiometric.Decrypt)
        is SecureAction.BiometricSucceeded -> onBiometricSucceeded(secureAction.cipher)
        is SecureAction.BiometricFailed -> onBiometricFailed(secureAction.error)
        SecureAction.Reset -> uiState.value = SecureUiState()
    }

    private fun encrypt(input: String) {
        if (uiState.value.isBusy) return
        uiState.update { it.copy(status = SecureUiStatus.Processing) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (iv, cipherText) = secureProvider.encrypt(
                    input.encodeToByteArray(),
                    STRONGBOX_KEY
                )
                uiState.update {
                    it.copy(
                        status = SecureUiStatus.Success,
                        input = input,
                        isMatched = null,
                        result = it.result.copy(
                            iv = iv.toBase64(),
                            cipherText = cipherText.toBase64(),
                            isBiometric = false
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                cancelled.printStackTrace()
                throw cancelled
            } catch (e: Exception) {
                e.printStackTrace()
                _event.send(SecureEvent.Failed(e.message ?: "Something went wrong."))
                uiState.update {
                    it.copy(
                        status = SecureUiStatus.Failure(e.message ?: "Something went wrong."),
                        result = SecureResult()
                    )
                }
            }
        }
    }

    private fun decrypt() {
        if (uiState.value.isBusy) return
        if (!uiState.value.result.isValid) return

        uiState.update { it.copy(status = SecureUiStatus.Processing) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result =
                    secureProvider.decrypt(
                        iv = uiState.value.result.iv!!.fromBase64(),
                        cipherText = uiState.value.result.cipherText!!.fromBase64(),
                        alias = STRONGBOX_KEY
                    )
                uiState.update {
                    it.copy(
                        status = SecureUiStatus.Success,
                        isMatched = result.contentEquals(it.input?.encodeToByteArray()),
                    )
                }
            } catch (cancelled: CancellationException) {
                cancelled.printStackTrace()
                throw cancelled
            } catch (e: Exception) {
                e.printStackTrace()
                _event.send(SecureEvent.Failed(e.message ?: "Something went wrong."))
                uiState.update {
                    it.copy(status = SecureUiStatus.Failure(e.message ?: "Something went wrong."))
                }
            }
        }
    }

    private fun getKeySecureLevel() {
        if (uiState.value.isBusy) return

        uiState.update { it.copy(status = SecureUiStatus.Processing) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val key = secureProvider.getOrCreateKey(alias = STRONGBOX_KEY, isBiometric = false)
                val level = secureProvider.getKeyLevel(key = key)
                uiState.update {
                    it.copy(status = SecureUiStatus.Success, secureLevel = level)
                }
            } catch (cancelled: CancellationException) {
                cancelled.printStackTrace()
                throw cancelled
            } catch (e: Exception) {
                e.printStackTrace()
                _event.send(SecureEvent.Failed(e.message ?: "Something went wrong."))
                uiState.update {
                    it.copy(
                        status = SecureUiStatus.Failure(e.message ?: "Something went wrong."),
                        result = SecureResult()
                    )
                }
            }
        }
    }

    /**
     * Step 1: prepare a cipher with the biometric key and ask the screen to show the prompt.
     */
    private fun requestBiometric(pending: PendingBiometric) {
        if (uiState.value.isBusy) return
        val result = uiState.value.result
        if (pending is PendingBiometric.Decrypt && !(result.isValid && result.isBiometric)) return

        uiState.update { it.copy(status = SecureUiStatus.Processing) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cipher = when (pending) {
                    is PendingBiometric.Encrypt ->
                        secureProvider.biometricCipher(Cipher.ENCRYPT_MODE, BIOMETRIC_KEY)

                    PendingBiometric.Decrypt -> secureProvider.biometricCipher(
                        mode = Cipher.DECRYPT_MODE,
                        alias = BIOMETRIC_KEY,
                        iv = result.iv!!.fromBase64()
                    )
                }
                pendingBiometric = pending
                _event.send(SecureEvent.RequestBiometric(cipher))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: KeyPermanentlyInvalidatedException) {
                e.printStackTrace()
                // Biometric enrollment changed: the old key and its data are gone for good.
                secureProvider.deleteKey(BIOMETRIC_KEY)
                fail("Biometrics changed. Key was reset, please encrypt again.", resetResult = true)
            } catch (e: Exception) {
                e.printStackTrace()
                fail(e.message ?: "Something went wrong.")
            }
        }
    }

    /**
     * Step 2: the user authenticated, so the cipher can now run.
     */
    private fun onBiometricSucceeded(cipher: Cipher) {
        val pending = pendingBiometric ?: return
        pendingBiometric = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (pending) {
                    is PendingBiometric.Encrypt -> {
                        val cipherText = cipher.doFinal(pending.input.encodeToByteArray())
                        uiState.update {
                            it.copy(
                                status = SecureUiStatus.Success,
                                input = pending.input,
                                isMatched = null,
                                result = SecureResult(
                                    iv = cipher.iv.toBase64(),
                                    cipherText = cipherText.toBase64(),
                                    isBiometric = true
                                )
                            )
                        }
                    }

                    PendingBiometric.Decrypt -> {
                        val plain = cipher.doFinal(uiState.value.result.cipherText!!.fromBase64())
                        uiState.update {
                            it.copy(
                                status = SecureUiStatus.Success,
                                isMatched = plain.contentEquals(it.input?.encodeToByteArray()),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                e.printStackTrace()
                fail(e.message ?: "Something went wrong.")
            }
        }
    }

    private fun onBiometricFailed(error: String) {
        pendingBiometric = null
        viewModelScope.launch { fail(error) }
    }

    private suspend fun fail(error: String, resetResult: Boolean = false) {
        _event.send(SecureEvent.Failed(error))
        uiState.update {
            it.copy(
                status = SecureUiStatus.Failure(error),
                result = if (resetResult) SecureResult() else it.result
            )
        }
    }
}
