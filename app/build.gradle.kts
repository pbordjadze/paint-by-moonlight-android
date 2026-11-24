plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

// The upload key (docs/release.md): CI writes the keystore from its secrets and names it here.
// Without one, a release build is signed with the debug key on a developer's machine (to install
// for a look) and left unsigned on CI, so nothing debug-signed can reach Play.
val uploadStore = System.getenv("MOONLIGHT_UPLOAD_STORE_FILE")?.let(::file)?.takeIf { it.isFile }
val onCI = System.getenv("CI") == "true"

// versionCode: the number of commits on HEAD, which only grows along main, where releases are
// made from (a full clone; VERSION_CODE overrides it). versionName: gradle.properties.
val commitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }

android {
    namespace = "com.paintbymoonlight"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.pbordjadze.paintbymoonlight"
        minSdk = 29
        targetSdk = 37
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: commitCount.get()
        versionName = providers.gradleProperty("moonlight.versionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (uploadStore != null) {
            create("upload") {
                storeFile = uploadStore
                storePassword = System.getenv("MOONLIGHT_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("MOONLIGHT_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("MOONLIGHT_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                uploadStore != null -> signingConfigs.getByName("upload")
                onCI -> null
                else -> signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        // The Swift runtime's libraries are loaded straight from the APK (uncompressed and
        // page-aligned), as Play expects of native code.
        jniLibs.useLegacyPackaging = false
    }
}

// The benchmark builds the baselineprofile plugin adds also get BenchmarkSeed, which paints the
// macrobenchmarks' library from the tour's photos; the release has neither.
androidComponents {
    onVariants { variant ->
        if (variant.buildType !in setOf("benchmarkRelease", "nonMinifiedRelease")) return@onVariants
        variant.sources.kotlin?.addStaticSourceDirectory("src/benchmarkSeed/java")
        variant.sources.assets?.addStaticSourceDirectory("src/androidTest/assets")
        variant.sources.manifests.addStaticManifestFile("src/benchmarkSeed/AndroidManifest.xml")
    }
}

baselineProfile {
    // Generated on CI's emulator (docs/release.md › Baseline profile) and committed, so a
    // release build never waits on a device.
    mergeIntoMain = true
    automaticGenerationDuringBuild = false
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.profileinstaller)
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
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

// Play's download size per ABI of the release bundle (bundletool's get-size over the split APKs
// Play would serve): `./gradlew :app:bundleSize`, which scripts/sizes.sh reports.
val bundletool = configurations.create("bundletool")
dependencies { bundletool(libs.bundletool) }
val releaseBundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")
val releaseApks = layout.buildDirectory.file("outputs/sizes/app-release.apks")
val bundleApks = tasks.register<JavaExec>("bundleApks") {
    dependsOn("bundleRelease")
    classpath = bundletool
    mainClass = "com.android.tools.build.bundletool.BundleToolMain"
    inputs.file(releaseBundle)
    outputs.file(releaseApks)
    doFirst { releaseApks.get().asFile.delete() }
    val aapt2 = androidComponents.sdkComponents.sdkDirectory.get().dir("build-tools/${android.buildToolsVersion}/aapt2")
    args("build-apks", "--bundle=${releaseBundle.get().asFile}", "--output=${releaseApks.get().asFile}", "--aapt2=$aapt2")
}
tasks.register<JavaExec>("bundleSize") {
    dependsOn(bundleApks)
    classpath = bundletool
    mainClass = "com.android.tools.build.bundletool.BundleToolMain"
    args("get-size", "total", "--apks=${releaseApks.get().asFile}", "--dimensions=ABI")
}
