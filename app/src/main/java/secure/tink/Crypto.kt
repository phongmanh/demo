package secure.tink

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

object Crypto {
    private const val KEYSET_NAME = "app_keyset"
    private const val PREF_FILE = "tink_keyset_prefs"   // exclude from backup!
    private const val MASTER_KEY_URI = "android-keystore://app_master_key"
    private const val KEY_TEMPLATE = "AES256_GCM"

    @Volatile
    private var aead: Aead? = null

    /**
     * Creates and configures an AEAD (Authenticated Encryption with Associated Data) primitive.
     *
     * This function sets up Tink's AEAD encryption by:
     * 1. Registering the AEAD configuration with Tink
     * 2. Creating an AndroidKeysetManager to handle key creation and management
     * 3. Configuring the manager with:
     *    - Shared preferences location for keyset storage
     *    - AES256_GCM key template (provides both confidentiality and integrity)
     *    - Android Keystore URI for master key protection
     * 4. Building the keyset handle and extracting the AEAD primitive
     *
     * AndroidKeysetManager Features:
     * - Automatic key generation on first run
     * - Key rotation capabilities when needed
     * - Secure storage using Android Keystore (hardware-backed if available)
     * - Automatic handling of keyset versioning and upgrades
     *
     * @param context The application context needed for shared preferences access
     * @return An AEAD primitive configured with AES256_GCM for authenticated encryption
     */
    fun getAead(context: Context): Aead = aead ?: synchronized(this) {
        aead ?: run {
            AeadConfig.register()

            getKeySetHandle(context = context)
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
                .also { aead = it }
        }
    }

    fun getKeySetHandle(context: Context): KeysetHandle {
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build().keysetHandle
    }


}
