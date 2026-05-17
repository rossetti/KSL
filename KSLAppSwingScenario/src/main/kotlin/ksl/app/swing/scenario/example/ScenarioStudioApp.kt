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

package ksl.app.swing.scenario.example

import ksl.app.swing.scenario.kslScenarioApp

/**
 * Minimal runnable example of `kslScenarioApp(...)`.  Run from
 * IntelliJ via right-click `main` → Run, or from Gradle via
 * `./gradlew :KSLAppSwingScenario:run`.
 *
 * Phase A only opens the placeholder frame — no scenarios document,
 * no file menu, no editor.  This file exists so the module's
 * `application` plugin has a `mainClass` to target end-to-end.
 *
 * Once Phases C–I land, this entry point will host a scenarios
 * document the analyst can populate from any bundled model on the
 * JVM classpath (e.g. `MM1Bundle`, `LKInventoryBundle` from
 * `KSLExamples`).
 */
fun main() = kslScenarioApp(appName = "KSL Scenario Studio")
