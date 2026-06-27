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
 *  Headless-guarded smoke test for [WelchConfigDialog]'s Swing shell.
 *
 *  Constructing a `JDialog` (and its `pack()` / layout) needs a display,
 *  so each test skips on a headless JVM via [assumeFalse].  The dialog's
 *  pure logic is covered separately and headless-safely by
 *  [WelchDialogLogicTest]; this test exercises only the widget → controller
 *  seam: that constructing the dialog seeds from the controller's config
 *  and that OK pushes the widget state through
 *  [SingleAppController.applyWelchConfig].
 */
class WelchConfigDialogTest {

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
            val m = Model("WelchDialogModel", autoCSVReports = false)
            Response(m, name = "System Time")
            TWResponse(m, name = "Num in System")
            return m
        }
    }

    private fun freshController(): SingleAppController {
        val c = SingleAppController("WelchDialogApp", responsefulBuilder)
        controller = c
        return c
    }

    @Test
    @DisplayName("OK pushes the selected widget state through applyWelchConfig")
    fun okPushesSelectionThroughApplyWelchConfig() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "JDialog construction requires a display")
        val c = freshController()
        val dialog = WelchConfigDialogImpl(c, owner = null)

        // Simulate the user enabling capture and selecting the first response.
        dialog.masterCheckBox.isSelected = true
        dialog.rowChecks[0].isSelected = true
        dialog.autoRenderCheck.isSelected = true
        dialog.onOk()

        val oc = c.outputConfig.value
        assertTrue(oc.enableWelchAnalysis, "OK must enable Welch capture")
        assertEquals(1, oc.welchResponses.size, "exactly the one checked response is captured")
        assertEquals(c.responseSnapshot[0].name, oc.welchResponses.single().responseName)
        assertTrue(oc.welchAutoRender, "auto-render checkbox must propagate")
        assertTrue(c.isDirty.value, "applying a change must flip dirty")
    }

    @Test
    @DisplayName("Dialog seeds its widgets from the controller's current config")
    fun dialogSeedsWidgetsFromConfig() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "JDialog construction requires a display")
        val c = freshController()
        // Pre-apply a Welch selection so the dialog has something to seed from.
        c.applyWelchConfig(
            enableWelchAnalysis = true,
            welchResponses = listOf(ksl.app.config.WelchResponseSpec(c.responseSnapshot[0].name, 1.0)),
            includePartialSums = true,
            includeBiasTest = false,
            includeBatchMeans = false,
            deletionPoint = -1,
            autoRender = false
        )
        val dialog = WelchConfigDialogImpl(c, owner = null)
        assertTrue(dialog.masterCheckBox.isSelected, "master toggle seeds from enableWelchAnalysis")
        assertTrue(dialog.rowChecks[0].isSelected, "configured response row seeds selected")
        assertFalse(dialog.rowChecks[1].isSelected, "unconfigured response row seeds unselected")
    }
}
