package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.app.swing.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies 8K.4 storage membership: delays key by suspensionName, else by entity type, with progress. */
class StorageReplayTest {

    private fun build(): ReplayModel {
        val events = listOf(
            AnimationEvent.EntityCreated(0.0, 1L, "Part"),
            AnimationEvent.EntityCreated(0.0, 2L, "Widget"),
            // Entity 1: a named delay "inspect" from t=1 to t=11.
            AnimationEvent.DelayStarted(1.0, 1L, duration = 10.0, arrivalTime = 11.0, suspensionName = "inspect"),
            // Entity 2: an UNNAMED delay -> keyed by its type "Widget".
            AnimationEvent.DelayStarted(1.0, 2L, duration = 10.0, arrivalTime = 11.0, suspensionName = null),
            AnimationEvent.DelayEnded(11.0, 1L, suspensionName = "inspect"),
            AnimationEvent.DelayEnded(11.0, 2L, suspensionName = null)
        )
        return ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))
    }

    @Test
    fun `delays populate storages keyed by name or type, and clear when they end (8K4)`() {
        val r = build()

        // Mid-delay (t=5): each entity is in its storage.
        assertEquals(listOf(1L), r.storageMembersAt("inspect", 5.0).map { it.entityId })
        assertEquals(listOf(2L), r.storageMembersAt("Widget", 5.0).map { it.entityId }, "unnamed delay keyed by type")
        assertEquals("inspect", r.entityStorageAt(1L, 5.0))
        assertEquals("Widget", r.entityStorageAt(2L, 5.0))

        // Progress window is carried for the belt: entity 1 delays over [1, 11].
        val m = r.storageMembersAt("inspect", 6.0).single()
        assertEquals(1.0, m.startTime, 1e-9)
        assertEquals(11.0, m.arrivalTime, 1e-9) // (6-1)/(11-1) = 0.5 progress

        // After the delays end (t=12): storages are empty and the entity is no longer in any.
        assertTrue(r.storageMembersAt("inspect", 12.0).isEmpty())
        assertTrue(r.storageMembersAt("Widget", 12.0).isEmpty())
        assertNull(r.entityStorageAt(1L, 12.0))

        // Before the delay starts (t=0): not yet in a storage.
        assertTrue(r.storageMembersAt("inspect", 0.0).isEmpty())
    }
}
