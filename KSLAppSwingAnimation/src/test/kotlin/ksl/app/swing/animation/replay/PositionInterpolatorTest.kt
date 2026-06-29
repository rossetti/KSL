package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
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
}
