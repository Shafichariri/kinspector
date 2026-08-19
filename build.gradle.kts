import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

group = "dev.inspector"

// Overridable so a tagged release can stamp the real version onto the daemon distribution --
// `inspector-0.2.0.zip` rather than `inspector-0.1.0-SNAPSHOT.zip` -- without a commit that edits
// this line every time. `.github/workflows/release.yml` passes the tag with its leading `v`
// stripped; local builds get the snapshot and are unaffected.
version = providers.gradleProperty("inspector.version").getOrElse("0.1.0-SNAPSHOT")

subprojects {
    group = rootProject.group
    version = rootProject.version

    // Print the assertion message and stack trace when a test fails.
    //
    // Gradle's default exception format is SHORT, which logs only
    // "java.lang.AssertionError at PerAttemptTest.kt:72" and discards the message. These tests
    // deliberately carry their evidence in that message — `describe()` prints the offending rows —
    // so the default threw away the only diagnosis available for a CI-only failure, which is
    // exactly the case where re-running locally does not help.
    tasks.withType<AbstractTestTask>().configureEach {
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showStackTraces = true
            showCauses = true
        }
    }
}
