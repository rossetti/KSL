package ksl.app.animation.replay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import ksl.animation.AnimationLayout
import ksl.animation.OverlaySpec
import ksl.app.session.AnimationTraceAttachment
import ksl.simulation.Model
import java.nio.file.Files
import java.nio.file.Path

/**
 *  KSLApp-tier test helper: the trace-**generation** half of the animation demos, homed with the
 *  replay engine it feeds. Runs a model for one replication with an [AnimationTraceAttachment]
 *  installed, producing the two-file output (`<base>.lay.json` + `<base>.atf`) that the replay-engine
 *  tests load and assert against. It is Swing-free — the rendering half (`renderFrame`) stays with the
 *  viewer in KSLAppSwingAnimation's `AnimationDemo`.
 */
object AnimationTraceDemo {

    /** The pair of files that together define a viewable animation. */
    data class TraceFiles(val layoutFile: Path, val traceFile: Path)

    /** Output dir for generated test animations: `build/animations/` (gitignored, removed by `gradle clean`). */
    fun galleryDir(): Path = Path.of("build", "animations").also { Files.createDirectories(it) }

    /**
     * Runs [model] for one replication with the animation attachment installed, writing
     * `<baseName>.lay.json` and `<baseName>.atf` into [outputDir] (the [galleryDir] by default).
     * Returns the two file paths. The attachment captures replication 1, so set up the model's run
     * length before calling.
     */
    fun generate(
        model: Model,
        layout: AnimationLayout,
        baseName: String = model.name,
        outputDir: Path = galleryDir(),
        overlays: OverlaySpec = OverlaySpec.OFF
    ): TraceFiles {
        val traceFile = outputDir.resolve("$baseName.atf")
        val layoutFile = outputDir.resolve("$baseName.lay.json")
        val attachment = AnimationTraceAttachment.replay(
            traceFile = traceFile, layout = layout, layoutFile = layoutFile, overlays = overlays
        )
        // Drive the attachment's lifecycle directly (the same hooks the run orchestrator would call).
        attachment.onAttach(model, CoroutineScope(SupervisorJob()))
        try {
            model.simulate()
        } finally {
            attachment.onDetach()
        }
        return TraceFiles(layoutFile, traceFile)
    }
}
