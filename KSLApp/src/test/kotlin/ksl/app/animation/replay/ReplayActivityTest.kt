package ksl.app.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.app.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the entity-activity timeline (8A.2): from seize/allocate/release events, [ReplayModel]
 * reports which queue an entity waits in and which resource it is in service at, at any time.
 */
class ReplayActivityTest {

    @Test
    fun `entity activity tracks queue then service then free`() {
        val events = listOf(
            AnimationEvent.EntityCreated(0.0, 1L, "Part"),
            AnimationEvent.SeizeQueued(1.0, 1L, resourceName = "Worker", queueName = "Worker:Q", amountRequested = 1),
            AnimationEvent.SeizeAllocated(3.0, 1L, resourceName = "Worker", amountAllocated = 1),
            AnimationEvent.Released(8.0, 1L, resourceName = "Worker", amountReleased = 1),
            AnimationEvent.EntityDisposed(9.0, 1L)
        )
        val m = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))

        // Before any seize: neither queued nor in service.
        assertNull(m.entityQueueAt(1L, 0.5))
        assertNull(m.entityServiceResourceAt(1L, 0.5))

        // [1,3): waiting in the queue.
        assertEquals("Worker:Q", m.entityQueueAt(1L, 2.0))
        assertNull(m.entityServiceResourceAt(1L, 2.0))

        // [3,8): in service at the resource (not queued).
        assertEquals("Worker", m.entityServiceResourceAt(1L, 5.0))
        assertNull(m.entityQueueAt(1L, 5.0))

        // After release: free again.
        assertNull(m.entityServiceResourceAt(1L, 8.5))
        assertNull(m.entityQueueAt(1L, 8.5))
    }

    @Test
    fun `agent is present from first sight until removal`() {
        val events = listOf(
            AnimationEvent.AgentPositionChanged(2.0, "ped", "crowd", 0.0, 0.0),
            AnimationEvent.AgentPositionChanged(4.0, "ped", "crowd", 5.0, 0.0),
            AnimationEvent.AgentRemoved(6.0, "ped")
        )
        val m = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))
        assertFalse(m.agentPresentAt("ped", 1.0), "not yet spawned")
        assertTrue(m.agentPresentAt("ped", 3.0), "present while moving")
        assertFalse(m.agentPresentAt("ped", 6.0), "removed at t=6")
        assertFalse(m.agentPresentAt("ped", 7.0), "stays removed (no ghost)")
    }
}
