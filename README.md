# Paint by Moonlight for Android

Your photos, painted by numbers: the Android app of Paint by Moonlight.

The pipeline that turns a photo into a painting is the iOS app's own, PaintCore from
[paint-by-number](https://github.com/pbordjadze/paint-by-number), compiled for Android with
Swift's Android SDK, so a photo makes the same template on both. Everything a painter touches is
native Jetpack Compose (Material 3 Expressive).

- `core/`: the Swift package the app loads as `libMoonlightCore.so`: PaintCore (pinned by
  revision in `core/Package.swift`), a C ABI (`MoonlightCore/CABI.swift`), the JNI glue
  (`CMoonlightJNI`) and `moonlight-probe`, which makes a painting from a PPM photo on the command
  line and prints its hash.
- `app/`: the app. `core/` (Kotlin) is the bridge (`Core`, `Scene`), `data/` the library on disk,
  `ui/` the screens, `print/` One Big Sheet (the template printed full size through the iOS
  app's print relay, `feedback-relay/print.js` in paint-by-number, paid with Google Pay),
  `unlock/` Unlimited Paintings (five free paintings, one back each day; one Google Play purchase,
  the product `unlimited_paintings`, lifts the limit). Debug builds, their tests and CI buy from
  `FakeUnlockStore` (`src/debug/`), never from Play; release builds from `PlayUnlockStore`.
- `scripts/build-core.sh` builds the core for each ABI into `app/src/main/jniLibs/`;
  `scripts/emulator-tour.sh` runs the probe and the app's tour on an emulator.
- CI (`.github/workflows/android.yml`) builds everything, checks the emulator makes the host's
  template byte for byte, and keeps the tour's screenshots and the APK as the run's artifact.

## Building

Swift 6.4.3 with its Android SDK (`swift sdk install …`, then the SDK's
`scripts/setup-android-sdk.sh` with `ANDROID_NDK_HOME` set to NDK r29), then:

    scripts/build-core.sh release
    ./gradlew assembleDebug

`MOONLIGHT_PAINTCORE_PATH=<paint-by-number checkout>` builds against a local PaintCore.
