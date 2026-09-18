plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Swapped by -Pinspector=off exactly as a consuming app would do it. This module exists to be the
// thing a consumer's app is, so it wires itself the way the README tells them to.
val inspectorOff = providers.gradleProperty("inspector").orNull == "off"

android {
    namespace = "dev.inspector.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.inspector.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // No release signing config: this is never shipped anywhere. `assembleRelease` still has
        // to work, because an unsigned release APK is what proves the -Pinspector=off swap leaves
        // no capture code behind — see scripts/check-release-clean.sh.
        release { isMinifyEnabled = false }
    }

    buildFeatures { compose = true }

    // The demo server is shared with :sample:desktop rather than copied. Two copies of a fixture
    // is how the two samples would quietly stop exercising the same endpoints.
    sourceSets["main"].kotlin.srcDir("../shared/kotlin")
}

// AGP 9 carries Kotlin itself, so there is no `kotlin { }` block here to hang a toolchain on.
kotlin { jvmToolchain(21) }



dependencies {
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
    // OkHttp rather than CIO: it is the engine an Android app actually uses, so the sample
    // exercises the one a consumer will.
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.compression)
    implementation(libs.kotlinx.coroutines.core)
    // Stands in for an SDK that owns its transport — Auth0, Retrofit, Coil.
    implementation(libs.okhttp)

    if (inspectorOff) {
        implementation(project(":inspector-noop"))
        implementation(project(":inspector-noop-ui"))
        implementation(project(":inspector-noop-stream"))
    } else {
        implementation(project(":inspector-core"))
        implementation(project(":inspector-ui"))
        implementation(project(":inspector-stream"))
    }
}
