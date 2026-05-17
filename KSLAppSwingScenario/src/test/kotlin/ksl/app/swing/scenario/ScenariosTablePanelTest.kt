/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.swing.scenario

import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.ScenarioSpec
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Phase E tests for [ScenariosTablePanel].  Construct the panel on
 *  the EDT (the panel registers Swing-dispatched collectors and Swing
 *  components expect EDT construction) and exercise the table-model
 *  surface through controller mutations.  A small `flushEdt()` helper
 *  drains pending Swing tasks so the StateFlow collectors have run
 *  before assertions.
 */
class ScenariosTablePanelTest {

    private var controller: ScenarioAppController? = null
    private var panel: ScenariosTablePanel? = null

    @AfterTest
    fun closeController() {
        controller?.close()
        controller = null
        panel = null
    }

    private fun setup(): Pair<ScenarioAppController, ScenariosTablePanel> {
        var c: ScenarioAppController? = null
        var p: ScenariosTablePanel? = null
        SwingUtilities.invokeAndWait {
            val ctl = ScenarioAppController("Table Panel Test")
            c = ctl
            p = ScenariosTablePanel(ctl)
        }
        flushEdt()
        controller = c
        panel = p
        return c!! to p!!
    }

    /** Drain pending Swing tasks so Swing-dispatched coroutine
     *  collectors fire before the test thread observes the table. */
    private fun flushEdt() {
        repeat(3) { SwingUtilities.invokeAndWait { /* drain */ } }
    }

    private fun spec(
        name: String,
        modelName: String = "mm1",
        reps: Int? = null
    ): ScenarioSpec = ScenarioSpec(
        name = name,
        modelReference = ModelReference.Embedded(modelName),
        runOverrides = reps?.let { ExperimentRunOverrides(numberOfReplications = it) }
    )

    @Test
    fun `table reflects current scenarios after add`() {
        val (ctl, pnl) = setup()
        assertEquals(0, pnl.table.rowCount)

        SwingUtilities.invokeAndWait { ctl.addScenario(spec("Alpha")) }
        flushEdt()
        assertEquals(1, pnl.table.rowCount)
        assertEquals("Alpha", pnl.table.getValueAt(0, ScenariosTablePanel.COL_NAME))
        assertEquals("embedded: mm1", pnl.table.getValueAt(0, ScenariosTablePanel.COL_MODEL))
        assertEquals(false, pnl.table.getValueAt(0, ScenariosTablePanel.COL_SKIP))
        assertEquals("(model defaults)", pnl.table.getValueAt(0, ScenariosTablePanel.COL_PARAMS))
    }

    @Test
    fun `run params column summarises overrides`() {
        val (ctl, pnl) = setup()
        SwingUtilities.invokeAndWait { ctl.addScenario(spec("Beta", reps = 50)) }
        flushEdt()
        val params = pnl.table.getValueAt(0, ScenariosTablePanel.COL_PARAMS) as String
        assertTrue(params.contains("50 reps"), "expected '50 reps' in '$params'")
    }

    @Test
    fun `toggling skip cell calls controller setSkipOnRun`() {
        val (ctl, pnl) = setup()
        SwingUtilities.invokeAndWait { ctl.addScenario(spec("Gamma")) }
        flushEdt()
        assertFalse(ctl.scenarios.value[0].skipOnRun)
        SwingUtilities.invokeAndWait {
            pnl.table.setValueAt(true, 0, ScenariosTablePanel.COL_SKIP)
        }
        flushEdt()
        assertTrue(ctl.scenarios.value[0].skipOnRun)
        assertEquals(true, pnl.table.getValueAt(0, ScenariosTablePanel.COL_SKIP))
    }

    @Test
    fun `table row selection mirrors controller selectedIndex`() {
        val (ctl, pnl) = setup()
        SwingUtilities.invokeAndWait {
            ctl.addScenario(spec("S1"))
            ctl.addScenario(spec("S2"))
            ctl.addScenario(spec("S3"))
        }
        flushEdt()

        SwingUtilities.invokeAndWait { ctl.setSelectedIndex(2) }
        flushEdt()
        assertEquals(2, pnl.table.selectedRow)

        SwingUtilities.invokeAndWait { ctl.setSelectedIndex(-1) }
        flushEdt()
        assertEquals(-1, pnl.table.selectedRow)
    }

    @Test
    fun `selecting row from table updates controller selectedIndex`() {
        val (ctl, pnl) = setup()
        SwingUtilities.invokeAndWait {
            ctl.addScenario(spec("S1"))
            ctl.addScenario(spec("S2"))
        }
        flushEdt()
        SwingUtilities.invokeAndWait { pnl.table.setRowSelectionInterval(1, 1) }
        flushEdt()
        assertEquals(1, ctl.selectedIndex.value)
    }

    @Test
    fun `table reflects delete and reorder`() {
        val (ctl, pnl) = setup()
        SwingUtilities.invokeAndWait {
            ctl.addScenario(spec("A"))
            ctl.addScenario(spec("B"))
            ctl.addScenario(spec("C"))
        }
        flushEdt()
        assertEquals(3, pnl.table.rowCount)

        SwingUtilities.invokeAndWait { ctl.deleteScenario(1) }
        flushEdt()
        assertEquals(2, pnl.table.rowCount)
        assertEquals("A", pnl.table.getValueAt(0, ScenariosTablePanel.COL_NAME))
        assertEquals("C", pnl.table.getValueAt(1, ScenariosTablePanel.COL_NAME))

        SwingUtilities.invokeAndWait { ctl.moveScenarioDown(0) }
        flushEdt()
        assertEquals("C", pnl.table.getValueAt(0, ScenariosTablePanel.COL_NAME))
        assertEquals("A", pnl.table.getValueAt(1, ScenariosTablePanel.COL_NAME))
    }
}
