import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kmp.library) apply false
    // Declared here, applied only by :sample:android. AGP is already on the build classpath via
    // the KMP library plugin above, so a module asking for it *with a version* fails resolution —
    // "already on the classpath with an unknown version, so compatibility cannot be checked".
    alias(libs.plugins.android.application) apply false
}

/*
 * The publishing namespace, and deliberately not `dev.inspector`.
 *
 * Maven Central verifies a `dev.*` namespace by DNS on the matching domain, and `inspector.dev` is
 * registered to somebody else — so `dev.inspector` was never claimable there however long it had
 * been in use here. `io.github.<user>` is verified by GitHub account ownership instead, which is
 * the route for a project without a domain.
 *
 * Changed **before** going to Central rather than as part of it: the coordinate change is the
 * breaking half and the repository move is not, so doing them together would make one event out
 * of two, and a consumer would have no way to tell which had broken them. Artifact ids and the
 * Kotlin package are untouched — `dev.inspector.Inspector` still imports exactly as it did.
 */
group = "io.github.shafichariri"

// Overridable so a tagged release can stamp the real version onto the daemon distribution --
// `inspector-0.2.0.zip` rather than `inspector-0.1.0-SNAPSHOT.zip` -- without a commit that edits
// this line every time. `.github/workflows/release.yml` passes the tag with its leading `v`
// stripped; local builds get the snapshot and are unaffected.
version = providers.gradleProperty("inspector.version").getOrElse("0.1.0-SNAPSHOT")

/*
 * Signing inputs, hoisted so the subprojects' signing config and the root's Central tasks read
 * one source. Two copies of this would drift in the direction that matters least visibly: the
 * bundle task would think a key was present while the publications went out unsigned.
 *
 * `filter` on blankness, not merely on presence. An unset repository secret arrives as an
 * *empty* environment variable rather than an absent one, so the obvious `isPresent` check turns
 * "no key configured" into "sign with the empty string" the moment a workflow passes
 * `${{ secrets.SIGNING_KEY }}` before the secret exists -- a failure at publish time, in CI, on
 * a tag, which is the worst place to discover it.
 */
val signingKey: Provider<String> = providers.gradleProperty("signingKey")
    .orElse(providers.environmentVariable("SIGNING_KEY"))
    .filter { it.isNotBlank() }
val signingPassword: Provider<String> = providers.gradleProperty("signingPassword")
    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
    .filter { it.isNotBlank() }

// Where every module stages its artifacts for the Central bundle: one directory under the ROOT
// build dir, not one per module, because the bundle is a single Maven repository layout.
val centralStagingDir: Provider<Directory> = layout.buildDirectory.dir("central/repo")

subprojects {
    group = rootProject.group
    version = rootProject.version

    /*
     * Captured here, in the `subprojects` body, where `this` is the Project.
     *
     * Two receivers have already got this wrong. Inside `pom {}` it is the POM, which is why the
     * original escaped the dollar so hard that the interpolation never ran — every POM from
     * 0.3.0 to 0.9.1 carries the placeholder text verbatim in its <name>. Inside
     * `pluginManager.withPlugin("maven-publish") {}` it is the applied plugin, so a plain `name`
     * there reads `Inspector maven-publish`, which is what the first attempt at this fix
     * published to the local repository. Neither mistake fails the build; both are only visible
     * by reading the generated POM.
     */
    // The `inspector-` prefix is dropped, or every name stutters: the artifact id already says
    // `inspector-core`, so `Inspector inspector-core` is what the original would have produced
    // had it ever interpolated. `Inspector core` and `Inspector noop-ui` are what a dependency
    // report wants beside the coordinate.
    val moduleLabel = "Inspector ${project.name.removePrefix("inspector-")}"

    // Publishing is opt-in per module: this configures whichever modules apply `maven-publish`
    // themselves, rather than listing them here. An allowlist in this file would be a second
    // place to keep current, and the failure mode is silent -- :inspector-daemon is a tool and
    // :sample:desktop is a demo, and neither should ever appear in the repository.
    pluginManager.withPlugin("maven-publish") {
        /*
         * A javadoc jar, because Maven Central rejects a publication without one.
         *
         * Kotlin has no javadoc, and Central checks only that the artifact is *present* -- an
         * empty jar satisfies it and is what most Kotlin libraries ship. Empty is also exactly
         * what a Dokka run that silently failed would produce, so this carries one line of text
         * instead: somebody who unzips it learns where the documentation actually is rather than
         * concluding the build dropped it. Dokka is the upgrade if real API docs are ever wanted;
         * it is a new toolchain across seven KMP modules, which is more than "the jar must exist"
         * is asking for.
         */
        val javadocStub = layout.buildDirectory.file("javadoc-jar/README.md")
        val writeJavadocStub = tasks.register("writeJavadocStub") {
            outputs.file(javadocStub)
            doLast {
                javadocStub.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(
                        "Inspector is written in Kotlin and ships no javadoc.\n\n" +
                            "Source, API notes and the integration guide:\n" +
                            "https://github.com/Shafichariri/kinspector\n"
                    )
                }
            }
        }
        val javadocJar = tasks.register<Jar>("javadocJar") {
            archiveClassifier = "javadoc"
            from(writeJavadocStub)
        }
        extensions.configure<PublishingExtension> {
            repositories {
                // A local Maven layout on disk, which the root `centralBundle` task zips and
                // `publishToCentralPortal` uploads. The Portal takes an archive, not a deploy:
                // it replaced OSSRH's protocol and there is no official Gradle plugin for it,
                // so "point a maven {} at Central" is not available however much it looks like
                // the obvious shape.
                maven {
                    name = "CentralBundle"
                    url = uri(centralStagingDir)
                }
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Shafichariri/kinspector")
                    // GitHub Packages requires a token for *downloads* too, not only publishing,
                    // even when the package is public -- so every consumer sets these as well.
                    // Properties first so a developer can keep them in ~/.gradle/gradle.properties
                    // and out of their environment; the env vars are what CI provides.
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
            }

            publications.withType<MavenPublication>().configureEach {
                // Attached to every publication, including each target's: Central wants a
                // javadoc artifact beside every coordinate, not only the root one.
                artifact(javadocJar)

                pom {
                    // Computed in the subprojects body; see the note on `moduleLabel`.
                    name = moduleLabel
                    description = "Network debugger for Compose Multiplatform apps that use Ktor."
                    url = "https://github.com/Shafichariri/kinspector"
                    // Consumers' dependency scanners read this, not the LICENSE file, so an
                    // unlicensed-looking POM gets a library flagged inside companies that check.
                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                    // Both required by Maven Central, and useful before it: a dependency report
                    // that cannot say who publishes a library or where its source is gets the
                    // library queried inside companies that check.
                    developers {
                        developer {
                            id = "Shafichariri"
                            name = "Chafic El Hariri"
                            url = "https://github.com/Shafichariri"
                        }
                    }
                    scm {
                        url = "https://github.com/Shafichariri/kinspector"
                        connection = "scm:git:https://github.com/Shafichariri/kinspector.git"
                        developerConnection = "scm:git:ssh://git@github.com/Shafichariri/kinspector.git"
                    }
                }
            }
        }

        /*
         * Signing, which Maven Central requires as a detached .asc beside every artifact.
         *
         * Wired to be completely inert without a key, and that is the load-bearing part. GitHub
         * Packages does not want signatures -- 1.0.0 went there unsigned -- so `build`,
         * `publishToMavenLocal` and the existing release job all have to keep working on a
         * machine that has never held a GPG key. Rather than declaring the signing tasks and
         * setting `isRequired = false`, no Sign task is created at all when no key is configured:
         * a task that exists and quietly signs nothing is the shape that ends with an unsigned
         * publication reaching Central and being rejected at the far end of a ten-minute job.
         *
         * The key is read in memory rather than from a keyring file. CI has no keyring, and a
         * secret that has to arrive as a file is one more moving part on the runner. Provide it
         * as the ASCII-armoured private key: `signingKey`/`signingPassword` in
         * ~/.gradle/gradle.properties locally, or SIGNING_KEY/SIGNING_PASSWORD in the
         * environment. Neither belongs in this repository.
         */
        if (signingKey.isPresent) {
            apply(plugin = "signing")
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(signingKey.get(), signingPassword.getOrElse(""))
                sign(extensions.getByType<PublishingExtension>().publications)
            }
            // Gradle does not infer this for a Kotlin Multiplatform publication: each publish
            // task consumes the matching Sign task's output without declaring the dependency, so
            // the build warns and, with a build cache or parallel execution, can publish before
            // the signature exists.
            tasks.withType<AbstractPublishToMaven>().configureEach {
                dependsOn(tasks.withType<Sign>())
            }
        }

        // Staging for Central runs only after the key check has passed, so a keyless attempt
        // stops before it has written half a repository rather than after.
        //
        // The guard hangs off **every** publish task targeting this repository, not off the
        // `publishAllPublications...` aggregate. Depending on the aggregate alone looks right
        // and is not: the aggregate waits for both the guard and the individual publications,
        // but imposes no order *between* them, so Gradle is free to run the publications first.
        // Measured -- a keyless run failed with the correct message and a non-zero exit after
        // it had already written 180 files. A guard that refuses once the thing it was guarding
        // has happened is decoration.
        tasks.withType<PublishToMavenRepository>().configureEach {
            if (name.endsWith("ToCentralBundleRepository")) {
                dependsOn(rootProject.tasks.named("requireSigningKey"))
            }
        }
        rootProject.tasks.named("stageCentralBundle") {
            dependsOn(tasks.named("publishAllPublicationsToCentralBundleRepository"))
        }
    }

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

/*
 * ---------------------------------------------------------------------------------------------
 * Maven Central, via the Central Portal.
 *
 * The Portal does not accept a Maven deploy. It replaced OSSRH's protocol, Sonatype's own Gradle
 * page says there is no official Gradle plugin for it, and what it takes is a **zipped Maven
 * repository layout** POSTed to a Publisher API. So "switch the repository URL to Central" is not
 * a thing that exists, however much the `maven {}` block invites it.
 *
 * Three tasks, deliberately separate, because they fail for different reasons and only the last
 * one leaves the machine:
 *
 *   requireSigningKey     refuses early, before anything is built
 *   centralBundle         produces build/central/bundle-<version>.zip
 *   publishToCentralPortal  uploads it and polls until the Portal has an opinion
 *
 * GitHub Packages keeps running alongside and is untouched by all of this: the two repositories
 * are separate `maven {}` entries and separate tasks, so a Central failure cannot take the
 * Packages release with it.
 * ---------------------------------------------------------------------------------------------
 */

// Refuses a keyless Central build *before* any module has staged anything.
//
// The build is otherwise deliberately happy without a key -- that is what keeps `build` and the
// GitHub Packages release working on a machine that has never held one. Central is the one place
// where unsigned is not a quieter outcome but a rejected one, so this is where the tolerance
// stops. Without it the bundle would build cleanly, upload, and be refused at the far end, which
// is the failure the whole signing design was shaped to avoid.
tasks.register("requireSigningKey") {
    group = "publishing"
    description = "Fails unless a signing key is configured. Guards the Central tasks."
    val present = signingKey.isPresent
    doLast {
        if (!present) {
            throw GradleException(
                """
                No signing key configured, and Maven Central rejects unsigned artifacts.

                Set `signingKey` and `signingPassword` in ~/.gradle/gradle.properties, or
                SIGNING_KEY and SIGNING_PASSWORD in the environment. `signingKey` is the
                ASCII-armoured private key, whole, including its BEGIN and END lines.

                A blank value counts as absent: an unset CI secret arrives as an empty string.
                """.trimIndent()
            )
        }
    }
}

// Lifecycle only. Every publishing module attaches its
// `publishAllPublicationsToCentralBundleRepository` to this from the subprojects block above,
// rather than this task naming the modules -- the same reason the root build does not keep an
// allowlist of what publishes.
tasks.register("stageCentralBundle") {
    group = "publishing"
    description = "Stages every published module into one local Maven layout for Central."
    dependsOn("requireSigningKey")
}

val centralBundle = tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Zips the staged Maven layout into a Central Portal deployment bundle."
    dependsOn("stageCentralBundle")
    from(centralStagingDir)
    // Gradle writes maven-metadata.xml into a file repository; the Portal validates the archive
    // as a deployment rather than as a repository and has no use for it. Excluded rather than
    // left to be ignored, because an unsignable file in a bundle of signed ones is exactly the
    // kind of thing a validator changes its mind about between releases.
    exclude("**/maven-metadata.xml*")
    archiveFileName = "bundle-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("central")
}

tasks.register("publishToCentralPortal") {
    group = "publishing"
    description = "Uploads the deployment bundle to the Central Portal and reports its state."
    dependsOn(centralBundle)

    // Portal *user token*, generated in the Portal account page -- not the GitHub token and not
    // the signing passphrase. Three different secrets are in play by now and they are not
    // interchangeable.
    val user = providers.gradleProperty("centralUsername")
        .orElse(providers.environmentVariable("CENTRAL_USERNAME"))
        .filter { it.isNotBlank() }
    val pass = providers.gradleProperty("centralPassword")
        .orElse(providers.environmentVariable("CENTRAL_PASSWORD"))
        .filter { it.isNotBlank() }
    // USER_MANAGED, not AUTOMATIC, and that default is the point: the upload validates and
    // stages, then waits for a human to press publish in the Portal. Releasing to Central is the
    // one irreversible act in this whole sequence -- a coordinate there can never be replaced or
    // deleted -- so it does not belong behind a Gradle task that a tag could trigger by accident.
    val publishingType = providers.gradleProperty("centralPublishingType").getOrElse("USER_MANAGED")
    val bundle = centralBundle.flatMap { it.archiveFile }
    val deploymentName = "${project.group}:${project.version}"

    doLast {
        if (!user.isPresent || !pass.isPresent) {
            throw GradleException(
                "No Portal credentials. Set centralUsername/centralPassword, or " +
                    "CENTRAL_USERNAME/CENTRAL_PASSWORD. These are the Portal user token, not " +
                    "the GitHub token and not the signing passphrase."
            )
        }
        val file = bundle.get().asFile
        val auth = java.util.Base64.getEncoder()
            .encodeToString("${user.get()}:${pass.get()}".toByteArray())

        val boundary = "----inspector${System.nanoTime()}"
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"bundle\"; filename=\"${file.name}\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val body = head + file.readBytes() + tail

        val client = java.net.http.HttpClient.newHttpClient()
        val uploadUri = java.net.URI.create(
            "https://central.sonatype.com/api/v1/publisher/upload" +
                "?name=" + java.net.URLEncoder.encode(deploymentName, "UTF-8") +
                "&publishingType=$publishingType"
        )
        val upload = java.net.http.HttpRequest.newBuilder(uploadUri)
            .header("Authorization", "Bearer $auth")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val uploaded = client.send(upload, java.net.http.HttpResponse.BodyHandlers.ofString())
        if (uploaded.statusCode() != 201) {
            throw GradleException("Portal upload failed: ${uploaded.statusCode()} ${uploaded.body()}")
        }
        val deploymentId = uploaded.body().trim()
        logger.lifecycle("Uploaded ${file.name} (${file.length()} bytes) as $deploymentId")

        // The Portal validates asynchronously, so a 201 says "received", never "accepted".
        // Reporting success on the upload alone would be the same mistake as reading a green
        // tick instead of the published artifact.
        val statusUri = java.net.URI.create(
            "https://central.sonatype.com/api/v1/publisher/status?id=$deploymentId"
        )
        repeat(60) {
            Thread.sleep(5_000)
            val status = client.send(
                java.net.http.HttpRequest.newBuilder(statusUri)
                    .header("Authorization", "Bearer $auth")
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
            )
            val payload = status.body()
            val state = Regex("\"deploymentState\"\\s*:\\s*\"([A-Z_]+)\"")
                .find(payload)?.groupValues?.get(1)
            logger.lifecycle("  $state")
            when (state) {
                "FAILED" -> throw GradleException("Portal rejected the deployment: $payload")
                "PUBLISHED" -> return@doLast
                "VALIDATED" -> {
                    logger.lifecycle(
                        "Validated and waiting for you. Press Publish at " +
                            "https://central.sonatype.com/publishing/deployments"
                    )
                    return@doLast
                }
            }
        }
        throw GradleException("Timed out waiting for the Portal; deployment $deploymentId")
    }
}
