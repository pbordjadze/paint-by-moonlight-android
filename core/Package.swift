// swift-tools-version: 6.2
import PackageDescription

// The Android app's native core: Paint by Moonlight's own pipeline (PaintCore, from
// pbordjadze/paint-by-number, pinned by revision so a photo makes the same template here as on
// iOS), wrapped in a small C ABI and a JNI glue the Kotlin app calls (MoonlightCore.kt).
// Built for each Android ABI by scripts/build-core.sh with Swift's Android SDK.
// Pinned by revision: PaintCore's optimization flags are unsafeFlags, which SwiftPM allows only
// for a dependency pinned to a revision or branch. MOONLIGHT_PAINTCORE_PATH builds against a local
// checkout instead (a change to the core tried on Android before it lands).
let paintCore: Package.Dependency = Context.environment["MOONLIGHT_PAINTCORE_PATH"].map { .package(path: $0) }
    ?? .package(url: "https://github.com/pbordjadze/paint-by-number.git", revision: "b69c4bb4fe0c64eb4d2624d37ca4a848fc5bb051")

let package = Package(
    name: "MoonlightCore",
    products: [
        .library(name: "MoonlightCore", type: .dynamic, targets: ["MoonlightCore", "CMoonlightJNI"]),
        .executable(name: "moonlight-probe", targets: ["moonlight-probe"]),
    ],
    dependencies: [paintCore],
    targets: [
        .target(
            name: "MoonlightCore",
            dependencies: [.product(name: "PaintCore", package: "paint-by-number"), "CxxHashMemory"],
            swiftSettings: [.unsafeFlags(["-O"], .when(configuration: .debug))]
        ),
        // JNI entry points, in C: they copy Java arrays in and out and call MoonlightCore's C ABI.
        // They're all the library exports (exports.map), which lets the linker drop what they never reach.
        .target(
            name: "CMoonlightJNI",
            exclude: ["exports.map"],
            linkerSettings: [.unsafeFlags(["-Xlinker", "--version-script=\(Context.packageDirectory)/Sources/CMoonlightJNI/exports.map"])]
        ),
        // A libc++ function the Android runtime needs and the NDK lacks (hash_memory.c).
        .target(name: "CxxHashMemory"),
        // Runs the same generation as the app on a photo from the command line, on a device or an
        // emulator (adb shell), and prints the template's hash: the proof that Android makes the
        // same template as Linux and iOS.
        .executableTarget(name: "moonlight-probe", dependencies: ["MoonlightCore"]),
    ],
    swiftLanguageModes: [.v6]
)
