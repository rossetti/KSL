package ksl.app.swing.animation.view

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutPoint
import ksl.animation.ObjectClassDefinition
import ksl.animation.StorageLayoutElement
import ksl.animation.StorageStyle
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/** Verifies 8K.4: a storage renders its delaying members (offscreen), here as a progress belt. */
class StorageRenderTest {

    @Test
    fun `progress-belt storage paints its members along the belt (8K4)`() {
        val layout = AnimationLayout(
            title = "Storage", width = 200.0, height = 100.0,
            objectClasses = listOf(ObjectClassDefinition("Part", color = "#d62728", size = 12.0)),
            storages = listOf(
                StorageLayoutElement("inspect", LayoutPoint(20.0, 50.0), style = StorageStyle.PROGRESS_BELT, width = 160.0)
            )
        )
        // Three parts delaying in "inspect" over [0, 20], so at t=10 they sit mid-belt.
        val events = (1L..3L).flatMap { id ->
            listOf(
                AnimationEvent.EntityCreated(0.0, id, "Part"),
                AnimationEvent.DelayStarted(0.0, id, duration = 20.0, arrivalTime = 20.0, suspensionName = "inspect")
            )
        }
        val model = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))

        val canvas = SimulationCanvas()
        canvas.setSize(400, 200)
        canvas.replay = model
        canvas.currentTime = 10.0
        val image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); canvas.paint(g); g.dispose()

        // The members are red (#d62728); expect a cluster of red pixels somewhere on the belt.
        val red = 0xd62728
        var redPx = 0
        for (y in 0 until image.height) for (x in 0 until image.width)
            if (image.getRGB(x, y) and 0xffffff == red) redPx++
        assertTrue(redPx > 50, "expected the storage to draw its red Part members, got $redPx red px")
    }
}
