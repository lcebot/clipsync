import java.security.KeyStore
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Compose compiler only. Kotlin itself is AGP 9's built-in Kotlin, see the root build file.
    alias(libs.plugins.kotlin.compose)
}

// Nothing is configured at build time. The app is set up on the device, which is also why a
// published APK cannot contain anybody's pre-shared key: not "should not", there is no field for
// it. Every setting lives in clipsync.conf in the app's private storage, written by the settings
// screen; the build knows about none of them.

// Release signing, PKCS12. Locally: android/keystore.properties (gitignored) with storeFile,
// storePassword and optionally keyAlias and keyPassword; storeFile is absolute, or relative to
// android/, since the keystore itself belongs outside the repository. On CI the same values arrive
// as environment variables. With neither, assembleRelease still runs and produces an unsigned APK,
// which cannot be installed.
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
// friendly name, which is not always what you expect and cannot be inspected without keytool. With
// no alias configured, read the store's only alias instead of making anyone guess.
val keystoreAlias = signingValue("keyAlias", "KEY_ALIAS") ?: keystoreFile?.takeIf { it.isFile }?.let { f ->
    KeyStore.getInstance("PKCS12").run {
        f.inputStream().use { load(it, keystorePassword?.toCharArray()) }
        aliases().toList().firstOrNull()
    }
}

android {
    namespace = "io.github.lcebot.clipsync"
    // 37.1 because the Compose 1.13 alphas that Material 3 1.5.0 alphas depend on declare it, and
    // AGP's checkAarMetadata fails the build below it; it is never a runtime surprise. The CI
    // installs the matching platforms;android-37.1 package. compileSdk only decides which APIs the
    // compiler can see; it does not change behaviour on any device, which is targetSdk's job.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.lcebot.clipsync"
        // 35 because pairing needs Conscrypt's native scrypt, which AOSP registers as
        // SecretKeyFactory.SCRYPT from android15-release on; android14 offers DESede and nothing
        // else there. The only stretching below 35 would be bundled pure-Java PBKDF2, which costs
        // seconds on a phone and still buys less brute-force resistance than a fraction of that in
        // scrypt. Conscrypt is an updatable Mainline module, so some API 34 devices have scrypt
        // anyway, but a key cannot be derived from "some", and the failure is
        // NoSuchAlgorithmException, a device that cannot pair at all.
        //
        // The price is every Android 14 device, which on this app's audience (Xposed and root users,
        // often on older ROMs) is a real number. What it buys: the pairing code, the whole of the
        // authentication for handing over the PSK, needs about 13 GPU-days to exhaust instead of
        // about 21 GPU-hours, while the phone spends less time on it. See Pairing.SCRYPT_N. Lowering
        // this means changing Pairing and clipsync_pair together: both ends must derive the same key
        // or pairing fails without saying why.
        minSdk = 35
        // Stays 35 through the Compose migration: moving it opts into new platform behaviour and is
        // reviewed on its own.
        targetSdk = 35
        // Both come from a git tag, which the workflow parses and passes in as -P: a release uses
        // its own tag, any other build uses the newest one plus the short commit in versionName
        // (1.0.3+a1b2c3d) while keeping that tag's versionCode. The values below are only the
        // fallback for a build with no tag in reach (a fresh clone with no tags, or a local build).
        //
        // Tags are vA.B.C with A and B one digit and C up to two, and versionCode is
        // A*1000 + B*100 + C, so 1.0.0 is 1000, 1.0.12 is 1012, 1.1.0 is 1100 and 2.0.0 is 2000.
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
            // v1 is JAR signing, only consulted below API 24, dead weight at minSdk 35, and the
            // scheme the Janus class of attacks targets. v3 (API 28+) carries the
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

    buildFeatures {
        compose = true
    }

    compileOptions {
        // libxposed api 102 uses sealed interfaces and records, which need Java 17. Kotlin's
        // jvmTarget follows targetCompatibility, so both languages emit the same bytecode level.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.libxposed.api)                  // libxposed API 102, the only entry (xposed.Entry)

    val composeBom = platform(libs.compose.bom.alpha)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.material3)            // 1.5.0-alpha29 through the alpha BOM
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.compose.ui.test.junit4)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    // Also the source of androidx.annotation (@Keep on Pairing.ScryptSpec). Pinning annotation
    // separately collides with AGP's consistent runtime and compile resolution.
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.coroutines.test)
}
