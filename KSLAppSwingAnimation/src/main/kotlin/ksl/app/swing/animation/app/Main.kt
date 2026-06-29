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

/**
 * The KSL **Animation** app entry point — the green-arrow `main` for IntelliJ and the target of the module's
 * `application` `mainClass` (so `./gradlew :KSLAppSwingAnimation:run` launches it). Mirrors the other KSL apps
 * (e.g. `ksl.app.swing.experiment.MainKt`).
 *
 * Runs in **bundle-picker mode**: at startup it discovers model bundles on the classpath (the bundled
 * `AnimationExamplesBundle` and KSLExamples' `BookExamplesBundle`) and lets the user pick a model to open in
 * the Capture · Run · Layout · Replay authoring app. Use **Bundles ▸ Open Model… / Load JAR…** to switch.
 */
fun main() = kslAnimationApp(appName = "KSL Animation App")
