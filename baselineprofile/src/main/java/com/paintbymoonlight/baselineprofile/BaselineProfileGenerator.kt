package com.paintbymoonlight.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's baseline profile: launch, the gallery, a painting opened, panned, zoomed and painted.
 * `./gradlew :app:generateBaselineProfile` with a device connected writes it to
 * app/src/main/generated/baselineProfiles/ (docs/release.md › Baseline profile).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun seed() = seedLibrary()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        waitForGallery()
        val canvas = openPainting()
        paintAWhile(canvas)
        device.pressBack()
        waitForGallery()
    }
}
