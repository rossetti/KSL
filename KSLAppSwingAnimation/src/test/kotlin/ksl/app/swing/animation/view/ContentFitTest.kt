package ksl.app.swing.animation.view

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.MovableResourceLayoutElement
import ksl.animation.io.AnimationSource
import ksl.animation.replay.ReplayModel
import java.awt.geom.Point2D
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regime B: a continuous-space mover whose coordinates fall outside the authored canvas is framed
 * on-screen by the content-aware world transform; content already inside the canvas is unaffected.
 */
class ContentFitTest {

    private val header = AnimationTraceHeader()

    private fun moverModel(toX: Double, toY: Double): ReplayModel {
        val events = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.SpatialElementMoved(
                0.0, "M", fromX = 0.0, fromY = 0.0, toX = toX, toY = toY,
                velocity = 10.0, duration = 10.0, arrivalTime = 10.0
            )
        )
        val layout = AnimationLayout(width = 1000.0, height = 700.0,
            movableResources = listOf(MovableResourceLayoutElement(name = "M")))
        return ReplayModel.build(AnimationSource(layout = layout, header = header, events = events))
    }

    @Test
    fun `coordinate bounds capture the real movement extent`() {
        val cb = moverModel(1400.0, 600.0).coordinateBounds()
        assertNotNull(cb, "a coordinate-based move yields bounds")
        assertTrue(cb.maxX >= 1400.0 && cb.maxY >= 600.0, "bounds include the move's far point; was $cb")
    }

    @Test
    fun `a mover beyond the canvas is framed on-screen`() {
        val canvas = SimulationCanvas().apply { setSize(900, 650); replay = moverModel(1400.0, 600.0) }
        val tx = canvas.worldTransform()
        // The far point of the move (outside the 1000x700 layout) must land within the viewport.
        val far = tx.transform(Point2D.Double(1400.0, 600.0), null)
        assertTrue(far.x in 0.0..900.0 && far.y in 0.0..650.0, "mover should be framed on-screen; was $far")
        // The layout's own corner is also still on-screen.
        val corner = tx.transform(Point2D.Double(1000.0, 700.0), null)
        assertTrue(corner.x in 0.0..900.0 && corner.y in 0.0..650.0, "layout corner stays framed; was $corner")
    }

    @Test
    fun `content within the canvas leaves framing unchanged`() {
        // Mover stays well inside the layout → union equals the layout bounds → origin not shifted.
        val canvas = SimulationCanvas().apply { setSize(900, 650); replay = moverModel(400.0, 300.0) }
        val tx = canvas.worldTransform()
        val origin = tx.transform(Point2D.Double(0.0, 0.0), null)
        // With no offset, world (0,0) maps to the top-left margin (20px) as before.
        assertTrue(kotlin.math.abs(origin.x - 20.0) < 1e-6 && kotlin.math.abs(origin.y - 20.0) < 1e-6,
            "world origin maps to the margin when content is inside the canvas; was $origin")
    }
}
