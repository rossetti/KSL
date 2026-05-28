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

import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.execute.AlgorithmStatePanel
import ksl.app.swing.simopt.execute.BestSoFarPanel
import ksl.app.swing.simopt.execute.LiveProgressPanel
import ksl.app.swing.simopt.execute.RunControlsPanel
import ksl.app.swing.simopt.runsetup.PreRunValidationPanel
import ksl.app.swing.simopt.runsetup.RunPreviewPanel
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 *  *Execute* step — Phase O7b.
 *
 *  Final pre-launch surface plus live progress.  Stacks six panels
 *  vertically inside a scroll pane so smaller windows still work:
 *
 *   1. [PreRunValidationPanel] — document + model-aware checks.
 *   2. [RunPreviewPanel] — read-only estimate of total runs, output
 *      directory, and trace file path.
 *   3. [RunControlsPanel] — Run / Cancel buttons, status line,
 *      stale-results banner.
 *   4. [LiveProgressPanel] — iteration counter, elapsed, ETA,
 *      best objective.
 *   5. [BestSoFarPanel] — one row per decision variable, updated
 *      from the latest iteration event.
 *   6. [AlgorithmStatePanel] — solver-specific state when the
 *      snapshot carries it (hidden otherwise).
 *
 *  The Results step (Phase O8) reads
 *  [SimoptAppController.lastResult] and renders the full
 *  convergence plot via `lets-plot`; this step keeps to numeric
 *  progress while the run is in flight.
 */
class ExecuteStepPanel(controller: SimoptAppController) : JPanel(BorderLayout()) {

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        stack.add(PreRunValidationPanel(controller))
        stack.add(Box.createVerticalStrut(8))
        stack.add(RunPreviewPanel(controller))
        stack.add(Box.createVerticalStrut(8))
        stack.add(RunControlsPanel(controller))
        stack.add(Box.createVerticalStrut(8))
        stack.add(LiveProgressPanel(controller))
        stack.add(Box.createVerticalStrut(8))
        stack.add(BestSoFarPanel(controller))
        stack.add(Box.createVerticalStrut(8))
        stack.add(AlgorithmStatePanel(controller))
        stack.add(Box.createVerticalGlue())

        val scroll = JScrollPane(
            stack,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = BorderFactory.createEmptyBorder()
        }
        add(scroll, BorderLayout.CENTER)
    }
}
