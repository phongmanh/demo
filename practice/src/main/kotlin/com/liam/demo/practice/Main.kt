package com.liam.demo.practice

/**
 * Console entry point for practicing plain Kotlin on the JVM (no device or emulator).
 *
 * Run from a terminal: ./gradlew :practice:run -q --console=plain
 * Or click the green ▶ next to `main` in Android Studio.
 */
fun main(args: Array<String>) {
    println("Hello, practice!")
    println("Args: ${args.joinToString()}")

    // Write your practice code below.
    val secureProvider = JvmSecureProvider()
    val (iv, cipherText) = secureProvider.encrypt("my secret note".toByteArray())
    println("Encrypted: ${cipherText.size} bytes")
    println("Decrypted: ${secureProvider.decrypt(iv, cipherText).decodeToString()}")

}
