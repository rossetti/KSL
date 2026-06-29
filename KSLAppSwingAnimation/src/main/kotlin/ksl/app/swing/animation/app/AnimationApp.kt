/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.animation.app

import ksl.app.editor.BundleLibraryController
import ksl.app.session.AppWorkspacePaths
import ksl.app.settings.UserSettingsStore
import ksl.app.settings.WorkspaceLayout
import ksl.app.swing.common.appearance.AppTheme
import ksl.app.swing.common.appearance.LookAndFeel
import ksl.simulation.ModelBuilderIfc
import javax.swing.SwingUtilities

/**
 * Top-level DSL entry point for the animation authoring app, mirroring `kslSingleApp`.
 *
 * **Builder mode** — register a [ModelBuilderIfc] inline:
 * ```kotlin
 * fun main() = kslAnimationApp(appName = "Pharmacy") {
 *     modelBuilder(PharmacyBuilder())
 * }
 * ```
 * Bundle-picker mode (omitting `modelBuilder`) is wired in 9C.3.
 */
fun kslAnimationApp(appName: String, block: KSLAnimationApp.() -> Unit) {
    require(appName.isNotBlank()) { "appName must be non-blank" }
    KSLAnimationApp(appName).also(block).launch()
}

/**
 * Launches the animation app in **bundle mode** — no developer-supplied builder. Following the Experiment app
 * convention, startup discovers available bundles but selects **no model**: the main frame opens with an
 * "open a model" prompt, and the user picks or loads one from within the frame (Bundles ▸ Open Model… /
 * Load JAR…). This is the standard entry point used by the module's `Main.kt` / `application` `mainClass`.
 */
fun kslAnimationApp(appName: String) {
    require(appName.isNotBlank()) { "appName must be non-blank" }
    KSLAnimationApp(appName).launch()
}

/**
 * Builder for the animation authoring app. Construction goes through [kslAnimationApp]; calling [launch]
 * installs the look-and-feel, constructs the [AnimationAppController] + [AnimationAppFrame] on the EDT,
 * and shows the window.
 *
 * @property appName window title and (for `ModelReference.Embedded`) the saved-config model identifier
 */
class KSLAnimationApp(val appName: String) {

    private var registeredBuilder: ModelBuilderIfc? = null

    /**
     * Registers the developer's [ModelBuilderIfc]. Optional — when omitted, [launch] opens the frame in
     * bundle mode with no model selected, persisting any chosen model as [ModelReference.ByBundleAndModelId].
     */
    fun modelBuilder(builder: ModelBuilderIfc) {
        check(registeredBuilder == null) { "modelBuilder(...) may only be called once" }
        registeredBuilder = builder
    }

    /** Launches the app on the Swing EDT. */
    fun launch() {
        // Install FlatLaf BEFORE invokeLater so the macOS menu-bar/appearance bootstrap takes effect
        // before any AWT class loads on the EDT.
        LookAndFeel.install(theme = AppTheme.SYSTEM, appName = appName)
        SwingUtilities.invokeLater {
            AnimationAppFrame(resolveController()).apply {
                pack()
                setLocationRelativeTo(null)
                isVisible = true
            }
        }
    }

    /**
     * EDT-side: builder mode uses the registered builder directly. Otherwise (bundle mode) we follow the
     * Experiment app convention — discover bundles so they are *pickable*, but select **no model** at startup
     * (no auto-load, no startup dialog). The frame opens showing an "open a model" prompt; the user picks or
     * loads a model from within the frame (Bundles ▸ Open Model… / Load JAR…), which reopens it on a real model.
     */
    private fun resolveController(): AnimationAppController {
        registeredBuilder?.let { return AnimationAppController(appName, it) }
        // Bundle-picker mode: discover bundles from the app-specific then shared workspace folders
        // (no classpath/SPI scan — examples ship as a discoverable bundle JAR, Phase 7).
        val activeWorkspace = UserSettingsStore().activeWorkspace()
        val appWorkspace = AppWorkspacePaths.appWorkspaceDir(activeWorkspace, appName)
        val bundleLibrary = BundleLibraryController()
        bundleLibrary.discoverFromDirectories(
            WorkspaceLayout.bundlesDir(appWorkspace),
            WorkspaceLayout.bundlesDir(activeWorkspace),
        )
        return AnimationAppController.forNoModel(appName, bundleLibrary)
    }

    /** Test-only: read the registered builder without launching. */
    internal fun registeredBuilderForTest(): ModelBuilderIfc? = registeredBuilder
}
