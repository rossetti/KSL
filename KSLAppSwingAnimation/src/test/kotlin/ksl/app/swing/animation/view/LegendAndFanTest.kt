package ksl.app.swing.animation.view

import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutShape
import ksl.animation.ObjectClassDefinition
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import java.awt.image.BufferedImage
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies 8I.3a (legend) and 8I.3b (fan-out of co-located agents). */
class LegendAndFanTest {

    private fun renderTopRightNonWhite(showLegend: Boolean): Int {
        val layout = AnimationLayout(
            title = "Legend", width = 300.0, height = 100.0,
            objectClasses = listOf(
                ObjectClassDefinition("Customer", LayoutShape.CIRCLE, color = "#1f77b4", size = 12.0),
                ObjectClassDefinition("Part", LayoutShape.TRIANGLE, color = "#d62728", size = 12.0)
            ),
            agentStateColors = mapOf("Working" to "#2ca02c")
        )
        val model = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), emptyList()))
        val canvas = SimulationCanvas()
        canvas.setSize(600, 200)
        canvas.replay = model
        canvas.showLegend = showLegend
        val image = BufferedImage(600, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); canvas.paint(g); g.dispose()
        // Count non-white pixels in the top-right region where the legend is drawn.
        var n = 0
        for (y in 0 until 140) for (x in 450 until 600)
            if (image.getRGB(x, y) and 0xffffff != 0xffffff) n++
        return n
    }

    @Test
    fun `legend draws in the top-right and the View toggle hides it (8I3a)`() {
        val withLegend = renderTopRightNonWhite(showLegend = true)
        val without = renderTopRightNonWhite(showLegend = false)
        assertTrue(withLegend > 50, "legend should paint swatches/text/border, got $withLegend px")
        assertTrue(without < withLegend, "toggling the legend off should remove its pixels ($without vs $withLegend)")
    }

    @Test
    fun `fanRingOffset spreads co-located agents and leaves a lone agent put (8I3b)`() {
        assertEquals(0.0 to 0.0, SimulationCanvas.fanRingOffset(0, 1, 10.0), "a single agent is not moved")

        val count = 5
        val radius = 10.0
        val offsets = (0 until count).map { SimulationCanvas.fanRingOffset(it, count, radius) }
        // All on the ring (distance == radius) and pairwise distinct.
        for ((ox, oy) in offsets) assertEquals(radius, hypot(ox, oy), 1e-9)
        assertEquals(count, offsets.toSet().size, "each co-located agent gets a distinct offset")
    }
}
