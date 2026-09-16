import java.security.KeyStore
import java.util.Properties

plugins {
    id("com.android.application")
}

val props = Properties().apply {
    val f = rootProject.file("clipsync.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Release signing, PKCS12. Locally: android/keystore.properties (gitignored) with storeFile /
// storePassword and optionally keyAlias / keyPassword; storeFile is absolute, or relative to
// android/ — the keystore itself belongs outside the repository. On CI the same values arrive as
// environment variables. With neither, assembleRelease still runs and produces an unsigned APK —
// which simply cannot be installed.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
val keystoreFile = signingValue("storeFile", "KEYSTORE_FILE")?.let { rootProject.file(it) }
val keystorePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
// A .p12 written by PowerShell's Export-PfxCertificate takes its entry name from the certificate's
// friendly name, which isn't always what you expect and can't be inspected without keytool. With no
// alias configured, read the store's only alias instead of making anyone guess.
val keystoreAlias = signingValue("keyAlias", "KEY_ALIAS") ?: keystoreFile?.takeIf { it.isFile }?.let { f ->
    KeyStore.getInstance("PKCS12").run {
        f.inputStream().use { load(it, keystorePassword?.toCharArray()) }
        aliases().toList().firstOrNull()
    }
}

android {
    namespace = "io.github.lcebot.clipsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lcebot.clipsync"
        minSdk = 35
        targetSdk = 35
        // versionCode encodes versionName: major * 1000 + minor * 100 + patch.
        // 1.0 -> 1000, 1.0.1 -> 1001, 1.1 -> 1100, 2.0 -> 2000. Both are edited by hand; nothing
        // derives them from a tag. Android only requires that this number never decreases.
        versionCode = 1000
        versionName = "1.0"
        buildConfigField("String", "HOST", "\"${props.getProperty("host", "")}\"")
        buildConfigField("int", "PORT", props.getProperty("port", "47521"))
        buildConfigField("String", "PSK", "\"${props.getProperty("psk", "")}\"")
        // ddns+mdns | ddns | mdns  (an empty host with the default mode makes the app fall back to mdns)
        buildConfigField("String", "MODE", "\"${props.getProperty("mode", if (props.getProperty("host", "").isBlank()) "mdns" else "ddns+mdns")}\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (keystoreAlias != null) create("release") {
            storeFile = keystoreFile
            storeType = "PKCS12"
            storePassword = keystorePassword
            keyAlias = keystoreAlias
            // PKCS12 keeps one password for the whole store; Export-PfxCertificate never sets a
            // separate one, so the store password is the key password unless told otherwise.
            keyPassword = signingValue("keyPassword", "KEY_PASSWORD") ?: keystorePassword
        }
    }

    buildTypes {
        release {
            if (keystoreAlias != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // libxposed api 102 uses sealed interfaces / records -> Java 17
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")     // libxposed API 102 (Entry) — the active entry
    compileOnly("de.robv.android.xposed:api:82")        // classic API: LegacyEntry is kept as reference only
    // androidx.annotation (for @NonNull in Entry) comes transitively via appcompat/core; pinning a
    // newer version explicitly collides with AGP's runtime/compile consistent resolution.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.android.material:material:1.14.0")   // Material 3 Expressive themes (1.14+)
}
