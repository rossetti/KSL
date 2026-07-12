package ksl.app.animation.replay

import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutPoint
import ksl.animation.NetworkStationLayoutElement
import ksl.app.animation.io.AnimationSource
import ksl.animation.AnimationEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Verifies 8I.4: a station transfer that spans time interpolates along the connector; an instantaneous one does not. */
class InterStationTransitTest {

    private val layout = AnimationLayout(
        stations = listOf(
            NetworkStationLayoutElement("A", LayoutPoint(0.0, 0.0)),
            NetworkStationLayoutElement("B", LayoutPoint(100.0, 0.0))
        )
    )

    @Test
    fun `a timed transfer slides the entity between stations (8I4)`() {
        val events = listOf(
            AnimationEvent.StationEntered(0.0, 1L, "A"),
            AnimationEvent.StationExited(10.0, 1L, "A"),
            AnimationEvent.StationEntered(20.0, 1L, "B") // 10 time units in transit A -> B
        )
        val r = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))
        // Midway through the transit (t=15): halfway along A(0,0) -> B(100,0).
        val mid = r.networkEntityTransitAt(1L, 15.0)
        assertEquals(50.0, mid!!.x, 1e-9)
        assertEquals(0.0, mid.y, 1e-9)
        // At a station (t=5, still at A) there is no transit position.
        assertNull(r.networkEntityTransitAt(1L, 5.0))
    }

    @Test
    fun `an instantaneous transfer produces no transit segment (8I4)`() {
        val events = listOf(
            AnimationEvent.StationEntered(0.0, 1L, "A"),
            AnimationEvent.StationExited(10.0, 1L, "A"),
            AnimationEvent.StationEntered(10.0, 1L, "B") // same instant -> instantaneous
        )
        val r = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))
        assertNull(r.networkEntityTransitAt(1L, 10.0), "instantaneous transfers do not animate")
    }
}
