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

/**
 *  Bundle-mode entry point for the released KSL Single app: runs
 *  `kslSingleApp(...)` with an empty DSL block (no `modelBuilder(...)`
 *  call), so the launch path shows the shared
 *  [ksl.app.swing.common.bundle.BundleModelPickerDialog] at startup.
 *
 *  At launch the picker presents every model found in the app's own
 *  `<KSLWork>/KSLSingle/bundles/`, the shared `<KSLWork>/bundles/`, and the
 *  suite's shipped examples.  *Load JAR…* inside the picker adds further
 *  bundles interactively, and once the window is open *Bundles → Open Model…*
 *  switches to another model without relaunching.
 *
 *  **Running this from an IDE shows an empty picker, and that is expected.**
 *  The shipped example bundles are found through
 *  `-Dksl.builtinBundles=<install>/examples/bundles`, which only the installed
 *  suite's generated launcher passes (see
 *  `ksl.app.settings.WorkspaceLayout.builtinBundlesDir`).  A plain `java -cp`
 *  or IDE run has no such property, so only the two workspace folders are
 *  searched — and they are empty until you put something in them.  To develop
 *  against the shipped examples, add that `-D` to the run configuration's VM
 *  options; otherwise drop a bundle JAR into `<KSLWork>/bundles/`.
 *
 *  The selected `(bundleId, modelId)` pair is persisted in saved
 *  configurations as
 *  [ksl.app.config.ModelReference.ByBundleAndModelId], so File →
 *  Open later resolves against the same bundle.
 *
 *  Companion to `MM1SingleApp` (the test-source demo of the
 *  developer-supplied `modelBuilder(...)` mode).
 */
fun main() = kslSingleApp(appName = "KSL Single App") {
    // No modelBuilder(...) call — the picker dialog appears at
    // launch and lets the user choose any model from any bundle
    // available to the JVM.
}
