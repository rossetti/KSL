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

package ksl.app.swing.single.example

import ksl.app.swing.single.kslSingleApp

/**
 *  Minimal runnable example of `kslSingleApp(...)` in **bundle-picker
 *  mode** — the DSL block is empty (no `modelBuilder(...)` call), so
 *  the launch path shows the [ksl.app.swing.single.BundleModelPickerDialog]
 *  at startup.
 *
 *  Run from IntelliJ via right-click `main` → Run.  At launch the
 *  picker presents every model in every bundle on the JVM classpath
 *  (the KSL examples module contributes the M/M/1 and LK-Inventory
 *  bundles when compiled with the test fixtures).  *Load JAR…*
 *  inside the picker adds further bundles interactively.
 *
 *  The selected `(bundleId, modelId)` pair is persisted in saved
 *  configurations as
 *  [ksl.app.config.ModelReference.ByBundleAndModelId], so File →
 *  Open later resolves against the same bundle.
 *
 *  Companion to [MM1SingleApp] (which demonstrates the
 *  developer-supplied `modelBuilder(...)` mode).
 */
fun main() = kslSingleApp(appName = "KSL Single App") {
    // No modelBuilder(...) call — the picker dialog appears at
    // launch and lets the user choose any model from any bundle
    // available to the JVM.
}
