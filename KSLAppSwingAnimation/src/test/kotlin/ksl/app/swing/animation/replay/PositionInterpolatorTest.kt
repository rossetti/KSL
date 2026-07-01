package ksl.app.swing.animation.replay

import ksl.animation.AnchorRef
import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutPoint
import ksl.animation.LocationLayoutElement
import ksl.animation.PathDefinition
import ksl.app.swing.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Boundary-condition tests for [PositionInterpolator] / [MotionTrack], plus a synthetic-event check
 * that [ReplayModel] interpolates entity moves and agent position samples.
 */
class PositionInterpolatorTest {

    @Test
    fun `pointOn handles before, start, midpoint, end, after, and zero-length`() {
        val seg = MotionSegment(t0 = 0.0, t1 = 10.0, x0 = 0.0, y0 = 0.0, z0 = 0.0, x1 = 10.0, y1 = 20.0, z1 = 0.0)
        assertEquals(WorldPoint(0.0, 0.0, 0.0), PositionInterpolator.pointOn(seg, -1.0), "before start")
        assertEquals(WorldPoint(0.0, 0.0, 0.0), PositionInterpolator.pointOn(seg, 0.0), "at start")
        assertEquals(WorldPoint(5.0, 10.0, 0.0), PositionInterpolator.pointOn(seg, 5.0), "midpoint")
        assertEquals(WorldPoint(10.0, 20.0, 0.0), PositionInterpolator.pointOn(seg, 10.0), "at end")
        assertEquals(WorldPoint(10.0, 20.0, 0.0), PositionInterpolator.pointOn(seg, 99.0), "after end (held)")

        val zero = MotionSegment(5.0, 5.0, 1.0, 2.0, 0.0, 9.0, 9.0, 0.0)
        assertEquals(WorldPoint(1.0, 2.0, 0.0), PositionInterpolator.pointOn(zero, 5.0), "zero-length -> start")
    }

    @Test
    fun `motion track holds between segments and after the last`() {
        val track = MotionTrack()
        track.add(MotionSegment(0.0, 2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0)) // x: 0 -> 2 over [0,2]
        track.add(MotionSegment(5.0, 7.0, 2.0, 0.0, 0.0, 2.0, 5.0, 0.0)) // y: 0 -> 5 over [5,7]

        assertEquals(WorldPoint(0.0, 0.0, 0.0), track.positionAt(-1.0), "before first -> start")
        assertEquals(WorldPoint(1.0, 0.0, 0.0), track.positionAt(1.0), "mid first")
        assertEquals(WorldPoint(2.0, 0.0, 0.0), track.positionAt(3.5), "between segments -> held at first's end")
        assertEquals(WorldPoint(2.0, 2.5, 0.0), track.positionAt(6.0), "mid second")
        assertEquals(WorldPoint(2.0, 5.0, 0.0), track.positionAt(99.0), "after last -> held at end")
        assertNull(MotionTrack().positionAt(0.0), "empty track -> null")
    }

    @Test
    fun `replay model interpolates entity moves and agent samples`() {
        val events = listOf(
            AnimationEvent.EntityCreated(0.0, 1L, "Part"),
            AnimationEvent.MoveStarted(
                2.0, 1L, fromX = 0.0, fromY = 0.0, toX = 10.0, toY = 0.0,
                velocity = 5.0, duration = 2.0, arrivalTime = 4.0
            ),
            AnimationEvent.AgentPositionChanged(0.0, "boid", "flock", 0.0, 0.0),
            AnimationEvent.AgentPositionChanged(2.0, "boid", "flock", 4.0, 0.0),
            AnimationEvent.EntityDisposed(8.0, 1L)
        )
        val replay = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))

        // Entity 1 moves 0 -> 10 over [2,4]; halfway at t=3.
        assertEquals(0.0, replay.entityPositionAt(1L, 1.0)!!.x, 1e-9, "before move -> from")
        assertEquals(5.0, replay.entityPositionAt(1L, 3.0)!!.x, 1e-9, "mid move")
        assertEquals(10.0, replay.entityPositionAt(1L, 5.0)!!.x, 1e-9, "after arrival -> held at destination")

        // Agent samples 0 -> 4 over [0,2]; halfway at t=1.
        assertEquals(2.0, replay.agentPositionAt("boid", 1.0)!!.x, 1e-9, "agent interpolated between samples")
    }

    @Test
    fun `pointOn follows via waypoints by arc length`() {
        // A(0,0) -> B(10,0) via a bump at (5,5): the two legs are equal length, so the arc-length midpoint is the bump.
        val seg = MotionSegment(0.0, 10.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, via = listOf(WorldPoint(5.0, 5.0)))
        assertEquals(WorldPoint(0.0, 0.0, 0.0), PositionInterpolator.pointOn(seg, 0.0), "start")
        assertEquals(WorldPoint(5.0, 5.0, 0.0), PositionInterpolator.pointOn(seg, 5.0), "midpoint sits on the waypoint, off the straight line")
        assertEquals(WorldPoint(10.0, 0.0, 0.0), PositionInterpolator.pointOn(seg, 10.0), "end")
    }

    @Test
    fun `bounds includes via waypoints`() {
        val track = MotionTrack().apply { add(MotionSegment(0.0, 10.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, via = listOf(WorldPoint(5.0, 8.0)))) }
        assertEquals(8.0, track.bounds()!!.maxY, 1e-9, "the via waypoint extends the bbox above the endpoints")
    }

    @Test
    fun `a mover follows an authored functional path between locations`() {
        val layout = AnimationLayout(
            locations = listOf(LocationLayoutElement("A", LayoutPoint(0.0, 0.0)), LocationLayoutElement("B", LayoutPoint(10.0, 0.0))),
            paths = listOf(PathDefinition("p", listOf(LayoutPoint(5.0, 5.0)), from = AnchorRef.location("A"), to = AnchorRef.location("B")))
        )
        val events = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.SpatialElementMoved(
                0.0, "T", fromX = Double.NaN, fromY = Double.NaN, toX = Double.NaN, toY = Double.NaN,
                velocity = 1.0, duration = 10.0, arrivalTime = 10.0, fromLocationName = "A", toLocationName = "B"
            )
        )
        val replay = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))
        val mid = replay.spatialElementPositionAt("T", 5.0)!!
        assertEquals(5.0, mid.x, 1e-9)
        assertEquals(5.0, mid.y, 1e-9, "mid-move sits on the waypoint, off the straight y=0 line")
        val end = replay.spatialElementPositionAt("T", 10.0)!!
        assertEquals(10.0, end.x, 1e-9, "arrives at B")
        assertEquals(0.0, end.y, 1e-9)
    }

    @Test
    fun `a Cartesian move follows its named location's placed position (drag stays connected)`() {
        // The trace says the entity moves to (560,180); the layout places "Station2" elsewhere, as if the user
        // dragged it. The animation must follow the layout position, not the raw trace coordinate.
        val layout = AnimationLayout(
            locations = listOf(
                LocationLayoutElement("Enter", LayoutPoint(80.0, 380.0)),
                LocationLayoutElement("Station2", LayoutPoint(600.0, 200.0)) // moved from the trace's (560,180)
            )
        )
        val events = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.MoveStarted(
                0.0, 1L, fromX = 80.0, fromY = 380.0, toX = 560.0, toY = 180.0,
                velocity = 30.0, duration = 10.0, arrivalTime = 10.0, fromLocationName = "Enter", toLocationName = "Station2"
            )
        )
        val replay = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))
        val end = replay.entityPositionAt(1L, 10.0)!!
        assertEquals(600.0, end.x, 1e-9, "arrives at the placed location, not the trace coordinate (560)")
        assertEquals(200.0, end.y, 1e-9)
    }
}
