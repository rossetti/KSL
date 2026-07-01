package ksl.app.swing.animation.app

import ksl.animation.ElementKind
import ksl.animation.animationInventory
import ksl.animation.scaffoldLayout
import ksl.examples.general.animationbundle.Example08ConveyorTandem
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 10.5a (§6.7): the inventory exposes each conveyor's structure pre-run (cell size, accumulating, and its
 * ordered, chained entry→exit segments) so the editor can route the belt against the locations it connects.
 */
class ConveyorInventoryTest {

    @Test
    fun `conveyor inventory carries cell size and chained segments`() {
        val inv = Example08ConveyorTandem.buildModel().animationInventory()
        val info = inv.conveyorInfos.firstOrNull { it.name == "Conveyor" }
        assertNotNull(info, "the conveyor's structure is in the inventory: ${inv.conveyorInfos.map { it.name }}")
        assertTrue(info.cellSize > 0, "cell size read from the built conveyor")
        assertTrue(info.segments.isNotEmpty(), "at least one chained segment")
        info.segments.forEach {
            assertTrue(it.entryLocation.isNotEmpty() && it.exitLocation.isNotEmpty(), "segment names its anchors")
            assertTrue(it.lengthCells >= 2, "each segment has >= 2 cells, got ${it.lengthCells}")
        }
        // Chained: each segment's exit is the next segment's entry.
        info.segments.zipWithNext().forEach { (a, b) ->
            assertTrue(a.exitLocation == b.entryLocation, "segments chain: ${a.exitLocation} -> ${b.entryLocation}")
        }
    }

    @Test
    fun `conveyor internal queues are not auto-placed but stay editor-placeable (F1)`() {
        val m = Example08ConveyorTandem.buildModel()
        val suffixes = listOf(":ExitingHoldQ", ":RidingHoldQ", ":AccessingHoldQ", ":AccessQ")
        // Opt-in preserved: the conveyor's hold/access queues are still classified as placeable QUEUEs.
        val queueNames = m.animationInventory().namesOf(ElementKind.QUEUE)
        assertTrue(queueNames.any { qn -> suffixes.any { qn.endsWith(it) } },
            "conveyor queues remain editor-placeable: $queueNames")
        // But the scaffold does NOT auto-place them (they animate as part of the belt).
        val scaffold = m.scaffoldLayout()
        assertTrue(scaffold.queues.none { q -> suffixes.any { q.queueName.endsWith(it) } },
            "no conveyor internal queue auto-placed: ${scaffold.queues.map { it.queueName }}")
    }
}
