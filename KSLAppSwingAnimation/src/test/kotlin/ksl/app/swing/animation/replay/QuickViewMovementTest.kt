package ksl.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UX U3: the Replay "Quick view" auto layout shows movement — a name-resolved (DistancesModel) mover is
 * placed with ring station anchors and resolves to a position; and the compatibility check flags movers a
 * layout omits.
 */
class QuickViewMovementTest {

    private val header = AnimationTraceHeader()

    // A transporter "T" shuttling between named locations A and B (coordinates NaN, as DistancesModel emits).
    private val moveEvents = listOf(
        AnimationEvent.ReplicationStarted(0.0, 1),
        AnimationEvent.SpatialElementMoved(
            0.0, "T",
            fromX = Double.NaN, fromY = Double.NaN, toX = Double.NaN, toY = Double.NaN,
            velocity = 5.0, duration = 10.0, arrivalTime = 10.0,
            fromLocationName = "A", toLocationName = "B"
        ),
        AnimationEvent.SpatialElementMoved(
            10.0, "T",
            fromX = Double.NaN, fromY = Double.NaN, toX = Double.NaN, toY = Double.NaN,
            velocity = 5.0, duration = 10.0, arrivalTime = 20.0,
            fromLocationName = "B", toLocationName = "A"
        )
    )

    @Test
    fun `quick view places the mover and its location anchors and the mover resolves`() {
        // First build (no layout) to discover names, then the auto layout, then a resolving build.
        val probe = ReplayModel.build(AnimationSource(layout = null, header = header, events = moveEvents))
        val auto = probe.autoLayout(moveEvents)
        assertContains(auto.movableResources.map { it.name }, "T")
        assertContains(auto.locations.map { it.locationName }, "A")
        assertContains(auto.locations.map { it.locationName }, "B")

        val model = ReplayModel.build(AnimationSource(layout = auto, header = header, events = moveEvents))
        val pos = model.spatialElementPositionAt("T", 5.0) // mid first move
        assertNotNull(pos, "the mover resolves to a position against the ring locations")
        assertTrue(pos.x in 0.0..auto.width && pos.y in 0.0..auto.height, "resolved position is on-canvas: $pos")
    }

    @Test
    fun `compatibility flags a mover the layout does not place`() {
        val model = ReplayModel.build(AnimationSource(layout = null, header = header, events = moveEvents))
        // A layout that places nothing for the mover.
        val report = layoutTraceCompatibility(AnimationLayout(), model)
        assertTrue(report.animatedButUnlaid.any { it.contains("mover") && it.contains("T") },
            "unshown mover is reported: ${report.summary()}")
    }
}
