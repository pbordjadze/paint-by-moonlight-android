package com.paintbymoonlight.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to the gallery's paintings, and the frames of a painting opened and painted, each
 * without the baseline profile (no AOT compilation, as on the first launch after a sideload) and
 * with it (as Play installs the app). CI's `profile` job reports the medians.
 */
@RunWith(AndroidJUnit4::class)
class Benchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun startupWithoutProfile() = startup(CompilationMode.None())
    @Test fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))
    @Test fun paintingWithoutProfile() = painting(CompilationMode.None())
    @Test fun paintingWithProfile() = painting(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(compilation: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { seedLibrary() },
    ) {
        startActivityAndWait()
        waitForGallery()
    }

    private fun painting(compilation: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            seedLibrary()
            pressHome()
            startActivityAndWait()
            waitForGallery()
        },
    ) {
        paintAWhile(openPainting())
        device.pressBack()
        waitForGallery()
    }
}
