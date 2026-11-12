# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Single-activity Jetpack Compose app (`com.liam.demo`) for experimenting with on-device security: Android Keystore AES-GCM keys (StrongBox/TEE), biometric-bound keys, Tink AEAD, and a SQLCipher-encrypted Room database. Two Gradle modules:

- `:app`: the Android app (minSdk 24, target/compileSdk 37, JVM 17).
- `:practice`: a plain Kotlin/JVM console module for trying out code on the desktop, with no device needed. `JvmSecureProvider` mirrors `secure.SecureProvider`'s encrypt/decrypt API but uses an in-memory key, because AndroidKeyStore only exists on a device.

Toolchain: AGP 9.x with built-in Kotlin (there is no separate `kotlin-android` plugin), Kotlin 2.4, KSP for the Room compiler, and Gradle configuration cache enabled. Dependency versions live in `gradle/libs.versions.toml`.

## Commands

```bash
./gradlew :app:assembleDebug                 # build
./gradlew :app:installDebug                  # install on a connected device/emulator
./gradlew :app:testDebugUnitTest             # JVM unit tests
./gradlew :app:testDebugUnitTest --tests "com.liam.demo.ExampleUnitTest.addition_isCorrect"
./gradlew :app:connectedDebugAndroidTest     # instrumented tests (needs a device)
./gradlew :app:ktlintCheck                   # lint; ktlintFormat to auto-fix
./gradlew :app:detekt                        # static analysis (config/detekt/detekt.yml on top of defaults)
./gradlew :app:koverVerify                   # coverage gate; koverHtmlReport for the report
./gradlew :practice:run -q --console=plain   # run the console practice module (stdin is wired up)
```

Kover only measures `*ViewModel` classes and fails below **80% line coverage**. A new ViewModel needs tests, or `koverVerify` will fail.

## Architecture

**Package layout does not follow the directory layout.** Everything under `app/src/main/java/secure/{envelope,keystore,tink,ui}/` declares `package secure`, and `app/src/main/java/database/` is `package database`. Neither sits under `com.liam.demo`. Import these as `secure.X` / `database.X`.

**UI pattern (MVI-style), in `secure/ui/`:**
- `SecureViewModel` holds `uiState: StateFlow<SecureUiState>` through a Kotlin explicit backing field (`field = MutableStateFlow(...)`), plus one-shot `uiEvent`s from a `Channel`.
- The screen sends `SecureAction`s to `viewModel.onAction(...)`. `SecureScreen` collects events. `SecureContent` is the stateless, previewable composable.
- Crypto work runs on `Dispatchers.IO`. Every handler checks `isBusy` before starting, rethrows `CancellationException`, and reports failures as both a `SecureEvent.Failed` (snackbar) and `SecureUiStatus.Failure`.
- `MainActivity` is an `AppCompatActivity` (that makes it a `FragmentActivity`, which `BiometricPrompt` requires) and hosts `SecureScreen` directly. Navigation (`com.liam.demo.navigation`) is only a stub so far.

**Biometric flow (two steps):**
1. The ViewModel builds a cipher from a biometric-bound key (`SecureProvider.biometricCipher`), stores the intent in `pendingBiometric`, and emits `SecureEvent.RequestBiometric(cipher)`.
2. The screen calls `BiometricAuthenticator.authenticate(...)` and returns `BiometricSucceeded(cipher)` or `BiometricFailed`. Only after that does the ViewModel run `cipher.doFinal`.

If the ViewModel catches `KeyPermanentlyInvalidatedException` (biometric enrollment changed), it deletes the key and resets the result.

**Crypto building blocks:**
- `SecureProvider`: AndroidKeyStore AES-256-GCM keys. It tries StrongBox first and falls back on `StrongBoxUnavailableException`. Key creation is serialized behind a `Mutex`. It also reports the key's security level and can create an EC signing key.
- `SecureError`: maps keystore exceptions (walking the cause chain) to `CryptoFailure` types. `withKeystoreRetry` retries only failures classified as `Temporary`, with exponential backoff.
- `DbManager` (envelope encryption): a random 32-byte DEK, wrapped by a Keystore KEK (`db_dek_v1`) and stored atomically in `noBackupFilesDir/db_dek.bin`. `passphrase()` returns it in SQLCipher raw-key form `x'hex'`, which skips PBKDF2, and zeroes the DEK afterwards.
- `Crypto` (Tink): a lazily created singleton AEAD. Its keyset lives in the `tink_keyset_prefs` shared prefs and is protected by the master key `android-keystore://app_master_key`. That prefs file must stay excluded from backup.
- `AppDatabase`: Room + SQLCipher via `SupportOpenHelperFactory(DbManager(context).passphrase())`. The Room schema export directory is `app/schemas`.

## Conventions

- ktlint uses the `android_studio` code style with a 100-character max line length. `.editorconfig` sets the trailing-comma rules, and `@Composable` functions are exempt from function-naming rules.
- R8 keep rules go in `app/src/main/keepRules/` (AGP 9 `optimization {}` block), not in `proguard-rules.pro`.
- `app/src/test` uses Konsist for architecture tests (for example, a `domain` module must not import `android.*` or `androidx.*`).
