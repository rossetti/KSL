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

import ksl.app.config.ModelReference
import ksl.app.editor.BundleLibraryController
import ksl.app.swing.common.appearance.AppTheme
import ksl.app.swing.common.appearance.LookAndFeel
import ksl.simulation.ModelBuilderIfc
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Top-level DSL entry point for building and launching a Single-app
 * GUI per scenario-app's sibling workflow.
 *
 * ## Two launch modes
 *
 * **Builder mode** — the developer registers a [ModelBuilderIfc]
 * inline.  Idiomatic usage:
 *
 * ```kotlin
 * fun main() = kslSingleApp(appName = "M/M/1 Queue") {
 *     modelBuilder(MM1Builder())
 * }
 * ```
 *
 * **Bundle-picker mode** — the developer omits `modelBuilder(...)`.
 * On launch, a modal dialog presents every model in every bundle
 * available on the classpath; the user can also load additional
 * bundle JARs via *Load JAR…*.  Idiomatic usage:
 *
 * ```kotlin
 * fun main() = kslSingleApp(appName = "KSL Single App") {
 *     // no modelBuilder(...)
 * }
 * ```
 *
 * The bundle-mode controller persists configurations as
 * [ModelReference.ByBundleAndModelId] so subsequent *File → Open*
 * resolves against the same bundle.
 *
 * **Why a named [ModelBuilderIfc] (no inline lambda overload).**
 * A `ModelBuilderIfc` compiled into the Single-app JAR is directly
 * consumable by the Scenario app via
 * [ModelReference.ByJar(jarPath, fqcn)][ModelReference.ByJar] —
 * same JAR, same compiled class, no extra packaging.  An inline
 * lambda would hide the builder behind a synthetic class name and
 * foreclose that cross-app reuse path.  See workflow-single.md §2
 * OQ 2.
 *
 * @param appName window title (and the default model name used to
 *   construct [ModelReference.Embedded] when the app saves a
 *   configuration in builder mode).  Required.
 * @param block configuration DSL — calls `modelBuilder(...)` at
 *   most once; if omitted, the picker mode runs at launch.
 */
fun kslSingleApp(appName: String, block: KSLSingleApp.() -> Unit) {
    require(appName.isNotBlank()) { "appName must be non-blank" }
    val app = KSLSingleApp(appName).also(block)
    app.launch()
}

/**
 * Builder for a single-model Swing app.  Idiomatic construction
 * goes through [kslSingleApp]; this class is the underlying
 * extension point for staged construction.
 *
 * Constructed with the window's [appName]; the developer optionally
 * registers a [ModelBuilderIfc] via [modelBuilder].  Calling
 * [launch] hands off to the Swing EDT and either constructs the
 * [SingleAppController] + [SingleAppFrame] immediately (when a
 * builder was registered) or runs the bundle picker first and uses
 * the picked builder.
 *
 * @property appName window title; also the default `Model.name`
 *   the runtime uses when constructing
 *   [ModelReference.Embedded] for saved configurations in builder
 *   mode.
 */
class KSLSingleApp(val appName: String) {

    private var registeredBuilder: ModelBuilderIfc? = null

    /** Registers the developer's [ModelBuilderIfc].  Optional —
     *  when omitted, [launch] shows the bundle picker at startup. */
    fun modelBuilder(builder: ModelBuilderIfc) {
        check(registeredBuilder == null) { "modelBuilder(...) may only be called once" }
        registeredBuilder = builder
    }

    /**
     * Launches the app on the Swing EDT.
     *
     *  - **Builder mode** (a [ModelBuilderIfc] was registered):
     *    constructs the [SingleAppController] + [SingleAppFrame]
     *    directly and shows the window.
     *  - **Bundle-picker mode** (no builder registered): constructs
     *    a fresh [BundleLibraryController], probes the classpath,
     *    presents [BundleModelPickerDialog].  On
     *    [BundleModelPickerDialog.Result.Selected] resolves the
     *    picked `(bundleId, modelId)` to a [ModelBuilderIfc] via
     *    `bundleLibrary.bundleProvider.value!!.builderFor(...)` and
     *    constructs the controller + frame against it.  On
     *    [BundleModelPickerDialog.Result.Cancelled] the JVM exits.
     *
     * Returns after the frame is shown (builder mode) or after the
     * picker has been dismissed (bundle mode).  The Swing thread
     * keeps the JVM alive until the user closes the window in the
     * Selected case.
     */
    fun launch() {
        // Install FlatLaf BEFORE invokeLater so the macOS bootstrap
        // (apple.laf.useScreenMenuBar, apple.awt.application.name)
        // takes effect before any AWT class loads on the EDT.
        LookAndFeel.install(theme = AppTheme.SYSTEM, appName = appName)
        SwingUtilities.invokeLater {
            val controller = resolveController() ?: return@invokeLater
            SingleAppFrame(controller).apply {
                pack()
                setLocationRelativeTo(null)
                isVisible = true
            }
        }
    }

    /** EDT-side: either use the registered builder directly or run
     *  the bundle picker.  Returns `null` when the user cancels the
     *  picker (in which case this method has already exited the
     *  JVM). */
    private fun resolveController(): SingleAppController? {
        val builder = registeredBuilder
        if (builder != null) {
            // Builder mode — current behavior.
            return SingleAppController(appName, builder)
        }
        // Bundle-picker mode.  Discover any classpath bundles (none in a
        // released app) plus whatever the user installed into ~/.ksl/bundles/.
        val bundleLibrary = BundleLibraryController()
        bundleLibrary.discoverFromClasspath()
        bundleLibrary.discoverFromUserBundlesDir()
        return when (val outcome = BundleModelPickerDialog.show(bundleLibrary)) {
            BundleModelPickerDialog.Result.Cancelled -> {
                // No model — exit the JVM cleanly.  The Swing
                // dispatch thread would otherwise keep the JVM
                // alive even though there's no window.
                exitProcess(0)
            }
            is BundleModelPickerDialog.Result.Selected -> {
                val provider = bundleLibrary.bundleProvider.value
                    ?: error(
                        "Internal: bundle picker returned Selected " +
                            "(${outcome.bundleId}, ${outcome.modelId}) but the " +
                            "bundle provider is null."
                    )
                val pickedBuilder = provider.builderFor(outcome.bundleId, outcome.modelId)
                SingleAppController(
                    appName = appName,
                    modelBuilder = pickedBuilder,
                    bundleLibrary = bundleLibrary,
                    sourceRef = ModelReference.ByBundleAndModelId(
                        bundleId = outcome.bundleId,
                        modelId = outcome.modelId
                    )
                )
            }
        }
    }

    /** Test-only: read the registered builder without launching. */
    internal fun registeredBuilderForTest(): ModelBuilderIfc? = registeredBuilder
}
