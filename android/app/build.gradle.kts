import java.security.KeyStore
import java.util.Properties

plugins {
    id("com.android.application")
}

// Nothing is configured at build time. The app is set up on the device, which is also why a
// published APK cannot contain anybody's pre-shared key — not "should not": there is no field for
// it. See docs/p2p-plan.md §10.

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
// Set by the release workflow from the tag name; absent everywhere else.
val releaseVersionName = (findProperty("clipsync.versionName") as String?)?.takeIf { it.isNotBlank() }
val releaseVersionCode = (findProperty("clipsync.versionCode") as String?)?.toIntOrNull()

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
        // Both come from a git tag, which the workflow parses and passes in as -P: a release uses
        // its own tag, any other build uses the newest one plus the short commit in versionName
        // (1.0.3+a1b2c3d) while keeping that tag's versionCode. The values below are only the
        // fallback for a build with no tag in reach (a fresh clone with no tags, or a local build).
        //
        // Tags are vA.B.C with A and B one digit and C up to two, and versionCode is
        // A*1000 + B*100 + C — so 1.0.0 -> 1000, 1.0.12 -> 1012, 1.1.0 -> 1100, 2.0.0 -> 2000.
        // Android only requires that the number never decreases, which that ordering guarantees.
        versionCode = releaseVersionCode ?: 1000
        versionName = releaseVersionName ?: "1.0"
    }

    signingConfigs {
        if (keystoreAlias != null) create("release") {
            storeFile = keystoreFile
            storeType = "PKCS12"
            storePassword = keystorePassword
            keyAlias = keystoreAlias
            // PKCS12 keeps one password for the whole store; Export-PfxCertificate never sets a
            // separate one, so the store password is the key password unless told otherwise.
            keyPassword = signingValue("keyPassword", "KEY_PASSWORD") ?: keystorePassword
            // v1 is the old JAR signing, only consulted below API 24 — dead weight at minSdk 35,
            // and the scheme the Janus class of attacks targeted. v3 (API 28+) carries the
            // proof-of-rotation lineage: without a v3 block in the installed APK there is no
            // supported way to ever move this app to a different key. v4 only buys incremental
            // `adb install` and needs its own .idsig file alongside the APK.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
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
