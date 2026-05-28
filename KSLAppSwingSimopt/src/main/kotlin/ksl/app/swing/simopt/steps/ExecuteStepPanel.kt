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
import ksl.app.swing.simopt.execute.CurrentBestSolutionPanel
import ksl.app.swing.simopt.execute.OptimizationPanel
import ksl.app.swing.simopt.execute.OptimizationProgressPanel
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 *  *Execute* step — Phase O7b (post-feedback redesign).
 *
 *  Top-aligned [OptimizationPanel] is **always visible** (no scroll)
 *  so the Optimize button stays reachable regardless of window size.
 *  Validation status, output directory, trace-file path, and the
 *  stale-results banner live in that same compact section.
 *
 *  The body underneath is a scroll pane containing the live
 *  progress + solution + algorithm-state panels:
 *
 *   1. [OptimizationProgressPanel] — iteration counter, elapsed,
 *      best objective.  No ETA — the framework provides no time
 *      estimate and a linear extrapolation would mislead more than
 *      help (cooling schedules in SA, growing replication counts
 *      in R-SPLINE, etc.).
 *   2. [CurrentBestSolutionPanel] — `JTable` of decision variables,
 *      scrollable, scales to many variables.
 *   3. [AlgorithmStatePanel] — solver-specific metrics, hidden
 *      when the snapshot's `solverSpecificState` is empty.
 *
 *  Detailed validation issues open on-demand via the [View…] button
 *  in [OptimizationPanel] — they no longer consume vertical space
 *  when the document is clean.
 */
class ExecuteStepPanel(controller: SimoptAppController) : JPanel(BorderLayout()) {

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        add(OptimizationPanel(controller), BorderLayout.NORTH)

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
        }
        stack.add(OptimizationProgressPanel(controller))
        stack.add(Box.createVerticalStrut(8))
        stack.add(CurrentBestSolutionPanel(controller))
        stack.add(Box.createVerticalStrut(8))
        stack.add(AlgorithmStatePanel(controller))
        stack.add(Box.createVerticalGlue())

        val scroll = JScrollPane(
            stack,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        ).apply { border = BorderFactory.createEmptyBorder() }
        add(scroll, BorderLayout.CENTER)
    }
}
