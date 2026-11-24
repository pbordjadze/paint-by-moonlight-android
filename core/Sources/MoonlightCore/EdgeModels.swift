#if canImport(FoundationEssentials) && !canImport(Darwin)
import FoundationEssentials
#else
import Foundation
#endif
import PaintCore

/// The coloring book's two models, which the app runs with LiteRT (EdgeModels.kt) from
/// app/src/main/assets/models: the same networks and weights as the iOS app's (its
/// `EdgeDetector`), converted by tools/models/convert.py, whose docstring is their provenance.
/// What runs here is what surrounds them, as iOS does it, so a photo gets the maps iOS makes up to
/// compute-order noise: the photo area-resampled to the map's size (PaintCore's resampler),
/// reflect-padded at the bottom and right to the network's stride and scaled as the network
/// wants it (`input`); the probability cropped back and rounded to the nearest of 256 levels
/// (`map`). HED's five side outputs are joined here too (bilinear upsampling, mean, sigmoid),
/// since a resize baked into the model would pin it to one photo shape. tools/edges.py does the
/// same on the host.
public enum EdgeModels {
    public enum Model: Int32, Sendable {
        /// HED: contours, whose silhouettes are strong and closed.
        case hed = 0
        /// Informative Drawings' generator: a line drawing with the fur, petals and glass a
        /// contour map lacks.
        case lineArt = 1

        /// The network's total stride: its input is padded to a multiple of it.
        var multiple: Int { self == .hed ? 16 : 4 }
        /// What a byte of the photo is in the model's input: HED takes 0...255, the drawing 0...1.
        var scale: Float { self == .hed ? 1 : 1 / 255 }
        /// The long side the map is made at: the generator was trained on small crops, so at 768
        /// its lines are a touch heavier and cleaner, and it runs four times faster (iOS's
        /// `EdgeDetector.drawingLongSide`).
        var longSide: Int { self == .hed ? maximumLongSide : 768 }
    }

    /// The longest side a map is made at.
    public static let maximumLongSide = 1152

    /// The size a map of a `width` × `height` photo has: the photo's, scaled down to fit
    /// `maxLongSide` (iOS's `EdgeDetector.mapSize`).
    public static func mapSize(width: Int, height: Int, maxLongSide: Int) -> (width: Int, height: Int) {
        let limit = min(max(maxLongSide, 16), maximumLongSide)
        let long = max(width, height)
        guard long > limit else { return (width, height) }
        let scale = Double(limit) / Double(long)
        return (max(1, Int((Double(width) * scale).rounded())), max(1, Int((Double(height) * scale).rounded())))
    }

    /// Where padded row or column `i` reads from in a line of `n` pixels: mirrored at the end
    /// without repeating the last pixel (NumPy's "reflect").
    static func reflect(_ i: Int, _ n: Int) -> Int {
        guard i >= n else { return i }
        guard n > 1 else { return 0 }
        let period = 2 * (n - 1)
        let m = i % period
        return m < n ? m : period - m
    }

    public struct Input: Sendable {
        /// [1, paddedHeight, paddedWidth, 3], RGB.
        public var values: [Float]
        /// The map's size, and the padded size the model runs at.
        public var width: Int, height: Int
        public var paddedWidth: Int, paddedHeight: Int
    }

    /// `model`'s input for a photo (straight sRGB RGBA8).
    public static func input(_ model: Model, for image: RGBAImage) -> Input {
        let size = mapSize(width: image.width, height: image.height, maxLongSide: model.longSide)
        let resized = size.width == image.width && size.height == image.height
            ? image : Resample.area(image, width: size.width, height: size.height)
        let w = resized.width, h = resized.height, m = model.multiple
        let pw = (w + m - 1) / m * m, ph = (h + m - 1) / m * m
        let columns = (0..<pw).map { reflect($0, w) }
        var values = [Float](repeating: 0, count: pw * ph * 3)
        let scale = model.scale
        resized.pixels.withUnsafeBufferPointer { source in
            values.withUnsafeMutableBufferPointer { target in
                for y in 0..<ph {
                    let row = reflect(y, h) * w
                    for x in 0..<pw {
                        let s = (row + columns[x]) * 4, t = (y * pw + x) * 3
                        target[t] = Float(source[s]) * scale
                        target[t + 1] = Float(source[s + 1]) * scale
                        target[t + 2] = Float(source[s + 2]) * scale
                    }
                }
            }
        }
        return Input(values: values, width: w, height: h, paddedWidth: pw, paddedHeight: ph)
    }

    /// One output of a model: [1, height, width, 1].
    public struct Output: Sendable {
        public var values: [Float]
        public var width: Int, height: Int

        public init(values: [Float], width: Int, height: Int) {
            self.values = values
            self.width = width
            self.height = height
        }
    }

    public enum MapError: Error { case unexpectedOutput }

    /// The map of `model`'s outputs for an input made by `input` (`width` × `height`, padded to
    /// `paddedWidth` × `paddedHeight`). Outputs are checked against those sizes: they come from
    /// across the JNI.
    public static func map(
        _ model: Model, outputs: [Output], width: Int, height: Int, paddedWidth: Int, paddedHeight: Int
    ) throws -> EdgeMap {
        guard width > 0, height > 0, width <= paddedWidth, height <= paddedHeight,
              outputs.allSatisfy({ $0.width > 0 && $0.height > 0 && $0.values.count == $0.width * $0.height })
        else { throw MapError.unexpectedOutput }
        var values = [UInt8](repeating: 0, count: width * height)
        switch model {
        case .lineArt:
            guard outputs.count == 1, outputs[0].width == paddedWidth, outputs[0].height == paddedHeight else {
                throw MapError.unexpectedOutput
            }
            let ink = outputs[0].values
            for y in 0..<height {
                for x in 0..<width { values[y * width + x] = level(ink[y * paddedWidth + x]) }
            }
        case .hed:
            // The sides at strides 1, 2, 4, 8, 16, whatever order the interpreter lists them in.
            let sides = outputs.sorted { $0.width * $0.height > $1.width * $1.height }
            guard sides.count == 5 else { throw MapError.unexpectedOutput }
            for (k, side) in sides.enumerated() where side.width << k != paddedWidth || side.height << k != paddedHeight {
                throw MapError.unexpectedOutput
            }
            var sum = [Float](repeating: 0, count: width * height)
            for (k, side) in sides.enumerated() {
                upsample(side, factor: 1 << k, into: &sum, width: width, height: height)
            }
            for i in sum.indices {
                let mean = Double(sum[i] / 5)
                values[i] = level(Float(1 / (1 + exp(-mean))))
            }
        }
        return EdgeMap(width: width, height: height, values: values)
    }

    /// Adds `side` upsampled by `factor` (bilinear, half-pixel centers, edges clamped: OpenCV's
    /// INTER_LINEAR, as the research and iOS's model compute it) to the top-left `width` ×
    /// `height` of `sum`.
    static func upsample(_ side: Output, factor: Int, into sum: inout [Float], width: Int, height: Int) {
        func taps(_ n: Int, _ count: Int) -> [(Int, Int, Float)] {
            (0..<count).map { o in
                let source = min(max((Double(o) + 0.5) / Double(factor) - 0.5, 0), Double(n - 1))
                let i0 = min(Int(source.rounded(.down)), n - 1)
                return (i0, min(i0 + 1, n - 1), Float(source - Double(i0)))
            }
        }
        let xs = taps(side.width, width), ys = taps(side.height, height)
        let v = side.values, w = side.width
        for y in 0..<height {
            let (y0, y1, fy) = ys[y]
            for x in 0..<width {
                let (x0, x1, fx) = xs[x]
                let top = v[y0 * w + x0] * (1 - fx) + v[y0 * w + x1] * fx
                let bottom = v[y1 * w + x0] * (1 - fx) + v[y1 * w + x1] * fx
                sum[y * width + x] += top * (1 - fy) + bottom * fy
            }
        }
    }

    /// A probability as a map level: the nearest of 256 (half up).
    static func level(_ p: Float) -> UInt8 {
        guard p.isFinite else { return 0 }
        return UInt8(min(max((Double(p) * 255 + 0.5).rounded(.down), 0), 255))
    }
}
