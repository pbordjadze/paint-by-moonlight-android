// moonlight-probe <photo.ppm> [quick|relaxed|detailed] [--out template.pbnt]
// moonlight-probe --shared <painting.moonlight>…
//
// Makes a painting from a PPM photo exactly as the Android app does (Engine.make) and prints
// what it made and the FNV-1a hash of the encoded template. Run on Linux and on an Android
// emulator (adb shell): scripts/emulator-tour.sh compares the two.
//
// With --shared, reads each `.moonlight` file as the app opens one someone sent
// (Exchange.read), prints what it holds, writes it back as the app sends one (Exchange.write,
// LZFSE) and reads that again, failing unless the painting came through whole, then reads
// damaged copies of it (cut short, bytes changed), which must each be refused, never crash:
// scripts/check-exchange.sh runs it on the iOS app's frozen fixtures.
#if canImport(FoundationEssentials) && !canImport(Darwin)
import FoundationEssentials
#else
import Foundation
#endif
import MoonlightCore
import PaintCore

var args = Array(CommandLine.arguments.dropFirst())
if args.first == "--shared" {
    exit(sharedProbe(Array(args.dropFirst())))
}
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
let scene = Scene.encode(made.template, nicknameSeed: 1, nicknameVersion: ColorNickname.currentVersion)
print("""
    photo \(image.width)x\(image.height), length \(length.rawValue)
    settings colors \(made.settings.colorCount) detail \(made.settings.detail) smoothness \(made.settings.smoothness)
    template \(made.template.width)x\(made.template.height), \(made.template.regions.count) areas, \(made.template.palette.count) paints, \(bytes.count) bytes
    scene \(scene.count) bytes
    valid \(made.template.validate().isValid)
    seconds \((seconds * 100).rounded() / 100)
    hash \(fnv1a(bytes))
    """)
if let outPath { _ = FileManager.default.createFile(atPath: outPath, contents: Data(bytes)) }

func fnv1a(_ bytes: some Sequence<UInt8>) -> String {
    var hash: UInt64 = 0xcbf2_9ce4_8422_2325
    for b in bytes { hash = (hash ^ UInt64(b)) &* 0x100_0000_01b3 }
    return String(hash, radix: 16)
}

func sharedProbe(_ paths: [String]) -> Int32 {
    var failed = false
    for path in paths {
        guard let data = FileManager.default.contents(atPath: path) else {
            print("\(path): unreadable")
            failed = true
            continue
        }
        do {
            let received = try Exchange.read(data)
            let shared = received.shared
            let names = shared.nicknames.map { "seed \($0.seed) version \($0.version)" } ?? "none"
            print("""
                \(path)
                  title "\(shared.title)", settings \(shared.settings == nil ? "none" : "sent"), names \(names)
                  photo \(shared.photo?.count ?? 0) bytes
                  template \(shared.template.width)x\(shared.template.height), \(shared.template.regions.count) areas, \
                \(shared.template.palette.count) paints, \(received.template.count) bytes, hash \(fnv1a(received.template))
                  valid \(shared.template.validate().isValid)
                """)
            let settings = try shared.settings.map { try JSONEncoder().encode($0) }
            let sent = try Exchange.write(
                template: received.template, title: shared.title, settingsJSON: settings, photo: shared.photo,
                nicknames: shared.nicknames)
            let back = try Exchange.read(sent)
            let sentBytes = [UInt8](sent)
            let whole = back.template == received.template && back.shared.title == shared.title
                && back.shared.settings == shared.settings && back.shared.photo == shared.photo && back.shared.nicknames == shared.nicknames
            print("  sent again: \(sent.count) bytes, \(templateCompressed(sentBytes) ? "lzfse" : "uncompressed"), \(whole ? "read back whole" : "CHANGED")")
            if !whole { failed = true }
            let damaged = damagedCopiesRead(sentBytes)
            print("  \(damaged.read) damaged copies read, \(damaged.refused) refused, none crashed")
        } catch {
            print("\(path): \(error)")
            failed = true
        }
    }
    return failed ? 1 : 0
}

/// Whether a `.moonlight` file's TMPL chunk is compressed (its method, after the chunk's header).
func templateCompressed(_ file: [UInt8]) -> Bool {
    func u32(_ at: Int) -> UInt32 { (0..<4).reduce(0) { $0 | UInt32(file[at + $1]) << (8 * UInt32($1)) } }
    var at = 12
    for _ in 0..<u32(8) {
        if file[at..<at + 4].elementsEqual("TMPL".utf8) { return u32(at + 12) != 0 }
        at += 12 + Int(u32(at + 8))
    }
    return false
}

/// Reads copies of `file` cut short at every length, which must each be refused, and with a
/// byte changed throughout (LZFSE's stream included), which must never crash: a copy may still
/// open (a changed title or paint; the format carries no checksum), and every template that
/// opens has been checked by `Template(encoded:)`. Returns how many were read and refused.
func damagedCopiesRead(_ file: [UInt8]) -> (read: Int, refused: Int) {
    var read = 0, refused = 0
    for length in stride(from: 0, to: file.count, by: max(1, file.count / 400)) {
        precondition((try? Exchange.read(Data(file[..<length]))) == nil, "a file cut at \(length) opened")
        read += 1
        refused += 1
    }
    var state: UInt64 = 0x9E37_79B9_7F4A_7C15
    for _ in 0..<2000 {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        var copy = file
        copy[Int((state >> 33) % UInt64(file.count))] ^= UInt8(truncatingIfNeeded: state >> 13) | 1
        if (try? Exchange.read(Data(copy))) == nil { refused += 1 }
        read += 1
    }
    return (read, refused)
}
