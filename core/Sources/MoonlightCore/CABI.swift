import Foundation
import PaintCore

// The C ABI the JNI glue (CMoonlightJNI) calls. Buffers handed out are malloc'd and freed with
// mc_free; a job is retained until mc_job_free. Statuses: 0 ok, 1 cancelled, 2 failed, 3 bad input.

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

/// The scene the canvas draws (Scene.swift) of an encoded template.
@_cdecl("mc_scene")
public func mc_scene(
    _ bytes: UnsafePointer<UInt8>, _ count: Int64, _ nicknameSeed: UInt64,
    _ out: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ outCount: UnsafeMutablePointer<Int64>
) -> Int32 {
    guard count > 0 else { return 3 }
    do {
        let template = try Template(encoded: Data(bytes: bytes, count: Int(count)))
        handOut(Scene.encode(template, nicknameSeed: nicknameSeed), out, outCount)
        return 0
    } catch {
        return 3
    }
}
