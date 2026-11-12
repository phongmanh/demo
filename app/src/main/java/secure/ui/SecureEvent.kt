package secure.ui

import javax.crypto.Cipher

sealed interface SecureEvent {
    data class Failed(val error: String) : SecureEvent
    data class RequestBiometric(val cipher: Cipher) : SecureEvent
}
