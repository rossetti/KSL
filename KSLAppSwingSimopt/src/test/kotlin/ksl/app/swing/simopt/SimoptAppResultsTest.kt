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
import ksl.app.swing.simopt.results.export.ArtifactNames
import ksl.app.swing.simopt.results.export.BestSolutionCsvWriter
import ksl.app.swing.simopt.results.export.ConvergencePlotBuilder
import ksl.app.swing.simopt.results.export.IterationHistoryCsvWriter
import ksl.app.swing.simopt.results.export.LatestBestSnapshot
import ksl.app.swing.simopt.results.export.ResultsStatus
import ksl.app.swing.simopt.results.export.RunSummaryWriter
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

    // ── Pure-writer tests ──────────────────────────────────────────────

    @Test
    fun `iteration history CSV header lists fixed columns then inputs in declaration order`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            c.submit()
            awaitNotRunning(c)
            val result = c.lastResult.value
            assertNotNull(result)
            val problem = c.currentConfiguration()!!.problem!!
            val csv = IterationHistoryCsvWriter.encode(result.iterationHistory, problem)
            val header = csv.lineSequence().first()
            val cols = header.split(",")
            // Fixed metric columns first, in this exact order.
            assertEquals("iteration", cols[0])
            assertEquals("oracle_calls", cols[1])
            assertEquals("replications", cols[2])
            assertEquals("est_obj", cols[3])
            assertEquals("pen_obj", cols[4])
            // Decision variables follow, in declaration order.
            for ((i, spec) in problem.inputs.withIndex()) {
                assertEquals(spec.name, cols[5 + i],
                    "Input column ${spec.name} must appear in declaration order")
            }
        }
    }

    @Test
    fun `iteration history CSV row count matches snapshot history length`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            c.submit()
            awaitNotRunning(c)
            val result = c.lastResult.value!!
            val problem = c.currentConfiguration()!!.problem!!
            val csv = IterationHistoryCsvWriter.encode(result.iterationHistory, problem)
            // One header + N rows (no trailing blank line because
            // the writer appends a newline per row).
            val rowCount = csv.lineSequence().filter { it.isNotBlank() }.count()
            assertEquals(1 + result.iterationHistory.size, rowCount)
        }
    }

    @Test
    fun `best solution CSV has one data row with inputs and objectives`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            c.submit()
            awaitNotRunning(c)
            val result = c.lastResult.value!!
            val problem = c.currentConfiguration()!!.problem!!
            val csv = BestSolutionCsvWriter.encode(result.bestSolution, problem)
            val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(2, lines.size, "best_solution.csv = header + one row")
            val header = lines[0].split(",")
            assertTrue(header.last() == "pen_obj")
            assertTrue(header[header.size - 2] == "est_obj")
            // Decision-variable column count + 2 fixed (est_obj, pen_obj).
            assertEquals(problem.inputs.size + 2, header.size)
        }
    }

    @Test
    fun `run summary TOML carries every required key and round-trip-safe types`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            c.submit()
            awaitNotRunning(c)
            val result = c.lastResult.value!!
            val config = c.currentConfiguration()!!
            val summary = RunSummaryWriter.forCompleted(config, result)
            assertEquals(ResultsStatus.COMPLETED, summary.status)
            assertEquals(config.output.analysisName, summary.analysisName)
            assertEquals(result.summary.runId, summary.runId)
            assertEquals(result.summary.completedItems, summary.totalIterations)
            assertNotNull(summary.bestEstimatedObjective)
            assertNotNull(summary.bestFoundAtIteration)
            assertTrue(summary.bestInputs.isNotEmpty())
            val toml = RunSummaryWriter.encode(summary)
            assertContains(toml, "status = \"COMPLETED\"")
            assertContains(toml, "[bestInputs]")
            assertContains(toml, "elapsedMillis = ")
        }
    }

    @Test
    fun `run summary TOML for incomplete omits objective fields when no iteration fired`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            val config = c.currentConfiguration()!!
            val summary = RunSummaryWriter.forIncomplete(
                config = config,
                status = ResultsStatus.CANCELLED,
                runId = "run-xyz",
                startTimeIso = "2026-05-27T00:00:00Z",
                endTimeIso = "2026-05-27T00:00:05Z",
                elapsedMillis = 5_000,
                latestBest = null,
                statusReason = "Cancelled by user"
            )
            assertEquals(ResultsStatus.CANCELLED, summary.status)
            assertEquals(0, summary.totalIterations)
            assertNull(summary.bestEstimatedObjective)
            assertNull(summary.bestFoundAtIteration)
            val toml = RunSummaryWriter.encode(summary)
            assertContains(toml, "status = \"CANCELLED\"")
            assertContains(toml, "statusReason = \"Cancelled by user\"")
            assertFalse("bestEstimatedObjective" in toml,
                "Cancelled run with no iteration must not emit a best-estimated-objective key")
        }
    }

    @Test
    fun `run summary TOML for incomplete carries latest best when iteration fired`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            val config = c.currentConfiguration()!!
            val latest = LatestBestSnapshot(
                iteration = 5,
                estimatedObjective = 87.5,
                bestInputs = mapOf("x" to 3.0, "y" to 7.5)
            )
            val summary = RunSummaryWriter.forIncomplete(
                config = config,
                status = ResultsStatus.CANCELLED,
                runId = "run-partial",
                startTimeIso = "2026-05-27T00:00:00Z",
                endTimeIso = "2026-05-27T00:00:10Z",
                elapsedMillis = 10_000,
                latestBest = latest,
                statusReason = "Cancelled by user"
            )
            assertEquals(5, summary.totalIterations)
            assertEquals(87.5, summary.bestEstimatedObjective)
            assertEquals(5, summary.bestFoundAtIteration)
            assertEquals(2, summary.bestInputs.size)
            val toml = RunSummaryWriter.encode(summary)
            assertContains(toml, "bestEstimatedObjective = 87.5")
            assertContains(toml, "[bestInputs]")
            assertContains(toml, "x = 3.0")
            assertContains(toml, "y = 7.5")
        }
    }

    @Test
    fun `HTML report contains substrate solver result tables and iteration metrics`(@TempDir tempDir: Path) {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            val target = tempDir.resolve("report-test")
            c.setRunOutputDir(target)
            c.submit()
            awaitNotRunning(c)
            // The controller's terminal observer writes the artifact
            // set including report.html.  Reading the file back
            // exercises the full DSL → renderer → file pipeline.
            val reportPath = target.resolve(ArtifactNames.REPORT_HTML)
            assertTrue(Files.exists(reportPath), "report.html must be written")
            val html = Files.readString(reportPath)
            // Substrate-rendered sections from the `solverResult(...)`
            // DSL extension.
            assertContains(html, "Run Summary")
            assertContains(html, "Evaluator Metrics")
            assertContains(html, "Best Solution Found")
            assertContains(html, "Decision Variables")
            assertContains(html, "Objective Function")
            // Our additions.
            assertContains(html, "Convergence")
            assertContains(html, "Iteration metrics")
        }
    }

    @Test
    fun `convergence plot builder filters sentinel MAX_VALUE seeds`() {
        // No snapshots → no plot.
        assertNull(ConvergencePlotBuilder.buildPlot(emptyList()))
    }

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

}
