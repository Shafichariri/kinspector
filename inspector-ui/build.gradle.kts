plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "dev.inspector.ui"
        compileSdk = 36
        minSdk = 24
    }
    jvm()
    // No iosX64: Compose Multiplatform 1.11+ dropped the Intel simulator, so no CMP app can
    // target it. Keeping it here would advertise a platform the UI module cannot serve.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":inspector-core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
            // Only for InspectorBackHandler. Every Compose Android app already has this — it is
            // what provides ComponentActivity.setContent.
            implementation(libs.androidx.activity.compose)
        }
    }
}
