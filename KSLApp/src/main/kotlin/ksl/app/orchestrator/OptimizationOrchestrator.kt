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

package ksl.app.orchestrator

import ksl.app.KSLAppSession
import ksl.app.session.*
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.simulation.SimulationDispatcher
import ksl.utilities.io.KSL
import ksl.utilities.observers.Emitter
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import ksl.simulation.IterativeProcessIfc.EndingStatus

/**
 * Orchestrates a simulation-optimization run driven by a pre-configured [Solver].
 *
 * Low-level API note: application and UI code should prefer [KSLAppSession],
 * which owns scope lifecycle, validation, warning emission, and dispatch across
 * all supported run modes. This orchestrator remains public so lower-level
 * tests and advanced integrations can exercise optimization execution directly.
 *
 * Hooks [Solver.iterationEmitter] to emit one [RunEvent.IterationCompleted] per
 * solver iteration, carrying the best inputs and estimated objective value found so far.
 * The full per-iteration [SolverStateSnapshot] history is collected and returned in
 * [RunResult.OptimizationCompleted.iterationHistory] for convergence analysis.
 *
 * The returned [RunHandle] emits:
 * - one [RunEvent.IterationCompleted] after each solver iteration
 * - a terminal [RunEvent.RunCompleted] (or [RunEvent.RunFailed])
 *
 * The resolved [RunResult] is [RunResult.OptimizationCompleted] carrying the
 * [OrchestratorSummary], the best [SolverStateSnapshot], and the full
 * iteration history. Per-oracle-call simulation snapshots are not surfaced
 * (the evaluator abstraction makes them non-trivial to intercept).
 *
 * **Thread note:** [Solver.runAllIterations] is synchronous and runs on the coroutine's thread.
 * Since [SimulationDispatcher.default] is IO-backed, blocking one thread while the solver runs
 * is acceptable.
 */
class OptimizationOrchestrator {

    /**
     * Submits the optimization problem for asynchronous execution.
     *
     * @param solver  the pre-configured solver (problem, model builder, and initial point
     *                already set)
     * @param scope   coroutine scope that owns the orchestrator coroutine
     * @return a [RunHandle] for observing progress and obtaining the result
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun submit(
        solver: Solver,
        scope: CoroutineScope = CoroutineScope(SimulationDispatcher.default + SupervisorJob()),
        preRunWarnings: List<RunWarningType> = emptyList()
    ): RunHandle {
        val runId = KSL.randomUUIDString()
        val lifecycle = RunLifecycle(runId, replay = 128, extraBufferCapacity = 64)

        val job = scope.launch(SimulationDispatcher.default, CoroutineStart.ATOMIC) {
            if (!lifecycle.tryStart()) return@launch

            val beginTime = Clock.System.now()
            var iterConnection: Emitter.Connection? = null
            val iterationHistory = mutableListOf<SolverStateSnapshot>()
            try {
                ensureActive()

                for (warning in preRunWarnings) {
                    lifecycle.emitProgress(RunEvent.RunWarning(warning))
                }

                lifecycle.emitProgress(
                    RunEvent.OptimizationRunStarted(
                        runId = runId,
                        modelIdentifier = solver.problemDefinition.modelIdentifier,
                        maxIterations = solver.maximumNumberIterations,
                        startTime = beginTime
                    )
                )

                iterConnection = solver.iterationEmitter.attach { snapshot: SolverStateSnapshot ->
                    iterationHistory.add(snapshot)
                    lifecycle.emitProgress(
                        RunEvent.IterationCompleted(
                            iteration = snapshot.iterationNumber,
                            bestInputs = HashMap(snapshot.bestSolutionSoFar.inputMap),
                            estimatedObjectiveValue = snapshot.estimatedObjFncValue
                        )
                    )
                }

                solver.runAllIterations()

                // Detect cancellation that arrived while the blocking solver call
                // was running (stopIterations() signals the solver to exit cleanly,
                // but the Job is still marked cancelled until we check here).
                ensureActive()

                val endTime = Clock.System.now()
                val completedIterations = solver.iterationCounter
                val summary = OrchestratorSummary(
                    runId = runId,
                    orchestratorName = solver.name,
                    totalItems = completedIterations,
                    completedItems = completedIterations,
                    failedItems = 0,
                    beginTime = beginTime,
                    endTime = endTime
                )
                val completedEvent =
                    RunEvent.RunCompleted(
                        RunSummary(
                            runId = runId,
                            modelIdentifier = solver.name,
                            experimentName = solver.name,
                            requestedReplications = completedIterations,
                            completedReplications = completedIterations,
                            endingStatus = EndingStatus.COMPLETED_ALL_STEPS,
                            beginTime = beginTime,
                            endTime = endTime
                        )
                    )
                val bestSnapshot = iterationHistory.lastOrNull()
                    ?: error("Solver completed but emitted no iteration snapshots")
                lifecycle.complete(
                    RunResult.OptimizationCompleted(summary, bestSnapshot, iterationHistory),
                    completedEvent
                )

            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val reason = e.message ?: "Cancelled by user"
                    lifecycle.completeCancelled(reason)
                }
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    val error = KSLRuntimeError.ExecutiveError(0.0, 0, e)
                    lifecycle.completeFailed(error)
                }
            } finally {
                iterConnection?.let { solver.iterationEmitter.detach(it) }
            }
        }

        return RunHandleImpl(
            lifecycle, job,
            onCancelHook = { reason -> runCatching { solver.stopIterations(reason) } }
        )
    }
}
