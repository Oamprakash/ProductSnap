plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Short commit ID, shown in the app's header and version name so every test report names its build.
// CI sets GITHUB_SHA; local builds ask git.
val buildId: String = System.getenv("GITHUB_SHA")?.take(7)
    ?: runCatching {
        providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
    ?: "local"

android {
    namespace = "com.oamsapps.productsnap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oamsapps.productsnap"
        minSdk = 29 // MediaStore scoped storage + ImageDecoder without legacy paths
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-$buildId"
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")
    }

    buildTypes {
        release {
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
}
