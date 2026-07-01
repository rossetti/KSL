package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutPoint
import ksl.animation.LocationLayoutElement
import ksl.animation.NetworkStationLayoutElement
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.view.SimulationCanvas
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L1 / disentanglement Phases 2–3: a name-based move resolves against LOCATIONS first (then stations, for
 * legacy layouts), so a location and a same-named station resolve independently; and a placed location renders.
 */
class LocationResolveTest {

    @Test
    fun `a name-based move resolves to a location, preferring it over a same-named station`() {
        val layout = AnimationLayout(
            stations = listOf(NetworkStationLayoutElement("B", LayoutPoint(0.0, 0.0))),   // same name as the location B
            locations = listOf(
                LocationLayoutElement("A", LayoutPoint(10.0, 10.0)),
                LocationLayoutElement("B", LayoutPoint(100.0, 20.0))
            )
        )
        val events = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.SpatialElementMoved(
                0.0, "T",
                fromX = Double.NaN, fromY = Double.NaN, toX = Double.NaN, toY = Double.NaN,
                velocity = 5.0, duration = 10.0, arrivalTime = 10.0,
                fromLocationName = "A", toLocationName = "B"
            )
        )
        val model = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))
        val pos = model.spatialElementPositionAt("T", 10.0)
        assertNotNull(pos, "the mover resolves against named positions")
        assertEquals(100.0, pos.x, 1e-9, "resolves to the LOCATION 'B' (100,20), not the same-named station (0,0)")
        assertEquals(20.0, pos.y, 1e-9)
    }

    @Test
    fun `a placed location draws a marker`() {
        val layout = AnimationLayout(
            width = 200.0, height = 100.0,
            locations = listOf(LocationLayoutElement("Depot", LayoutPoint(100.0, 50.0), label = "Depot"))
        )
        val model = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), emptyList()))
        val canvas = SimulationCanvas().apply { setSize(400, 200); replay = model; currentTime = 0.0 }
        val image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); canvas.paint(g); g.dispose()
        var nonWhite = 0
        for (y in 0 until image.height) for (x in 0 until image.width)
            if (image.getRGB(x, y) and 0xffffff != 0xffffff) nonWhite++
        assertTrue(nonWhite > 20, "a placed location draws its marker + label, got $nonWhite non-white px")
    }
}
