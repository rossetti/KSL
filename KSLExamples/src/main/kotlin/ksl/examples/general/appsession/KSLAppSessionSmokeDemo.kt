/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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
import ksl.app.session.KSLRuntimeError
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.session.RunWarningType
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc

private const val MODEL_ID = "AppSessionSmokeMM1"
private const val TIMEOUT_MS = 30_000L

private val RunEvent.isTerminal: Boolean
    get() = this is RunEvent.RunCompleted ||
        this is RunEvent.RunCancelled ||
        this is RunEvent.RunFailed

/**
 * Console/TUI smoke demo for the application-facing interaction layer.
 *
 * The important design point is that this file uses [KSLAppSession] and [RunSpec]
 * as the application protocol. It does not select or import lower-level
 * orchestrators directly.
 */
fun main(): Unit = runBlocking {
    runKSLAppSessionSmokeDemo(::println)
}

/**
 * Runs several small app-session workflows and returns observed events/results.
 *
 * Returning a value keeps this example testable without asserting on console
 * text. A GUI/TUI would use the same pieces: submit a [RunSpec], observe the
 * [RunHandle.events] stream, then route the terminal [RunResult] to a result
 * view.
 */
suspend fun runKSLAppSessionSmokeDemo(
    writeLine: (String) -> Unit = ::println
): KSLAppSessionSmokeDemoReport {
    val provider = buildDemoProvider()
    val renderer = ConsoleRunRenderer(writeLine)
    val session = KSLAppSession(provider)

    try {
        val successful = observeRun(
            name = "successful single run",
            handle = session.submit(RunSpec.Single(singleRunConfig())),
            renderer = renderer
        )

        val warning = observeRun(
            name = "warning single run",
            handle = session.submit(RunSpec.Single(warningConfig())),
            renderer = renderer
        )

        val invalid = observeRun(
            name = "invalid single run",
            handle = session.submit(RunSpec.Single(invalidConfig())),
            renderer = renderer
        )

        val cancelled = observeCancelledRun(
            session = session,
            renderer = renderer
        )

        val scenarios = observeRun(
            name = "scenario sweep",
            handle = session.submit(RunSpec.Scenarios(scenarioConfig())),
            renderer = renderer
        )

        return KSLAppSessionSmokeDemoReport(
            successfulSingleRun = successful,
            warningSingleRun = warning,
            invalidSingleRun = invalid,
            cancelledSingleRun = cancelled,
            scenarioRun = scenarios
        )
    } finally {
        session.close()
    }
}

/**
 * Test-friendly summary of the whole smoke demo.
 */
data class KSLAppSessionSmokeDemoReport(
    val successfulSingleRun: ObservedSessionRun,
    val warningSingleRun: ObservedSessionRun,
    val invalidSingleRun: ObservedSessionRun,
    val cancelledSingleRun: ObservedSessionRun,
    val scenarioRun: ObservedSessionRun
)

/**
 * Captures the public lifecycle that an application observes for one submitted
 * run.
 */
data class ObservedSessionRun(
    val name: String,
    val result: RunResult,
    val events: List<RunEvent>
)

/**
 * Collects lifecycle events while awaiting the terminal result.
 *
 * The flow is a hot [kotlinx.coroutines.flow.SharedFlow] that does not close
 * after the terminal event, so the collector explicitly stops once a terminal
 * event has been observed.
 */
private suspend fun observeRun(
    name: String,
    handle: RunHandle,
    renderer: ConsoleRunRenderer
): ObservedSessionRun = coroutineScope {
    val events = mutableListOf<RunEvent>()
    val terminalEventSeen = CompletableDeferred<Unit>()

    val eventJob = launch {
        handle.events.collect { event ->
            events += event
            renderer.render(event)
            if (event.isTerminal) {
                terminalEventSeen.complete(Unit)
            }
        }
    }

    val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
    withTimeout(TIMEOUT_MS) { terminalEventSeen.await() }
    eventJob.cancelAndJoin()

    renderer.render(result)
    ObservedSessionRun(name, result, events.toList())
}

/**
 * Demonstrates application-driven cancellation through the public [RunHandle].
 */
private suspend fun observeCancelledRun(
    session: KSLAppSession,
    renderer: ConsoleRunRenderer
): ObservedSessionRun = coroutineScope {
    val handle = session.submit(
        RunSpec.Single(singleRunConfig(replications = 25, replicationLength = 10_000.0))
    )
    val events = mutableListOf<RunEvent>()
    val started = CompletableDeferred<Unit>()
    val terminalEventSeen = CompletableDeferred<Unit>()

    val eventJob = launch {
        handle.events.collect { event ->
            events += event
            renderer.render(event)
            if (event is RunEvent.RunStarted) {
                started.complete(Unit)
            }
            if (event.isTerminal) {
                terminalEventSeen.complete(Unit)
            }
        }
    }

    withTimeout(TIMEOUT_MS) { started.await() }
    handle.cancel("Demo cancellation")

    val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
    withTimeout(TIMEOUT_MS) { terminalEventSeen.await() }
    eventJob.cancelAndJoin()

    renderer.render(result)
    ObservedSessionRun("cancelled single run", result, events.toList())
}

private fun buildDemoProvider(): ModelProviderIfc =
    MapModelProvider(
        MODEL_ID,
        object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = buildDemoModel()
        }
    )

private fun buildDemoModel(): Model {
    val model = Model(MODEL_ID, autoCSVReports = false)
    model.numberOfReplications = 3
    model.lengthOfReplication = 100.0
    model.lengthOfReplicationWarmUp = 20.0
    model.experimentName = "App Session Smoke Demo"
    GIGcQueue(model, numServers = 1, name = "MM1")
    return model
}

private fun singleRunConfig(
    replications: Int = 3,
    replicationLength: Double = 100.0
): RunConfiguration {
    val runParameters = buildDemoModel().extractRunParameters().copy(
        numberOfReplications = replications,
        lengthOfReplication = replicationLength,
        lengthOfReplicationWarmUp = 20.0,
        experimentName = "App Session Smoke Demo",
        runName = "Baseline"
    )
    return RunConfiguration(
        modelReference = ModelReference.ByProviderId(MODEL_ID),
        experimentRunParameters = runParameters
    )
}

private fun warningConfig(): RunConfiguration {
    val base = singleRunConfig()
    return base.copy(
        experimentRunParameters = base.experimentRunParameters.copy(
            experimentName = "",
            runName = ""
        )
    )
}

private fun invalidConfig(): RunConfiguration {
    val base = singleRunConfig()
    return base.copy(
        experimentRunParameters = base.experimentRunParameters.copy(
            lengthOfReplication = 10.0,
            lengthOfReplicationWarmUp = 10.0
        )
    )
}

private fun scenarioConfig(): RunConfiguration {
    val base = singleRunConfig()
    return base.copy(
        scenarios = listOf(
            ScenarioSpec("LowLoad", base.experimentRunParameters),
            ScenarioSpec(
                "LongerRun",
                base.experimentRunParameters.copy(
                    numberOfReplications = 4,
                    lengthOfReplication = 150.0
                )
            )
        )
    )
}

/**
 * Minimal console renderer that mirrors what a TUI presenter would do.
 */
private class ConsoleRunRenderer(
    private val writeLine: (String) -> Unit
) {

    fun render(event: RunEvent) {
        when (event) {
            is RunEvent.RunWarning -> renderWarning(event.warning)
            is RunEvent.RunStarted ->
                writeLine("Started ${event.modelIdentifier}: ${event.totalReplications} reps")
            is RunEvent.ReplicationEnded ->
                writeLine("Replication ${event.repNumber}/${event.totalReplications} complete")
            is RunEvent.ScenarioCompleted ->
                writeLine("Scenario ${event.index}/${event.totalScenarios} complete: ${event.scenarioName}")
            is RunEvent.RunCompleted ->
                writeLine("Run completed: ${event.summary.completedReplications} reps")
            is RunEvent.RunFailed ->
                writeLine("Run failed: ${event.error}")
            is RunEvent.RunCancelled ->
                writeLine("Run cancelled: ${event.reason}")
            is RunEvent.ReplicationStarted,
            is RunEvent.SimTimeAdvanced,
            is RunEvent.DesignPointCompleted,
            is RunEvent.IterationCompleted -> Unit
        }
    }

    fun render(result: RunResult) {
        when (result) {
            is RunResult.Completed -> {
                writeLine("Single run result: ${result.summary.completedReplications} completed reps")
                result.snapshot.acrossRepStats.take(5).forEach {
                    writeLine("  ${it.stat_name}: average=${it.average}")
                }
            }
            is RunResult.BatchCompleted ->
                writeLine("Batch result: ${result.summary.completedItems}/${result.summary.totalItems} complete")
            is RunResult.OptimizationCompleted ->
                writeLine("Optimization result: ${result.summary.completedItems} iterations")
            is RunResult.Failed -> renderFailure(result.error)
            is RunResult.Cancelled ->
                writeLine("Cancelled result: ${result.reason}")
        }
    }

    private fun renderWarning(warning: RunWarningType) {
        when (warning) {
            is RunWarningType.ConfigurationWarnings ->
                warning.warnings.forEach { writeLine("Configuration warning: ${it.path}: ${it.message}") }
            is RunWarningType.InfiniteHorizonNoTimeout ->
                writeLine("Configuration warning: ${warning.modelIdentifier} has no finite horizon or timeout")
        }
    }

    private fun renderFailure(error: KSLRuntimeError) {
        when (error) {
            is KSLRuntimeError.ConfigurationError -> {
                writeLine("Configuration error: ${error.message}")
                error.validationResult?.errors?.forEach {
                    writeLine("  ${it.path}: ${it.message}")
                }
            }
            else -> writeLine("Runtime error: $error")
        }
    }

}
