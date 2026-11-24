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
///     u32 flags     (1: a coloring book: its drawing's lines stay over the paint, color edges unseen)
///     u32 paints;   per paint:  f32 r, g, b (sRGB, 0…1, for pictures), f32 r, g, b (Display P3,
///                               0…1: the template's own paint, as iOS draws it), then three UTF-8
///                               strings (u32 bytes, the bytes): the nickname, the plain color name
///                               ("Dark grayish green") and the hex code ("#3A5F4B")
///     u32 regions;  per region: u32 paint index, f32 area, u32 flags (1: a detail area),
///                               f32 minX, minY, maxX, maxY,
///                               u32 loops; per loop: u32 points, then f32 x, y each
///                               (closed, first point not repeated; holes are loops too: fill even-odd)
///     u32 edges;    per edge:   u32 left region, u32 right region (0xFFFFFFFF: the canvas's
///                               border), u32 drawn (0: a coloring book's color edge, which the
///                               canvas leaves out), u32 points, then f32 x, y each (every boundary once)
///     u32 labels;   per label:  f32 x, y, font size, u32 region, f32 radius (its free circle)
///     u32 strokes;  per stroke: u32 points, then f32 x, y each (the drawing's lines inside areas)
public enum Scene {
    public static let version: UInt32 = 2

    /// `nicknameVersion` is the vocabulary the painting recorded when it was made (its
    /// `meta.json`), so a later vocabulary never renames its paints.
    public static func encode(_ template: Template, nicknameSeed: UInt64, nicknameVersion: Int) -> [UInt8] {
        var w = Writer()
        w.bytes(Array("MLSC".utf8))
        w.u32(version)
        w.f32(Float(template.width))
        w.f32(Float(template.height))

        // The drawing's layers as iOS's canvas reads them (`DrawableLineArt`): line art that
        // doesn't match the edges draws as classic.
        let art = template.lineArt.flatMap { $0.edgeLayers.count == template.edges.count ? $0 : nil }
        w.u32(art == nil ? 0 : 1)

        let names = ColorNickname.assign(template.palette, seed: nicknameSeed, version: nicknameVersion)
        w.u32(UInt32(template.palette.count))
        for (i, paint) in template.palette.enumerated() {
            let rgb = ColorScience.okLabToEncoded(paint.oklab, space: .sRGB)
            w.f32(rgb.x); w.f32(rgb.y); w.f32(rgb.z)
            let p3 = displayP3(paint, space: template.colorSpace)
            w.f32(p3.x); w.f32(p3.y); w.f32(p3.z)
            w.string(i < names.count ? names[i] : "")
            w.string(paint.colorName.englishTitle)
            w.string("#" + paint.hexDigits.uppercased())
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
        for (e, edge) in template.edges.enumerated() {
            w.u32(edge.left)
            w.u32(edge.right)
            w.u32(art.map { LineLayer.isDrawn($0.edgeLayers[e]) ? 1 : 0 } ?? 1)
            w.points(template.points(of: edge))
        }

        w.u32(UInt32(template.labels.count))
        for label in template.labels {
            let region = Int(label.region)
            let paint = region < template.regions.count ? template.regions[region].colorIndex : 0
            let size = LabelSizing.fontSize(
                radius: label.radius, digits: LabelSizing.digitCount(colorIndex: paint),
                detail: detail.contains(label.region))
            w.f32(label.position.x); w.f32(label.position.y); w.f32(size); w.u32(label.region)
            w.f32(label.radius)
        }

        let points = art?.strokePoints.count ?? 0
        let strokes = (art?.strokes ?? []).filter {
            $0.pointCount >= 2 && Int($0.pointStart) + Int($0.pointCount) <= points && LineLayer.isDrawn($0.layer)
        }
        w.u32(UInt32(strokes.count))
        for stroke in strokes {
            let start = Int(stroke.pointStart)
            w.points(art!.strokePoints[start..<start + Int(stroke.pointCount)])
        }
        return w.buffer
    }

    /// A paint as encoded Display P3: the template's own color, converted only when the template
    /// is sRGB (whose colors P3 holds exactly).
    static func displayP3(_ paint: PaletteColor, space: RGBColorSpace) -> SIMD3<Float> {
        guard space == .sRGB else { return paint.rgb }
        let rgb = paint.rgb
        let linear = ColorScience.sRGBToP3Linear(SIMD3(
            ColorScience.decodeSRGB(rgb.x), ColorScience.decodeSRGB(rgb.y), ColorScience.decodeSRGB(rgb.z)))
        let c = linear.clamped(lowerBound: .zero, upperBound: SIMD3(repeating: 1))
        return SIMD3(ColorScience.encodeSRGB(c.x), ColorScience.encodeSRGB(c.y), ColorScience.encodeSRGB(c.z))
    }
}

struct Writer {
    var buffer: [UInt8] = []

    mutating func bytes(_ b: [UInt8]) { buffer.append(contentsOf: b) }
    mutating func u32(_ v: UInt32) {
        withUnsafeBytes(of: v.littleEndian) { buffer.append(contentsOf: $0) }
    }
    mutating func f32(_ v: Float) { u32(v.bitPattern) }
    mutating func string(_ s: String) {
        let utf8 = Array(s.utf8)
        u32(UInt32(utf8.count))
        bytes(utf8)
    }
    mutating func points<C: Collection<SIMD2<Float>>>(_ points: C) {
        u32(UInt32(points.count))
        for p in points { f32(p.x); f32(p.y) }
    }
}
