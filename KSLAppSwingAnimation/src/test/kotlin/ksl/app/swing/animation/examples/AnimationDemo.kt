package ksl.app.swing.animation.examples

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import ksl.animation.AnimationLayout
import ksl.animation.OverlaySpec
import ksl.app.session.AnimationTraceAttachment
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import ksl.app.swing.animation.view.SimulationCanvas
import ksl.simulation.Model
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import ksl.app.animation.io.load

/**
 * Test-tree helper for the replay/overlay tests, ported from the KSL-Private examples `AnimationDemo`
 * — the trace-**generation** half only; the Swing viewer half (`launchViewer`/`generateAndOpen`) is
 * intentionally omitted (the app authors layouts and replays through the running application).
 *
 * It runs a model for one replication with the [AnimationTraceAttachment] installed, producing the
 * two-file output (`<base>.lay.json` + `<base>.atf`) that the tests load and assert against.
 */
object AnimationDemo {

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

    /**
     * Renders a single frame of the animation defined by [files] at the given [fraction] of its
     * timeline to an off-screen image (no display required) — for headless verification that an
     * example's layout/trace renders.
     */
    fun renderFrame(files: TraceFiles, fraction: Double = 0.5, width: Int = 900, height: Int = 560): BufferedImage {
        val model = ReplayModel.build(AnimationSource.load(files.layoutFile, files.traceFile))
        val canvas = SimulationCanvas()
        canvas.setSize(width, height)
        canvas.replay = model
        val r = model.timeRange
        canvas.currentTime = r.start + fraction.coerceIn(0.0, 1.0) * (r.endInclusive - r.start)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        canvas.paint(g)
        g.dispose()
        return image
    }

    /** Renders a frame (see [renderFrame]) and writes it as a PNG to [out]. */
    fun renderFrameToPng(files: TraceFiles, out: Path, fraction: Double = 0.5, width: Int = 900, height: Int = 560) {
        ImageIO.write(renderFrame(files, fraction, width, height), "png", out.toFile())
    }
}
