// AGP 9.3 (July 2026). It needs Gradle 9.x and JDK 17, and supports compileSdk up to 37.
// The CI workflow pins the matching Gradle; if this pair is ever rejected, AGP 9.0.1 with Gradle
// 9.1.0 is the pairing Google documents explicitly.
plugins {
    id("com.android.application") version "9.3.0" apply false
}
