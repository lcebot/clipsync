// AGP 9 needs Gradle 9 and JDK 17, and builds Kotlin itself ("built-in Kotlin"), so the Kotlin
// Android plugin is never applied to a module. It is listed here, not applied, to pin the compiler
// version on the classpath; the Compose compiler plugin must match it exactly.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
