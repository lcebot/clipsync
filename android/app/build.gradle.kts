import java.util.Properties

plugins {
    id("com.android.application")
}

val props = Properties().apply {
    val f = rootProject.file("clipsync.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.lcebot.clipsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lcebot.clipsync"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "HOST", "\"${props.getProperty("host", "")}\"")
        buildConfigField("int", "PORT", props.getProperty("port", "47521"))
        buildConfigField("String", "PSK", "\"${props.getProperty("psk", "")}\"")
        // ddns+mdns | ddns | mdns  (an empty host with the default mode makes the app fall back to mdns)
        buildConfigField("String", "MODE", "\"${props.getProperty("mode", if (props.getProperty("host", "").isBlank()) "mdns" else "ddns+mdns")}\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
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
