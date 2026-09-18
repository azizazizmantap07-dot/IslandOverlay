// Top-level build file — AGP 9.4 + built-in Kotlin
plugins {
    id("com.android.application") version "9.4.0" apply false
    // Compose compiler plugin (Kotlin itself is built into AGP 9)
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

// Use Kotlin 2.3.21 (newer than AGP's default 2.2.10) so Compose matches.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}
