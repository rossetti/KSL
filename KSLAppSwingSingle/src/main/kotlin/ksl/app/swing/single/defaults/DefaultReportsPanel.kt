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

package ksl.app.swing.single.defaults

import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Reports tab content for `kslSingleApp(...)`.
 *
 * Hosts three standard-report buttons plus an `Advanced…` placeholder.
 * The buttons are always rendered; the *enclosing* tab is what gates
 * availability — `SingleAppFrame` calls `JTabbedPane.setEnabledAt` on
 * the Reports tab based on whether a snapshot exists.  When the tab is
 * disabled the buttons aren't reachable, so there's no need for
 * per-button gating here.
 *
 * @param onStandardReport invoked when a *Standard …* button is
 *   clicked.  Argument is one of `"HTML"`, `"Markdown"`, `"Text"`.
 * @param onAdvanced invoked when *Advanced…* is clicked.  Reserved
 *   for a future per-render options dialog.
 */
class DefaultReportsPanel(
    onStandardReport: (format: String) -> Unit,
    onAdvanced: () -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)) {

    init {
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        listOf(
            "Standard HTML report" to "HTML",
            "Standard Markdown report" to "Markdown",
            "Standard Text report" to "Text"
        ).forEach { (label, format) ->
            add(JButton(label).apply { addActionListener { onStandardReport(format) } })
        }
        add(JButton("Advanced…").apply { addActionListener { onAdvanced() } })
    }
}
