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

package ksl.examples.general.appsession

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationOutputConfig
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.SolverSpec
import ksl.app.notification.NotificationSink
import ksl.app.optimization.paths.OptimizationPaths
import ksl.app.optimization.results.ArtifactNames
import ksl.app.optimization.results.BestSolutionCsvWriter
import ksl.app.optimization.results.IterationHistoryCsvWriter
import ksl.app.optimization.results.RunSummaryWriter
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import java.nio.file.Files
import java.nio.file.Path

/**
 *  End-to-end optimization-workflow demo for a host that has no
 *  Swing / AWT dependency.  Rounds out the four-workflow set of
 *  headless reference implementations (Single, Scenario,
 *  Experiment, Optimization) by adding the optimization analogue
 *  of the report-write + sink-integration shape established by
 *  E.1.1 through E.1.4:
 *
 *  1. Build an [OptimizationRunConfiguration] in memory (LK
 *     inventory problem — Stochastic Hill Climbing over
 *     `Inventory.orderQuantity` × `Inventory.reorderPoint` to
 *     minimise `TotalCost`).
 *  2. Submit through [KSLAppSession.submit] with a
 *     [RunSpec.Optimization].
 *  3. Observe the [RunHandle.events] flow up to the terminal
 *     event.  `IterationCompleted` events surface as INFO
 *     notifications so a headless host can stream per-iteration
 *     progress alongside any other status messages.
 *  4. Await the [RunHandle.result] terminal — assert
 *     [RunResult.OptimizationCompleted] with a non-empty iteration
 *     history.
 *  5. Compute the canonical artifact directory via the substrate
 *     path objects:
 *         `AppWorkspacePaths.appWorkspaceDir(workspace, app)`
 *         → `OptimizationPaths.outputDir(appWs, analysisName)`
 *         → `OptimizationPaths.nextRunSubdir(analysisDir)`.
 *  6. Write three substrate artifacts into the resulting `run-NNN`
 *     directory:
 *      - `summary.toml` via [RunSummaryWriter]
 *      - `iteration_history.csv` via [IterationHistoryCsvWriter]
 *      - `best_solution.csv` via [BestSolutionCsvWriter]
 *     (The fourth and fifth artifacts — the convergence PNG and
 *     the rendered HTML report — require a live `Solver` /
 *     `SolverResult` pair that the substrate's session pipeline
 *     does not surface on `RunResult.OptimizationCompleted`.  A
 *     non-Swing host that wants those would either drive the
 *     solver directly via `ksl.simopt.solvers.Solver.runAllIterations`
 *     and call `ResultsArtifactWriter.writeCompleted` with the
 *     live instance, or upgrade the session pipeline to surface
 *     a `SolverResult` attachment.  Out of scope for E.1.5; the
 *     three text artifacts above are the substrate's session-
 *     reachable subset and exercise the full
 *     `OptimizationPaths` → writers path.)
 *  7. Surface every user-facing message through a [NotificationSink].
 *
 *  Calls into [KSLAppSession], [RunSpec.Optimization],
 *  [OptimizationPaths], the three CSV/TOML writers,
 *  [AppWorkspacePaths], and [NotificationSink] only.  No reach
 *  into low-level orchestrators (`ksl.app.orchestrator.*`), no
 *  Swing or AWT imports — the test asserts both invariants by
 *  reading this source.
 *
 *  Part of Phase E.1.5 — substrate-validation reference demos.
 *  Closes the four-workflow parity.
 */
suspend fun main() {
    val workspace = Files.createTempDirectory("ksl-optimization-headless-demo-")
    val notifier = NotificationSink.Collecting()
    val report = runKSLAppSessionOptimizationHeadlessDemo(workspace, notifier, ::println)
    println("== Final demo summary ==")
    println("  run result:        ${report.runResult::class.simpleName}")
    println("  iterations:        ${report.iterationCount}")
    println("  events:            ${report.events.size}")
    println("  run directory:     ${report.runDirectory}")
    println("  summary.toml:      ${report.summaryTomlPath} (written = ${report.summaryTomlWritten})")
    println("  iteration_history: ${report.iterationHistoryCsvPath} (written = ${report.iterationHistoryCsvWritten})")
    println("  best_solution:     ${report.bestSolutionCsvPath} (written = ${report.bestSolutionCsvWritten})")
    println("  notifications:     ${notifier.specs().size}")
    println()
    notifier.specs().forEachIndexed { i, spec ->
        println("    [${spec.severity}] $i. ${spec.message}")
    }
}

/**
 *  Application identifier — per-app subdirectory under the
 *  caller-supplied workspace.
 */
const val OPTIMIZATION_HEADLESS_DEMO_APP_NAME: String =
    "KSLAppSessionOptimizationHeadlessDemo"

/**
 *  Analysis identifier — drives the second-level output
 *  subdirectory (`output/<sanitizeAnalysisName(name)>/`) and the
 *  run-NNN subdirectory underneath.
 */
const val OPTIMIZATION_HEADLESS_DEMO_ANALYSIS_NAME: String = "lk-shc"

/**
 *  Objective response on the LK inventory model.
 */
const val OPTIMIZATION_HEADLESS_DEMO_RESPONSE: String = "TotalCost"

/** Max iterations for the Stochastic Hill Climbing solver — kept
 *  small so the headless test finishes quickly. */
const val OPTIMIZATION_HEADLESS_DEMO_MAX_ITERATIONS: Int = 5

/** Replications per evaluation — matches the existing
 *  `KSLAppSessionOptimizationDemo` fixture for consistency. */
const val OPTIMIZATION_HEADLESS_DEMO_REPS_PER_EVAL: Int = 3

private const val MODEL_ID: String = "HeadlessOptimizationLK"
private const val TIMEOUT_MS: Long = 90_000L

/**
 *  Run the demo against [workspace].  Notifications go to
 *  [notifier]; [writeLine] mirrors the smoke-demo's console-trace
 *  hook so a real CLI host can stream lifecycle output.
 */
suspend fun runKSLAppSessionOptimizationHeadlessDemo(
    workspace: Path,
    notifier: NotificationSink = NotificationSink.NOOP,
    writeLine: (String) -> Unit = {}
): KSLAppSessionOptimizationHeadlessDemoReport {
    notifier.info("Starting headless optimization demo at workspace: $workspace")
    writeLine("[demo] workspace = $workspace")

    val provider = buildProvider()
    val config = lkOptimizationConfig()
    notifier.info(
        "Submitting Stochastic Hill Climbing run " +
            "(max $OPTIMIZATION_HEADLESS_DEMO_MAX_ITERATIONS iterations) through " +
            "KSLAppSession.submit(RunSpec.Optimization)."
    )

    val observation = KSLAppSession(provider).use { session ->
        observeRun(session.submit(RunSpec.Optimization(config)), notifier, writeLine)
    }

    if (observation.result !is RunResult.OptimizationCompleted) {
        notifier.error(
            "Optimization did not complete cleanly: ${observation.result::class.simpleName}"
        )
        return failedReport(workspace, observation.result, observation.events, config)
    }
    val opt = observation.result
    val bestSnapshot = opt.bestSolution
    notifier.info(
        "Optimization completed in ${opt.iterationHistory.size} iterations.  " +
            "Best estimated objective = ${bestSnapshot.estimatedObjFncValue}."
    )

    // ── Compute substrate-canonical run output directory ────────────────
    val appWorkspace = AppWorkspacePaths.appWorkspaceDir(workspace, OPTIMIZATION_HEADLESS_DEMO_APP_NAME)
    val analysisDir = OptimizationPaths.outputDir(appWorkspace, config.output.analysisName)
    Files.createDirectories(analysisDir)
    val runDir = OptimizationPaths.nextRunSubdir(analysisDir)
    Files.createDirectories(runDir)
    writeLine("[demo] runDir = $runDir")
    notifier.info("Writing optimization artifacts into $runDir.")

    // ── Write three substrate artifacts ─────────────────────────────────
    val summaryTomlPath = runDir.resolve(ArtifactNames.SUMMARY_TOML)
    val iterationHistoryCsvPath = runDir.resolve(ArtifactNames.ITERATION_HISTORY_CSV)
    val bestSolutionCsvPath = runDir.resolve(ArtifactNames.BEST_SOLUTION_CSV)

    val summary = RunSummaryWriter.forCompleted(
        config = config,
        result = opt,
        solverInstance = null,
        runDir = runDir
    )
    val summaryWritten = RunSummaryWriter.write(summary, summaryTomlPath).also {
        if (it) notifier.info("Wrote summary: $summaryTomlPath.")
        else notifier.warn("Failed to write summary at $summaryTomlPath.")
    }

    val problem = config.problem
        ?: throw IllegalStateException("Optimization config must carry a problem spec to write artifacts.")
    val historyWritten = IterationHistoryCsvWriter.write(
        history = opt.iterationHistory,
        problem = problem,
        path = iterationHistoryCsvPath
    ).also {
        if (it) notifier.info("Wrote iteration history: $iterationHistoryCsvPath.")
        else notifier.warn("Failed to write iteration history at $iterationHistoryCsvPath.")
    }

    val bestWritten = BestSolutionCsvWriter.write(
        best = bestSnapshot,
        problem = problem,
        path = bestSolutionCsvPath
    ).also {
        if (it) notifier.info("Wrote best solution: $bestSolutionCsvPath.")
        else notifier.warn("Failed to write best solution at $bestSolutionCsvPath.")
    }

    return KSLAppSessionOptimizationHeadlessDemoReport(
        runResult = opt,
        events = observation.events,
        iterationCount = opt.iterationHistory.size,
        appWorkspace = appWorkspace,
        analysisDir = analysisDir,
        runDirectory = runDir,
        summaryTomlPath = summaryTomlPath,
        summaryTomlWritten = summaryWritten,
        iterationHistoryCsvPath = iterationHistoryCsvPath,
        iterationHistoryCsvWritten = historyWritten,
        bestSolutionCsvPath = bestSolutionCsvPath,
        bestSolutionCsvWritten = bestWritten
    )
}

/**
 *  Captures every observable side-effect of one headless
 *  optimization demo invocation so the companion test can pin
 *  behaviour without scraping console text.
 */
data class KSLAppSessionOptimizationHeadlessDemoReport(
    val runResult: RunResult,
    val events: List<RunEvent>,
    val iterationCount: Int,
    val appWorkspace: Path,
    val analysisDir: Path,
    val runDirectory: Path,
    val summaryTomlPath: Path,
    val summaryTomlWritten: Boolean,
    val iterationHistoryCsvPath: Path,
    val iterationHistoryCsvWritten: Boolean,
    val bestSolutionCsvPath: Path,
    val bestSolutionCsvWritten: Boolean
)

private data class OptimizationObservedRun(val result: RunResult, val events: List<RunEvent>)

private val RunEvent.isTerminal: Boolean
    get() = this is RunEvent.RunCompleted ||
        this is RunEvent.RunCancelled ||
        this is RunEvent.RunFailed

private suspend fun observeRun(
    handle: RunHandle,
    notifier: NotificationSink,
    writeLine: (String) -> Unit
): OptimizationObservedRun = coroutineScope {
    val events = mutableListOf<RunEvent>()
    val terminalEventSeen = CompletableDeferred<Unit>()

    val eventJob = launch {
        handle.events.collect { event ->
            events += event
            writeLine("[event] $event")
            // Surface per-iteration progress on the sink so a
            // headless host can stream live optimisation progress.
            if (event is RunEvent.IterationCompleted) {
                notifier.info(
                    "Iteration ${event.iteration}: best estimated objective = " +
                        event.estimatedObjectiveValue
                )
            }
            if (event.isTerminal) terminalEventSeen.complete(Unit)
        }
    }

    val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
    withTimeout(TIMEOUT_MS) { terminalEventSeen.await() }
    eventJob.cancelAndJoin()
    OptimizationObservedRun(result, events.toList())
}

private fun buildProvider(): ModelProviderIfc =
    MapModelProvider(MODEL_ID, object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model = buildModel()
    })

private fun buildModel(): Model {
    val model = Model(MODEL_ID, autoCSVReports = false)
    LKInventoryModel(model, "Inventory")
    model.lengthOfReplication = 120.0
    model.numberOfReplications = OPTIMIZATION_HEADLESS_DEMO_REPS_PER_EVAL
    model.lengthOfReplicationWarmUp = 20.0
    return model
}

private fun lkOptimizationConfig(): OptimizationRunConfiguration {
    val template = ModelRunTemplate(
        modelReference = ModelReference.ByProviderId(MODEL_ID),
        runParameters = buildModel().extractRunParameters()
    )
    val problem = OptimizationProblemSpec(
        problemName = "InventoryProblem",
        modelIdentifier = MODEL_ID,
        objectiveResponseName = OPTIMIZATION_HEADLESS_DEMO_RESPONSE,
        inputs = listOf(
            OptimizationInputSpec(
                "Inventory.orderQuantity",
                lowerBound = 1.0, upperBound = 100.0, granularity = 1.0
            ),
            OptimizationInputSpec(
                "Inventory.reorderPoint",
                lowerBound = 1.0, upperBound = 100.0, granularity = 1.0
            )
        )
    )
    val solver = SolverSpec.StochasticHillClimbing(
        maxIterations = OPTIMIZATION_HEADLESS_DEMO_MAX_ITERATIONS,
        replicationsPerEvaluation = OPTIMIZATION_HEADLESS_DEMO_REPS_PER_EVAL
    )
    return OptimizationRunConfiguration(
        model = template,
        problem = problem,
        solver = solver,
        output = OptimizationOutputConfig(
            analysisName = OPTIMIZATION_HEADLESS_DEMO_ANALYSIS_NAME
        )
    )
}

private fun failedReport(
    workspace: Path,
    result: RunResult,
    events: List<RunEvent>,
    config: OptimizationRunConfiguration
): KSLAppSessionOptimizationHeadlessDemoReport {
    val appWorkspace = AppWorkspacePaths.appWorkspaceDir(workspace, OPTIMIZATION_HEADLESS_DEMO_APP_NAME)
    val analysisDir = OptimizationPaths.outputDir(appWorkspace, config.output.analysisName)
    return KSLAppSessionOptimizationHeadlessDemoReport(
        runResult = result,
        events = events,
        iterationCount = 0,
        appWorkspace = appWorkspace,
        analysisDir = analysisDir,
        runDirectory = analysisDir,
        summaryTomlPath = analysisDir.resolve(ArtifactNames.SUMMARY_TOML),
        summaryTomlWritten = false,
        iterationHistoryCsvPath = analysisDir.resolve(ArtifactNames.ITERATION_HISTORY_CSV),
        iterationHistoryCsvWritten = false,
        bestSolutionCsvPath = analysisDir.resolve(ArtifactNames.BEST_SOLUTION_CSV),
        bestSolutionCsvWritten = false
    )
}

/**
 *  Convenience entry point for environments that aren't already in
 *  a suspending context (e.g. a Java `main`).
 */
fun runKSLAppSessionOptimizationHeadlessDemoBlocking(
    workspace: Path,
    notifier: NotificationSink = NotificationSink.NOOP,
    writeLine: (String) -> Unit = {}
): KSLAppSessionOptimizationHeadlessDemoReport = runBlocking {
    runKSLAppSessionOptimizationHeadlessDemo(workspace, notifier, writeLine)
}
