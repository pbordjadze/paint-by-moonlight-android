#if canImport(FoundationEssentials) && !canImport(Darwin)
import FoundationEssentials
#else
import Foundation
#endif
import PaintCore

/// What the app found in a photo before the pipeline runs, as iOS finds it with Vision and its
/// Core ML models: the coloring book's two maps (EdgeModels), the subjects' mask (ML Kit's
/// subject segmentation), the faces with their eyes' contours (ML Kit's face detection) and
/// the lines of text (ML Kit's text recognition). Each is optional: what is missing is left out,
/// as iOS leaves out what Vision can't find, and without both maps a painting is classic.
///
/// From them follow the inputs iOS gives PaintCore, made the way its app makes them: the
/// importance map and the subject hints (`SubjectImportance`, without Vision's saliency, which
/// has no counterpart here), the eyes (`EyeFinder`), the subjects' silhouettes (`ObjectFinder`)
/// and the writing (`TextFinder`).
///
/// The app hands them over as one buffer (`decode`), read as hostile input: "MLIN", a version
/// (UInt32), then records of a tag (four ASCII bytes), a length (UInt32) and that many bytes,
/// all little-endian, unknown tags skipped. DRAW and CONT: the drawing and the contours, width
/// and height (UInt32) then a byte per pixel. MASK: the subjects' mask the same way, 255 = a
/// subject. FACE: the face's box (x, y, width, height, Float32, normalized to the photo with the
/// origin top-left), then the left eye's and the right eye's contour, each a point count
/// (UInt32) and that many x, y pairs (Float32, normalized). TEXT: a line's quadrilateral, four
/// x, y pairs (Float32, normalized, clockwise from the top left).
public struct Findings: Sendable, Equatable {
    public struct Mask: Sendable, Equatable {
        public var width: Int, height: Int
        public var values: [UInt8]

        public init(width: Int, height: Int, values: [UInt8]) {
            self.width = width
            self.height = height
            self.values = values
        }
    }

    public struct Face: Sendable, Equatable {
        /// Normalized to the photo, origin top-left.
        public var box: (x: Float, y: Float, width: Float, height: Float)
        public var leftEye: [SIMD2<Float>]
        public var rightEye: [SIMD2<Float>]

        public init(box: (x: Float, y: Float, width: Float, height: Float), leftEye: [SIMD2<Float>], rightEye: [SIMD2<Float>]) {
            self.box = box
            self.leftEye = leftEye
            self.rightEye = rightEye
        }

        public static func == (a: Face, b: Face) -> Bool {
            a.box == b.box && a.leftEye == b.leftEye && a.rightEye == b.rightEye
        }
    }

    public var drawing: EdgeMap?
    public var contours: EdgeMap?
    public var mask: Mask?
    public var faces: [Face] = []
    public var text: [[SIMD2<Float>]] = []

    public init(drawing: EdgeMap? = nil, contours: EdgeMap? = nil, mask: Mask? = nil, faces: [Face] = [], text: [[SIMD2<Float>]] = []) {
        self.drawing = drawing
        self.contours = contours
        self.mask = mask
        self.faces = faces
        self.text = text
    }

    public static let version: UInt32 = 1
    /// No map, mask or list is larger: a photo is at most 16384 px on a side.
    static let maximumCount = 1 << 28

    public enum DecodingError: Error { case malformed }

    /// The findings in `bytes` (see the type's documentation).
    public static func decode(_ bytes: UnsafeBufferPointer<UInt8>) throws -> Findings {
        var reader = Reader(bytes: bytes)
        guard try reader.tag() == "MLIN", try reader.uint32() >= 1 else { throw DecodingError.malformed }
        var found = Findings()
        while !reader.isAtEnd {
            let tag = try reader.tag()
            let length = Int(try reader.uint32())
            var record = try reader.sub(length)
            switch tag {
            case "DRAW": found.drawing = try record.map()
            case "CONT": found.contours = try record.map()
            case "MASK":
                let map = try record.map()
                found.mask = Mask(width: map.width, height: map.height, values: map.values)
            case "FACE":
                let box = (x: try record.float(), y: try record.float(), width: try record.float(), height: try record.float())
                guard [box.x, box.y, box.width, box.height].allSatisfy(\.isFinite) else { throw DecodingError.malformed }
                found.faces.append(Face(box: box, leftEye: try record.points(), rightEye: try record.points()))
            case "TEXT":
                var quad: [SIMD2<Float>] = []
                for _ in 0..<4 { quad.append(try record.point()) }
                found.text.append(quad)
            default:
                break
            }
        }
        return found
    }

    /// The bytes `decode` reads: what the probe hands the core, as the app does.
    public func encoded() -> [UInt8] {
        var out: [UInt8] = []
        func u32(_ v: Int) { withUnsafeBytes(of: UInt32(v).littleEndian) { out += $0 } }
        func f32(_ v: Float) { withUnsafeBytes(of: v.bitPattern.littleEndian) { out += $0 } }
        func record(_ tag: String, _ body: [UInt8]) {
            out += Array(tag.utf8)
            u32(body.count)
            out += body
        }
        func map(_ w: Int, _ h: Int, _ values: [UInt8]) -> [UInt8] {
            var body: [UInt8] = []
            withUnsafeBytes(of: UInt32(w).littleEndian) { body += $0 }
            withUnsafeBytes(of: UInt32(h).littleEndian) { body += $0 }
            return body + values
        }
        out += Array("MLIN".utf8)
        u32(Int(Self.version))
        if let drawing { record("DRAW", map(drawing.width, drawing.height, drawing.values)) }
        if let contours { record("CONT", map(contours.width, contours.height, contours.values)) }
        if let mask { record("MASK", map(mask.width, mask.height, mask.values)) }
        for face in faces {
            let start = out.count
            for v in [face.box.x, face.box.y, face.box.width, face.box.height] { f32(v) }
            for eye in [face.leftEye, face.rightEye] {
                u32(eye.count)
                for p in eye { f32(p.x); f32(p.y) }
            }
            let body = Array(out[start...])
            out.removeSubrange(start...)
            record("FACE", body)
        }
        for quad in text {
            let start = out.count
            for p in quad { f32(p.x); f32(p.y) }
            let body = Array(out[start...])
            out.removeSubrange(start...)
            record("TEXT", body)
        }
        return out
    }

    struct Reader {
        let bytes: UnsafeBufferPointer<UInt8>
        var offset = 0
        var end: Int

        init(bytes: UnsafeBufferPointer<UInt8>, offset: Int = 0, end: Int? = nil) {
            self.bytes = bytes
            self.offset = offset
            self.end = end ?? bytes.count
        }

        var isAtEnd: Bool { offset >= end }

        mutating func take(_ count: Int) throws -> Int {
            guard count >= 0, count <= end - offset else { throw DecodingError.malformed }
            defer { offset += count }
            return offset
        }

        mutating func uint32() throws -> UInt32 {
            let at = try take(4)
            return (0..<4).reduce(UInt32(0)) { $0 | UInt32(bytes[at + $1]) << (8 * $1) }
        }

        mutating func float() throws -> Float { Float(bitPattern: try uint32()) }

        mutating func tag() throws -> String {
            let at = try take(4)
            return String(decoding: bytes[at..<at + 4], as: UTF8.self)
        }

        mutating func sub(_ count: Int) throws -> Reader {
            let at = try take(count)
            return Reader(bytes: bytes, offset: at, end: at + count)
        }

        mutating func map() throws -> EdgeMap {
            let w = Int(try uint32()), h = Int(try uint32())
            guard w > 0, h > 0, w <= 16384, h <= 16384, w * h <= maximumCount else { throw DecodingError.malformed }
            let at = try take(w * h)
            return EdgeMap(width: w, height: h, values: Array(bytes[at..<at + w * h]))
        }

        mutating func point() throws -> SIMD2<Float> {
            let p = SIMD2(try float(), try float())
            guard p.x.isFinite, p.y.isFinite else { throw DecodingError.malformed }
            return p
        }

        mutating func points() throws -> [SIMD2<Float>] {
            let count = Int(try uint32())
            guard count <= (end - offset) / 8 else { throw DecodingError.malformed }
            return try (0..<count).map { _ in try point() }
        }
    }
}

// MARK: What PaintCore is given

extension Findings {
    /// The coloring book's input for a `width` × `height` photo, as iOS makes it: the drawing
    /// resampled to the contours' size and laid over them, the contours deciding the outlines,
    /// with the eyes, the subjects and the writing. Nil without both maps: a classic painting.
    public func lineArt(width: Int, height: Int) -> LineArtInput? {
        guard let drawing, let contours else { return nil }
        return LineArtInput(
            drawing: drawing.resampled(width: contours.width, height: contours.height), contours: contours,
            detector: .drawingAndContours, eyes: eyes(width: width, height: height), objects: objects, writing: writing)
    }

    /// The faces as Suggested settings read them (iOS's `SubjectImportance.hints`): clipped to
    /// the photo, quantized to hundredths and sorted, so small differences between runs don't
    /// move a suggestion. ML Kit finds no animals.
    public var hints: SubjectHints {
        func hundredths(_ v: Float) -> Float { (v * 100).rounded() / 100 }
        let rects = faces.compactMap { face -> NormalizedRect? in
            let x0 = max(face.box.x, 0), y0 = max(face.box.y, 0)
            let x1 = min(face.box.x + face.box.width, 1), y1 = min(face.box.y + face.box.height, 1)
            guard x1 > x0, y1 > y0 else { return nil }
            let rect = NormalizedRect(x: hundredths(x0), y: hundredths(y0), width: hundredths(x1 - x0), height: hundredths(y1 - y0))
            return rect.width > 0 && rect.height > 0 ? rect : nil
        }
        return SubjectHints(faces: rects.sorted { ($0.y, $0.x, $0.width, $0.height) < ($1.y, $1.x, $1.width, $1.height) })
    }

    /// The importance map of a `width` × `height` photo (iOS's `SubjectImportance.analysis`):
    /// 0.25, the subjects at 0.75, faces near 1, at 256 px on the long side. Nil when nothing was
    /// found: PaintCore then makes its own.
    public func importance(width: Int, height: Int) -> Grid<Float>? {
        let mask = subjects
        guard mask != nil || !faces.isEmpty else { return nil }
        let aspect = Double(width) / Double(height)
        let w = aspect >= 1 ? 256 : max(1, Int(256 * aspect)), h = aspect >= 1 ? max(1, Int(256 / aspect)) : 256
        var map = Grid<Float>(width: w, height: h, repeating: 0.25)
        if let mask {
            let sampled = Self.boxSampled(mask, width: w, height: h)
            for i in 0..<map.count { map.storage[i] = max(map.storage[i], 0.75 * sampled[i]) }
        }
        for face in faces {
            // The box grows to include hair, chin and ears.
            let bx = Double(face.box.x), by = Double(face.box.y), bw = Double(face.box.width), bh = Double(face.box.height)
            let cx = (bx + bw / 2) * Double(w), cy = (by + bh / 2) * Double(h)
            let rx = max(1, bw * 1.5 * Double(w) / 2), ry = max(1, bh * 1.6 * Double(h) / 2)
            for y in 0..<h {
                for x in 0..<w {
                    let dx = (Double(x) + 0.5 - cx) / rx, dy = (Double(y) + 0.5 - cy) / ry
                    let d = dx * dx + dy * dy
                    if d < 1.6 { map[x, y] = max(map[x, y], Float(min(1, 1.25 - 0.5 * d))) }
                }
            }
        }
        return map
    }

    /// The mask when it holds a subject: some pixel at least half confident. ML Kit's segmenter
    /// always answers with a soft mask, where Vision finds a subject or none, so a landscape's
    /// faint confidence must not become a subject.
    var subjects: Mask? {
        guard let mask, mask.values.contains(where: { $0 >= 128 }) else { return nil }
        return mask
    }

    /// The mask box-sampled to `w` × `h`, 0...1. Not stretched to its peak as iOS stretches a
    /// faint saliency map: a subject's mask is confident, and `subjects` leaves out a faint one.
    static func boxSampled(_ mask: Mask, width w: Int, height h: Int) -> [Float] {
        let bw = mask.width, bh = mask.height
        var out = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            let y0 = y * bh / h, y1 = max(y0 + 1, (y + 1) * bh / h)
            for x in 0..<w {
                let x0 = x * bw / w, x1 = max(x0 + 1, (x + 1) * bw / w)
                var sum = 0
                for sy in y0..<y1 {
                    for sx in x0..<x1 { sum += Int(mask.values[sy * bw + sx]) }
                }
                out[y * w + x] = Float(sum) / Float(255 * (y1 - y0) * (x1 - x0))
            }
        }
        return out
    }

    /// The subjects' silhouettes (iOS's `ObjectFinder`): the mask at 384 px on its long side,
    /// each pixel inside when the mean of those it covers is at least half, traced by
    /// `MaskContours`.
    public var objects: [[SIMD2<Float>]] {
        guard let mask = subjects else { return [] }
        let w = mask.width, h = mask.height
        let scale = 384 / Double(max(w, h))
        let ow = max(1, min(w, Int((Double(w) * scale).rounded()))), oh = max(1, min(h, Int((Double(h) * scale).rounded())))
        var sum = [Int](repeating: 0, count: ow * oh), count = [Int](repeating: 0, count: ow * oh)
        for y in 0..<h {
            let oy = min(oh - 1, y * oh / h)
            for x in 0..<w {
                let o = oy * ow + min(ow - 1, x * ow / w)
                sum[o] += Int(mask.values[y * w + x])
                count[o] += 1
            }
        }
        let inside = sum.indices.map { count[$0] > 0 && 2 * sum[$0] >= 255 * count[$0] }
        return MaskContours.outlines(of: inside, width: ow, height: oh)
    }

    /// The lines of text (iOS's `TextFinder.ordered`): quantized to 1/4096 of the photo and
    /// sorted by their top, then their left edge.
    public var writing: [[SIMD2<Float>]] {
        let quantum: Float = 4096
        let lines = text.map { $0.map { SIMD2(($0.x * quantum).rounded() / quantum, ($0.y * quantum).rounded() / quantum) } }
        return lines.sorted { a, b in
            let ay = a.map(\.y).min() ?? 0, by = b.map(\.y).min() ?? 0
            if ay != by { return ay < by }
            return (a.map(\.x).min() ?? 0) < (b.map(\.x).min() ?? 0)
        }
    }

    /// The eyes (iOS's `EyeFinder`, whose constants these are): each eye's contour smoothed
    /// into an almond, and its iris, a circle sized from the eye's width and clipped to the
    /// contour; only the eyes that look like their face's. ML Kit gives no pupil, so an iris
    /// is centered on its contour, as iOS centers one Vision gives no pupil for.
    public func eyes(width: Int, height: Int) -> [[SIMD2<Float>]] {
        let size = SIMD2(Double(width), Double(height))
        let longSide = Double(max(width, height))
        var found: [Eye] = []
        for face in faces {
            func pixels(_ points: [SIMD2<Float>]) -> [SIMD2<Double>] { points.map { SIMD2<Double>($0) * size } }
            let box = (x: Double(face.box.x) * size.x, y: Double(face.box.y) * size.y,
                       width: Double(face.box.width) * size.x, height: Double(face.box.height) * size.y)
            guard let left = Eye(contour: pixels(face.leftEye), longSide: longSide),
                  let right = Eye(contour: pixels(face.rightEye), longSide: longSide),
                  Eye.plausible(left, right, in: box)
            else { continue }
            found += [left, right]
        }
        func centroid(_ points: [SIMD2<Double>]) -> SIMD2<Double> { points.reduce(.zero, +) / Double(points.count) }
        let ordered = found.sorted {
            let a = centroid($0.contour), b = centroid($1.contour)
            return (a.y, a.x) < (b.y, b.x)
        }
        func normalized(_ points: [SIMD2<Double>]) -> [SIMD2<Float>] {
            points.map { p in
                SIMD2(Float(min(max((p.x / size.x * 4096).rounded() / 4096, 0), 1)),
                      Float(min(max((p.y / size.y * 4096).rounded() / 4096, 0), 1)))
            }
        }
        return ordered.map { normalized($0.contour) } + ordered.compactMap { $0.iris.map(normalized) }
    }

    /// One eye in photo pixels (origin top-left).
    struct Eye {
        static let irisRadiusPerEyeWidth = 0.2
        static let minimumEyeWidth = 0.012
        static let minimumVisibleIris = 0.15
        static let faceMargin = 0.1
        static let eyeWidthPerFace: ClosedRange<Double> = 0.1...0.4
        static let separationPerFace: ClosedRange<Double> = 0.25...0.75
        static let minimumSeparationPerEyeWidth = 1.2
        static let contourSubdivisions = 4
        static let irisPoints = 32

        var contour: [SIMD2<Double>]
        var iris: [SIMD2<Double>]?
        var width: Double
        var middle: SIMD2<Double>

        init?(contour points: [SIMD2<Double>], longSide: Double) {
            guard points.count >= 3 else { return nil }
            var span = 0.0
            for a in points {
                for b in points { span = max(span, distance(a, b)) }
            }
            guard span >= Self.minimumEyeWidth * longSide else { return nil }
            width = span
            contour = Self.smoothed(points)
            middle = points.reduce(.zero, +) / Double(points.count)
            let radius = Self.irisRadiusPerEyeWidth * span
            let circle = (0..<Self.irisPoints).map { i -> SIMD2<Double> in
                let angle = 2 * Double.pi * Double(i) / Double(Self.irisPoints)
                return middle + radius * SIMD2(cos(angle), sin(angle))
            }
            let visible = Self.clip(circle, to: Self.convexHull(contour))
            iris = visible.count >= 3 && abs(Self.area(visible)) >= Self.minimumVisibleIris * Double.pi * radius * radius ? visible : nil
        }

        static func plausible(_ a: Eye, _ b: Eye, in face: (x: Double, y: Double, width: Double, height: Double)) -> Bool {
            guard face.width > 0, face.height > 0 else { return false }
            let mx = face.width * faceMargin, my = face.height * faceMargin
            for p in a.contour + b.contour
            where p.x < face.x - mx || p.x > face.x + face.width + mx || p.y < face.y - my || p.y > face.y + face.height + my {
                return false
            }
            guard eyeWidthPerFace.contains(a.width / face.width), eyeWidthPerFace.contains(b.width / face.width) else { return false }
            let separation = distance(a.middle, b.middle)
            return separationPerFace.contains(separation / face.width) && separation >= minimumSeparationPerEyeWidth * max(a.width, b.width)
        }

        /// A closed centripetal Catmull–Rom spline through `points`, `contourSubdivisions`
        /// points per segment.
        static func smoothed(_ points: [SIMD2<Double>]) -> [SIMD2<Double>] {
            let n = points.count
            var out: [SIMD2<Double>] = []
            for i in 0..<n {
                let p0 = points[(i + n - 1) % n], p1 = points[i], p2 = points[(i + 1) % n], p3 = points[(i + 2) % n]
                func knot(_ a: SIMD2<Double>, _ b: SIMD2<Double>) -> Double { max(distance(a, b).squareRoot(), 1e-6) }
                let t0 = 0.0, t1 = t0 + knot(p0, p1), t2 = t1 + knot(p1, p2), t3 = t2 + knot(p2, p3)
                for k in 0..<contourSubdivisions {
                    let t = t1 + (t2 - t1) * Double(k) / Double(contourSubdivisions)
                    let a1 = blend(p0, p1, t0, t1, t), a2 = blend(p1, p2, t1, t2, t), a3 = blend(p2, p3, t2, t3, t)
                    let b1 = blend(a1, a2, t0, t2, t), b2 = blend(a2, a3, t1, t3, t)
                    out.append(blend(b1, b2, t1, t2, t))
                }
            }
            return out
        }

        static func blend(_ a: SIMD2<Double>, _ b: SIMD2<Double>, _ ta: Double, _ tb: Double, _ t: Double) -> SIMD2<Double> {
            ((tb - t) / (tb - ta)) * a + ((t - ta) / (tb - ta)) * b
        }

        static func convexHull(_ points: [SIMD2<Double>]) -> [SIMD2<Double>] {
            let sorted = points.sorted { ($0.x, $0.y) < ($1.x, $1.y) }
            guard sorted.count >= 3 else { return sorted }
            func cross(_ o: SIMD2<Double>, _ a: SIMD2<Double>, _ b: SIMD2<Double>) -> Double {
                (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
            }
            var lower: [SIMD2<Double>] = [], upper: [SIMD2<Double>] = []
            for p in sorted {
                while lower.count >= 2 && cross(lower[lower.count - 2], lower[lower.count - 1], p) <= 0 { lower.removeLast() }
                lower.append(p)
            }
            for p in sorted.reversed() {
                while upper.count >= 2 && cross(upper[upper.count - 2], upper[upper.count - 1], p) <= 0 { upper.removeLast() }
                upper.append(p)
            }
            return Array(lower.dropLast() + upper.dropLast())
        }

        /// Sutherland–Hodgman: the part of `subject` inside the convex polygon `clip`.
        static func clip(_ subject: [SIMD2<Double>], to clip: [SIMD2<Double>]) -> [SIMD2<Double>] {
            guard clip.count >= 3 else { return [] }
            let orientation: Double = area(clip) >= 0 ? 1 : -1
            func side(_ a: SIMD2<Double>, _ b: SIMD2<Double>, _ p: SIMD2<Double>) -> Double {
                orientation * ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x))
            }
            var output = subject
            for i in 0..<clip.count where !output.isEmpty {
                let a = clip[i], b = clip[(i + 1) % clip.count]
                let input = output
                output = []
                for j in 0..<input.count {
                    let p = input[j], q = input[(j + 1) % input.count]
                    let sp = side(a, b, p), sq = side(a, b, q)
                    if sp >= 0 { output.append(p) }
                    if (sp >= 0) != (sq >= 0) { output.append(p + (q - p) * (sp / (sp - sq))) }
                }
            }
            return output
        }

        static func area(_ polygon: [SIMD2<Double>]) -> Double {
            var sum = 0.0
            for i in 0..<polygon.count {
                let a = polygon[i], b = polygon[(i + 1) % polygon.count]
                sum += a.x * b.y - b.x * a.y
            }
            return sum / 2
        }
    }
}

private func distance(_ a: SIMD2<Double>, _ b: SIMD2<Double>) -> Double {
    let d = a - b
    return (d.x * d.x + d.y * d.y).squareRoot()
}
