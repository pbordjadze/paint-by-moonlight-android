// moonlight-probe <photo.ppm> [quick|relaxed|detailed] [--out template.pbnt]
//
// Makes a painting from a PPM photo exactly as the Android app does (Engine.make) and prints
// what it made and the FNV-1a hash of the encoded template. Run on Linux and on an Android
// emulator (adb shell): scripts/emulator-tour.sh compares the two.
#if canImport(FoundationEssentials) && !canImport(Darwin)
import FoundationEssentials
#else
import Foundation
#endif
import MoonlightCore
import PaintCore

var args = Array(CommandLine.arguments.dropFirst())
var outPath: String?
if let i = args.firstIndex(of: "--out"), i + 1 < args.count {
    outPath = args[i + 1]
    args.removeSubrange(i...(i + 1))
}
guard let path = args.first, let data = FileManager.default.contents(atPath: path) else {
    let usage = "usage: moonlight-probe <photo.ppm> [quick|relaxed|detailed] [--out t.pbnt]\n"
    _ = usage.withCString { write(2, $0, strlen($0)) }
    exit(2)
}
let length = args.count > 1 ? PaintingLength(rawValue: args[1]) ?? .relaxed : .relaxed
let image = try Netpbm.read(data)
let start = Date()
let made = try Engine.make(image, length: length)
let seconds = Date().timeIntervalSince(start)
let bytes = [UInt8](made.template.encoded())
var hash: UInt64 = 0xcbf2_9ce4_8422_2325
for b in bytes { hash = (hash ^ UInt64(b)) &* 0x100_0000_01b3 }
let scene = Scene.encode(made.template, nicknameSeed: 1, nicknameVersion: ColorNickname.currentVersion)
print("""
    photo \(image.width)x\(image.height), length \(length.rawValue)
    settings colors \(made.settings.colorCount) detail \(made.settings.detail) smoothness \(made.settings.smoothness)
    template \(made.template.width)x\(made.template.height), \(made.template.regions.count) areas, \(made.template.palette.count) paints, \(bytes.count) bytes
    scene \(scene.count) bytes
    valid \(made.template.validate().isValid)
    seconds \((seconds * 100).rounded() / 100)
    hash \(String(hash, radix: 16))
    """)
if let outPath { _ = FileManager.default.createFile(atPath: outPath, contents: Data(bytes)) }
