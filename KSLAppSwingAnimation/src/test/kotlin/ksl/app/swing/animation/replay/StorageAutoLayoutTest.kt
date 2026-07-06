package ksl.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Recovery D1: auto-layout places a storage per named delay and per entity-type with bare-delay activity, so
 * delay-dominated models render their delays without hand-authoring — but NOT for a seize-delay-release service
 * phase, whose entities are already drawn inside the resource glyph (the guard).
 */
class StorageAutoLayoutTest {

    private fun autoLayoutOf(events: List<AnimationEvent>) =
        ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events)).autoLayout(events)

    @Test
    fun `a named delay is auto-placed as a storage keyed by its name`() {
        val events = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.EntityCreated(0.0, 1L, "Part"),
            AnimationEvent.DelayStarted(0.0, 1L, duration = 5.0, arrivalTime = 5.0, suspensionName = "oven"),
            AnimationEvent.DelayEnded(5.0, 1L, suspensionName = "oven")
        )
        assertContains(autoLayoutOf(events).storages.map { it.suspensionName }, "oven")
    }

    @Test
    fun `a bare delay on an unseized type is auto-placed as a type storage`() {
        val events = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.EntityCreated(0.0, 1L, "Student"),
            AnimationEvent.DelayStarted(0.0, 1L, duration = 5.0, arrivalTime = 5.0),
            AnimationEvent.DelayEnded(5.0, 1L)
        )
        assertContains(autoLayoutOf(events).storages.map { it.suspensionName }, "Student")
    }

    @Test
    fun `bare-delay storages are skipped in an agent model, but named delays remain`() {
        val events = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.EntityCreated(0.0, 1L, "Pedestrian"),
            AnimationEvent.AgentPositionChanged(0.0, "p1", "space", 1.0, 1.0),
            AnimationEvent.DelayStarted(0.0, 1L, duration = 1.0, arrivalTime = 1.0), // bare → would be a "Pedestrian" storage
            AnimationEvent.DelayStarted(0.0, 1L, duration = 1.0, arrivalTime = 1.0, suspensionName = "rest"),
            AnimationEvent.DelayEnded(1.0, 1L)
        )
        val storages = autoLayoutOf(events).storages.map { it.suspensionName }
        assertTrue(storages.none { it == "Pedestrian" }, "no by-type storage in an agent model: $storages")
        assertContains(storages, "rest", "named delays are still placed even with agents")
    }

    @Test
    fun `a seize-delay-release service phase is not auto-placed (guard against double-drawing)`() {
        val events = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.EntityCreated(0.0, 1L, "Customer"),
            AnimationEvent.SeizeAllocated(0.0, 1L, "Server", 1),
            AnimationEvent.DelayStarted(0.0, 1L, duration = 5.0, arrivalTime = 5.0), // the service delay (bare)
            AnimationEvent.DelayEnded(5.0, 1L)
        )
        assertTrue(
            autoLayoutOf(events).storages.none { it.suspensionName == "Customer" },
            "in-service entities are shown in the resource, not duplicated as a type storage"
        )
    }
}
