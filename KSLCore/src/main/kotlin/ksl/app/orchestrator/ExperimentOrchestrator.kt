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
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.simulation.SimulationDispatcher
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.SimulationSnapshot
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import ksl.simulation.IterativeProcessIfc.EndingStatus

/**
 * Orchestrates a designed-experiment run from a pre-built [ParallelDesignedExperiment].
 *
 * Low-level API note: application and UI code should prefer [KSLAppSession],
 * which owns scope lifecycle, validation, warning emission, and dispatch across
 * all supported run modes. This orchestrator remains public so lower-level
 * tests and advanced integrations can exercise designed-experiment execution
 * directly.
 *
 * Calls [ParallelDesignedExperiment.simulateAll] with an optional [numRepsPerDesignPoint]
 * override, capturing one [SimulationSnapshot.ExperimentCompleted] per design point via the
 * [onDesignPointComplete] callback.
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
        experiment: ParallelDesignedExperiment,
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

                experiment.simulateAll(
                    numRepsPerDesignPoint = numRepsPerDesignPoint,
                    onDesignPointComplete = { designPoint: DesignPoint, snapshot ->
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
                )

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
