plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.paintbymoonlight"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.pbordjadze.paintbymoonlight"
        minSdk = 29
        targetSdk = 37
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed in CI from the upload key's secrets once Play Console exists; until then
            // release builds are signed with the debug key so they install for a look.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // The line models are memory-mapped from the APK, which needs them stored uncompressed.
    androidResources { noCompress += "tflite" }
    packaging {
        // The Swift runtime's libraries are loaded straight from the APK (uncompressed and
        // page-aligned), as Play expects of native code.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.play.services.wallet)
    implementation(libs.compose.pay.button)
    // The line models run on LiteRT's interpreter (core/EdgeModels.kt), which needs none of what
    // LiteRT's model delivery brings along.
    implementation(libs.litert) {
        exclude(group = "com.google.guava")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-guava")
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }
    // On-device ML Kit through Google Play services (core/PhotoFinder.kt): its models download
    // once with Play services, not in the APK.
    implementation(libs.mlkit.faces)
    implementation(libs.mlkit.subjects)
    implementation(libs.mlkit.text)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
