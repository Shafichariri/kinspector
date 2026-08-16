plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "dev.inspector.daemon.MainKt"
    applicationName = "inspector"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":inspector-model"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    // The round-trip test drives the real sink against the real daemon.
    testImplementation(project(":inspector-stream"))
}

tasks.test {
    useJUnitPlatform()
}
