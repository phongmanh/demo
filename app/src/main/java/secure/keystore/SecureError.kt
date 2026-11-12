package secure.keystore

import android.os.Build
import android.security.KeyStoreException
import android.security.keystore.KeyExpiredException
import android.security.keystore.KeyNotYetValidException
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import kotlin.time.Duration.Companion.milliseconds

class SecureError {

    sealed interface CryptoFailure {
        data object NeedsAuth : CryptoFailure
        data object Temporary : CryptoFailure
        data object Expired : CryptoFailure
        data object BadData : CryptoFailure
        data object KeyLost : CryptoFailure
        data class Unknown(val cause: Throwable) : CryptoFailure
    }

    fun classify(error: Throwable): CryptoFailure {
        for (e in generateSequence(error) { it.cause }) {
            when (e) {
                is KeyPermanentlyInvalidatedException -> return CryptoFailure.KeyLost
                is UserNotAuthenticatedException -> return CryptoFailure.NeedsAuth
                is KeyExpiredException,
                is KeyNotYetValidException -> return CryptoFailure.Expired

                is AEADBadTagException -> return CryptoFailure.BadData
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                classifyKeystoreError(e)?.let { return it }
            } else if (e is ProviderException) {
                return CryptoFailure.Temporary
            }
        }
        return CryptoFailure.Unknown(error)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun classifyKeystoreError(e: Throwable): CryptoFailure? {
        if (e !is KeyStoreException) return null
        return when {
            e.requiresUserAuthentication() -> CryptoFailure.NeedsAuth
            e.isTransientFailure -> CryptoFailure.Temporary
            else -> null
        }
    }

    suspend fun <T> withKeystoreRetry(
        maxAttempts: Int = 3,
        block: () -> T
    ): T {
        var waitMs = 200L
        repeat(maxAttempts - 1) {
            try {
                return block()
            } catch (e: GeneralSecurityException) {
                if (classify(e) !is CryptoFailure.Temporary) throw e
            } catch (e: ProviderException) {
                if (classify(e) !is CryptoFailure.Temporary) throw e
            }
            delay(waitMs.milliseconds)
            waitMs *= 2
        }
        return block()
    }

}
