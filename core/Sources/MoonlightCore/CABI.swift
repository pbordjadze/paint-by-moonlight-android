#if canImport(FoundationEssentials) && !canImport(Darwin)
import FoundationEssentials
#else
import Foundation
#endif
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

/// Makes a painting from straight (unpremultiplied) RGBA8 sRGB pixels and what the app found
/// in them (`findings`, `findingsCount` bytes: `Findings.decode`; none when the count is 0). On
/// success `template` holds the encoded template (the `.pbnt` bytes iOS saves) and `settings`
/// the settings' JSON.
@_cdecl("mc_make")
public func mc_make(
    _ job: UnsafeMutableRawPointer?, _ rgba: UnsafePointer<UInt8>, _ width: Int32, _ height: Int32,
    _ length: Int32, _ findings: UnsafePointer<UInt8>?, _ findingsCount: Int64,
    _ template: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ templateCount: UnsafeMutablePointer<Int64>,
    _ settings: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ settingsCount: UnsafeMutablePointer<Int64>
) -> Int32 {
    let w = Int(width), h = Int(height)
    guard w > 0, h > 0, w <= 16384, h <= 16384 else { return 3 }
    let lengths: [PaintingLength] = [.quick, .relaxed, .detailed]
    guard length >= 0, Int(length) < lengths.count, findingsCount >= 0 else { return 3 }
    let found: Findings
    if let findings, findingsCount > 0 {
        guard let decoded = try? Findings.decode(UnsafeBufferPointer(start: findings, count: Int(findingsCount))) else { return 3 }
        found = decoded
    } else {
        found = Findings()
    }
    let image = RGBAImage(width: w, height: h, pixels: Array(UnsafeBufferPointer(start: rgba, count: w * h * 4)))
    let handle = job.map { Unmanaged<Job>.fromOpaque($0).takeUnretainedValue() }
    do {
        let made = try Engine.make(image, length: lengths[Int(length)], findings: found, job: handle)
        handOut([UInt8](made.template.encoded()), template, templateCount)
        handOut([UInt8](try JSONEncoder().encode(made.settings)), settings, settingsCount)
        return 0
    } catch {
        return handle?.isCancelled == true ? 1 : 2
    }
}

/// A line model's input for a photo (straight RGBA8 sRGB): [1, H, W, 3] float32 into `out`
/// (`outCount` floats), and in `size` the map's width and height, then the padded width and
/// height the model runs at (EdgeModels.input). `model`: 0 HED, 1 the line drawing.
@_cdecl("mc_model_input")
public func mc_model_input(
    _ rgba: UnsafePointer<UInt8>, _ width: Int32, _ height: Int32, _ model: Int32,
    _ out: UnsafeMutablePointer<UnsafeMutablePointer<Float>?>, _ outCount: UnsafeMutablePointer<Int64>,
    _ size: UnsafeMutablePointer<Int32>
) -> Int32 {
    let w = Int(width), h = Int(height)
    guard w > 0, h > 0, w <= 16384, h <= 16384, let which = EdgeModels.Model(rawValue: model) else { return 3 }
    let image = RGBAImage(width: w, height: h, pixels: Array(UnsafeBufferPointer(start: rgba, count: w * h * 4)))
    let input = EdgeModels.input(which, for: image)
    let buffer = malloc(max(input.values.count, 1) * MemoryLayout<Float>.stride)!.assumingMemoryBound(to: Float.self)
    input.values.withUnsafeBufferPointer { buffer.update(from: $0.baseAddress!, count: input.values.count) }
    out.pointee = buffer
    outCount.pointee = Int64(input.values.count)
    for (i, v) in [input.width, input.height, input.paddedWidth, input.paddedHeight].enumerated() { size[i] = Int32(v) }
    return 0
}

/// A line model's map from its outputs: `count` outputs, output i being `shapes[2i + 1]` wide and
/// `shapes[2i]` high, its floats at `outputs[i]`; `size` is what `mc_model_input` gave. `out`
/// gets the map, a byte per pixel (EdgeModels.map).
@_cdecl("mc_model_map")
public func mc_model_map(
    _ model: Int32, _ outputs: UnsafePointer<UnsafePointer<Float>?>, _ shapes: UnsafePointer<Int32>, _ count: Int32,
    _ size: UnsafePointer<Int32>,
    _ out: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>, _ outCount: UnsafeMutablePointer<Int64>
) -> Int32 {
    guard let which = EdgeModels.Model(rawValue: model), count > 0, count <= 16 else { return 3 }
    var list: [EdgeModels.Output] = []
    for i in 0..<Int(count) {
        let h = Int(shapes[2 * i]), w = Int(shapes[2 * i + 1])
        guard h > 0, w > 0, h <= 16384, w <= 16384, let values = outputs[i] else { return 3 }
        list.append(EdgeModels.Output(values: Array(UnsafeBufferPointer(start: values, count: w * h)), width: w, height: h))
    }
    do {
        let map = try EdgeModels.map(
            which, outputs: list, width: Int(size[0]), height: Int(size[1]), paddedWidth: Int(size[2]), paddedHeight: Int(size[3]))
        handOut(map.values, out, outCount)
        return 0
    } catch {
        return 3
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
