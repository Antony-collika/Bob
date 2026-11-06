plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    // Required since Kotlin 2.0 when Compose is enabled (AGP errors out
    // without it: "the Compose Compiler Gradle plugin is required").
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
