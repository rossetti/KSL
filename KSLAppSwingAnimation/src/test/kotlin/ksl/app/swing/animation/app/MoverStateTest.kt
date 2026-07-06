package ksl.app.swing.animation.app

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.MoverMode
import ksl.animation.io.AnimationSource
import ksl.animation.replay.ReplayModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P6a (C2): ReplayModel tracks a movable/transport resource's mode and carried entity over time, so the
 * renderer can style it and draw the carried entity while it is TRANSPORTING.
 */
class MoverStateTest {

    @Test
    fun `replay tracks mover mode and carried entity over time`() {
        val events = listOf(
            AnimationEvent.EntityCreated(0.0, 7L, "Part"),
            AnimationEvent.SpatialElementMoved(
                1.0, "Truck", 0.0, 0.0, 0.0, 100.0, 0.0, 0.0,
                velocity = 10.0, duration = 10.0, arrivalTime = 11.0,
                mode = MoverMode.TRANSPORTING, carriedEntityId = 7L, carriedEntityType = "Part"
            ),
            AnimationEvent.SpatialElementMoveCompleted(11.0, "Truck", 100.0, 0.0, 0.0)
        )
        val m = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))

        val during = m.moverStateAt("Truck", 5.0)
        assertEquals(MoverMode.TRANSPORTING, during?.mode, "carrying while the transport move is in progress")
        assertEquals(7L, during?.carriedEntityId)
        assertEquals("Part", during?.carriedEntityType)

        val after = m.moverStateAt("Truck", 11.5)
        assertEquals(MoverMode.EMPTY, after?.mode, "at rest after the move completes")
        assertEquals(null, after?.carriedEntityId, "no longer carrying after drop-off")
    }
}
