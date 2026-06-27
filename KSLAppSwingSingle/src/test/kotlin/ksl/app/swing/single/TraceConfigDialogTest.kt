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

import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Headless-guarded smoke test for [TraceConfigDialog]'s Swing shell.
 *
 *  Constructing a `JDialog` (and its layout) needs a display, so each test
 *  skips on a headless JVM via [assumeFalse].  The dialog's pure logic is
 *  covered headless-safely by [TraceDialogLogicTest]; this test exercises only
 *  the widget → controller seam: constructing the dialog seeds from config and
 *  OK pushes the widget state through [SingleAppController.applyTraceConfig].
 */
class TraceConfigDialogTest {

    private var controller: SingleAppController? = null

    @AfterTest
    fun closeController() {
        controller?.close()
        controller = null
    }

    private val responsefulBuilder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val m = Model("TraceDialogModel", autoCSVReports = false)
            Response(m, name = "System Time")
            TWResponse(m, name = "Num in System")
            return m
        }
    }

    private fun freshController(): SingleAppController {
        val c = SingleAppController("TraceDialogApp", responsefulBuilder)
        controller = c
        return c
    }

    @Test
    @DisplayName("OK pushes the selected widget state through applyTraceConfig")
    fun okPushesSelectionThroughApplyTraceConfig() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "JDialog construction requires a display")
        val c = freshController()
        val dialog = TraceConfigDialogImpl(c, owner = null)

        dialog.masterCheckBox.isSelected = true
        dialog.setCaptured(0, true)
        dialog.onOk()

        val oc = c.outputConfig.value
        assertTrue(oc.enableResponseTrace, "OK must enable trace capture")
        assertEquals(1, oc.traceResponses.size, "exactly the one checked response is captured")
        assertEquals(c.responseSnapshot[0].name, oc.traceResponses.single().responseName)
        assertTrue(c.isDirty.value, "applying a change must flip dirty")
    }

    @Test
    @DisplayName("Dialog seeds its widgets from the controller's current config")
    fun dialogSeedsWidgetsFromConfig() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "JDialog construction requires a display")
        val c = freshController()
        c.applyTraceConfig(
            enableResponseTrace = true,
            traceResponses = listOf(ksl.app.config.TraceResponseSpec(c.responseSnapshot[0].name, 1))
        )
        val dialog = TraceConfigDialogImpl(c, owner = null)
        assertTrue(dialog.masterCheckBox.isSelected, "master toggle seeds from enableResponseTrace")
        assertTrue(dialog.isCaptured(0), "configured response row seeds selected")
        assertFalse(dialog.isCaptured(1), "unconfigured response row seeds unselected")
    }
}
