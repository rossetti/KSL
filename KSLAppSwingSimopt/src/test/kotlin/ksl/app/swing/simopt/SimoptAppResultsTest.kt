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

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.config.ModelReference
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.AlgorithmKind
import ksl.app.optimization.results.ArtifactNames
import ksl.app.optimization.results.BestSolutionCsvWriter
import ksl.app.optimization.results.ConvergencePlotBuilder
import ksl.app.optimization.results.IterationHistoryCsvWriter
import ksl.app.optimization.results.LatestBestSnapshot
import ksl.app.optimization.results.ResultsStatus
import ksl.app.optimization.results.RunSummaryWriter
import ksl.controls.ControlType
import ksl.examples.general.appsupport.SimoptTestModelsBundle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Phase O8 — Results step tests.
 *
 *  Two surfaces under test:
 *
 *  1. **Pure writers** (`IterationHistoryCsvWriter`,
 *     `BestSolutionCsvWriter`, `RunSummaryWriter`, `HtmlReportWriter`)
 *     — exercised against a real `RunResult.OptimizationCompleted`
 *     produced by submitting the LK fixture from
 *     [SimoptAppExecuteTest].  Each writer is tested for column
 *     ordering, row count, and key content; tests inspect the
 *     in-memory `encode(...)` projection so they don't depend on
 *     filesystem state.
 *
 *  2. **Controller integration** — after a successful run, the
 *     full artifact set should land in `runOutputDir/`.  One smoke
 *     test asserts every expected file exists; per-file content
 *     details belong to the writer-level tests.
 */
class SimoptAppResultsTest {

    private val lkBundleId = SimoptTestModelsBundle().bundleId
    private val lkModelId = SimoptTestModelsBundle.LK_OPT_MODEL_ID
    private fun lkRef() = ModelReference.ByBundleAndModelId(lkBundleId, lkModelId)

    /** Seed a tiny runnable optimization — same shape as the Execute
     *  test fixture so we exercise the real artifact-write path. */
    private fun seedRunnableProblem(c: SimoptAppController) {
        c.setModelReference(lkRef())
        val descriptor = c.currentModelDescriptor.value
        assertNotNull(descriptor)
        c.setObjectiveResponseName(descriptor.responseNames.first())
        val intControl = descriptor.controls.numericControls.firstOrNull {
            it.controlType == ControlType.INTEGER
        }
        assertNotNull(intControl)
        c.addInput(OptimizationInputSpec(
            name = intControl.keyName,
            lowerBound = 5.0,
            upperBound = 8.0,
            granularity = 1.0
        ))
        c.setAlgorithmKind(AlgorithmKind.STOCHASTIC_HILL_CLIMBING)
        c.setCommonMaxIterations(2)
        c.setCommonReplicationsPerEvaluation(1)
    }

    private fun awaitNotRunning(c: SimoptAppController) {
        runBlocking {
            withTimeout(60_000) {
                c.runningFlow.first { !it }
            }
        }
    }

    // Pure-writer tests live in KSLTesting at
    // `ksl/app/optimization/results/OptimizationResultsWriterTest.kt`.
    // They exercise the substrate writers (CSV, TOML, HTML, plot)
    // through `KSLAppSession.submit(RunSpec.Optimization(...))` —
    // i.e. without any Swing controller.  This file keeps only the
    // tests that exercise the SimopAppController's particular
    // wiring around those writers.

    // ── Controller integration smoke ───────────────────────────────────

    @Test
    fun `successful submit writes the full artifact set to runOutputDir`(@TempDir tempDir: Path) {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            // Override the run output to a temp dir so the test
            // doesn't pollute the developer workspace.
            val target = tempDir.resolve("artifacts-run")
            c.setRunOutputDir(target)
            c.submit()
            awaitNotRunning(c)
            val result = c.lastResult.value
            assertNotNull(result)
            // The summary, CSVs, and HTML report should always
            // land.  The PNG depends on lets-plot's native renderer
            // — assert only that the directory exists and the
            // text-format artifacts are present.
            assertTrue(Files.exists(target.resolve(ArtifactNames.SUMMARY_TOML)),
                "summary.toml must exist")
            assertTrue(Files.exists(target.resolve(ArtifactNames.ITERATION_HISTORY_CSV)),
                "iteration_history.csv must exist")
            assertTrue(Files.exists(target.resolve(ArtifactNames.BEST_SOLUTION_CSV)),
                "best_solution.csv must exist")
            assertTrue(Files.exists(target.resolve(ArtifactNames.REPORT_HTML)),
                "report.html must exist")
        }
    }

    // ── Solver configuration reporting ─────────────────────────────────

    @Test
    fun `summary toml carries solverConfiguration block populated from the live solver`(@TempDir tempDir: Path) {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            val target = tempDir.resolve("config-toml")
            c.setRunOutputDir(target)
            c.submit()
            awaitNotRunning(c)
            val toml = Files.readString(target.resolve(ArtifactNames.SUMMARY_TOML))
            assertContains(toml, "[solverConfiguration]")
            // Base-class fields the override merges in via `super`.
            assertContains(toml, "maximumNumberIterations =")
            assertContains(toml, "replicationsPerEvaluation =")
            // StochasticSolver-level field — SHC inherits this
            // override without adding its own.
            assertContains(toml, "streamNumber =")
        }
    }

    @Test
    fun `html report contains the Solver configuration section`(@TempDir tempDir: Path) {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            val target = tempDir.resolve("config-html")
            c.setRunOutputDir(target)
            c.submit()
            awaitNotRunning(c)
            val html = Files.readString(target.resolve(ArtifactNames.REPORT_HTML))
            assertContains(html, "Solver configuration")
            assertContains(html, "maximumNumberIterations")
            assertContains(html, "streamNumber")
        }
    }

    // The dotted-key TOML quoting test lives in KSLTesting alongside
    // the other substrate writer tests.

    // ── Auto-derived solver / problem names ────────────────────────────

    @Test
    fun `solver and problem names auto-derive when the user leaves the fields blank`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            // The seed never calls setCommonSolverName / setProblemName,
            // so both flows stay null.  The spec recomputation must
            // substitute readable defaults so the persisted document
            // (and any future report) doesn't fall back to the
            // substrate's `Identity(null)` → `"ID_<counter>"` pattern.
            assertEquals(
                "Stochastic Hill Climbing",
                c.solverSpec.value?.name,
                "Solver name must default to the algorithm display name"
            )
            assertEquals(
                "LKInventoryModel",
                c.problemSpec.value?.problemName,
                "Problem name must default to the descriptor's model name"
            )
        }
    }

    @Test
    fun `explicit solver name overrides the algorithm-derived default`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            c.setCommonSolverName("shc-baseline")
            assertEquals("shc-baseline", c.solverSpec.value?.name)
        }
    }

    @Test
    fun `explicit problem name overrides the model-derived default`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            c.setProblemName("Custom problem label")
            assertEquals("Custom problem label", c.problemSpec.value?.problemName)
        }
    }

    @Test
    fun `summary toml shows the derived solver and problem names not ID_ counters`(@TempDir tempDir: Path) {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            val target = tempDir.resolve("names-toml")
            c.setRunOutputDir(target)
            c.submit()
            awaitNotRunning(c)
            val toml = Files.readString(target.resolve(ArtifactNames.SUMMARY_TOML))
            // The configurationProperties values come from the live
            // solver after it's built by the factory — if the
            // controller's effective-name fallbacks worked, the
            // [solverConfiguration] table shows the readable names
            // rather than the `Identity(null)` "ID_<counter>" pattern.
            assertContains(toml, "name = \"Stochastic Hill Climbing\"")
            assertContains(toml, "problemDefinition = \"LKInventoryModel\"")
            assertFalse(
                Regex("""name = "ID_\d+"""").containsMatchIn(toml),
                "summary.toml must not carry Identity auto-IDs as the solver name"
            )
            assertFalse(
                Regex("""problemDefinition = "ID_\d+"""").containsMatchIn(toml),
                "summary.toml must not carry Identity auto-IDs as the problem name"
            )
        }
    }

    // ── runDirectory + key-ordering ────────────────────────────────────

    @Test
    fun `summary toml carries a runDirectory matching the run output folder leaf`(@TempDir tempDir: Path) {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            val target = tempDir.resolve("rundir-test")
            c.setRunOutputDir(target)
            c.submit()
            awaitNotRunning(c)
            val toml = Files.readString(target.resolve(ArtifactNames.SUMMARY_TOML))
            // Leaf name of the runOutputDir is what the user sees in
            // the Results step's "Output folder" line — surfacing it
            // here gives a human-friendly identifier without removing
            // the substrate's UUID-based `runId`.
            assertContains(toml, "runDirectory = \"rundir-test\"")
        }
    }

    // TOML key-order and round-trip tests live in KSLTesting — they
    // exercise the substrate `RunSummary` data class's field
    // declaration order and the encoder/decoder symmetry without
    // needing the Swing controller.
}
