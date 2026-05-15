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

package ksl.app.swing.single.framework

import ksl.simulation.ModelBuilderIfc
import javax.swing.SwingUtilities

/**
 * Top-level DSL entry point for building and launching a Single-app
 * GUI per scenario-app's sibling workflow.  Idiomatic usage:
 *
 * ```kotlin
 * fun main() = kslSingleApp(appName = "M/M/1 Queue") {
 *     modelBuilder(MM1Builder())
 * }
 * ```
 *
 * The function constructs a [KSLSingleApp], runs the supplied
 * configuration block against it, validates that a `modelBuilder`
 * was registered, and launches the frame on the Swing EDT.
 *
 * **Why a named [ModelBuilderIfc] (no inline lambda overload).**
 * A `ModelBuilderIfc` compiled into the Single-app JAR is directly
 * consumable by the Scenario app via
 * `ksl.app.config.ModelReference.ByJar(jarPath, fqcn)` — same JAR,
 * same compiled class, no extra packaging.  An inline lambda would
 * hide the builder behind a synthetic class name and foreclose
 * that cross-app reuse path.  See workflow-single.md §2 OQ 2.
 *
 * @param appName window title (and the default model name used to
 *   construct [ksl.app.config.ModelReference.Embedded] when the
 *   app saves a configuration).  Required.
 * @param block configuration DSL — must call `modelBuilder(...)`
 *   exactly once.
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
 * Constructed with the window's [appName]; the developer registers
 * exactly one [ModelBuilderIfc] via [modelBuilder].  Calling
 * [launch] hands off to the Swing EDT, constructs the
 * [SingleAppController] and [SingleAppFrame], and shows the
 * window.
 *
 * @property appName window title; also the default `Model.name`
 *   the runtime uses when constructing
 *   `ksl.app.config.ModelReference.Embedded` for saved
 *   configurations.
 */
class KSLSingleApp(val appName: String) {

    private var registeredBuilder: ModelBuilderIfc? = null

    /** Registers the developer's [ModelBuilderIfc].  Required. */
    fun modelBuilder(builder: ModelBuilderIfc) {
        check(registeredBuilder == null) { "modelBuilder(...) may only be called once" }
        registeredBuilder = builder
    }

    /**
     * Launches the app: validates configuration, constructs
     * controller + frame on the EDT, makes the frame visible.
     * Returns after the frame is shown; the Swing thread keeps the
     * JVM alive until the user closes the window.
     */
    fun launch() {
        val builder = registeredBuilder
            ?: error("kslSingleApp requires modelBuilder(...) to be called inside its block")
        SwingUtilities.invokeLater {
            val controller = SingleAppController(appName, builder)
            SingleAppFrame(controller).apply {
                pack()
                setLocationRelativeTo(null)
                isVisible = true
            }
        }
    }

    /** Test-only: read the registered builder without launching. */
    internal fun registeredBuilderForTest(): ModelBuilderIfc? = registeredBuilder
}
