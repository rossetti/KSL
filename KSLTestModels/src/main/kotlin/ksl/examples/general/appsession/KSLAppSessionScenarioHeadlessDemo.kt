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
import ksl.app.comparison.AnalysisType
import ksl.app.comparison.BatchCompletedComparisonSource
import ksl.app.comparison.ComparisonSelectionModel
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RVParameterOverride
import ksl.app.config.ReportFormat
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.notification.NotificationSink
import ksl.app.results.batchreports.BatchReportsWriter
import ksl.app.results.comparison.ComparisonReportRenderer
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path

/**
 *  End-to-end scenario-sweep + batch-report + comparison-analyzer
 *  demo for a host that has no Swing / AWT dependency.  Builds on
 *  the single-workflow demo `KSLAppSessionSingleHeadlessDemo` by
 *  adding the two flows a multi-document host (scenario sweep,
 *  experiment, simopt) needs after a `BatchCompleted` result:
 *
 *  1. Build a 3-scenario [RunConfiguration] in memory.  The three
 *     scenarios differ in their `ServiceTime` mean (fast / normal /
 *     slow) so their `System Time` response distributions are
 *     visibly different and the box plot has something to show.
 *  2. Submit through [KSLAppSession.submit] with a
 *     [RunSpec.Scenarios].
 *  3. Observe the [RunHandle.events] flow up to the terminal event.
 *  4. Await the [RunHandle.result] terminal — assert
 *     [RunResult.BatchCompleted].
 *  5. Compute the analysis output directory via [AppWorkspacePaths].
 *  6. Render a per-batch summary via
 *     [BatchReportsWriter.renderBatchSummary].
 *  7. Wrap the [RunResult.BatchCompleted] in a
 *     [BatchCompletedComparisonSource].
 *  8. Drive a [ComparisonSelectionModel] over the source:
 *     `selectAll` → `validateForResponse("System Time", BOX_PLOT)` →
 *     `gatherObservationsFor("System Time")`.
 *  9. Render the cross-scenario box plot via
 *     [ComparisonReportRenderer.renderBoxPlot].
 *  10. Surface every user-facing message through a
 *     [NotificationSink].
 *
 *  Calls into [KSLAppSession], [BatchReportsWriter],
 *  [BatchCompletedComparisonSource], [ComparisonSelectionModel],
 *  [ComparisonReportRenderer], [AppWorkspacePaths], and
 *  [NotificationSink] only.  No reach into low-level orchestrators
 *  (`ksl.app.orchestrator.*`), no Swing or AWT imports — the test
 *  asserts both invariants by reading this source.
 *
 *  Part of Phase E.1.2 — substrate-validation reference demos.
 */
suspend fun main() {
    val workspace = Files.createTempDirectory("ksl-scenario-headless-demo-")
    val notifier = NotificationSink.Collecting()
    val report = runKSLAppSessionScenarioHeadlessDemo(workspace, notifier, ::println)
    println("== Final demo summary ==")
    println("  run result:        ${report.runResult::class.simpleName}")
    println("  scenarios:         ${report.scenarioNames}")
    println("  events:            ${report.events.size}")
    println("  batch summary:     ${report.batchSummaryFile}")
    println("  box plot:          ${report.boxPlotFile}")
    println("  notifications:     ${notifier.specs().size}")
    println()
    notifier.specs().forEachIndexed { i, spec ->
        println("    [${spec.severity}] $i. ${spec.message}")
    }
}

/**
 *  Application identifier — drives the per-app subdirectory under
 *  the caller-supplied workspace via
 *  [AppWorkspacePaths.sanitizeAppName].
 */
const val SCENARIO_HEADLESS_DEMO_APP_NAME: String = "KSLAppSessionScenarioHeadlessDemo"

/**
 *  Analysis identifier — drives the second-level output subdirectory
 *  (`output/<sanitizeAnalysisName(name)>/`).
 */
const val SCENARIO_HEADLESS_DEMO_ANALYSIS_NAME: String = "scenario-sweep"

/**
 *  The response the box-plot demo renders.  Picked because the
 *  GIGcQueue model records this as a per-replication observation
 *  response (`Response` rather than time-weighted), so cross-
 *  scenario averages have natural box-plot semantics.
 */
const val SCENARIO_HEADLESS_DEMO_RESPONSE: String = "System Time"

private const val MODEL_ID: String = "HeadlessScenarioMM1"
private const val TIMEOUT_MS: Long = 60_000L

/** Names of the three scenarios this demo submits, in commit order. */
val SCENARIO_HEADLESS_DEMO_SCENARIO_NAMES: List<String> =
    listOf("FastService", "NormalService", "SlowService")

/**
 *  Run the demo against [workspace].  All notifications go to
 *  [notifier].  The optional [writeLine] mirrors the smoke demo's
 *  console-trace hook so a real CLI host can stream lifecycle
 *  output.
 *
 *  Returns a [KSLAppSessionScenarioHeadlessDemoReport] capturing
 *  every observable side-effect for assertion.
 */
suspend fun runKSLAppSessionScenarioHeadlessDemo(
    workspace: Path,
    notifier: NotificationSink = NotificationSink.NOOP,
    writeLine: (String) -> Unit = {}
): KSLAppSessionScenarioHeadlessDemoReport {
    notifier.info("Starting headless scenario-sweep demo at workspace: $workspace")
    writeLine("[demo] workspace = $workspace")

    val provider = buildProvider()
    val config = scenarioSweepConfig()
    notifier.info(
        "Submitting ${SCENARIO_HEADLESS_DEMO_SCENARIO_NAMES.size}-scenario sweep through " +
            "KSLAppSession.submit(RunSpec.Scenarios)."
    )

    val observation = KSLAppSession(provider).use { session ->
        observeRun(session.submit(RunSpec.Scenarios(config)), writeLine)
    }

    if (observation.result !is RunResult.BatchCompleted) {
        notifier.error(
            "Scenario sweep did not complete cleanly: ${observation.result::class.simpleName}"
        )
        return failedReport(
            workspace = workspace,
            result = observation.result,
            events = observation.events,
            reason = "Sweep did not produce a BatchCompleted result; cannot render."
        )
    }
    val batch = observation.result
    notifier.info(
        "Sweep completed with ${batch.snapshots.size} scenario " +
            "snapshot${if (batch.snapshots.size == 1) "" else "s"}."
    )

    val appWorkspace = AppWorkspacePaths.appWorkspaceDir(workspace, SCENARIO_HEADLESS_DEMO_APP_NAME)
    val analysisOutputDir = AppWorkspacePaths.outputDir(appWorkspace, SCENARIO_HEADLESS_DEMO_ANALYSIS_NAME)
    val reportsDir = AppWorkspacePaths.reportsDir(appWorkspace, SCENARIO_HEADLESS_DEMO_ANALYSIS_NAME)
    writeLine("[demo] analysisOutputDir = $analysisOutputDir")
    writeLine("[demo] reportsDir         = $reportsDir")

    // ── Step 1: Batch summary report ────────────────────────────────────
    notifier.info("Rendering batch summary report under $analysisOutputDir.")
    val summaryOutcome = BatchReportsWriter.renderBatchSummary(
        result = batch,
        outputDir = analysisOutputDir,
        formats = setOf(ReportFormat.HTML),
        batchFileStem = "scenario-summary",
        reportTitle = "Headless Demo — Scenario Sweep Summary",
        itemTypeNamePlural = "scenarios",
        itemColumnHeader = "Scenario"
    )
    val batchSummaryFile: Path? = summaryOutcome.htmlPath?.also {
        notifier.info("Wrote batch summary: $it")
    } ?: run {
        summaryOutcome.errors.forEach { notifier.warn("Batch summary: $it") }
        null
    }

    // ── Step 2: Box-plot comparison across scenarios ────────────────────
    notifier.info(
        "Wrapping batch result as a ComparisonDataSourceIfc and selecting " +
            "all scenarios for the box plot."
    )
    val comparisonSource = BatchCompletedComparisonSource(batch)
    val selectionModel = ComparisonSelectionModel(listOf(comparisonSource))
    selectionModel.selectAll()
    val validation = selectionModel.validateForResponse(
        responseName = SCENARIO_HEADLESS_DEMO_RESPONSE,
        type = AnalysisType.BOX_PLOT
    )
    if (!validation.ok) {
        notifier.error(
            "Validation rejected the box-plot configuration: ${validation.reason}"
        )
        return KSLAppSessionScenarioHeadlessDemoReport(
            runResult = batch,
            events = observation.events,
            scenarioNames = batch.snapshots.map { it.experiment.exp_name },
            appWorkspace = appWorkspace,
            analysisOutputDir = analysisOutputDir,
            reportsDir = reportsDir,
            batchSummaryFile = batchSummaryFile,
            boxPlotFile = null
        )
    }

    val observations = selectionModel.gatherObservationsFor(SCENARIO_HEADLESS_DEMO_RESPONSE)
    notifier.info(
        "Gathered ${observations.size} per-scenario observation arrays for " +
            "response '$SCENARIO_HEADLESS_DEMO_RESPONSE'."
    )

    val boxPlotOutcome = ComparisonReportRenderer.renderBoxPlot(
        sourceLabel = comparisonSource.sourceLabel,
        responseName = SCENARIO_HEADLESS_DEMO_RESPONSE,
        observations = observations,
        outputDir = reportsDir,
        formats = setOf(ReportFormat.HTML)
    )
    val boxPlotFile: Path? = boxPlotOutcome.htmlPath?.also {
        notifier.info("Wrote box-plot report: $it")
    } ?: run {
        boxPlotOutcome.errors.forEach { notifier.warn("Box plot: $it") }
        null
    }

    return KSLAppSessionScenarioHeadlessDemoReport(
        runResult = batch,
        events = observation.events,
        scenarioNames = batch.snapshots.map { it.experiment.exp_name },
        appWorkspace = appWorkspace,
        analysisOutputDir = analysisOutputDir,
        reportsDir = reportsDir,
        batchSummaryFile = batchSummaryFile,
        boxPlotFile = boxPlotFile
    )
}

/**
 *  Captures every observable side-effect of one headless scenario
 *  demo invocation so the companion test can pin behaviour without
 *  scraping console text.
 */
data class KSLAppSessionScenarioHeadlessDemoReport(
    val runResult: RunResult,
    val events: List<RunEvent>,
    val scenarioNames: List<String>,
    val appWorkspace: Path,
    val analysisOutputDir: Path,
    val reportsDir: Path,
    val batchSummaryFile: Path?,
    val boxPlotFile: Path?
)

private data class ScenarioObservedRun(val result: RunResult, val events: List<RunEvent>)

private val RunEvent.isTerminal: Boolean
    get() = this is RunEvent.RunCompleted ||
        this is RunEvent.RunCancelled ||
        this is RunEvent.RunFailed

private suspend fun observeRun(
    handle: RunHandle,
    writeLine: (String) -> Unit
): ScenarioObservedRun = coroutineScope {
    val events = mutableListOf<RunEvent>()
    val terminalEventSeen = CompletableDeferred<Unit>()

    val eventJob = launch {
        handle.events.collect { event ->
            events += event
            writeLine("[event] $event")
            if (event.isTerminal) terminalEventSeen.complete(Unit)
        }
    }

    val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
    withTimeout(TIMEOUT_MS) { terminalEventSeen.await() }
    eventJob.cancelAndJoin()
    ScenarioObservedRun(result, events.toList())
}

private fun buildProvider(): MapModelProvider =
    MapModelProvider(MODEL_ID, object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(MODEL_ID, autoCSVReports = false)
            model.numberOfReplications = 3
            model.lengthOfReplication = 200.0
            model.lengthOfReplicationWarmUp = 20.0
            GIGcQueue(model, numServers = 1, name = "Q")
            return model
        }
    })

/**
 *  Three scenarios differing in their ServiceTime mean.  All three
 *  remain stable (ρ < 1 against the default TBA mean of 1.0) but
 *  the System Time distributions widen noticeably across the
 *  spectrum, giving the box plot something to differentiate.
 */
private fun scenarioSweepConfig(): RunConfiguration {
    val baseOverrides = ExperimentRunOverrides(
        numberOfReplications = 5,
        lengthOfReplication = 200.0,
        lengthOfReplicationWarmUp = 20.0
    )
    return RunConfiguration(
        scenarios = listOf(
            ScenarioSpec(
                name = "FastService",
                modelReference = ModelReference.ByProviderId(MODEL_ID),
                runOverrides = baseOverrides,
                rvOverrides = listOf(
                    RVParameterOverride("$MODEL_ID:ServiceTime", "mean", 0.3)
                )
            ),
            ScenarioSpec(
                name = "NormalService",
                modelReference = ModelReference.ByProviderId(MODEL_ID),
                runOverrides = baseOverrides
                // No RV override — uses the model default ServiceTime mean (0.5).
            ),
            ScenarioSpec(
                name = "SlowService",
                modelReference = ModelReference.ByProviderId(MODEL_ID),
                runOverrides = baseOverrides,
                rvOverrides = listOf(
                    RVParameterOverride("$MODEL_ID:ServiceTime", "mean", 0.7)
                )
            )
        )
    )
}

private fun failedReport(
    workspace: Path,
    result: RunResult,
    events: List<RunEvent>,
    reason: String
): KSLAppSessionScenarioHeadlessDemoReport {
    val appWorkspace = AppWorkspacePaths.appWorkspaceDir(workspace, SCENARIO_HEADLESS_DEMO_APP_NAME)
    val analysisOutputDir = AppWorkspacePaths.outputDir(appWorkspace, SCENARIO_HEADLESS_DEMO_ANALYSIS_NAME)
    val reportsDir = AppWorkspacePaths.reportsDir(appWorkspace, SCENARIO_HEADLESS_DEMO_ANALYSIS_NAME)
    return KSLAppSessionScenarioHeadlessDemoReport(
        runResult = result,
        events = events,
        scenarioNames = emptyList(),
        appWorkspace = appWorkspace,
        analysisOutputDir = analysisOutputDir,
        reportsDir = reportsDir,
        batchSummaryFile = null,
        boxPlotFile = null
    ).also {
        // The reason is surfaced through the notifier at the call site;
        // suppressing the unused warning here keeps this helper focused
        // on path/state assembly.
        @Suppress("UNUSED_PARAMETER") fun unused() = reason
    }
}

/**
 *  Convenience entry point for environments that aren't already in
 *  a suspending context (e.g. a Java `main`).
 */
fun runKSLAppSessionScenarioHeadlessDemoBlocking(
    workspace: Path,
    notifier: NotificationSink = NotificationSink.NOOP,
    writeLine: (String) -> Unit = {}
): KSLAppSessionScenarioHeadlessDemoReport = runBlocking {
    runKSLAppSessionScenarioHeadlessDemo(workspace, notifier, writeLine)
}
