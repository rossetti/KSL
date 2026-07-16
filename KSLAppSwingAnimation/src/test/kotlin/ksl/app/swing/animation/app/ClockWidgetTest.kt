package ksl.app.swing.animation.app

import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The clock is a first-class display widget: an opt-in element the user adds from the palette that can be
 * selected, moved, resized, and deleted on the canvas — the same as every other element. It is not
 * auto-injected by the scaffold, and it is not orphaned with no way to edit it.
 */
class ClockWidgetTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            Model("TRclock").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `addClock then move, resize, and remove operate on the clocks collection`() {
        val c = AnimationAppController("Anim", builder)
        try {
            c.newBlankLayout()
            c.addClock(40.0, 50.0, label = "Sim time", format = "0.00", fontSize = 18.0)
            val placed = c.layout.value!!.clocks.single()
            assertEquals("Sim time", placed.label)
            assertEquals(18.0, placed.fontSize)
            assertEquals(40.0, placed.position.x); assertEquals(50.0, placed.position.y)

            // Move by a delta.
            c.moveClockAt(0, 5.0, -7.0)
            val moved = c.layout.value!!.clocks.single()
            assertEquals(45.0, moved.position.x); assertEquals(43.0, moved.position.y)

            // Resize (font size) via the editor path.
            c.setClockAt(0, moved.copy(fontSize = 30.0))
            assertEquals(30.0, c.layout.value!!.clocks.single().fontSize)

            // Remove.
            c.removeClockAt(0)
            assertTrue(c.layout.value!!.clocks.isEmpty(), "the clock can be removed")
        } finally {
            c.close()
        }
    }

    @Test
    fun `a placed clock can be hit-tested, selected, and deleted on the canvas`() {
        val c = AnimationAppController("Anim", builder)
        try {
            val result = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                c.addClock(100.0, 100.0, label = "Time", fontSize = 14.0)
                panel.refreshForTest()
                val hit = panel.pickClockAtWorldForTest(102.0, 99.0) // hit-test near the anchor
                val outlined = panel.selectClockForTest(0)           // selecting shows the outline
                panel.removeSelectedForTest()                        // Delete-key path
                Triple(hit, outlined, panel.selectedClockIndexForTest())
            }
            assertNotNull(result.first, "the clock is hit-testable at its anchor")
            assertEquals(0, result.first)
            assertTrue(result.second, "selecting the clock shows its outline")
            assertNull(result.third, "the selection clears after delete")
            assertTrue(c.layout.value!!.clocks.isEmpty(), "Delete removes the selected clock")
        } finally {
            c.close()
        }
    }

    @Test
    fun `scaffold no longer auto-places a clock`() {
        val c = AnimationAppController("Anim", builder)
        try {
            c.scaffoldLayout()
            assertTrue(c.layout.value!!.clocks.isEmpty(), "the clock is opt-in; the scaffold must not inject one")
        } finally {
            c.close()
        }
    }
}
