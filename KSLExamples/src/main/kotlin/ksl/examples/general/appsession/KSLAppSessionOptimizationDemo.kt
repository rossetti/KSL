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
import ksl.app.config.ModelRunTemplate
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.SolverSpec
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.session.RunWarningType
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc

private const val LK_MODEL_ID = "AppSessionOptimizationDemoLK"
private const val TIMEOUT_MS = 60_000L

private val RunEvent.isTerminal: Boolean
    get() = this is RunEvent.RunCompleted ||
        this is RunEvent.RunCancelled ||
        this is RunEvent.RunFailed

/**
 * Console/TUI demo for the application-facing optimization workflow.
 *
 * The point of this file is the same as
 * [runKSLAppSessionSmokeDemo]'s: a developer should be able to read a
 * single, focused source file and see exactly which app-layer pieces are
 * involved when running a simulation-optimization problem.  It uses
 * [KSLAppSession] and [RunSpec] as the application protocol and does not
 * import any low-level orchestrator directly.
 *
 * The optimization problem is the classic LK inventory problem: choose
 * `Inventory.orderQuantity` and `Inventory.reorderPoint` over [1, 100]
 * (integer-ordered) to minimize the response `TotalCost`.  A small
 * Stochastic Hill Climber is used so the demo finishes quickly.
 */
fun main(): Unit = runBlocking {
    runKSLAppSessionOptimizationDemo(::println)
}

/**
 * Runs the full optimization demo and returns a structured report
 * suitable for tests.
 */
suspend fun runKSLAppSessionOptimizationDemo(
    writeLine: (String) -> Unit = ::println
): KSLAppSessionOptimizationDemoReport {
    val provider = buildLKDemoProvider()
    val renderer = ConsoleOptimizationRenderer(writeLine)
    val session = KSLAppSession(provider)

    return try {
        val handle = session.submit(RunSpec.Optimization(lkOptimizationConfig()))
        val observed = observeOptimizationRun(handle, renderer)
        KSLAppSessionOptimizationDemoReport(observed)
    } finally {
        session.close()
    }
}

/**
 * Test-friendly summary of the optimization demo.  Reuses
 * [ObservedSessionRun] from the smoke demo so both demos share a single
 * lifecycle-observation contract.
 */
data class KSLAppSessionOptimizationDemoReport(
    val optimizationRun: ObservedSessionRun
)

/**
 * Collects lifecycle events for the optimization run while awaiting the
 * terminal result.  Mirrors the smoke demo's `observeRun` but is kept
 * local so the smoke demo's helper can stay private.
 */
private suspend fun observeOptimizationRun(
    handle: RunHandle,
    renderer: ConsoleOptimizationRenderer
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
    ObservedSessionRun("optimization run", result, events.toList())
}

private fun buildLKDemoProvider(): ModelProviderIfc =
    MapModelProvider(
        LK_MODEL_ID,
        object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = buildLKDemoModel()
        }
    )

private fun buildLKDemoModel(): Model {
    val model = Model(LK_MODEL_ID, autoCSVReports = false)
    LKInventoryModel(model, "Inventory")
    model.lengthOfReplication = 120.0
    model.numberOfReplications = 3
    model.lengthOfReplicationWarmUp = 20.0
    model.experimentName = "App Session Optimization Demo"
    return model
}

private fun lkOptimizationConfig(): OptimizationRunConfiguration {
    val template = ModelRunTemplate(
        modelReference = ModelReference.ByProviderId(LK_MODEL_ID),
        runParameters  = buildLKDemoModel().extractRunParameters()
    )
    val problem = OptimizationProblemSpec(
        problemName           = "InventoryProblem",
        modelIdentifier       = LK_MODEL_ID,
        objectiveResponseName = "TotalCost",
        inputs = listOf(
            OptimizationInputSpec("Inventory.orderQuantity",
                lowerBound = 1.0, upperBound = 100.0, granularity = 1.0),
            OptimizationInputSpec("Inventory.reorderPoint",
                lowerBound = 1.0, upperBound = 100.0, granularity = 1.0)
        )
    )
    val solver = SolverSpec.StochasticHillClimbing(
        maxIterations = 5,
        replicationsPerEvaluation = 3
    )
    return OptimizationRunConfiguration(template, problem, solver)
}

/**
 * Minimal console renderer specialized for the optimization narrative.
 * Per-iteration `IterationCompleted` events get a dedicated line; the
 * terminal `OptimizationCompleted` result prints the best solution.
 */
private class ConsoleOptimizationRenderer(
    private val writeLine: (String) -> Unit
) {

    fun render(event: RunEvent) {
        when (event) {
            is RunEvent.RunWarning -> renderWarning(event.warning)
            is RunEvent.RunStarted ->
                writeLine("Started ${event.modelIdentifier}: ${event.totalReplications} reps per evaluation")
            is RunEvent.IterationCompleted -> {
                val inputsStr = event.bestInputs.entries
                    .joinToString(", ") { (k, v) -> "$k=$v" }
                writeLine(
                    "Iteration ${event.iteration}: best inputs ($inputsStr), " +
                        "estimated objective = ${event.estimatedObjectiveValue}"
                )
            }
            is RunEvent.RunCompleted ->
                writeLine("Optimization run completed in ${event.summary.completedReplications} reps")
            is RunEvent.RunFailed ->
                writeLine("Run failed: ${event.error}")
            is RunEvent.RunCancelled ->
                writeLine("Run cancelled: ${event.reason}")
            // Other event types are not central to the optimization narrative
            is RunEvent.ReplicationStarted,
            is RunEvent.ReplicationEnded,
            is RunEvent.SimTimeAdvanced,
            is RunEvent.ScenarioCompleted,
            is RunEvent.DesignPointCompleted -> Unit
        }
    }

    fun render(result: RunResult) {
        when (result) {
            is RunResult.OptimizationCompleted -> {
                val best = result.bestSolution
                writeLine("Best solution after ${result.summary.completedItems} iterations:")
                best.bestSolutionSoFar.inputMap.entries.forEach { (k, v) ->
                    writeLine("  $k = $v")
                }
                writeLine("  estimated objective = ${best.estimatedObjFncValue}")
            }
            is RunResult.Failed -> writeLine("Run failed: ${result.error}")
            is RunResult.Cancelled ->
                writeLine("Cancelled result: ${result.reason}")
            is RunResult.Completed,
            is RunResult.BatchCompleted ->
                // Not expected for an Optimization spec; surface defensively.
                writeLine("Unexpected non-optimization result: $result")
        }
    }

    private fun renderWarning(warning: RunWarningType) {
        when (warning) {
            is RunWarningType.ConfigurationWarnings ->
                warning.warnings.forEach {
                    writeLine("Configuration warning: ${it.path}: ${it.message}")
                }
            is RunWarningType.InfiniteHorizonNoTimeout ->
                writeLine(
                    "Configuration warning: ${warning.modelIdentifier} has no finite horizon or timeout"
                )
        }
    }
}
