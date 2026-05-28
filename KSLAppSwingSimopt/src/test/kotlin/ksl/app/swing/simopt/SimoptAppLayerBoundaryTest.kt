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

package ksl.app.swing.simopt

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Layer-boundary check.  GUI files in this module must not import
 * `ksl.app.orchestrator.*` — that package is an implementation detail
 * abstracted over by `KSLAppSession`.
 *
 * ### Exemption — `SimoptAppController.kt`
 *
 * Phase O7b makes one principled exception: the controller is the
 * integration layer between the persisted document and the substrate.
 * It builds a `Solver` via `OptimizationSolverFactory`, attaches the
 * host-managed CSV / console trackers required by `SolverTrackingSpec`
 * (which documents that *the host* performs attachment), and only then
 * hands the live solver to `OptimizationOrchestrator`.  Routing that
 * sequence through `KSLAppSession.submit(RunSpec.Optimization(...))`
 * would rebuild the solver internally and discard the externally-
 * attached trackers — so the controller is the one file in this module
 * where orchestrator-package coupling is intentional.
 *
 * Every other GUI file (panels under `simopt/steps/`, `simopt/execute/`,
 * `simopt/runsetup/`, `simopt/algorithm/`, `simopt/problem/`, and
 * `simopt/stepper/`) remains forbidden from importing the orchestrator
 * package and must coordinate with the substrate through controller
 * methods + `StateFlow`s.
 *
 * Model-wiring code (the `KSLModelBundle` implementations in
 * `ksl.examples.general.appsupport`) lives in `KSLExamples`, so no
 * module-local exclusion is needed.
 */
class SimoptAppLayerBoundaryTest {

    @Test
    fun `GUI files do not import ksl_app_orchestrator`() {
        val guiFiles = collectGuiFiles()
        assertTrue(guiFiles.isNotEmpty(), "No GUI source files found — test fixture broken?")

        val violations = guiFiles.flatMap { f ->
            if (f.name in EXEMPT_FILES) return@flatMap emptyList<String>()
            val text = f.readText()
            FORBIDDEN_IMPORT_REGEX.findAll(text)
                .map { "${f.name}: ${it.value.trim()}" }
                .toList()
        }
        assertTrue(violations.isEmpty(),
            "Layer-boundary leak — the GUI must use KSLAppSession, not a specific orchestrator:\n" +
                violations.joinToString("\n  ", prefix = "  "))
    }

    private fun collectGuiFiles(): List<File> {
        val moduleSrc = File("src/main/kotlin/ksl/app/swing/simopt")
        if (!moduleSrc.isDirectory) return emptyList()
        return moduleSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    private companion object {
        val FORBIDDEN_IMPORT_REGEX =
            Regex("""^import\s+ksl\.app\.orchestrator\..*$""", RegexOption.MULTILINE)

        /**
         * Files exempted from the orchestrator-import ban.  See the
         * class-level KDoc for the rationale: the controller is the
         * integration layer and must coordinate tracker attach with
         * the orchestrator submission directly.
         */
        val EXEMPT_FILES: Set<String> = setOf("SimoptAppController.kt")
    }
}
