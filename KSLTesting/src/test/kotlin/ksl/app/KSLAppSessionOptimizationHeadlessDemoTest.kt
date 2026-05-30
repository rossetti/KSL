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

package ksl.app

import kotlinx.coroutines.runBlocking
import ksl.app.notification.NotificationSeverity
import ksl.app.notification.NotificationSink
import ksl.app.optimization.paths.OptimizationPaths
import ksl.app.optimization.results.ArtifactNames
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.examples.general.appsession.OPTIMIZATION_HEADLESS_DEMO_ANALYSIS_NAME
import ksl.examples.general.appsession.OPTIMIZATION_HEADLESS_DEMO_APP_NAME
import ksl.examples.general.appsession.OPTIMIZATION_HEADLESS_DEMO_RESPONSE
import ksl.examples.general.appsession.runKSLAppSessionOptimizationHeadlessDemo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Substrate-validation tests for
 *  `KSLAppSessionOptimizationHeadlessDemo`.
 *
 *  Drives the optimization-workflow demo with a JUnit `@TempDir`
 *  workspace and asserts:
 *
 *  - The Stochastic Hill Climbing run reaches
 *    `RunResult.OptimizationCompleted` end-to-end via the
 *    substrate `KSLAppSession` / `RunHandle` API, with a
 *    non-empty iteration history and a best-solution snapshot.
 *  - The substrate path objects (`AppWorkspacePaths` +
 *    `OptimizationPaths`) compose to produce the canonical
 *    `<workspace>/<app>/output/<analysis>/run-NNN/` layout.
 *  - The substrate result writers (`RunSummaryWriter`,
 *    `IterationHistoryCsvWriter`, `BestSolutionCsvWriter`)
 *    materialise their artifacts headlessly without a Swing
 *    host and without a live `Solver` / `SolverResult`
 *    instance.
 *  - The `NotificationSink.Collecting` contract captures every
 *    user-facing message including per-iteration progress and
 *    artifact-write confirmations.
 *  - The demo source does not import Swing / AWT or low-level
 *    orchestrators, mirroring the E.1.1 / E.1.2 / E.1.3 / E.1.4
 *    invariant.
 */
class KSLAppSessionOptimizationHeadlessDemoTest {

    @Test
    fun `headless optimization workflow produces a non-empty OptimizationCompleted`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionOptimizationHeadlessDemo(workspace, notifier)

        val opt = assertIs<RunResult.OptimizationCompleted>(report.runResult)
        assertTrue(opt.iterationHistory.isNotEmpty(),
            "Optimization result must carry a non-empty iteration history.")
        assertEquals(opt.iterationHistory.size, report.iterationCount,
            "Demo report's iterationCount must equal the substrate iteration-history size.")
        assertTrue(report.events.any { it is RunEvent.OptimizationRunStarted },
            "Expected an OptimizationRunStarted event in the observed stream.")
        assertTrue(report.events.any { it is RunEvent.IterationCompleted },
            "Expected at least one IterationCompleted event in the observed stream.")
        assertTrue(report.events.any { it is RunEvent.RunCompleted },
            "Expected a terminal RunCompleted event.")
    }

    @Test
    fun `run output directory is the substrate-derived AppWorkspacePaths + OptimizationPaths layout`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionOptimizationHeadlessDemo(workspace, notifier)

        val expectedAppWorkspace = AppWorkspacePaths.appWorkspaceDir(
            workspace,
            OPTIMIZATION_HEADLESS_DEMO_APP_NAME
        )
        val expectedAnalysisDir = OptimizationPaths.outputDir(
            expectedAppWorkspace,
            OPTIMIZATION_HEADLESS_DEMO_ANALYSIS_NAME
        )
        assertEquals(expectedAppWorkspace, report.appWorkspace)
        assertEquals(expectedAnalysisDir, report.analysisDir)

        // Fresh @TempDir, so `nextRunSubdir` returns run-001.
        assertEquals(expectedAnalysisDir.resolve("run-001"), report.runDirectory,
            "First run on a fresh analysis dir must land in run-001.")
        assertTrue(report.runDirectory.toFile().exists(),
            "Substrate run directory must exist on disk.")
    }

    @Test
    fun `all three substrate artifacts are written under run-NNN`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionOptimizationHeadlessDemo(workspace, notifier)

        // Per-writer flags
        assertTrue(report.summaryTomlWritten, "RunSummaryWriter must succeed in writing summary.toml.")
        assertTrue(report.iterationHistoryCsvWritten,
            "IterationHistoryCsvWriter must succeed in writing iteration_history.csv.")
        assertTrue(report.bestSolutionCsvWritten,
            "BestSolutionCsvWriter must succeed in writing best_solution.csv.")

        // Paths match substrate ArtifactNames + on-disk existence
        assertEquals(report.runDirectory.resolve(ArtifactNames.SUMMARY_TOML),
            report.summaryTomlPath)
        assertEquals(report.runDirectory.resolve(ArtifactNames.ITERATION_HISTORY_CSV),
            report.iterationHistoryCsvPath)
        assertEquals(report.runDirectory.resolve(ArtifactNames.BEST_SOLUTION_CSV),
            report.bestSolutionCsvPath)
        assertTrue(report.summaryTomlPath.toFile().exists(),
            "summary.toml must exist on disk.")
        assertTrue(report.iterationHistoryCsvPath.toFile().exists(),
            "iteration_history.csv must exist on disk.")
        assertTrue(report.bestSolutionCsvPath.toFile().exists(),
            "best_solution.csv must exist on disk.")
    }

    @Test
    fun `artifact contents reflect the substrate run state`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionOptimizationHeadlessDemo(workspace, notifier)

        // Summary TOML mentions the analysis name, the algorithm, and the
        // best-inputs section with the demo's two control names.  The
        // objective response name is intentionally omitted from the
        // summary — it's part of the problem definition rather than the
        // result projection.
        val summaryBody = Files.readString(report.summaryTomlPath)
        assertTrue(summaryBody.isNotBlank(), "summary.toml must not be blank.")
        assertTrue(summaryBody.contains(OPTIMIZATION_HEADLESS_DEMO_ANALYSIS_NAME),
            "summary.toml must mention the analysis name " +
                "'$OPTIMIZATION_HEADLESS_DEMO_ANALYSIS_NAME'.")
        assertTrue(summaryBody.contains("StochasticHillClimbing"),
            "summary.toml must record the solver's algorithm class name.")
        assertTrue(summaryBody.contains("[bestInputs]"),
            "summary.toml must include a [bestInputs] section.")
        assertTrue(summaryBody.contains("Inventory.orderQuantity"),
            "summary.toml must surface the first decision variable name.")
        assertTrue(summaryBody.contains("Inventory.reorderPoint"),
            "summary.toml must surface the second decision variable name.")

        // Iteration history CSV has a header and at least one data row.
        val historyBody = Files.readString(report.iterationHistoryCsvPath)
        val historyLines = historyBody.lineSequence().filter { it.isNotBlank() }.toList()
        assertTrue(historyLines.size >= 2,
            "iteration_history.csv must have a header plus at least one data row; " +
                "got ${historyLines.size} non-blank line(s).")

        // Best solution CSV has both columns we asked for (the two input names) and
        // a data row.
        val bestBody = Files.readString(report.bestSolutionCsvPath)
        val bestLines = bestBody.lineSequence().filter { it.isNotBlank() }.toList()
        assertTrue(bestLines.size >= 2,
            "best_solution.csv must have a header plus at least one data row.")
        val header = bestLines.first()
        assertTrue("Inventory.orderQuantity" in header,
            "best_solution.csv header must include the first input column; got: $header")
        assertTrue("Inventory.reorderPoint" in header,
            "best_solution.csv header must include the second input column; got: $header")
    }

    @Test
    fun `headless optimization demo emits notifications through the substrate sink`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        runKSLAppSessionOptimizationHeadlessDemo(workspace, notifier)

        val specs = notifier.specs()
        // Conservative lower bound: start + submit + completion + per-iter (≥1)
        // + writing-artifacts + 3× artifact-written = 8 minimum.
        assertTrue(specs.size >= 7,
            "Demo must emit at least seven notifications; got ${specs.size}.")
        assertTrue(specs.all { it.severity == NotificationSeverity.INFO },
            "Happy-path demo must emit only INFO notifications; got " +
                specs.map { it.severity }.distinct())
        assertTrue(specs.any { it.message.startsWith("Iteration ") },
            "Demo must surface per-iteration progress via the sink.")
        assertTrue(specs.any { it.message.contains("Wrote summary") },
            "Demo must announce the summary.toml write with a file path.")
        assertTrue(specs.any { it.message.contains("Wrote iteration history") },
            "Demo must announce the iteration_history.csv write with a file path.")
        assertTrue(specs.any { it.message.contains("Wrote best solution") },
            "Demo must announce the best_solution.csv write with a file path.")
    }

    @Test
    fun `headless optimization demo does not import low-level orchestrators or Swing`() {
        val source = readDemoSource()
        // Same import-line scoping as the prior E.1 tests.
        val importLines = source.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .toList()

        assertTrue(importLines.none { it.startsWith("import ksl.app.orchestrator") },
            "Demo must drive runs through KSLAppSession, not orchestrators.  Offending imports: " +
                importLines.filter { it.startsWith("import ksl.app.orchestrator") })
        assertTrue(importLines.none { it.startsWith("import javax.swing") },
            "Demo must not import any Swing API.")
        assertTrue(importLines.none { it.startsWith("import java.awt") },
            "Demo must not import any AWT API.")
    }

    private fun readDemoSource(): String {
        val repoRoot = File(System.getProperty("user.dir")).parentFile
        return repoRoot.resolve(
            "KSLExamples/src/main/kotlin/ksl/examples/general/appsession/" +
                "KSLAppSessionOptimizationHeadlessDemo.kt"
        ).readText()
    }
}
