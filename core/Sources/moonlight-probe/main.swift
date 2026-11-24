// moonlight-probe <photo.ppm> [quick|relaxed|detailed] [--out template.pbnt]
//                 [--contours contours.pgm --drawing drawing.pgm] [--mask subjects.pgm] [--svg prefix]
//
// Makes a painting from a PPM photo exactly as the Android app does (Engine.make) and prints
// what it made and the FNV-1a hash of the encoded template. Run on Linux and on an Android
// emulator (adb shell): scripts/emulator-tour.sh compares the two.
//
// With --contours and --drawing (the line models' maps, which tools/edges.py makes on the host
// with the app's own models) the painting is the coloring book the app makes; --mask adds a
// subjects' mask (255 inside), as ML Kit's subject segmentation gives the app. They reach the
// core as the app's findings do, encoded and decoded (Findings). --svg writes the template and
// the finished painting as SVG (PaintCore's SVGExport), prefix-template.svg and
// prefix-painted.svg, for a look.
#if canImport(FoundationEssentials) && !canImport(Darwin)
import FoundationEssentials
#else
import Foundation
#endif
import MoonlightCore
import PaintCore

var args = Array(CommandLine.arguments.dropFirst())
@MainActor func option(_ name: String) -> String? {
    guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
    defer { args.removeSubrange(i...(i + 1)) }
    return args[i + 1]
}
let outPath = option("--out"), contoursPath = option("--contours"), drawingPath = option("--drawing")
let maskPath = option("--mask"), svgPrefix = option("--svg")
func fail(_ message: String) -> Never {
    _ = "moonlight-probe: \(message)\n".withCString { write(2, $0, strlen($0)) }
    exit(2)
}
func gray(_ path: String) -> EdgeMap {
    guard let data = FileManager.default.contents(atPath: path), let image = try? Netpbm.read(data) else {
        fail("cannot read \(path)")
    }
    return EdgeMap(width: image.width, height: image.height, values: stride(from: 0, to: image.pixels.count, by: 4).map { image.pixels[$0] })
}
guard let path = args.first, let data = FileManager.default.contents(atPath: path) else {
    let usage = "usage: moonlight-probe <photo.ppm> [quick|relaxed|detailed] [--out t.pbnt] [--contours c.pgm --drawing d.pgm] [--mask m.pgm] [--svg prefix]\n"
    _ = usage.withCString { write(2, $0, strlen($0)) }
    exit(2)
}
let length = args.count > 1 ? PaintingLength(rawValue: args[1]) ?? .relaxed : .relaxed
let image = try Netpbm.read(data)
var findings = Findings()
if let contoursPath { findings.contours = gray(contoursPath) }
if let drawingPath { findings.drawing = gray(drawingPath) }
if let maskPath {
    let mask = gray(maskPath)
    findings.mask = Findings.Mask(width: mask.width, height: mask.height, values: mask.values)
}
// Through the bytes the app hands over, so the probe reads them as the core does.
let encoded = findings.encoded()
findings = try encoded.withUnsafeBufferPointer { try Findings.decode($0) }
let start = Date()
let made = try Engine.make(image, length: length, findings: findings)
let seconds = Date().timeIntervalSince(start)
let bytes = [UInt8](made.template.encoded())
var hash: UInt64 = 0xcbf2_9ce4_8422_2325
for b in bytes { hash = (hash ^ UInt64(b)) &* 0x100_0000_01b3 }
let scene = Scene.encode(made.template, nicknameSeed: 1, nicknameVersion: ColorNickname.currentVersion)
print("""
    photo \(image.width)x\(image.height), length \(length.rawValue)
    settings colors \(made.settings.colorCount) detail \(made.settings.detail) smoothness \(made.settings.smoothness) lines \(made.settings.lineArt.style.rawValue)
    template \(made.template.width)x\(made.template.height), \(made.template.regions.count) areas, \(made.template.palette.count) paints, \(bytes.count) bytes
    scene \(scene.count) bytes
    valid \(made.template.validate().isValid)
    seconds \((seconds * 100).rounded() / 100)
    hash \(String(hash, radix: 16))
    """)
if let outPath { _ = FileManager.default.createFile(atPath: outPath, contents: Data(bytes)) }
if let svgPrefix {
    for (name, options) in [("template", SVGExport.Options()), ("painted", SVGExport.Options(painted: true, numbers: false))] {
        _ = FileManager.default.createFile(atPath: "\(svgPrefix)-\(name).svg", contents: Data(SVGExport.render(made.template, options: options).utf8))
    }
}
