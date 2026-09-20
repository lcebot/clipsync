import java.security.KeyStore
import java.util.Properties

plugins {
    id("com.android.application")
}

// Nothing is configured at build time. The app is set up on the device, which is also why a
// published APK cannot contain anybody's pre-shared key: not "should not", there is no field for
// it. Every setting lives in clipsync.conf in the app's private storage, written by the settings
// screen; the build knows about none of them.

// Release signing, PKCS12. Locally: android/keystore.properties (gitignored) with storeFile /
// storePassword and optionally keyAlias / keyPassword; storeFile is absolute, or relative to
// android/, since the keystore itself belongs outside the repository. On CI the same values arrive
// as environment variables. With neither, assembleRelease still runs and produces an unsigned APK,
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
    // 35 is not a spare number here: it is exactly what the Material dependency below demands.
    // An AAR records the compileSdk it was built against, and AGP refuses at BUILD time: a
    // checkReleaseAarMetadata failure saying the dependency "requires libraries and applications
    // that depend on it to compile against version N or later", never a runtime surprise, if the
    // consumer compiles lower. Material 1.14.0 is built with compileSdkVersion 35 (its root
    // build.gradle at tag 1.14.0 sets `compileSdkVersion = 35`, and the 1.14.0 release notes list
    // only minSdk 23 and AGP 8.11.1 under "Important"). An earlier review guessed 1.13+ wanted 36;
    // it does not, since 1.13.0's notes say 35 as well. So this may stay at 35, and 1.14 is not a reason
    // to raise it.
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lcebot.clipsync"
        // 35, and this number has been 35, then 34, and now 35 again. The round trip is the whole
        // comment, because the reason it went down is still true and is not the reason it came back.
        //
        // It went from 35 to 34 because nothing in the app needed 35: the highest API actually
        // called is the specialUse foreground-service type, which is 34. 35 was excluding most of
        // the devices this app is FOR (Xposed and root users are disproportionately on older ROMs)
        // in exchange for nothing at all. That argument was correct and has not been refuted.
        //
        // It came back to 35 because something finally does need it, and it is not an API call:
        // Conscrypt's native scrypt. AOSP's external/conscrypt registers
        // `SecretKeyFactory.SCRYPT` for the first time on the android15-release branch; on
        // android14-release and android14-qpr3-release the SecretKeyFactory block is DESEDE and its
        // TDEA alias and nothing else. Below 35 the only stretching available to this app is the
        // bundled pure-Java BouncyCastle PBKDF2, which costs SECONDS on a phone and still buys less
        // brute-force resistance than a fraction of that in scrypt does. See Pairing.SCRYPT_N for the
        // measured comparison. Conscrypt is an updatable Mainline module, so some API-34 devices
        // will have picked scrypt up anyway, but "will have" is not something to derive a key from,
        // and the failure mode is NoSuchAlgorithmException, i.e. a device that cannot pair at all.
        //
        // The price is paid in devices, knowingly: every Android 14 device is now excluded, and on
        // this app's audience that is a real number rather than a rounding error. What it buys is
        // one specific thing: the pairing code, which is the whole of the authentication for
        // handing over the PSK, goes from ~21 GPU-hours to exhaust to ~13 GPU-days, while the phone
        // spends less time on it than before. Nothing else in the app changes. If that trade ever
        // stops looking worth it, the way back is to revert this number AND Pairing/clipsync_pair
        // together: the two ends derive the same key or pairing silently fails, so they move as one.
        minSdk = 35
        targetSdk = 35
        // Both come from a git tag, which the workflow parses and passes in as -P: a release uses
        // its own tag, any other build uses the newest one plus the short commit in versionName
        // (1.0.3+a1b2c3d) while keeping that tag's versionCode. The values below are only the
        // fallback for a build with no tag in reach (a fresh clone with no tags, or a local build).
        //
        // Tags are vA.B.C with A and B one digit and C up to two, and versionCode is
        // A*1000 + B*100 + C, so 1.0.0 becomes 1000, 1.0.12 becomes 1012, 1.1.0 becomes 1100, and
        // 2.0.0 becomes 2000.
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
            // v1 is the old JAR signing, only consulted below API 24, dead weight at minSdk 35,
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
        // libxposed api 102 uses sealed interfaces / records, which needs Java 17
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")     // libxposed API 102, the only entry (Entry)
    // androidx.annotation (for @NonNull in Entry) comes transitively via appcompat/core; pinning a
    // newer version explicitly collides with AGP's runtime/compile consistent resolution.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.13.1")
    // Material 3 Expressive themes (1.14+). Needs compileSdk 35 and minSdk 23, both satisfied
    // above; see the note on compileSdk for why 35 is the exact requirement and not a guess.
    implementation("com.google.android.material:material:1.14.0")
}
