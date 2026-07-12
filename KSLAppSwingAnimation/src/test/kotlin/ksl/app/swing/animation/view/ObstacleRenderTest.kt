package ksl.app.swing.animation.view

import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.SpatialSpaceDescriptor
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import ksl.modeling.agent.Cell
import ksl.modeling.agent.GridGeometrySpec
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/** P5a/G2: a grid obstacle overlay (model-extracted blocked cells) paints filled cells over its space. */
class ObstacleRenderTest {

    @Test
    fun `obstacle overlay paints blocked cells over a grid space`() {
        val layout = AnimationLayout(
            title = "Obstacles", width = 200.0, height = 200.0,
            spaces = listOf(SpatialSpaceDescriptor.Grid("floor", cols = 10, rows = 10, cellSize = 20.0)),
            spaceGeometry = listOf(
                GridGeometrySpec("floor", cols = 10, rows = 10, blockedCells = listOf(Cell(4, 4), Cell(4, 5), Cell(5, 4)))
            )
        )
        val model = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), emptyList()))
        val canvas = SimulationCanvas()
        canvas.setSize(400, 400)
        canvas.replay = model
        canvas.currentTime = 0.0
        val image = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); canvas.paint(g); g.dispose()

        // The obstacle fill is dark (#444 over a light background): expect a cluster of dark pixels.
        var darkPx = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            val rr = (rgb shr 16) and 0xff; val gg = (rgb shr 8) and 0xff; val bb = rgb and 0xff
            if (rr < 0xb0 && gg < 0xb0 && bb < 0xb0) darkPx++
        }
        assertTrue(darkPx > 50, "expected the obstacle overlay to draw filled dark cells, got $darkPx dark px")
    }
}
