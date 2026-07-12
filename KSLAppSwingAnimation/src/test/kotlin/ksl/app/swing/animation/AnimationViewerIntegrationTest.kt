package ksl.app.swing.animation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import ksl.animation.AnimationLayout
import ksl.animation.BarDisplayElement
import ksl.animation.LayoutPoint
import ksl.animation.ObjectClassDefinition
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.app.session.AnimationTraceAttachment
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import ksl.app.swing.animation.view.SimulationCanvas
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end pipeline test (no display required): a real process model emits a two-file animation
 * via the [AnimationTraceAttachment]; the files are loaded, indexed into a [ReplayModel], and a
 * [SimulationCanvas] paints a mid-run frame offscreen into a [BufferedImage]. Asserts the frame is
 * non-blank and saves a PNG so the rendered result can be inspected.
 */
class AnimationViewerIntegrationTest {

    @TempDir
    lateinit var tempRoot: Path

    private class ShopModel(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val worker = ResourceWithQ(parent = this, name = "Worker", capacity = 1)
        private val st = RandomVariable(this, ExponentialRV(0.8, 2))
        private val tba = ExponentialRV(0.6, 1)
        private val tip = Response(this, name = "TimeInSystem")

        @Suppress("unused")
        private val generator = EntityGenerator(::Cust, tba, tba)

        private inner class Cust : Entity() {
            @Suppress("unused")
            val proc: KSLProcess = process(isDefaultProcess = true) {
                val arrival = time
                val a = seize(worker)
                delay(st)
                release(a)
                tip.value = time - arrival
            }
        }
    }

    @Test
    fun `renders a mid-run frame from a real two-file trace`() {
        val tmp = Files.createTempDirectory(tempRoot, "vieweritest")
        val traceFile = tmp.resolve("run.atf")
        val layoutFile = tmp.resolve("run.lay.json")

        val model = Model("shop", pathToOutputDirectory = tmp)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        ShopModel(model)

        // A positioned layout so the frame has recognizable geometry (queue, resource, value bar).
        val layout = AnimationLayout(
            title = "Pharmacy",
            width = 400.0, height = 250.0,
            objectClasses = listOf(ObjectClassDefinition(typeName = "Cust", color = "#1f77b4")),
            queues = listOf(QueueLayoutElement(queueName = "WorkerQ", position = LayoutPoint(60.0, 120.0))),
            resources = listOf(ResourceLayoutElement(resourceName = "Worker", position = LayoutPoint(260.0, 120.0))),
            bars = listOf(
                BarDisplayElement(
                    responseName = "TimeInSystem", position = LayoutPoint(60.0, 200.0),
                    width = 280.0, height = 18.0, maxValue = 30.0, label = "Time in system"
                )
            )
        )

        val attachment = AnimationTraceAttachment.replay(traceFile = traceFile, layout = layout, layoutFile = layoutFile)
        attachment.onAttach(model, CoroutineScope(SupervisorJob()))
        try {
            model.simulate()
        } finally {
            attachment.onDetach()
        }

        val replay = ReplayModel.build(AnimationSource.load(layoutFile, traceFile))
        assertTrue(replay.timeRange.endInclusive > 0.0)

        val canvas = SimulationCanvas()
        canvas.setSize(900, 560)
        canvas.replay = replay
        canvas.currentTime = replay.timeRange.endInclusive / 2.0

        val image = BufferedImage(900, 560, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        canvas.paint(g)
        g.dispose()

        var painted = 0
        for (y in 0 until image.height) for (x in 0 until image.width)
            if (image.getRGB(x, y) and 0xffffff != 0xffffff) painted++
        assertTrue(painted > 500, "expected a populated frame, got $painted non-white pixels")

        val out = File(System.getProperty("java.io.tmpdir"), "ksl-pharmacy-frame.png")
        ImageIO.write(image, "png", out)
        println("Saved pharmacy frame to ${out.absolutePath}")
    }
}
