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
import ksl.controls.experiments.DesignPoint
import ksl.controls.experiments.DesignedExperiment
import ksl.controls.experiments.DesignedExperimentIfc
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.simulation.SimulationDispatcher
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.SimulationSnapshot
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import ksl.simulation.IterativeProcessIfc.EndingStatus

/**
 * Orchestrates a designed-experiment run from a pre-built
 * [DesignedExperimentIfc] — either a [ParallelDesignedExperiment]
 * (concurrent) or a [DesignedExperiment] (sequential).  Branches on
 * the concrete type to call the right `simulateAll` entry point.
 *
 * Low-level API note: application and UI code should prefer [KSLAppSession],
 * which owns scope lifecycle, validation, warning emission, and dispatch across
 * all supported run modes. This orchestrator remains public so lower-level
 * tests and advanced integrations can exercise designed-experiment execution
 * directly.
 *
 * Capturing per-design-point snapshots via the `onDesignPointComplete`
 * callback works for both variants — `ParallelDesignedExperiment`
 * exposes the callback natively; `DesignedExperiment` was retrofitted
 * to attach an `InMemorySnapshotCollector` per point so the same shape
 * holds for both execution modes.
 *
 * The returned [RunHandle] emits:
 * - one [RunEvent.DesignPointCompleted] per design point (in commit order)
 * - a terminal [RunEvent.RunCompleted] (or [RunEvent.RunFailed])
 *
 * The resolved [RunResult] is [RunResult.BatchCompleted] carrying one snapshot
 * per successfully completed design point.
 */
class ExperimentOrchestrator {

    /**
     * Submits the designed experiment for asynchronous execution.
     *
     * @param experiment          the pre-built experiment to run
     * @param numRepsPerDesignPoint optional replication count override; `null` uses the
     *                            value already set on each design point
     * @param scope               coroutine scope that owns the orchestrator coroutine
     * @return a [RunHandle] for observing progress and obtaining the result
     */
    fun submit(
        experiment: DesignedExperimentIfc,
        numRepsPerDesignPoint: Int? = null,
        scope: CoroutineScope = CoroutineScope(SimulationDispatcher.default + SupervisorJob()),
        preRunWarnings: List<RunWarningType> = emptyList()
    ): RunHandle {
        val runId = KSL.randomUUIDString()
        val lifecycle = RunLifecycle(runId, replay = 128, extraBufferCapacity = 64)

        val job = scope.launch(SimulationDispatcher.default, CoroutineStart.ATOMIC) {
            if (!lifecycle.tryStart()) return@launch

            val beginTime = Clock.System.now()
            try {
                ensureActive()

                for (warning in preRunWarnings) {
                    lifecycle.emitProgress(RunEvent.RunWarning(warning))
                }

                val totalPoints = experiment.design.designPoints().size
                val capturedSnapshots = mutableListOf<SimulationSnapshot.ExperimentCompleted?>()

                lifecycle.emitProgress(
                    RunEvent.ExperimentRunStarted(
                        runId = runId,
                        modelIdentifier = experiment.name,
                        totalDesignPoints = totalPoints,
                        startTime = beginTime
                    )
                )

                // Per-point progress + snapshot capture: identical
                // shape for both variants thanks to the retrofitted
                // DesignedExperiment callback.
                val onPointComplete: (DesignPoint, SimulationSnapshot.ExperimentCompleted?) -> Unit =
                    { designPoint, snapshot ->
                        capturedSnapshots.add(snapshot)
                        lifecycle.emitProgress(
                            RunEvent.DesignPointCompleted(
                                pointId = designPoint.number,
                                index = capturedSnapshots.size,
                                totalDesignPoints = totalPoints,
                                snapshot = snapshot
                            )
                        )
                    }
                when (experiment) {
                    is ParallelDesignedExperiment -> experiment.simulateAll(
                        numRepsPerDesignPoint = numRepsPerDesignPoint,
                        onDesignPointComplete = onPointComplete
                    )
                    is DesignedExperiment -> {
                        // Sequential simulateAll is blocking — run it
                        // on the simulation dispatcher so the
                        // orchestrator coroutine isn't blocked.
                        withContext(SimulationDispatcher.default) {
                            experiment.simulateAll(
                                numRepsPerDesignPoint = numRepsPerDesignPoint,
                                onDesignPointComplete = onPointComplete
                            )
                        }
                    }
                    else -> error(
                        "Unsupported DesignedExperimentIfc variant: ${experiment::class.simpleName}"
                    )
                }

                val endTime = Clock.System.now()
                val successSnapshots = capturedSnapshots.filterNotNull()
                val failedCount = capturedSnapshots.count { it == null }
                val endingStatus = if (failedCount == 0) EndingStatus.COMPLETED_ALL_STEPS
                                   else EndingStatus.UNFINISHED

                val summary = OrchestratorSummary(
                    runId = runId,
                    orchestratorName = experiment.name,
                    totalItems = totalPoints,
                    completedItems = successSnapshots.size,
                    failedItems = failedCount,
                    beginTime = beginTime,
                    endTime = endTime
                )
                val completedEvent =
                    RunEvent.RunCompleted(
                        RunSummary(
                            runId = runId,
                            modelIdentifier = experiment.name,
                            experimentName = experiment.name,
                            requestedReplications = totalPoints,
                            completedReplications = successSnapshots.size,
                            endingStatus = endingStatus,
                            beginTime = beginTime,
                            endTime = endTime
                        )
                    )
                lifecycle.complete(
                    RunResult.BatchCompleted(summary, successSnapshots),
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
            }
        }

        return RunHandleImpl(lifecycle, job)
    }
}
