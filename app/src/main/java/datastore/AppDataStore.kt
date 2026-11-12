package datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import secure.tink.Crypto
import secure.fromBase64
import secure.toBase64
import java.io.InputStream
import java.io.OutputStream

private const val PREFERS_FILE_NAME = "secure_storage_prefs"
private val ASSOCIATED_DATA = PREFERS_FILE_NAME.toByteArray()

val Context.securePrefs: DataStore<Preferences> by preferencesDataStore(PREFERS_FILE_NAME)

@Serializable
data class AppSettings(
    val token: String? = null,
    val refreshToken: String? = null,
    val userId: String? = null
)

object AppSettingsSerializer : Serializer<AppSettings> {
    private val json = Json {
        ignoreUnknownKeys = true
    }
    override val defaultValue: AppSettings
        get() = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings {
        try {
            return json.decodeFromString(
                AppSettings.serializer(),
                input.readBytes().decodeToString()
            )
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot parse SecureSettings", e)
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(json.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}


suspend fun DataStore<Preferences>.save(aead: Aead, key: Preferences.Key<String>, value: String) {
    val encoded = aead.encrypt(value.toByteArray(), ASSOCIATED_DATA).toBase64()

    this.edit { prefer ->
        prefer[key] = encoded
    }
}

suspend fun DataStore<Preferences>.get(aead: Aead, key: Preferences.Key<String>): String {
    val encoded = this.data.first()[key]
    return aead.decrypt(encoded?.fromBase64(), ASSOCIATED_DATA).decodeToString()
}


fun createEncryptedAppSettingData(context: Context): DataStore<AppSettings> {
    val appContext = context.applicationContext

    val aeadSerialize = AeadSerializer(
        aead = LazyAead { Crypto.getAead(context = context) },
        wrappedSerializer = AppSettingsSerializer,
        associatedData = ASSOCIATED_DATA
    )

    return DataStoreFactory.create(
        serializer = aeadSerialize,
        corruptionHandler = ReplaceFileCorruptionHandler {
            AppSettings()
        },
        produceFile = { appContext.dataStoreFile(PREFERS_FILE_NAME) }
    )
}

private class LazyAead(provider: () -> Aead) : Aead {
    private val delegate: Aead by lazy(provider)

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        delegate.encrypt(plaintext, associatedData)

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        delegate.decrypt(ciphertext, associatedData)
}


