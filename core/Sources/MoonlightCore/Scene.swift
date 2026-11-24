#if canImport(FoundationEssentials) && !canImport(Darwin)
import FoundationEssentials
#else
import Foundation
#endif
import PaintCore

/// What the Android canvas draws, flattened from a template into one little-endian buffer that
/// Kotlin reads with a ByteBuffer (`Scene.kt` is its reader; change both together and bump
/// `version`). Everything is in canvas units, the template's working-image pixels.
///
///     "MLSC", u32 version
///     f32 width, f32 height
///     u32 paints;   per paint:  f32 r, g, b (sRGB, 0…1), u32 name bytes, the UTF-8 nickname
///     u32 regions;  per region: u32 paint index, f32 area, u32 flags (1: a detail area),
///                               f32 minX, minY, maxX, maxY,
///                               u32 loops; per loop: u32 points, then f32 x, y each
///                               (closed, first point not repeated; holes are loops too: fill even-odd)
///     u32 edges;    per edge:   u32 points, then f32 x, y each (every boundary once)
///     u32 labels;   per label:  f32 x, y, font size, u32 region
///     u32 strokes;  per stroke: u32 points, then f32 x, y each (line art drawn inside areas)
public enum Scene {
    public static let version: UInt32 = 1

    public static func encode(_ template: Template, nicknameSeed: UInt64) -> [UInt8] {
        var w = Writer()
        w.bytes(Array("MLSC".utf8))
        w.u32(version)
        w.f32(Float(template.width))
        w.f32(Float(template.height))

        let names = ColorNickname.assign(template.palette, seed: nicknameSeed, version: ColorNickname.currentVersion)
        w.u32(UInt32(template.palette.count))
        for (i, paint) in template.palette.enumerated() {
            let rgb = ColorScience.okLabToEncoded(paint.oklab, space: .sRGB)
            w.f32(rgb.x); w.f32(rgb.y); w.f32(rgb.z)
            let name = Array((i < names.count ? names[i] : "").utf8)
            w.u32(UInt32(name.count))
            w.bytes(name)
        }

        let detail = Set(template.detailRegions)
        w.u32(UInt32(template.regions.count))
        for (i, region) in template.regions.enumerated() {
            w.u32(region.colorIndex)
            w.f32(region.area)
            w.u32(detail.contains(UInt32(i)) ? 1 : 0)
            w.f32(Float(region.bounds.minX)); w.f32(Float(region.bounds.minY))
            w.f32(Float(region.bounds.maxX)); w.f32(Float(region.bounds.maxY))
            let rings = template.rings[Int(region.ringStart)..<Int(region.ringStart + region.ringCount)]
            w.u32(UInt32(rings.count))
            for ring in rings { w.points(template.polygon(of: ring)) }
        }

        w.u32(UInt32(template.edges.count))
        for edge in template.edges { w.points(template.points(of: edge)) }

        w.u32(UInt32(template.labels.count))
        for label in template.labels {
            let region = Int(label.region)
            let paint = region < template.regions.count ? template.regions[region].colorIndex : 0
            let size = LabelSizing.fontSize(
                radius: label.radius, digits: LabelSizing.digitCount(colorIndex: paint),
                detail: detail.contains(label.region))
            w.f32(label.position.x); w.f32(label.position.y); w.f32(size); w.u32(label.region)
        }

        let art = template.lineArt
        let strokes = art?.strokes ?? []
        w.u32(UInt32(strokes.count))
        for stroke in strokes {
            let start = Int(stroke.pointStart)
            w.points(art!.strokePoints[start..<start + Int(stroke.pointCount)])
        }
        return w.buffer
    }
}

struct Writer {
    var buffer: [UInt8] = []

    mutating func bytes(_ b: [UInt8]) { buffer.append(contentsOf: b) }
    mutating func u32(_ v: UInt32) {
        withUnsafeBytes(of: v.littleEndian) { buffer.append(contentsOf: $0) }
    }
    mutating func f32(_ v: Float) { u32(v.bitPattern) }
    mutating func points<C: Collection<SIMD2<Float>>>(_ points: C) {
        u32(UInt32(points.count))
        for p in points { f32(p.x); f32(p.y) }
    }
}
