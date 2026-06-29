package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.TraceFileReader
import ksl.app.session.RunResult
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import ksl.app.swing.animation.view.SimulationCanvas
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression for the "replay area is blank" bug: [SimulationCanvas] draws every element off the layout, so
 * a produced trace replayed with a null layout renders nothing. The Simulate auto-load must fall back to a
 * scaffolded layout ([AnimationAppController.buildScaffoldLayout]) so the trace's elements appear. Headless
 * (offscreen [BufferedImage] render, the module's standard canvas-test technique).
 */
class ReplayScaffoldFallbackTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model = Model("TRScaffold").apply {
            numberOfReplications = 1
            lengthOfReplication = 480.0
            TestAndRepairShopWithMovableResources(this, "TR")
        }
    }

    /** Non-white pixel count of a frame rendered at [time] from [layout] + the trace [events]. */
    private fun paintedPixels(layout: AnimationLayout?, events: List<AnimationEvent>, time: Double, savePngTo: Path? = null): Int {
        val replay = ReplayModel.build(
            AnimationSource(layout = layout, header = AnimationTraceHeader(), events = events)
        )
        val canvas = SimulationCanvas().apply { setSize(800, 600); this.replay = replay; currentTime = time }
        val image = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        canvas.paint(g)
        g.dispose()
        savePngTo?.let { javax.imageio.ImageIO.write(image, "png", it.toFile()); println("Saved scaffolded frame to $it") }
        var painted = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getRGB(x, y) and 0xffffff != 0xffffff) painted++
        }
        return painted
    }

    @Test
    fun `produced trace renders blank with no layout but populated with the scaffold fallback`() {
        val workspace = Files.createTempDirectory("anim-scaffold")
        val controller = AnimationAppController("TRScaffold", builder)
        controller.workspaceOverride = workspace
        try {
            controller.submit()
            val result = runBlocking { withTimeout(60_000) { controller.lastResult.filterNotNull().first() } }
            assertIs<RunResult.Completed>(result)
            val trace: Path = controller.lastTraceFile.value!!
            val events = TraceFileReader.readAll(trace).second
            val midpoint = events.maxOf { it.simTime } / 2.0

            // The bug: no layout → blank canvas (only the fit-to-view background, essentially nothing).
            val withoutLayout = paintedPixels(null, events, midpoint)
            // The fix: the scaffold fallback yields a populated layout the canvas can draw.
            val scaffold = controller.buildScaffoldLayout()
            assertTrue(scaffold != null && scaffold.resources.isNotEmpty(), "scaffold places the model's resources")
            val pngOut = workspace.resolve("scaffolded-frame.png")
            val withScaffold = paintedPixels(scaffold, events, midpoint, savePngTo = pngOut)
            // Copy to a stable build path so it survives the temp-dir cleanup below.
            val stable = Path.of("build", "scaffolded-frame.png")
            Files.createDirectories(stable.parent)
            Files.copy(pngOut, stable, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            println("Scaffold render: $withScaffold non-white px (vs $withoutLayout without layout)")

            assertTrue(withScaffold > 1000, "scaffolded replay draws real content, got $withScaffold non-white px")
            assertTrue(withScaffold > withoutLayout * 5, "scaffold draws far more than the empty layout ($withScaffold vs $withoutLayout)")
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }
}
