# Paint by Moonlight for Android

Your photos, painted by numbers: the Android app of Paint by Moonlight.

The pipeline that turns a photo into a painting is the iOS app's own, PaintCore from
[paint-by-number](https://github.com/pbordjadze/paint-by-number), compiled for Android with
Swift's Android SDK, so a photo makes the same template on both. Everything a painter touches is
native Jetpack Compose (Material 3 Expressive).

- `core/`: the Swift package the app loads as `libMoonlightCore.so`: PaintCore (pinned by
  revision in `core/Package.swift`), a C ABI (`MoonlightCore/CABI.swift`), the JNI glue
  (`CMoonlightJNI`) and `moonlight-probe`, which makes a painting from a PPM photo on the command
  line and prints its hash. `MoonlightCore/Exchange.swift` reads and writes the iOS app's
  `.moonlight` file (a painting sent to someone else to paint) with PaintCore's `SharedPainting`
  and LZFSE (`CLZFSE`, Apple's reference implementation, BSD-3-Clause);
  `moonlight-probe --shared <file>…` reads such files, sends them again and reads damaged copies.
- `app/`: the app. `core/` (Kotlin) is the bridge (`Core`, `Scene`) and what the app finds in a
  photo before the core paints it (`Findings`: the coloring book's two line models, run with
  LiteRT by `EdgeModels`, and ML Kit's faces, subjects and text), `data/` the library on disk,
  `ui/` the screens, `print/` One Big Sheet (the template printed full size through the iOS
  app's print relay, `feedback-relay/print.js` in paint-by-number, paid with Google Pay),
  `unlock/` Unlimited Paintings (five free paintings, one back each day; one Google Play purchase,
  the product `unlimited_paintings`, lifts the limit). Debug builds, their tests and CI buy from
  `FakeUnlockStore` (`src/debug/`), never from Play; release builds from `PlayUnlockStore`.
- `app/src/main/assets/models/`: the line models (HED and Informative Drawings, the iOS app's),
  converted by `tools/models/convert.py` (its docstring is their provenance; credits and
  licenses in `ACKNOWLEDGEMENTS.md`). `tools/edges.py` makes a photo's two maps on the host as
  the app does, for `moonlight-probe <photo.ppm> --contours … --drawing …`, the coloring book.
- `scripts/build-core.sh` builds the core for each ABI into `app/src/main/jniLibs/`;
  `scripts/emulator-tour.sh` runs the probe and the app's tour on an emulator.
- CI (`.github/workflows/android.yml`) builds everything, checks the emulator makes the host's
  template byte for byte, and keeps the tour's screenshots and the APK as the run's artifact.
- `baselineprofile/`: the journey that makes the app's baseline profile and the macrobenchmarks
  that measure it (the Profile workflow). Releasing on Google Play, the upload key, versions and
  sizes: `docs/release.md` (the Release workflow).

## Building

Swift 6.4.3 with its Android SDK (`swift sdk install …`, then the SDK's
`scripts/setup-android-sdk.sh` with `ANDROID_NDK_HOME` set to NDK r29), then:

    scripts/build-core.sh release
    ./gradlew assembleDebug

`MOONLIGHT_PAINTCORE_PATH=<paint-by-number checkout>` builds against a local PaintCore.
