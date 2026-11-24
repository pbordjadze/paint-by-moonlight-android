plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// The app's baseline profile and its macrobenchmarks (docs/release.md › Baseline profile), run on a
// connected device or emulator: CI's `profile` job.
android {
    namespace = "com.paintbymoonlight.baselineprofile"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // An emulator's timings are noisy but comparable run to run; a phone needs no suppression.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
