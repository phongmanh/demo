// Plain Kotlin/JVM module for console practice. Runs on your computer, not on a device.
plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.liam.demo.practice.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
