plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase (Crashlytics and Analytics) is switched on by app/google-services.json, which is kept out of this
// public repo: copy it in from the Firebase console locally, and CI writes it from the GOOGLE_SERVICES_JSON secret.
// Without it the app builds and runs with telemetry off.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// Short commit ID, shown in the app's header and version name so every test report names its build.
// CI sets GITHUB_SHA; local builds ask git.
val buildId: String = System.getenv("GITHUB_SHA")?.take(7)
    ?: runCatching {
        providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
    ?: "local"

// Release signing with the Play upload key, which never lives in this repo. Locally the four values come from
// ~/.gradle/gradle.properties; in CI from secrets (the keystore arrives base64-encoded and is decoded to a temp
// file). Without them the release build is left unsigned, and debug builds are unaffected.
fun signingValue(name: String): String? =
    (providers.gradleProperty(name).orNull ?: System.getenv(name))?.takeIf { it.isNotBlank() }
val uploadKeystore = signingValue("PRODUCTSNAP_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.oamsapps.productsnap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oamsapps.productsnap"
        minSdk = 29 // MediaStore scoped storage + ImageDecoder without legacy paths
        targetSdk = 35
        // Play needs a higher number for every upload: CI's run number, or 1 for local builds.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.0-$buildId"
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")
    }

    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = uploadKeystore
                storePassword = signingValue("PRODUCTSNAP_KEYSTORE_PASSWORD")
                keyAlias = signingValue("PRODUCTSNAP_KEY_ALIAS")
                keyPassword = signingValue("PRODUCTSNAP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Batch runs as a foreground job that survives the app going to the background
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Tested photo pipeline: segmentation, mask check, edge cleanup, compositing
    implementation(project(":core:imaging"))

    // On-device background removal (model downloaded by Play services)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")

    // Premium subscription
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    // Crash reports and usage events (active only with google-services.json; see the top of this file)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")
}
