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

import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 *  *Reports* tab — on-demand cross-scenario reporting.
 *
 *  Phase G ships only the scaffolding: a disabled button explaining
 *  that reports become available after running scenarios.  Phase H
 *  fills in the actual on-demand generation against
 *  `ksl.utilities.io.report` once run results are wired through the
 *  controller.  There is intentionally no auto-rendering — the
 *  analyst chooses what to materialize after every run.
 */
class ReportsTabPanel(
    @Suppress("unused") private val controller: ScenarioAppController
) : JPanel() {

    private val generateButton = JButton("Generate Cross-Scenario Report").apply {
        isEnabled = false
        toolTipText = "Available after Simulate completes for the current scenarios."
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        add(JLabel(
            "<html>Cross-scenario reports are generated on demand after running scenarios.<br>" +
                "Pick the report formats on <b>Output Options</b>, then run from the toolbar (Phase H)."
        ).apply { alignmentX = LEFT_ALIGNMENT })
        add(Box.createVerticalStrut(12))
        generateButton.alignmentX = LEFT_ALIGNMENT
        add(generateButton)
        add(Box.createVerticalGlue())
    }
}
