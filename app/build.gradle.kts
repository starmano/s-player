import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// CI numbers every build after its workflow run, which is also the build-N release tag that the
// app compares itself with when it checks for updates. Local builds are build 1.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Release builds are signed with the key in signing.p12 (made once by the "Create signing key"
// workflow) so that each build can install over the previous one. Its password comes from the
// SIGNING_PASSWORD secret; without it, builds fall back to the debug key.
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotEmpty() }
val signingKeystore = file("signing.p12")

android {
    namespace = "com.starmano.splayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.starmano.splayer"
        // Picture-in-picture on phones needs Android 8.0 (API 26).
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.3.1"
        // The FFmpeg decoder is native code; phones are ARM, so leave out the x86 copies.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (signingPassword != null && signingKeystore.exists()) {
            create("release") {
                storeFile = signingKeystore
                storePassword = signingPassword
                keyAlias = "s-player"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // The published APK is this build; keep it unshrunk until a shrunk one is tried on a phone.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.ffmpeg)

    testImplementation(libs.junit)
}
