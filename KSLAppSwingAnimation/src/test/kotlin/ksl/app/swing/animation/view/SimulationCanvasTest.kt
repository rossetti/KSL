package ksl.app.swing.animation.view

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutPoint
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.animation.SpatialSpaceDescriptor
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders [SimulationCanvas] offscreen (no display) into a [BufferedImage] so the painting code can
 * be exercised headlessly in CI. Asserts the frame is non-blank and saves a PNG artifact.
 */
class SimulationCanvasTest {

    private fun sampleReplay(): ReplayModel {
        val layout = AnimationLayout(
            title = "Canvas Test",
            width = 200.0, height = 100.0,
            spaces = listOf(SpatialSpaceDescriptor.Continuous("space", 0.0, 200.0, 0.0, 100.0)),
            queues = listOf(QueueLayoutElement(queueName = "WaitQ", position = LayoutPoint(20.0, 80.0))),
            resources = listOf(ResourceLayoutElement(resourceName = "Worker", position = LayoutPoint(150.0, 50.0)))
        )
        val events = listOf(
            AnimationEvent.EntityCreated(0.0, 1L, "Part"),
            AnimationEvent.QueueLengthChanged(0.0, "WaitQ", 3),
            AnimationEvent.ResourceStateChanged(0.0, "Worker", "Worker_Busy", busyUnits = 1, capacity = 1),
            AnimationEvent.MoveStarted(
                0.0, 1L, fromX = 20.0, fromY = 50.0, toX = 150.0, toY = 50.0,
                velocity = 26.0, duration = 5.0, arrivalTime = 5.0
            ),
            AnimationEvent.EntityDisposed(10.0, 1L)
        )
        return ReplayModel.build(AnimationSource(layout = layout, header = AnimationTraceHeader(), events = events))
    }

    @Test
    fun `canvas paints a non-blank frame offscreen`() {
        val canvas = SimulationCanvas()
        canvas.setSize(640, 360)
        canvas.replay = sampleReplay()
        canvas.currentTime = 2.5 // mid-move, queue populated, resource busy

        val image = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        canvas.paint(g)
        g.dispose()

        // Count non-white pixels: the layout/entities should have drawn something.
        var painted = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) and 0xffffff != 0xffffff) painted++
            }
        }
        assertTrue(painted > 100, "expected the canvas to paint content, got $painted non-white pixels")

        val out = File(System.getProperty("java.io.tmpdir"), "ksl-canvas-frame.png")
        ImageIO.write(image, "png", out)
        println("Saved canvas frame to ${out.absolutePath}")
    }
}
