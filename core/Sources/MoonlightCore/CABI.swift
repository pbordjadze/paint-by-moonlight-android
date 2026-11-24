#if canImport(FoundationEssentials) && !canImport(Darwin)
import FoundationEssentials
#else
import Foundation
#endif
import PaintCore

// The C ABI the JNI glue (CMoonlightJNI) calls. Buffers handed out are malloc'd and freed with
// mc_free; a job is retained until mc_job_free. Statuses: 0 ok, 1 cancelled, 2 failed, 3 bad input,
// 4 a file from a newer app.

private func handOut(_ bytes: [UInt8], _ out: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ count: UnsafeMutablePointer<Int64>) {
    let buffer = malloc(max(bytes.count, 1))!.assumingMemoryBound(to: UInt8.self)
    bytes.withUnsafeBufferPointer { buffer.update(from: $0.baseAddress!, count: bytes.count) }
    out.pointee = buffer
    count.pointee = Int64(bytes.count)
}

@_cdecl("mc_free")
public func mc_free(_ pointer: UnsafeMutableRawPointer?) { free(pointer) }

@_cdecl("mc_pipeline_version")
public func mc_pipeline_version() -> UInt32 { TemplateGenerator.pipelineVersion }

@_cdecl("mc_job_new")
public func mc_job_new() -> UnsafeMutableRawPointer { Unmanaged.passRetained(Job()).toOpaque() }

@_cdecl("mc_job_free")
public func mc_job_free(_ job: UnsafeMutableRawPointer) { Unmanaged<Job>.fromOpaque(job).release() }

@_cdecl("mc_job_cancel")
public func mc_job_cancel(_ job: UnsafeMutableRawPointer) { Unmanaged<Job>.fromOpaque(job).takeUnretainedValue().cancel() }

@_cdecl("mc_job_progress")
public func mc_job_progress(_ job: UnsafeMutableRawPointer) -> Float {
    Unmanaged<Job>.fromOpaque(job).takeUnretainedValue().progress
}

/// Makes a painting from straight (unpremultiplied) RGBA8 sRGB pixels. On success `template`
/// holds the encoded template (the `.pbnt` bytes iOS saves) and `settings` the settings' JSON.
@_cdecl("mc_make")
public func mc_make(
    _ job: UnsafeMutableRawPointer?, _ rgba: UnsafePointer<UInt8>, _ width: Int32, _ height: Int32,
    _ length: Int32,
    _ template: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ templateCount: UnsafeMutablePointer<Int64>,
    _ settings: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ settingsCount: UnsafeMutablePointer<Int64>
) -> Int32 {
    let w = Int(width), h = Int(height)
    guard w > 0, h > 0, w <= 16384, h <= 16384 else { return 3 }
    let lengths: [PaintingLength] = [.quick, .relaxed, .detailed]
    guard length >= 0, Int(length) < lengths.count else { return 3 }
    let image = RGBAImage(width: w, height: h, pixels: Array(UnsafeBufferPointer(start: rgba, count: w * h * 4)))
    let handle = job.map { Unmanaged<Job>.fromOpaque($0).takeUnretainedValue() }
    do {
        let made = try Engine.make(image, length: lengths[Int(length)], job: handle)
        handOut([UInt8](made.template.encoded()), template, templateCount)
        handOut([UInt8](try JSONEncoder().encode(made.settings)), settings, settingsCount)
        return 0
    } catch {
        return handle?.isCancelled == true ? 1 : 2
    }
}

/// The scene the canvas draws (Scene.swift) of an encoded template, its paints named by the
/// vocabulary version the painting recorded.
@_cdecl("mc_scene")
public func mc_scene(
    _ bytes: UnsafePointer<UInt8>, _ count: Int64, _ nicknameSeed: UInt64, _ nicknameVersion: Int32,
    _ out: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ outCount: UnsafeMutablePointer<Int64>
) -> Int32 {
    guard count > 0 else { return 3 }
    do {
        let template = try Template(encoded: Data(bytes: bytes, count: Int(count)))
        handOut(Scene.encode(template, nicknameSeed: nicknameSeed, nicknameVersion: Int(nicknameVersion)), out, outCount)
        return 0
    } catch {
        return 3
    }
}

/// What `mc_shared_read` tells the app about a painting someone sent, beside its template and
/// photo: the title (empty when the sender's was), the settings' JSON to record, and the
/// sender's paint names, the seed as the signed bits Kotlin's Long holds (both or neither).
struct ReceivedInfo: Encodable {
    var title: String
    var settings: String
    var nicknameSeed: Int64?
    var nicknameVersion: Int?
}

/// Reads a `.moonlight` file someone sent (`Exchange.read`, as hostile input). On success
/// `template` is the encoded template, `info` `ReceivedInfo`'s JSON and `photo` the sender's
/// photo's bytes (NULL and 0 when it has none). Statuses: 3 damaged or not a painting, 4 sent
/// from a newer app.
@_cdecl("mc_shared_read")
public func mc_shared_read(
    _ bytes: UnsafePointer<UInt8>?, _ count: Int64,
    _ template: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ templateCount: UnsafeMutablePointer<Int64>,
    _ info: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ infoCount: UnsafeMutablePointer<Int64>,
    _ photo: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ photoCount: UnsafeMutablePointer<Int64>
) -> Int32 {
    guard let bytes, count > 0 else { return 3 }
    do {
        let received = try Exchange.read(Array(UnsafeBufferPointer(start: bytes, count: Int(count))))
        let settings = try JSONEncoder().encode(received.settings)
        let names = received.shared.nicknames
        let about = ReceivedInfo(
            title: received.shared.title, settings: String(decoding: settings, as: UTF8.self),
            nicknameSeed: names.map { Int64(bitPattern: $0.seed) }, nicknameVersion: names?.version)
        let aboutJSON = [UInt8](try JSONEncoder().encode(about))
        handOut(received.template, template, templateCount)
        handOut(aboutJSON, info, infoCount)
        if let picture = received.shared.photo {
            handOut([UInt8](picture), photo, photoCount)
        } else {
            photo.pointee = nil
            photoCount.pointee = 0
        }
        return 0
    } catch Exchange.ReadError.needsNewerApp {
        return 4
    } catch {
        return 3
    }
}

/// Writes a painting as a `.moonlight` file to send (`Exchange.write`): its encoded template,
/// title (UTF-8), settings' JSON and photo (either may be NULL with count 0), and the seed and
/// vocabulary version its paints are named by. Status 3 when the template doesn't decode.
@_cdecl("mc_shared_write")
public func mc_shared_write(
    _ template: UnsafePointer<UInt8>?, _ templateCount: Int64,
    _ title: UnsafePointer<UInt8>?, _ titleCount: Int64,
    _ settings: UnsafePointer<UInt8>?, _ settingsCount: Int64,
    _ photo: UnsafePointer<UInt8>?, _ photoCount: Int64,
    _ nicknameSeed: UInt64, _ nicknameVersion: Int32,
    _ out: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ outCount: UnsafeMutablePointer<Int64>
) -> Int32 {
    func bytes(_ p: UnsafePointer<UInt8>?, _ n: Int64) -> [UInt8]? {
        guard let p, n > 0 else { return nil }
        return Array(UnsafeBufferPointer(start: p, count: Int(n)))
    }
    guard let templateBytes = bytes(template, templateCount) else { return 3 }
    do {
        let file = try Exchange.write(
            template: templateBytes, title: String(decoding: bytes(title, titleCount) ?? [], as: UTF8.self),
            settingsJSON: bytes(settings, settingsCount), photo: bytes(photo, photoCount),
            nicknames: SharedPainting.Nicknames(seed: nicknameSeed, version: Int(nicknameVersion)))
        handOut(file, out, outCount)
        return 0
    } catch {
        return 3
    }
}
