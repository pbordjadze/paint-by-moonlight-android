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
    /// The longest template this app reads from a file. iOS allows 256 MB, but decompressed on
    /// Android (and copied to the Java heap) that would exhaust memory long before. The largest
    /// template the core makes is a Detailed painting of a 2048-pixel photo, the largest the app
    /// takes: 3.8 MB for paint-by-number's corpus (barn, upscaled to 2048 px; November 24, 2026),
    /// so this is about four times that, room for an iOS painting's line art and edits.
    static let maximumTemplateBytes = 16 * 1024 * 1024

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
        public var template: Data

        /// The settings to record: the sender's, else what iOS records for a painting without
        /// (`Library.receive`): classic lines for a template without line art, else the defaults.
        public var settings: GenerationSettings {
            shared.settings ?? (shared.template.lineArt == nil
                ? GenerationSettings(lineArt: LineArtSettings(style: .classic)) : GenerationSettings())
        }
    }

    public static func read(_ file: Data) throws(ReadError) -> Received {
        do {
            // The decompressed template, kept: it is the `.pbnt` the app saves, with no second encoding.
            var raw: Data?
            let shared = try SharedPainting(encoded: file, maximumTemplateBytes: maximumTemplateBytes) { method, stream, length in
                guard method == .lzfse else { throw Template.CodingError.corrupt("compression") }
                let decoded = try lzfseDecoded(stream, length: length)
                raw = decoded
                return decoded
            }
            return Received(shared: shared, template: raw ?? shared.template.encoded())
        } catch let error as Template.CodingError where error.requiresNewerReader {
            throw .needsNewerApp
        } catch {
            throw .damaged
        }
    }

    /// The file's bytes for a painting: `template` as saved (`.pbnt`), never its progress.
    /// `settingsJSON` that doesn't decode travels as none, as iOS's reader takes it.
    public static func write(
        template: Data, title: String, settingsJSON: Data?, photo: Data?, nicknames: SharedPainting.Nicknames?
    ) throws -> Data {
        let shared = SharedPainting(
            title: title, settings: settingsJSON.flatMap { try? JSONDecoder().decode(GenerationSettings.self, from: $0) },
            template: try Template(encoded: template), photo: photo, nicknames: nicknames)
        do {
            return try shared.encoded(compression: .lzfse) { raw in
                guard let compressed = lzfseEncoded(raw) else { throw NotSmaller() }
                return compressed
            }
        } catch is NotSmaller {
            // A template LZFSE can't shrink travels uncompressed, which every reader takes.
            return try shared.encoded()
        }
    }

    private struct NotSmaller: Error {}

    /// `stream` decoded, exactly `length` bytes of it or a throw. The buffer grows only as the
    /// stream fills it, so a hostile length reserves no more than the stream decodes to (as the
    /// iOS app's `ArtworkStore.lzfseDecoded`).
    static func lzfseDecoded(_ stream: Data, length: Int) throws -> Data {
        guard !stream.isEmpty, length > 0 else { throw Template.CodingError.corrupt("compression") }
        var scratch = [UInt8](repeating: 0, count: lzfse_decode_scratch_size())
        // One extra byte tells a stream exactly `length` long from a longer one.
        var capacity = min(64 * 1024, length + 1)
        while true {
            var out = Data(count: capacity)
            let count = out.withUnsafeMutableBytes { dst in
                stream.withUnsafeBytes { src in
                    scratch.withUnsafeMutableBytes {
                        lzfse_decode_buffer(
                            dst.bindMemory(to: UInt8.self).baseAddress!, capacity, src.bindMemory(to: UInt8.self).baseAddress!, src.count,
                            $0.baseAddress!)
                    }
                }
            }
            guard count > 0, count <= length else { throw Template.CodingError.corrupt("compression") }
            if count < capacity {
                guard count == length else { throw Template.CodingError.corrupt("compression") }
                out.count = count
                return out
            }
            capacity = min(capacity * 2, length + 1)
        }
    }

    /// `raw` in LZFSE, or nil when it wouldn't come out smaller.
    static func lzfseEncoded(_ raw: Data) -> Data? {
        guard !raw.isEmpty else { return nil }
        var scratch = [UInt8](repeating: 0, count: lzfse_encode_scratch_size())
        var out = Data(count: raw.count)
        let count = out.withUnsafeMutableBytes { dst in
            raw.withUnsafeBytes { src in
                scratch.withUnsafeMutableBytes {
                    lzfse_encode_buffer(
                        dst.bindMemory(to: UInt8.self).baseAddress!, dst.count, src.bindMemory(to: UInt8.self).baseAddress!, src.count,
                        $0.baseAddress!)
                }
            }
        }
        guard count > 0 else { return nil }
        out.count = count
        return out
    }
}
