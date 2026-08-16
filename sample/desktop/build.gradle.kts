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

    if (inspectorOff) {
        implementation(project(":inspector-noop"))
        implementation(project(":inspector-noop-ui"))
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
