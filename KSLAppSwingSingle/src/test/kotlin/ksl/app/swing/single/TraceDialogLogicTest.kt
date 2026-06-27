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

package ksl.app.swing.single

import ksl.app.config.OutputConfig
import ksl.app.config.TraceResponseSpec
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Tests for [TraceDialogLogic] — the pure, Swing-free logic backing
 *  [TraceConfigDialog].  Headless-safe: constructs no Swing components.
 */
class TraceDialogLogicTest {

    private val probes = listOf(
        ResponseProbe("System Time", isTimeWeighted = false),
        ResponseProbe("Num in System", isTimeWeighted = true)
    )

    @Test
    @DisplayName("initialRows seed selected/maxReps from config and default unselected rows to 1")
    fun initialRowsSeedSelectedFromConfigAndDefaultUnselected() {
        val config = OutputConfig(
            enableResponseTrace = true,
            traceResponses = listOf(TraceResponseSpec("Num in System", maxReplications = 3))
        )
        val rows = TraceDialogLogic.initialRows(config, probes).associateBy { it.name }

        val configured = rows.getValue("Num in System")
        assertTrue(configured.selected, "configured response must be pre-selected")
        assertEquals(3, configured.maxReplications)

        val unconfigured = rows.getValue("System Time")
        assertFalse(unconfigured.selected, "unconfigured response must be unselected")
        assertEquals(1, unconfigured.maxReplications, "default cap is 1")
    }

    @Test
    @DisplayName("selectedSpecs collects only checked rows, preserving order")
    fun selectedSpecsCollectsOnlyCheckedRows() {
        val rows = listOf(
            TraceRowState("System Time", isTimeWeighted = false, selected = true, maxReplications = 1),
            TraceRowState("Num in System", isTimeWeighted = true, selected = false, maxReplications = 3),
            TraceRowState("WaitingTime", isTimeWeighted = false, selected = true, maxReplications = 2)
        )
        assertEquals(
            listOf(TraceResponseSpec("System Time", 1), TraceResponseSpec("WaitingTime", 2)),
            TraceDialogLogic.selectedSpecs(rows)
        )
    }

    @Test
    @DisplayName("summary reads off when disabled or empty, else a response count")
    fun summaryReadsOffVsCount() {
        assertEquals("off", TraceDialogLogic.summary(OutputConfig()))
        assertEquals(
            "off",
            TraceDialogLogic.summary(
                OutputConfig(enableResponseTrace = false, traceResponses = listOf(TraceResponseSpec("X", 1)))
            )
        )
        assertEquals(
            "1 response",
            TraceDialogLogic.summary(
                OutputConfig(enableResponseTrace = true, traceResponses = listOf(TraceResponseSpec("X", 1)))
            )
        )
        assertEquals(
            "2 responses",
            TraceDialogLogic.summary(
                OutputConfig(
                    enableResponseTrace = true,
                    traceResponses = listOf(TraceResponseSpec("X", 1), TraceResponseSpec("Y", 2))
                )
            )
        )
    }
}
