plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }

// Swapped by -Pinspector=off exactly as a consuming app would do it, so the sample doubles as
// the reference wiring documented in the README.
val inspectorOff = providers.gradleProperty("inspector").orNull == "off"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.compression)
    implementation(libs.kotlinx.coroutines.core)
    // Stands in for an SDK that owns its transport — Auth0, Retrofit, Coil — so the sample
    // exercises the non-Ktor capture path alongside the Ktor one.
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

compose.desktop {
    application {
        mainClass = "dev.inspector.sample.MainKt"
        // ./gradlew :sample:desktop:run -Dinspector.sample.autofire=true
        System.getProperty("inspector.sample.autofire")?.let {
            jvmArgs += "-Dinspector.sample.autofire=$it"
        }
    }
}
