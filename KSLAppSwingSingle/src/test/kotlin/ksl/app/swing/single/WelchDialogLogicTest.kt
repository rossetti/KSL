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
import ksl.app.config.WelchResponseSpec
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Tests for [WelchDialogLogic] — the pure, Swing-free logic backing
 *  [WelchConfigDialog].  Headless-safe: constructs no Swing components.
 */
class WelchDialogLogicTest {

    private val probes = listOf(
        ResponseProbe("System Time", isTimeWeighted = false),
        ResponseProbe("Num in System", isTimeWeighted = true)
    )

    @Test
    @DisplayName("initialRows seed selected/interval from config and default unselected by type")
    fun initialRowsSeedSelectedFromConfigAndDefaultsByType() {
        val config = OutputConfig(
            enableWelchAnalysis = true,
            welchResponses = listOf(WelchResponseSpec("Num in System", 7.5))
        )
        val rows = WelchDialogLogic.initialRows(config, probes).associateBy { it.name }

        // Selected response carries its stored interval.
        val tw = rows.getValue("Num in System")
        assertTrue(tw.selected, "configured response must be pre-selected")
        assertEquals(7.5, tw.interval)

        // Unselected tally response defaults to 1.0; an unselected
        // time-weighted response would default to 10.0.
        val tally = rows.getValue("System Time")
        assertFalse(tally.selected, "unconfigured response must be unselected")
        assertEquals(1.0, tally.interval, "tally default interval is 1.0")
    }

    @Test
    @DisplayName("defaultInterval differs by response type")
    fun defaultIntervalDiffersByType() {
        assertEquals(1.0, WelchDialogLogic.defaultInterval(isTimeWeighted = false))
        assertEquals(10.0, WelchDialogLogic.defaultInterval(isTimeWeighted = true))
    }

    @Test
    @DisplayName("selectedSpecs collects only checked rows, preserving order")
    fun selectedSpecsCollectsOnlyCheckedRows() {
        val rows = listOf(
            WelchRowState("System Time", isTimeWeighted = false, selected = true, interval = 1.0),
            WelchRowState("Num in System", isTimeWeighted = true, selected = false, interval = 10.0),
            WelchRowState("WaitingTime", isTimeWeighted = false, selected = true, interval = 2.0)
        )
        val specs = WelchDialogLogic.selectedSpecs(rows)
        assertEquals(
            listOf(WelchResponseSpec("System Time", 1.0), WelchResponseSpec("WaitingTime", 2.0)),
            specs
        )
    }

    @Test
    @DisplayName("summary reads off when disabled or empty, else a response count")
    fun summaryReadsOffVsCount() {
        assertEquals("off", WelchDialogLogic.summary(OutputConfig()))
        assertEquals(
            "off",
            WelchDialogLogic.summary(
                OutputConfig(enableWelchAnalysis = false, welchResponses = listOf(WelchResponseSpec("X", 1.0)))
            )
        )
        assertEquals(
            "1 response",
            WelchDialogLogic.summary(
                OutputConfig(enableWelchAnalysis = true, welchResponses = listOf(WelchResponseSpec("X", 1.0)))
            )
        )
        assertEquals(
            "2 responses",
            WelchDialogLogic.summary(
                OutputConfig(
                    enableWelchAnalysis = true,
                    welchResponses = listOf(WelchResponseSpec("X", 1.0), WelchResponseSpec("Y", 10.0))
                )
            )
        )
    }
}
