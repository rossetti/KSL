package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.app.swing.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V5a: the Replay Quick view places a conveyor's anchor locations as station anchors (from
 * `ConveyorDefined`), so the belt resolves on-canvas without an authored layout — the conveyor analogue
 * of the U3 movement work.
 */
class QuickViewConveyorTest {

    private val header = AnimationTraceHeader()

    private val events = listOf(
        AnimationEvent.ReplicationStarted(0.0, 1),
        AnimationEvent.ConveyorDefined(0.0, "Belt", anchorLocations = listOf("In", "Mid", "Out"), anchorCells = listOf(0, 5, 10))
    )

    @Test
    fun `quick view places conveyor anchor stations and the belt resolves`() {
        val probe = ReplayModel.build(AnimationSource(layout = null, header = header, events = events))
        val auto = probe.autoLayout(events)
        listOf("In", "Mid", "Out").forEach { assertContains(auto.locations.map { s -> s.locationName }, it) }

        val model = ReplayModel.build(AnimationSource(layout = auto, header = header, events = events))
        assertContains(model.conveyorNames, "Belt")
        val p = model.conveyorCellPosition("Belt", 5) // the "Mid" anchor cell
        assertNotNull(p, "conveyor cell resolves against the placed anchor stations")
        assertTrue(p.x in 0.0..auto.width && p.y in 0.0..auto.height, "belt cell is on-canvas: $p")
    }
}
