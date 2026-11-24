# Releasing on Google Play

How a build of Paint by Moonlight for Android gets to Play: what the owner sets up once, what each
release takes, and what the release build does (signing, versions, shrinking, the baseline
profile, size).

## Once, by the owner

These need the owner's accounts, so nothing in the repository can do them.

1. **Play Console.** A developer account (play.google.com/console; a one-time fee and identity
   verification), then *Create app*: name Paint by Moonlight, default language, app (not game),
   free. The package name is `com.pbordjadze.paintbymoonlight` (`applicationId` in
   `app/build.gradle.kts`); Play fixes it with the first upload, for good.
2. **Testing before production.** A personal developer account created since November 2023 has
   to run a closed test with at least 12 testers for 14 days before Play opens production to it.
   Make the closed track early: the clock is the slowest part of the first release.
3. **The upload key.** Play App Signing (the default for new apps) keeps the key the app is
   signed with on phones; you keep only an *upload key*, which Play can reset if it's lost. Make
   it once, on your own machine:

       keytool -genkeypair -v -keystore upload.jks -alias upload \
         -keyalg RSA -keysize 4096 -validity 10000

   Keep `upload.jks` and its two passwords in your password manager. Never commit them.
4. **The repository's secrets** (GitHub › the repository › Settings › Secrets and variables ›
   Actions › New repository secret):

   | Secret | Value |
   | --- | --- |
   | `ANDROID_UPLOAD_KEYSTORE_BASE64` | the output of `base64 -w0 upload.jks` (macOS: `base64 -i upload.jks`) |
   | `ANDROID_UPLOAD_STORE_PASSWORD` | the keystore's password |
   | `ANDROID_UPLOAD_KEY_ALIAS` | `upload` (or the alias you chose) |
   | `ANDROID_UPLOAD_KEY_PASSWORD` | the key's password (the keystore's, if you gave one) |

5. **The store's forms** (Play Console › the app › Policy and programs / Grow): the listing
   (description, screenshots: the tour's are in each CI run's artifact, icon, feature graphic),
   a privacy policy URL, the content rating questionnaire, target audience, ads (none), and Data
   safety. For Data safety: the app sends nothing unless a painter orders One Big Sheet, which
   sends the painting, the name, address and email Google Pay hands over, and a payment token, to
   the print relay, for the order only (`print/`).
6. **Google Pay in production.** One Big Sheet pays through Google Pay, which runs in its test
   environment until the relay says `PRODUCTION` (`GooglePayConfig`). Going live needs a business
   profile in the Google Pay & Wallet Console and Google's review of the integration
   (screenshots of the flow), then the relay's live keys.

## Each release

1. On GitHub › Actions › **Release** › Run workflow, from `main`. It builds the core, then the App
   Bundle signed with the upload key, checks it isn't debug-signed, writes its download size per
   ABI to the run's summary, and keeps `app-release.aab` (with `mapping.txt`) as the run's
   artifact, named `paint-by-moonlight-signed-<sha>`. Without the secrets it still builds, named
   `…-unsigned-…`, for a size check; Play refuses that one.
2. Play Console › the app › Testing › Internal testing (or the closed track) › Create new release
   › upload the `.aab`, add release notes, roll out. Promote the same release to production
   when it has been tried.

**Versions.** `versionName` is `moonlight.versionName` in `gradle.properties`; raise it for each
release. `versionCode` is the number of commits on the commit built (the workflow clones the whole
history), which only grows along `main`, so release from `main`. Play refuses a versionCode it has
already seen: to release again from the same commit, give the workflow a higher one
(`version_code`).

**On your own machine.** `./gradlew :app:bundleRelease` signs with the debug key, so the build
installs for a look; Play refuses it. To sign with the upload key, set
`MOONLIGHT_UPLOAD_STORE_FILE` (the `.jks`'s path), `MOONLIGHT_UPLOAD_STORE_PASSWORD`,
`MOONLIGHT_UPLOAD_KEY_ALIAS` and `MOONLIGHT_UPLOAD_KEY_PASSWORD`. On CI (`CI=true`) a release
without the key is left unsigned, never debug-signed.

## Shrinking

Release builds run R8 in full mode (AGP's default) with resource shrinking. The rules R8 needs
beyond the libraries' own are in `app/proguard-rules.pro`: the JNI glue
(`core/Sources/CMoonlightJNI`) finds `NativeCore`'s native methods and `CoreException(int)` by
name. kotlinx.serialization and Google Pay ship their own rules, which AGP applies
(`app/build/outputs/mapping/release/configuration.txt` lists every rule used). The bundle carries
`mapping.txt`, so Play Console de-obfuscates crash stack traces by itself.

## Baseline profile

A baseline profile lists the classes and methods the app uses at launch and on the painting
screen, so Android compiles them ahead of time when Play installs the app instead of
interpreting them on the first runs; the startup profile also lays them out first in the dex.
`baselineprofile/` holds the journey that makes it (`BaselineProfileGenerator`: launch, the
gallery, Parrots opened, panned, zoomed, an area tapped, back) and the macrobenchmarks that
measure it (`Benchmarks`: cold start to the gallery, and the painting's frames, each without and
with the profile). Both run on the benchmark builds the plugin adds, which are the release build
signed with the debug key; those builds (and only those) also carry `BenchmarkSeed`
(`app/src/benchmarkSeed/`), which paints the journey's two paintings on the device, since a
release build's library can't be written from outside.

To make it again after the launch, the gallery or the painting screen change: Actions ›
**Profile** › Run workflow (it builds the core first, so allow an hour). Its summary has the benchmarks' table; then

    git fetch origin refs/ci-shots/<branch>-profile
    for f in baseline-prof.txt startup-prof.txt; do
      git show FETCH_HEAD:$f > app/src/main/generated/baselineProfiles/$f
    done

and commit them. With a phone or an emulator on adb, `scripts/profile.sh <out>` does the same
locally and leaves the profiles in place. A release build with no profile committed still works;
it starts slower until the phone has compiled it in the background.

## Size

The native core is most of the app: `scripts/build-core.sh` links the Swift runtime and
FoundationEssentials into `libMoonlightCore.so` statically, so each ABI ships two libraries (the
core and the NDK's `libc++_shared.so`) instead of fifteen, and only the parts the core reaches;
the library exports only its JNI entry points (`core/Sources/CMoonlightJNI/exports.map`). Both are
linked for 16 KB pages, which Play requires of apps targeting Android 15 and later.
`scripts/sizes.sh` prints Play's download size per ABI for the release bundle (bundletool's
`get-size`, through `./gradlew :app:bundleSize`) and each ABI's native libraries.

Measured November 24, 2026 (release bundle, before the baseline profile):

| | runtime as 13 `.so` files | runtime linked in |
| --- | ---: | ---: |
| Play download, arm64-v8a | 8.56 MiB | 7.40 MiB |
| Play download, x86_64 | 9.06 MiB | 7.90 MiB |
| native libraries installed, arm64-v8a | 19.8 MiB | 14.9 MiB |
| native libraries installed, x86_64 | 21.0 MiB | 16.1 MiB |
| App Bundle | 18.9 MiB | 16.6 MiB |

The same photo makes the same template either way (`moonlight-probe`'s hash, every painting
length), in the same time. What's left of `libMoonlightCore.so` (x86_64, by symbol) is the Swift
standard library (4.1 MB), FoundationEssentials (2.8 MB), PaintCore (2.3 MB), the other Swift
libraries (1.7 MB) and C++ (1.4 MB). Tried and left out: identical code folding (`--icf=all`,
1% smaller, at the cost of functions that compare as the same), Swift's LTO (SwiftPM's
`-lto=llvm-thin` doesn't build PaintCore), and `-Osize` (PaintCore's optimization is its own
package's `-Ounchecked`, for the speed of making a painting; the core's own code is 9 KB).
