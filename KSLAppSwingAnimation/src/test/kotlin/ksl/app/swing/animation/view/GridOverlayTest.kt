package ksl.app.swing.animation.view

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.ResourceLayoutElement
import ksl.animation.LayoutPoint
import ksl.animation.io.AnimationSource
import ksl.animation.replay.ReplayModel
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/** U5: the canvas can draw a coordinate grid as a spatial reference; toggling it changes what's painted. */
class GridOverlayTest {

    private fun model(): ReplayModel {
        val layout = AnimationLayout(width = 800.0, height = 600.0,
            resources = listOf(ResourceLayoutElement(resourceName = "R", position = LayoutPoint(400.0, 300.0))))
        val events = listOf(AnimationEvent.ResourceStateChanged(0.0, "R", "R_Idle", busyUnits = 0, capacity = 1))
        return ReplayModel.build(AnimationSource(layout = layout, header = AnimationTraceHeader(), events = events))
    }

    private fun painted(showGrid: Boolean): Int {
        val canvas = SimulationCanvas().apply { setSize(700, 520); replay = model(); this.showGrid = showGrid }
        val img = BufferedImage(700, 520, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); canvas.paint(g); g.dispose()
        var n = 0
        for (y in 0 until 520) for (x in 0 until 700) if (img.getRGB(x, y) and 0xffffff != 0xffffff) n++
        return n
    }

    @Test
    fun `grid draws even with no replay loaded`() {
        val canvas = SimulationCanvas().apply { setSize(700, 520); showGrid = true } // replay stays null
        val img = BufferedImage(700, 520, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); canvas.paint(g); g.dispose()
        var n = 0
        for (y in 0 until 520) for (x in 0 until 700) if (img.getRGB(x, y) and 0xffffff != 0xffffff) n++
        assertTrue(n > 1000, "grid shows on an empty canvas (no layout), got $n px")
    }

    @Test
    fun `grid adds painted content and is off by default`() {
        assertTrue(!SimulationCanvas().showGrid, "grid is off by default")
        val off = painted(false)
        val on = painted(true)
        assertTrue(on > off + 2000, "the grid draws additional gridlines/labels (on=$on, off=$off)")
    }
}
