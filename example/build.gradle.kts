plugins {
    id("com.android.application")
}

// A folder of resources with a network security config of its own, lab_network_security.xml --
// to trust a test certificate authority for a backend on your own computer, say. Without it, the
// example trusts only the system's authorities.
val extraResources: String? = providers.gradleProperty("necessary.extraRes").orNull

android {
    namespace = "com.necessarysdk.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.necessarysdk.example"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.5"
        // The backends the example offers: "Name=https://...;Other=https://...", or one address.
        manifestPlaceholders["networkSecurityConfig"] =
            if (extraResources != null) "@xml/lab_network_security" else "@xml/network_security"
        // Writes each scan's signed report, as sent, to the device log (tag EXAMPLE_REPORT), so that
        // whoever tests the example can check it against the contract. Off unless asked for.
        buildConfigField("boolean", "LOG_REPORTS", providers.gradleProperty("necessary.logReports").orElse("false").get())
        buildConfigField("String", "NECESSARY_BACKENDS", "\"${providers.gradleProperty("necessary.backends").orElse("").get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    if (extraResources != null) {
        sourceSets.getByName("main").res.srcDir(extraResources)
    }

    buildTypes {
        release {
            // Signed with the debug key so that a release build installs without a key of your own.
            // A release build is not debuggable: judge the measurements on it.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":necessary"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
