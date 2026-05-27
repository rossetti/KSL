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

package ksl.app.swing.simopt.steps

import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.runsetup.EvaluationSettingsPanel
import ksl.app.swing.simopt.runsetup.PreRunValidationPanel
import ksl.app.swing.simopt.runsetup.RunPreviewPanel
import ksl.app.swing.simopt.runsetup.TrackingPanel
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 *  *Run Setup* step — Phase O7a.
 *
 *  Hosts four sub-panels in a vertical scrollable stack:
 *
 *   1. **Pre-run validation** — live document checks + "Re-check
 *      against model" button.  Informational; does not gate step
 *      completion (Run Setup is complete when `solverSpec != null`).
 *   2. **Evaluation settings** — `EvaluationSpec` editor.  Marks dirty
 *      but does NOT drop `lastResult`.
 *   3. **Tracking & trace** — `SolverTrackingSpec` editor + live
 *      resolved-path display.  Same preference semantics.
 *   4. **Run preview** — read-only summary: estimated total runs,
 *      resolved output directory, resolved trace path.
 */
class RunSetupStepPanel(
    private val controller: SimoptAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit = { _, _ -> }
) : JPanel(BorderLayout()) {

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(PreRunValidationPanel(controller))
            add(Box.createVerticalStrut(8))
            add(EvaluationSettingsPanel(controller))
            add(Box.createVerticalStrut(8))
            add(TrackingPanel(controller, onMessage))
            add(Box.createVerticalStrut(8))
            add(RunPreviewPanel(controller))
            add(Box.createVerticalGlue())
        }

        add(JScrollPane(
            stack,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        ), BorderLayout.CENTER)
    }
}
