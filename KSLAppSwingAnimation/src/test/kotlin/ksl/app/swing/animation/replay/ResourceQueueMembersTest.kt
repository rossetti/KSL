package ksl.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies 8I.1b: a resource queue's members render as entity ids (typed), not the RequestQ's ids. */
class ResourceQueueMembersTest {

    @Test
    fun `resource-queue members come from the seize stream and shadow QObjectEnqueued (8I1b)`() {
        val events = listOf(
            AnimationEvent.EntityCreated(0.0, 1L, "Part"),
            AnimationEvent.EntityCreated(0.0, 2L, "Part"),
            // The seize stream carries the entity ids waiting in the resource's RequestQ.
            AnimationEvent.SeizeQueued(1.0, 1L, "W", "W:Q", amountRequested = 1),
            AnimationEvent.SeizeQueued(2.0, 2L, "W", "W:Q", amountRequested = 1),
            // QObjectEnqueued for the same queue would carry the Request's id (here a bogus 99) — it must
            // be shadowed by the seize-stream membership.
            AnimationEvent.QObjectEnqueued(2.0, 99L, "W:Q"),
            AnimationEvent.SeizeAllocated(4.0, 1L, "W", amountAllocated = 1),
            AnimationEvent.Released(6.0, 1L, "W", amountReleased = 1)
        )
        val r = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))

        // Both parts waiting (t=3): typed entity ids in arrival order, not the Request id 99.
        assertEquals(listOf(1L, 2L), r.queueMembersAt("W:Q", 3.0))
        assertEquals("Part", r.entityTypeOf(r.queueMembersAt("W:Q", 3.0).first()))

        // After entity 1 is allocated (t=5): only entity 2 remains in the line.
        assertEquals(listOf(2L), r.queueMembersAt("W:Q", 5.0))
    }
}
