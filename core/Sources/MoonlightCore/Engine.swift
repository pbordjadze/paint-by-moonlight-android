#if canImport(FoundationEssentials) && !canImport(Darwin)
import FoundationEssentials
#else
import Foundation
#endif
import PaintCore
import Synchronization

/// A painting made from a photo, as the iOS app makes a new one (its `CreateModel`): Auto
/// chooses the settings for the painter's Painting Length on a draft of the photo, then the
/// template is generated at those settings with the new paintings' tuning and beauty paint.
///
/// What the app found in the photo (`Findings`: the line models' maps, ML Kit's subjects,
/// faces and text) gives PaintCore what iOS gives it from Vision and Core ML: the importance
/// map and the subject hints, and the coloring book's input, so a painting is the coloring book
/// iOS makes (`LineArtSettings()`, the Lines slider at its middle). Without both maps (the
/// models didn't load) it is a classic painting, and records classic settings, so it
/// regenerates as made; without a subject or a face PaintCore makes its own importance, as
/// `pbn` does. The pipeline's seed is the default one iOS uses too (the paints' nicknames are
/// seeded per painting, in `Scene`).
public enum Engine {
    /// The iOS app's `CreateModel.newTuning` and `newTaste`, `newPaintMix`.
    static let tuning = PipelineTuning(subjectCellSize: 2.5, faceCellSize: 3)
    static let taste = AutoTaste.finished
    static let paintMix: PaintMix? = .beauty

    /// Candidates Auto tries: the iOS app's rule (3 below 6 cores, 5 otherwise).
    static var maxCandidates: Int { ProcessInfo.processInfo.activeProcessorCount < 6 ? 3 : 5 }

    public struct Made: Sendable {
        public var template: Template
        public var settings: GenerationSettings
    }

    public static func make(
        _ image: RGBAImage, length: PaintingLength, findings: Findings = Findings(), job: Job? = nil
    ) throws -> Made {
        let cancel = CancellationCheck { job?.isCancelled ?? false }
        job?.report(0.02)
        let importance = findings.importance(width: image.width, height: image.height)
        let hints = findings.hints
        let lineArtInput = findings.lineArt(width: image.width, height: image.height)
        try cancel.throwIfCancelled()
        let draft = AutoSettings.draftImage(from: image)
        let decision = try AutoSettings.choose(
            image: draft, sourceSize: (image.width, image.height), importance: importance, hints: hints,
            preference: length, maxCandidates: maxCandidates,
            lineArt: lineArtInput == nil ? LineArtSettings(style: .classic) : LineArtSettings(),
            tuning: tuning, taste: taste, cancel: cancel, firstDraft: nil)
        job?.report(0.4)
        let chosen = decision.settings
        let settings = GenerationSettings(
            colorCount: chosen.colorCount, detail: chosen.detail, smoothness: chosen.smoothness, seed: chosen.seed,
            lineArt: chosen.lineArt, tuning: chosen.tuning, drawing: nil, paintMix: paintMix
        ).normalized
        let output = try TemplateGenerator(settings: settings).generate(
            from: image, importance: importance, lineArt: lineArtInput, cancel: cancel,
            progress: { fraction in job?.report(0.4 + 0.6 * fraction) })
        job?.report(1)
        return Made(template: output.template, settings: settings)
    }
}

/// One generation's shared state with the app: its progress, read while it runs, and its
/// cancellation, set from any thread.
public final class Job: Sendable {
    private let cancelled = Atomic<Bool>(false)
    private let progressBits = Atomic<UInt32>(0)

    public init() {}

    public var isCancelled: Bool { cancelled.load(ordering: .relaxed) }
    public func cancel() { cancelled.store(true, ordering: .relaxed) }

    public var progress: Float { Float(bitPattern: progressBits.load(ordering: .relaxed)) }
    func report(_ fraction: Float) {
        let clamped = min(max(fraction, 0), 1)
        // Progress only moves forward: stages report from several threads.
        var current = progressBits.load(ordering: .relaxed)
        while Float(bitPattern: current) < clamped {
            let (exchanged, original) = progressBits.compareExchange(
                expected: current, desired: clamped.bitPattern, ordering: .relaxed)
            if exchanged { break }
            current = original
        }
    }
}
