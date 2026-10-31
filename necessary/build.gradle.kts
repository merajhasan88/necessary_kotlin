import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

plugins {
    id("com.android.library")
    `maven-publish`
    signing
}

group = "com.necessarysdk"
version = "0.1.5"
val repository = "https://github.com/merajhasan88/necessary_kotlin"
// A key named in signing.gnupg.keyName (empty: none) signs what is published.
val signingKey: String? = providers.gradleProperty("signing.gnupg.keyName").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.necessarysdk"
    compileSdk = 36
    // The native code comparison (src/main/cpp) is built with this NDK.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    testOptions {
        unitTests {
            all {
                it.outputs.upToDateWhen { false }
                it.testLogging {
                    events("passed", "skipped", "failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
                // The end-to-end test reads the backend's address and test token from these.
                listOf("NECESSARY_BACKEND_URL", "NECESSARY_TEST_TOKEN", "NECESSARY_BACKEND_NAME").forEach { name ->
                    System.getenv(name)?.let { value -> it.environment(name, value) }
                }
                it.systemProperty("necessary.root", rootDir.absolutePath)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

// Publishing to Maven Central: the release variant with its sources and docs,
// signed with the key that signing.gnupg.keyName names (gpg asks for its
// passphrase), staged as a Maven repository, bundled, and uploaded to the
// Central Portal for its owner to press Publish there. The Portal's user token
// is read from mavenCentralUsername and mavenCentralPassword, in
// ~/.gradle/gradle.properties: never in this repository.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.necessarysdk"
            artifactId = "necessary"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("necessary")
                description.set(
                    "Device integrity for Android apps: a key the phone never exports, signed measurements of " +
                        "the phone and the app, and a proof on each request. Your backend decides.",
                )
                url.set("https://necessarysdk.com")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("merajhasan88")
                        name.set("Meraj Hasan")
                        url.set("https://github.com/merajhasan88")
                    }
                }
                scm {
                    url.set(repository)
                    connection.set("scm:git:$repository.git")
                    developerConnection.set("scm:git:$repository.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging"))
        }
    }
}

signing {
    // Only where a key is named: a build without one still builds and tests.
    isRequired = signingKey != null
    useGpgCmd()
    sign(publishing.publications["release"])
}

tasks.withType<Sign>().configureEach {
    onlyIf { signingKey != null }
}

val cleanStaging by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.dir("staging"))
}

tasks.named("publishReleasePublicationToStagingRepository") { dependsOn(cleanStaging) }

/** The staged release as the Central Portal takes it: the repository layout, without its metadata files. */
val centralBundle by tasks.registering(Zip::class) {
    dependsOn("publishReleasePublicationToStagingRepository")
    from(layout.buildDirectory.dir("staging")) { exclude("**/maven-metadata*") }
    archiveFileName.set("necessary-${project.version}-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("central"))
}

/** Uploads the bundle to the Central Portal, to be published there by hand ("USER_MANAGED"). */
val uploadToCentral by tasks.registering {
    dependsOn(centralBundle)
    doLast {
        val username = providers.gradleProperty("mavenCentralUsername").orNull
        val password = providers.gradleProperty("mavenCentralPassword").orNull
        check(username != null && password != null) {
            "mavenCentralUsername and mavenCentralPassword, the Portal's user token, belong in ~/.gradle/gradle.properties"
        }
        check(signingKey != null) {
            "signing.gnupg.keyName belongs in ~/.gradle/gradle.properties: Maven Central takes signed files only"
        }
        val bundle = centralBundle.get().archiveFile.get().asFile
        val boundary = "necessary-${System.nanoTime()}"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val body = head.toByteArray() + bundle.readBytes() + "\r\n--$boundary--\r\n".toByteArray()
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        val name = URLEncoder.encode("com.necessarysdk:necessary:${project.version}", "UTF-8")
        val request = HttpRequest.newBuilder(
            URI("https://central.sonatype.com/api/v1/publisher/upload?name=$name&publishingType=USER_MANAGED"),
        )
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "The Central Portal answered ${response.statusCode()}: ${response.body()}" }
        logger.lifecycle(
            "Uploaded ${bundle.name}: deployment ${response.body().trim()}. " +
                "In the Central Portal, open Deployments, wait for VALIDATED, then press Publish.",
        )
    }
}
