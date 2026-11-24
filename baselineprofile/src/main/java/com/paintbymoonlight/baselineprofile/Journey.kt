package com.paintbymoonlight.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

const val PACKAGE = "com.pbordjadze.paintbymoonlight"
private const val WAIT = 20_000L

/** Paints the library the journey opens (the app's BenchmarkSeed); at once once it's there. */
fun MacrobenchmarkScope.seedLibrary() {
    val result = device.executeShellCommand("content call --uri content://$PACKAGE.benchmarkseed --method seed")
    check("Result: Bundle" in result) { "the benchmark build didn't seed its library: $result" }
}

/** The gallery, once its paintings show. */
fun MacrobenchmarkScope.waitForGallery() {
    check(device.wait(Until.hasObject(By.descContains("Parrots")), WAIT)) { "the gallery never showed Parrots" }
}

/** From the gallery, opens Parrots and waits for its canvas. */
fun MacrobenchmarkScope.openPainting(): UiObject2 {
    device.findObject(By.descContains("Parrots")).click()
    return checkNotNull(device.wait(Until.findObject(By.desc("Painting canvas")), WAIT)) { "the canvas never showed" }
}

/** What a painter does first on a painting: looks around, zooms in on an area, paints it. */
fun MacrobenchmarkScope.paintAWhile(canvas: UiObject2) {
    canvas.setGestureMargin(device.displayWidth / 8)
    canvas.pinchOpen(0.6f)
    device.waitForIdle()
    canvas.swipe(Direction.LEFT, 0.5f)
    canvas.swipe(Direction.UP, 0.5f)
    canvas.swipe(Direction.RIGHT, 0.5f)
    canvas.click()
    device.waitForIdle()
    canvas.pinchClose(0.6f)
    device.waitForIdle()
}
