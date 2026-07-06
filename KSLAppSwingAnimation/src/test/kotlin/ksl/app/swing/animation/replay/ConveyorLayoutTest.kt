package ksl.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Recovery B2: a conveyor trace lays its anchors out as a straight belt spaced by cell index (not a ring) and
 * synthesizes a ConveyorLayoutElement, so the belt resolves, draws straight, and shows a travel direction.
 */
class ConveyorLayoutTest {

    private val events = listOf(
        AnimationEvent.ReplicationStarted(0.0, 1),
        AnimationEvent.ConveyorDefined(0.0, "Belt", anchorLocations = listOf("In", "Mid", "Out"), anchorCells = listOf(0, 5, 10))
    )

    @Test
    fun `conveyor anchors are collinear, spaced by cell index, and a belt element is synthesized`() {
        val layout = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events)).autoLayout(events)
        val inS = layout.locations.first { it.locationName == "In" }
        val midS = layout.locations.first { it.locationName == "Mid" }
        val outS = layout.locations.first { it.locationName == "Out" }
        // Same lane (collinear) and x increasing by cell index.
        assertEquals(inS.position!!.y, midS.position!!.y, 1e-9)
        assertEquals(inS.position!!.y, outS.position!!.y, 1e-9)
        assertTrue(inS.position!!.x < midS.position!!.x && midS.position!!.x < outS.position!!.x)
        // Mid (cell 5 of 10) sits halfway between the endpoints.
        assertEquals((inS.position!!.x + outS.position!!.x) / 2.0, midS.position!!.x, 1e-6)
        // A belt element is synthesized so the renderer colors it and draws direction arrows.
        assertContains(layout.conveyors.map { it.conveyorName }, "Belt")
        assertTrue(layout.conveyors.first { it.conveyorName == "Belt" }.showDirection)
    }

    @Test
    fun `the synthesized layout resolves the belt's cells on-canvas`() {
        val auto = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events)).autoLayout(events)
        val model = ReplayModel.build(AnimationSource(layout = auto, header = AnimationTraceHeader(), events = events))
        assertContains(model.conveyorNames, "Belt")
        val mid = model.conveyorCellPosition("Belt", 5)
        assertNotNull(mid, "cell 5 resolves against the placed anchor stations")
        assertTrue(mid.x in 0.0..auto.width && mid.y in 0.0..auto.height, "belt cell is on-canvas: $mid")
    }
}
