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

import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.WindowConstants

/**
 * Default top-level frame for a `kslScenarioApp(...)` instance.
 *
 * **Phase A — shell only.**  Just opens a window so the module
 * builds end-to-end and the developer-facing entry point
 * [kslScenarioApp] has something to instantiate.  Subsequent
 * phases build the actual content:
 *
 *  - Phase C — `ScenarioDocumentController` (document state).
 *  - Phase D — File menu wiring (New / Open / Save / Save As /
 *    Reset to Defaults / Set Working Directory / Recent).
 *  - Phase E — *Scenarios* tab (master JTable + row actions).
 *  - Phase F — `ScenarioEditorWindow` (modeless child window).
 *  - Phase G — *Output Options* + *Reports* tabs.
 *  - Phase H — Run wiring + per-scenario row chips + execution-mode
 *    toggle + console drawer.
 *  - Phase I — Bundle library UX.
 *
 * See workflow-scenario.md (forthcoming) for the implementation
 * sequence locked at the end of the §1–12 planning discussion.
 *
 * @param appName window title; matches the `appName` passed to
 *   [kslScenarioApp].  Also used as the per-app workspace
 *   subdirectory under `<KSLWork>/<appName>/` in later phases.
 */
class ScenarioAppFrame(
    private val appName: String
) : JFrame(appName) {

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(960, 680)

        // Phase A placeholder content.  Replaced by the toolbar +
        // tabs + console drawer + status bar layout in Phases D–I.
        contentPane.layout = BorderLayout()
        contentPane.add(buildPlaceholder(), BorderLayout.CENTER)
    }

    private fun buildPlaceholder(): JPanel {
        val label = JLabel(
            "Scenario app — Phase A shell.  Content lands in Phases C–I.",
            SwingConstants.CENTER
        )
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(40, 40, 40, 40)
            add(label, BorderLayout.CENTER)
        }
    }
}
