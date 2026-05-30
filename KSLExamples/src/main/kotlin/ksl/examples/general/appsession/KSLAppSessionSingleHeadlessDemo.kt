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
import ksl.app.notification.NotificationSink
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.single.results.StandardReportFormat
import ksl.app.single.results.StandardReportMaterializer
import ksl.app.single.results.StandardReportOutcome
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path

/**
 *  End-to-end single-workflow demo for a host that has no Swing / AWT
 *  dependency.  Demonstrates how a CLI, web service, or headless test
 *  fixture would drive the full pipeline:
 *
 *  1. Build a [RunConfiguration] in memory (no controller / no GUI).
 *  2. Submit through [KSLAppSession.submit] with a [RunSpec.Single].
 *  3. Observe the [RunHandle.events] flow up to the terminal event.
 *  4. Await the [RunHandle.result] terminal.
 *  5. Compute the reports-directory path via [AppWorkspacePaths].
 *  6. Render the standard report via [StandardReportMaterializer].
 *  7. Surface every user-facing message through a [NotificationSink].
 *
 *  Calls into [KSLAppSession], [StandardReportMaterializer],
 *  [AppWorkspacePaths], and [NotificationSink] only.  No reach into
 *  low-level orchestrators (`ksl.app.orchestrator.*`), no Swing or
 *  AWT imports — the test
 *  [`ksl.app.KSLAppSessionSingleHeadlessDemoTest`] asserts both of
 *  those invariants by reading this source file.
 *
 *  The companion test in KSLTesting drives this with a JUnit
 *  `@TempDir` workspace and asserts the rendered report file lands
 *  under the path
 *  `<workspace>/<sanitizeAppName(APP_NAME)>/output/<ANALYSIS_NAME>/reports/standard.html`,
 *  proving the substrate report writer integrates with the substrate
 *  path conventions end-to-end.
 *
 *  This file is part of Phase E.1.1 — substrate-validation reference
 *  demos.
 */
suspend fun main() {
    val workspace = Files.createTempDirectory("ksl-single-headless-demo-")
    val notifier = NotificationSink.Collecting()
    val report = runKSLAppSessionSingleHeadlessDemo(workspace, notifier, ::println)
    println("== Final demo summary ==")
    println("  run result:      ${report.runResult::class.simpleName}")
    println("  events:          ${report.events.size}")
    println("  report file:     ${report.reportFile}")
    println("  outcome:         ${report.materializerOutcome::class.simpleName}")
    println("  notifications:   ${notifier.specs().size}")
    println()
    notifier.specs().forEachIndexed { i, spec ->
        println("    [${spec.severity}] $i. ${spec.message}")
    }
}

/**
 *  Application identifier used as the per-app subdirectory under the
 *  caller-supplied workspace.  Through [AppWorkspacePaths.sanitizeAppName]
 *  this becomes the literal segment name (no spaces here, so the
 *  sanitiser is a no-op for this demo).
 */
const val SINGLE_HEADLESS_DEMO_APP_NAME: String = "KSLAppSessionSingleHeadlessDemo"

/**
 *  Analysis identifier — drives the second-level output subdirectory
 *  (`output/<sanitizeAnalysisName(name)>/`).  Picked to be already
 *  filesystem-safe so reading test assertions stays literal.
 */
const val SINGLE_HEADLESS_DEMO_ANALYSIS_NAME: String = "demo-analysis"

private const val MODEL_ID: String = "HeadlessSingleMM1"
private const val TIMEOUT_MS: Long = 30_000L

/**
 *  Run the demo against [workspace] (typically a JUnit `@TempDir` or
 *  `Files.createTempDirectory(...)` result).  All notifications go to
 *  [notifier]; the demo emits an INFO on submit, an INFO when the run
 *  completes, an INFO on successful render with the absolute path of
 *  the rendered file, and an ERROR on any failure branch.  The
 *  optional [writeLine] sink mirrors the smoke demo's console-trace
 *  hook so a real CLI host can stream lifecycle output.
 *
 *  Returns a [KSLAppSessionSingleHeadlessDemoReport] capturing every
 *  observable side-effect for assertion.
 */
suspend fun runKSLAppSessionSingleHeadlessDemo(
    workspace: Path,
    notifier: NotificationSink = NotificationSink.NOOP,
    writeLine: (String) -> Unit = {}
): KSLAppSessionSingleHeadlessDemoReport {
    notifier.info("Starting headless single-app demo at workspace: $workspace")
    writeLine("[demo] workspace = $workspace")

    val provider = buildProvider()
    val config = mm1Config(provider)
    notifier.info("Submitting single run through KSLAppSession.submit(RunSpec.Single).")

    val observation = KSLAppSession(provider).use { session ->
        observeRun(session.submit(RunSpec.Single(config)), writeLine)
    }

    if (observation.result !is RunResult.Completed) {
        notifier.error(
            "Run did not complete cleanly: ${observation.result::class.simpleName}"
        )
        return KSLAppSessionSingleHeadlessDemoReport(
            runResult = observation.result,
            events = observation.events,
            appWorkspace = AppWorkspacePaths.appWorkspaceDir(workspace, SINGLE_HEADLESS_DEMO_APP_NAME),
            reportsDir = null,
            reportFile = null,
            materializerOutcome = StandardReportOutcome.Failed(
                reason = "Run did not produce a Completed result; cannot render."
            )
        )
    }

    notifier.info("Run completed.  Rendering HTML standard report.")
    val appWorkspace = AppWorkspacePaths.appWorkspaceDir(workspace, SINGLE_HEADLESS_DEMO_APP_NAME)
    val reportsDir = AppWorkspacePaths.reportsDir(appWorkspace, SINGLE_HEADLESS_DEMO_ANALYSIS_NAME)
    writeLine("[demo] reportsDir = $reportsDir")

    val outcome = StandardReportMaterializer.materialize(
        result = observation.result,
        format = StandardReportFormat.HTML,
        reportsDir = reportsDir
    )

    val reportFile: Path? = when (outcome) {
        is StandardReportOutcome.Ok -> {
            notifier.info("Wrote standard.html report at: ${outcome.file.absolutePath}")
            outcome.file.toPath()
        }
        is StandardReportOutcome.Failed -> {
            notifier.error("Report rendering failed: ${outcome.reason}")
            null
        }
    }

    return KSLAppSessionSingleHeadlessDemoReport(
        runResult = observation.result,
        events = observation.events,
        appWorkspace = appWorkspace,
        reportsDir = reportsDir,
        reportFile = reportFile,
        materializerOutcome = outcome
    )
}

/**
 *  Captures every observable side-effect of one headless demo
 *  invocation so the companion test can pin behaviour without
 *  scraping console text.
 */
data class KSLAppSessionSingleHeadlessDemoReport(
    val runResult: RunResult,
    val events: List<RunEvent>,
    val appWorkspace: Path,
    val reportsDir: Path?,
    val reportFile: Path?,
    val materializerOutcome: StandardReportOutcome
)

private data class ObservedRun(val result: RunResult, val events: List<RunEvent>)

private val RunEvent.isTerminal: Boolean
    get() = this is RunEvent.RunCompleted ||
        this is RunEvent.RunCancelled ||
        this is RunEvent.RunFailed

private suspend fun observeRun(
    handle: RunHandle,
    writeLine: (String) -> Unit
): ObservedRun = coroutineScope {
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
    ObservedRun(result, events.toList())
}

private fun buildProvider(): MapModelProvider =
    MapModelProvider(MODEL_ID, object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(MODEL_ID, autoCSVReports = false)
            model.numberOfReplications = 3
            model.lengthOfReplication = 100.0
            GIGcQueue(model, numServers = 1, name = "Q")
            return model
        }
    })

private fun mm1Config(provider: MapModelProvider): RunConfiguration {
    val model = provider.provideModel(MODEL_ID)
    return RunConfiguration(
        scenarios = listOf(
            ScenarioSpec(
                name = "single",
                modelReference = ModelReference.ByProviderId(MODEL_ID),
                runOverrides = model.extractRunParameters().toOverrides()
            )
        )
    )
}

/**
 *  Convenience entry point for environments that aren't already in a
 *  suspending context (e.g. a Java `main`).
 */
fun runKSLAppSessionSingleHeadlessDemoBlocking(
    workspace: Path,
    notifier: NotificationSink = NotificationSink.NOOP,
    writeLine: (String) -> Unit = {}
): KSLAppSessionSingleHeadlessDemoReport = runBlocking {
    runKSLAppSessionSingleHeadlessDemo(workspace, notifier, writeLine)
}
