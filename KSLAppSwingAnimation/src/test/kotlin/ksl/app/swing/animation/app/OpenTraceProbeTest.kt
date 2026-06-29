package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.swing.animation.view.SimulationCanvas
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live reproduction of the "Open trace…" blank (needs a display; skips when headless): produce a trace,
 * then load it through [ReplayPanel.open] exactly as the toolbar button does (no sibling layout file) and
 * assert the canvas is populated by the trace-derived fallback layout rather than blank.
 */
class OpenTraceProbeTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TROpen").apply {
                numberOfReplications = 1
                lengthOfReplication = 480.0
                TestAndRepairShopWithMovableResources(this, "TR")
            }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `Open trace renders via the trace-derived fallback layout`() {
        if (GraphicsEnvironment.isHeadless()) { println("headless; skipping Open-trace probe"); return }
        val workspace = Files.createTempDirectory("anim-open")
        val controller = AnimationAppController("TROpen", builder).apply { workspaceOverride = workspace }
        try {
            controller.submit()
            runBlocking { withTimeout(60_000) { controller.lastResult.filterNotNull().first() } }
            val trace = controller.lastTraceFile.value!!

            val painted = onEdt {
                val panel = ReplayPanel(controller)
                // A real, shown frame lays the canvas out to a non-zero size (a bare panel leaves it 0x0).
                val frame = javax.swing.JFrame("open-trace probe").apply {
                    contentPane.add(panel); setSize(900, 700); isVisible = true
                }
                panel.loadTraceForTest(trace) // pair the trace with the default (Quick view) layout
                val canvas = panel.components.filterIsInstance<SimulationCanvas>().first()
                val w = canvas.width; val h = canvas.height
                check(w > 0 && h > 0) { "canvas not laid out: ${w}x$h" }
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics(); canvas.paint(g); g.dispose()
                ImageIO.write(img, "png", java.nio.file.Path.of("build", "open-trace-frame.png").toFile())
                var px = 0
                for (y in 0 until h) for (x in 0 until w) if (img.getRGB(x, y) and 0xffffff != 0xffffff) px++
                println("canvas ${w}x$h painted=$px")
                frame.dispose()
                px
            }
            println("Open-trace painted=$painted")
            // Real content: many non-white px for the elements, but mostly white background (not all-black).
            assertTrue(painted in 1000..400_000, "Open trace should render the fallback layout, got $painted px")
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }
}
