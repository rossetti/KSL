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

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Tests for [WelchReportDialogLogic] — the pure, Swing-free logic backing
 *  [WelchReportDialog].  Headless-safe: constructs no Swing components.
 */
class WelchReportDialogLogicTest {

    @Test
    @DisplayName("defaultStem sanitizes the analysis name and appends _Welch")
    fun defaultStemSanitizesAndSuffixes() {
        assertEquals(
            "Queue_Study_Welch",
            WelchReportDialogLogic.defaultStem("Queue Study", appName = "App")
        )
    }

    @Test
    @DisplayName("defaultStem falls back to the app name for blank or Untitled analysis names")
    fun defaultStemFallsBackToAppName() {
        assertEquals("MyApp_Welch", WelchReportDialogLogic.defaultStem("", appName = "MyApp"))
        assertEquals("MyApp_Welch", WelchReportDialogLogic.defaultStem("Untitled", appName = "MyApp"))
    }

    @Test
    @DisplayName("foundLabel reports the response count and names, or an empty-state line")
    fun foundLabelReportsCountAndNames() {
        assertEquals("No Welch data found for this run.", WelchReportDialogLogic.foundLabel(emptyList()))
        assertEquals(
            "Found Welch data for 1 response: System Time",
            WelchReportDialogLogic.foundLabel(listOf("System Time"))
        )
        assertEquals(
            "Found Welch data for 2 responses: System Time, Num in System",
            WelchReportDialogLogic.foundLabel(listOf("System Time", "Num in System"))
        )
    }

    @Test
    @DisplayName("saveEnabled requires data, at least one format, and a stem")
    fun saveEnabledRequiresAllThree() {
        assertTrue(WelchReportDialogLogic.saveEnabled(hasAnalyzers = true, anyFormat = true, stemOk = true))
        assertFalse(WelchReportDialogLogic.saveEnabled(hasAnalyzers = false, anyFormat = true, stemOk = true))
        assertFalse(WelchReportDialogLogic.saveEnabled(hasAnalyzers = true, anyFormat = false, stemOk = true))
        assertFalse(WelchReportDialogLogic.saveEnabled(hasAnalyzers = true, anyFormat = true, stemOk = false))
    }
}
