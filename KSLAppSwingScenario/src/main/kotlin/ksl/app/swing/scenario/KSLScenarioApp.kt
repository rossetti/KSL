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

import javax.swing.SwingUtilities

/**
 * Top-level DSL entry point for launching a Scenario-app GUI.
 * Idiomatic usage:
 *
 * ```kotlin
 * fun main() = kslScenarioApp(appName = "Queueing Scenarios")
 * ```
 *
 * Unlike `kslSingleApp` (which requires the developer to register one
 * named [ksl.simulation.ModelBuilderIfc]), the Scenario app discovers
 * model bundles from the JVM classpath at startup via
 * [ksl.app.bundle.BundleLoader] and lets the analyst pick which
 * bundled model each scenario references at the document level.  The
 * developer's only required input is [appName] — the window title
 * and the workspace-subdirectory name.
 *
 * The optional [block] is a configuration DSL on [KSLScenarioApp] —
 * currently empty, kept for forward compatibility with future
 * developer-supplied bundle pre-loading or default-config hooks.
 *
 * @param appName window title; also used as the per-app workspace
 *   subdirectory under `<KSLWork>/<appName>/`.  Required, non-blank.
 * @param block configuration DSL — optional; currently has no
 *   required calls.
 */
fun kslScenarioApp(appName: String, block: KSLScenarioApp.() -> Unit = {}) {
    require(appName.isNotBlank()) { "appName must be non-blank" }
    val app = KSLScenarioApp(appName).also(block)
    app.launch()
}

/**
 * Builder for a multi-scenario Swing app.  Idiomatic construction
 * goes through [kslScenarioApp]; this class is the staged-construction
 * extension point for future developer-facing hooks (custom bundle
 * sources, frame customisation, etc.) — Phase A keeps the surface
 * intentionally minimal.
 *
 * @property appName window title; also drives the workspace
 *   subdirectory under `<KSLWork>/<appName>/`.
 */
class KSLScenarioApp(val appName: String) {

    /**
     * Launches the app on the Swing EDT.  Constructs the
     * [ScenarioAppFrame] and shows it.  Returns after the frame is
     * shown; the Swing thread keeps the JVM alive until the user
     * closes the window.
     */
    fun launch() {
        SwingUtilities.invokeLater {
            ScenarioAppFrame(appName).apply {
                pack()
                setLocationRelativeTo(null)
                isVisible = true
            }
        }
    }
}
