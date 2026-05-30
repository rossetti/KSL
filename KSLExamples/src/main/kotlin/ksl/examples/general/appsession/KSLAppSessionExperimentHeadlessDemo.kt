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
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.config.toOverrides
import ksl.app.experiment.regression.RegressionFitRecord
import ksl.app.notification.NotificationSink
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.controls.experiments.Factor
import ksl.controls.experiments.LinearModel
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.writeHtml
import ksl.utilities.statistic.RegressionResultsIfc
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 *  End-to-end experiment-workflow demo for a host that has no
 *  Swing / AWT dependency.  Rounds out the four-workflow set
 *  (Single, Scenario, Experiment, Optimization) of headless
 *  reference implementations.  Demonstrates:
 *
 *  1. Build a `2²` full-factorial design (4 design points) over the
 *     LK Inventory model's `Inventory.orderQuantity` and
 *     `Inventory.reorderPoint` controls.
 *  2. Submit through [KSLAppSession.submit] with a
 *     [RunSpec.Experiment].
 *  3. Observe the [RunHandle.events] flow up to terminal; assert
 *     [RunResult.BatchCompleted] with one snapshot per design point.
 *  4. Drive a substrate regression fit through
 *     `ParallelDesignedExperiment.regressionResults(...)` against
 *     the first-order linear model on `RQInventory:Item:TotalCost`.
 *  5. Wrap the fit in the substrate [RegressionFitRecord] DTO —
 *     the same record shape any non-Swing host would persist for a
 *     "recent fits" history.
 *  6. Render the regression report HTML via
 *     `RegressionResultsIfc.toReport(...).writeHtml(...)` into the
 *     [AppWorkspacePaths.reportsDir]-derived directory.
 *  7. Surface every user-facing message through a [NotificationSink].
 *
 *  Calls into [KSLAppSession], [ParallelDesignedExperiment],
 *  [AppWorkspacePaths], [RegressionFitRecord], and the substrate
 *  report-DSL extensions only.  No reach into low-level
 *  orchestrators (`ksl.app.orchestrator.*`), no Swing or AWT
 *  imports — the test asserts both invariants by reading this
 *  source.
 *
 *  Part of Phase E.1.3 — substrate-validation reference demos.
 */
suspend fun main() {
    val workspace = Files.createTempDirectory("ksl-experiment-headless-demo-")
    val notifier = NotificationSink.Collecting()
    val report = runKSLAppSessionExperimentHeadlessDemo(workspace, notifier, ::println)
    println("== Final demo summary ==")
    println("  run result:           ${report.runResult::class.simpleName}")
    println("  design points:        ${report.designPointCount}")
    println("  events:               ${report.events.size}")
    println("  fit record response:  ${report.fitRecord?.response}")
    println("  fit record coded:     ${report.fitRecord?.coded}")
    println("  fit record CL:        ${report.fitRecord?.confidenceLevel}")
    println("  regression report:    ${report.regressionReportFile}")
    println("  notifications:        ${notifier.specs().size}")
    println()
    notifier.specs().forEachIndexed { i, spec ->
        println("    [${spec.severity}] $i. ${spec.message}")
    }
}

/**
 *  Application identifier — per-app subdirectory under the
 *  caller-supplied workspace.
 */
const val EXPERIMENT_HEADLESS_DEMO_APP_NAME: String = "KSLAppSessionExperimentHeadlessDemo"

/**
 *  Analysis identifier — second-level output subdirectory.
 */
const val EXPERIMENT_HEADLESS_DEMO_ANALYSIS_NAME: String = "rq-factorial"

/**
 *  Response the demo fits a regression model against.  Matches the
 *  LK inventory model's primary cost-rate output: the `TotalCost`
 *  TWResponse declared on `LKInventoryModel`.  KSL response names
 *  are taken literally from the constructor's `name` parameter and
 *  are not auto-prefixed by the parent model element's path, so the
 *  substrate-facing name is the bare `TotalCost`.
 */
const val EXPERIMENT_HEADLESS_DEMO_RESPONSE: String = "TotalCost"

/**
 *  Experiment name passed to [ParallelDesignedExperiment].
 */
const val EXPERIMENT_HEADLESS_DEMO_EXPERIMENT_NAME: String = "HeadlessRQFactorial"

/**
 *  Number of replications per design point — chosen small for fast
 *  test execution while leaving enough degrees of freedom for the
 *  first-order regression's residual term.
 */
const val EXPERIMENT_HEADLESS_DEMO_REPS_PER_POINT: Int = 5

/**
 *  Number of design points the 2² full-factorial produces.  Pinned
 *  as a const so the test can assert the snapshot count without
 *  re-deriving it.
 */
const val EXPERIMENT_HEADLESS_DEMO_DESIGN_POINTS: Int = 4

private const val MODEL_ID: String = "HeadlessExperimentLK"
private const val TIMEOUT_MS: Long = 60_000L

/**
 *  Run the demo against [workspace].  Notifications go to
 *  [notifier]; [writeLine] mirrors the smoke-demo's console-trace
 *  hook so a real CLI host can stream lifecycle output.
 */
suspend fun runKSLAppSessionExperimentHeadlessDemo(
    workspace: Path,
    notifier: NotificationSink = NotificationSink.NOOP,
    writeLine: (String) -> Unit = {}
): KSLAppSessionExperimentHeadlessDemoReport {
    notifier.info("Starting headless experiment demo at workspace: $workspace")
    writeLine("[demo] workspace = $workspace")

    val provider = buildProvider()
    val experiment = buildExperiment()
    val designPointCount = experiment.design.designPoints().size
    val config = lkRunConfig(provider)
    notifier.info(
        "Submitting $designPointCount-point full-factorial design through " +
            "KSLAppSession.submit(RunSpec.Experiment), " +
            "$EXPERIMENT_HEADLESS_DEMO_REPS_PER_POINT replications per point."
    )

    val observation = KSLAppSession(provider).use { session ->
        observeRun(
            handle = session.submit(
                RunSpec.Experiment(
                    config = config,
                    experiment = experiment,
                    numRepsPerDesignPoint = EXPERIMENT_HEADLESS_DEMO_REPS_PER_POINT
                )
            ),
            writeLine = writeLine
        )
    }

    if (observation.result !is RunResult.BatchCompleted) {
        notifier.error(
            "Experiment did not complete cleanly: ${observation.result::class.simpleName}"
        )
        return failedReport(workspace, observation.result, observation.events)
    }
    val batch = observation.result
    notifier.info(
        "Experiment completed with ${batch.snapshots.size} design-point " +
            "snapshot${if (batch.snapshots.size == 1) "" else "s"}."
    )

    val appWorkspace = AppWorkspacePaths.appWorkspaceDir(workspace, EXPERIMENT_HEADLESS_DEMO_APP_NAME)
    val reportsDir = AppWorkspacePaths.reportsDir(appWorkspace, EXPERIMENT_HEADLESS_DEMO_ANALYSIS_NAME)
    Files.createDirectories(reportsDir)
    writeLine("[demo] reportsDir = $reportsDir")

    // ── Fit first-order regression ──────────────────────────────────────
    val confidenceLevel = 0.95
    val coded = true
    notifier.info(
        "Fitting first-order regression for response " +
            "'$EXPERIMENT_HEADLESS_DEMO_RESPONSE' (coded = $coded, CL = $confidenceLevel)."
    )
    val linearModel: LinearModel = experiment.design.linearModel(LinearModel.Type.FirstOrder)
    val fit: RegressionResultsIfc = experiment.regressionResults(
        responseName = EXPERIMENT_HEADLESS_DEMO_RESPONSE,
        linearModel = linearModel,
        coded = coded
    )

    val fitRecord = RegressionFitRecord(
        timestamp = LocalDateTime.now(),
        response = EXPERIMENT_HEADLESS_DEMO_RESPONSE,
        modelExpression = linearModel.asString(),
        coded = coded,
        confidenceLevel = confidenceLevel,
        fit = fit
    )

    // ── Render regression report HTML ───────────────────────────────────
    val safeResponseStem = EXPERIMENT_HEADLESS_DEMO_RESPONSE.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val reportFile = reportsDir.resolve("regression-$safeResponseStem.html")
    notifier.info("Rendering regression report HTML to $reportFile.")

    val doc = fit.toReport(
        title = "Headless Demo — Regression for $EXPERIMENT_HEADLESS_DEMO_RESPONSE",
        confidenceLevel = confidenceLevel
    )
    val ctx = RenderContext(outputDir = reportsDir, plotDir = reportsDir.resolve("plots"))
    doc.writeHtml(path = reportFile, ctx = ctx)
    notifier.info("Wrote regression report: $reportFile")

    return KSLAppSessionExperimentHeadlessDemoReport(
        runResult = batch,
        events = observation.events,
        designPointCount = batch.snapshots.size,
        appWorkspace = appWorkspace,
        reportsDir = reportsDir,
        fitRecord = fitRecord,
        regressionReportFile = reportFile
    )
}

/**
 *  Captures every observable side-effect of one headless experiment
 *  demo invocation so the companion test can pin behaviour without
 *  scraping console text.
 */
data class KSLAppSessionExperimentHeadlessDemoReport(
    val runResult: RunResult,
    val events: List<RunEvent>,
    val designPointCount: Int,
    val appWorkspace: Path,
    val reportsDir: Path,
    val fitRecord: RegressionFitRecord?,
    val regressionReportFile: Path?
)

private data class ExperimentObservedRun(val result: RunResult, val events: List<RunEvent>)

private val RunEvent.isTerminal: Boolean
    get() = this is RunEvent.RunCompleted ||
        this is RunEvent.RunCancelled ||
        this is RunEvent.RunFailed

private suspend fun observeRun(
    handle: RunHandle,
    writeLine: (String) -> Unit
): ExperimentObservedRun = coroutineScope {
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
    ExperimentObservedRun(result, events.toList())
}

private fun buildProvider(): ModelProviderIfc =
    MapModelProvider(MODEL_ID, object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(MODEL_ID, autoCSVReports = false)
            LKInventoryModel(model, "Inventory")
            model.lengthOfReplication = 120.0
            model.numberOfReplications = EXPERIMENT_HEADLESS_DEMO_REPS_PER_POINT
            model.lengthOfReplicationWarmUp = 20.0
            return model
        }
    })

/**
 *  Build the 2² full-factorial over (orderQuantity, reorderPoint).
 *  Factor settings map factor names to model-control paths
 *  (`Inventory.orderQuantity` / `Inventory.reorderPoint`).
 */
private fun buildExperiment(): ParallelDesignedExperiment {
    val oq = TwoLevelFactor("OrderQuantity", low = 5.0, high = 20.0)
    val rp = TwoLevelFactor("ReorderPoint", low = 1.0, high = 5.0)
    val design = TwoLevelFactorialDesign(setOf(oq, rp))
    // Explicit `Map<Factor, String>` because Kotlin's bidirectional type
    // inference doesn't widen `Map<TwoLevelFactor, String>` to
    // `Map<Factor, String>` when the val is later passed via a named
    // argument (invariant generics + named-arg evaluation).
    val settings: Map<Factor, String> = mapOf(
        oq to "Inventory.orderQuantity",
        rp to "Inventory.reorderPoint"
    )
    val builder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(MODEL_ID, autoCSVReports = false)
            LKInventoryModel(model, "Inventory")
            model.lengthOfReplication = 120.0
            model.numberOfReplications = EXPERIMENT_HEADLESS_DEMO_REPS_PER_POINT
            model.lengthOfReplicationWarmUp = 20.0
            return model
        }
    }
    return ParallelDesignedExperiment(
        name = EXPERIMENT_HEADLESS_DEMO_EXPERIMENT_NAME,
        modelBuilder = builder,
        factorSettings = settings,
        design = design
    )
}

private fun lkRunConfig(provider: ModelProviderIfc): RunConfiguration {
    val model = provider.provideModel(MODEL_ID)
    val params = model.extractRunParameters()
        .copy(numberOfReplications = EXPERIMENT_HEADLESS_DEMO_REPS_PER_POINT)
    return RunConfiguration(
        scenarios = listOf(
            ScenarioSpec(
                name = "single",
                modelReference = ModelReference.ByProviderId(MODEL_ID),
                runOverrides = params.toOverrides()
            )
        )
    )
}

private fun failedReport(
    workspace: Path,
    result: RunResult,
    events: List<RunEvent>
): KSLAppSessionExperimentHeadlessDemoReport {
    val appWorkspace = AppWorkspacePaths.appWorkspaceDir(workspace, EXPERIMENT_HEADLESS_DEMO_APP_NAME)
    val reportsDir = AppWorkspacePaths.reportsDir(appWorkspace, EXPERIMENT_HEADLESS_DEMO_ANALYSIS_NAME)
    return KSLAppSessionExperimentHeadlessDemoReport(
        runResult = result,
        events = events,
        designPointCount = 0,
        appWorkspace = appWorkspace,
        reportsDir = reportsDir,
        fitRecord = null,
        regressionReportFile = null
    )
}

/**
 *  Convenience entry point for environments that aren't already in
 *  a suspending context (e.g. a Java `main`).
 */
fun runKSLAppSessionExperimentHeadlessDemoBlocking(
    workspace: Path,
    notifier: NotificationSink = NotificationSink.NOOP,
    writeLine: (String) -> Unit = {}
): KSLAppSessionExperimentHeadlessDemoReport = runBlocking {
    runKSLAppSessionExperimentHeadlessDemo(workspace, notifier, writeLine)
}
