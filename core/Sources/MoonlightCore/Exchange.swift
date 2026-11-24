#if canImport(FoundationEssentials) && !canImport(Darwin)
import FoundationEssentials
#else
import Foundation
#endif
import CLZFSE
import PaintCore

/// A painting sent between painters, the iOS app's `.moonlight` file (PaintCore's
/// `SharedPainting`, which reads and writes it), with the template compressed by LZFSE as the
/// iOS app's `SharedPaintingCodec` does, so a file goes either way between the two apps.
public enum Exchange {
    /// What the iOS app's library bounds a template by (`ArtworkStore.maximumTemplateBytes`),
    /// so a file one app opens the other does too.
    static let maximumTemplateBytes = 256 * 1024 * 1024

    /// Why a file couldn't be read: it needs a newer app (a format or compression this one
    /// doesn't know yet), or it is damaged or not a painting.
    public enum ReadError: Error, Equatable {
        case needsNewerApp
        case damaged
    }

    /// A painting someone sent, as the app adds it: the template (encoded, `.pbnt` bytes, checked
    /// by `Template(encoded:)` on the way), its title (cleaned by PaintCore; empty when the
    /// sender's was), the settings that made it, the sender's photo's bytes (undecoded: the app
    /// decodes it as any opened image) and the sender's paint names, if the file carries them.
    public struct Received: Sendable {
        public var shared: SharedPainting
        public var template: [UInt8]

        /// The settings to record: the sender's, else what iOS records for a painting without
        /// (`Library.receive`): classic lines for a template without line art, else the defaults.
        public var settings: GenerationSettings {
            shared.settings ?? (shared.template.lineArt == nil
                ? GenerationSettings(lineArt: LineArtSettings(style: .classic)) : GenerationSettings())
        }
    }

    public static func read(_ bytes: [UInt8]) throws(ReadError) -> Received {
        do {
            let shared = try SharedPainting(encoded: Data(bytes), maximumTemplateBytes: maximumTemplateBytes) { method, stream, length in
                guard method == .lzfse else { throw Template.CodingError.corrupt("compression") }
                return try lzfseDecoded([UInt8](stream), length: length)
            }
            return Received(shared: shared, template: [UInt8](shared.template.encoded()))
        } catch let error as Template.CodingError where error.requiresNewerReader {
            throw .needsNewerApp
        } catch {
            throw .damaged
        }
    }

    /// The file's bytes for a painting: `template` as saved (`.pbnt`), never its progress.
    /// `settingsJSON` that doesn't decode travels as none, as iOS's reader takes it.
    public static func write(
        template: [UInt8], title: String, settingsJSON: [UInt8]?, photo: [UInt8]?, nicknames: SharedPainting.Nicknames?
    ) throws -> [UInt8] {
        let shared = SharedPainting(
            title: title, settings: settingsJSON.flatMap { try? JSONDecoder().decode(GenerationSettings.self, from: Data($0)) },
            template: try Template(encoded: Data(template)), photo: photo.map { Data($0) }, nicknames: nicknames)
        // A template LZFSE can't shrink travels uncompressed, which every reader takes.
        guard let compressed = lzfseEncoded(template) else { return [UInt8](try shared.encoded()) }
        return [UInt8](try shared.encoded(compression: .lzfse) { _ in Data(compressed) })
    }

    /// `stream` decoded, exactly `length` bytes of it or a throw. The buffer grows only as the
    /// stream fills it, so a hostile length reserves no more than the stream decodes to (as the
    /// iOS app's `ArtworkStore.lzfseDecoded`).
    static func lzfseDecoded(_ stream: [UInt8], length: Int) throws -> Data {
        guard !stream.isEmpty, length > 0 else { throw Template.CodingError.corrupt("compression") }
        var scratch = [UInt8](repeating: 0, count: lzfse_decode_scratch_size())
        // One extra byte tells a stream exactly `length` long from a longer one.
        var capacity = min(64 * 1024, length + 1)
        while true {
            var out = [UInt8](repeating: 0, count: capacity)
            let count = out.withUnsafeMutableBufferPointer { dst in
                stream.withUnsafeBufferPointer { src in
                    scratch.withUnsafeMutableBytes { lzfse_decode_buffer(dst.baseAddress!, capacity, src.baseAddress!, src.count, $0.baseAddress!) }
                }
            }
            guard count > 0, count <= length else { throw Template.CodingError.corrupt("compression") }
            if count < capacity {
                guard count == length else { throw Template.CodingError.corrupt("compression") }
                return Data(out[..<count])
            }
            capacity = min(capacity * 2, length + 1)
        }
    }

    /// `raw` in LZFSE, or nil when it wouldn't come out smaller.
    static func lzfseEncoded(_ raw: [UInt8]) -> [UInt8]? {
        guard !raw.isEmpty else { return nil }
        var scratch = [UInt8](repeating: 0, count: lzfse_encode_scratch_size())
        var out = [UInt8](repeating: 0, count: raw.count)
        let count = out.withUnsafeMutableBufferPointer { dst in
            raw.withUnsafeBufferPointer { src in
                scratch.withUnsafeMutableBytes { lzfse_encode_buffer(dst.baseAddress!, dst.count, src.baseAddress!, src.count, $0.baseAddress!) }
            }
        }
        return count > 0 ? Array(out[..<count]) : nil
    }
}
