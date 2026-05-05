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

import ksl.app.session.*
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.simulation.SimulationDispatcher
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.SimulationSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock
import ksl.simulation.IterativeProcessIfc.EndingStatus

/**
 * Orchestrates a designed-experiment run from a pre-built [ParallelDesignedExperiment].
 *
 * Calls [ParallelDesignedExperiment.simulateAll] with an optional [numRepsPerDesignPoint]
 * override, capturing one [SimulationSnapshot.ExperimentCompleted] per design point via the
 * additive `onScenarioComplete` callback.
 *
 * The returned [RunHandle] emits:
 * - one [RunEvent.DesignPointCompleted] per design point (in commit order)
 * - a terminal [RunEvent.RunCompleted] (or [RunEvent.RunFailed])
 *
 * The resolved [RunResult] is [RunResult.OrchestratorCompleted] carrying one snapshot
 * per successfully completed design point.
 *
 * **Thread note:** [ParallelDesignedExperiment.simulateAll] calls `runBlocking` internally.
 * This is called from a coroutine on [SimulationDispatcher.default] (an IO-backed dispatcher),
 * so blocking one thread is acceptable.
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
        scope: CoroutineScope = CoroutineScope(SimulationDispatcher.default + SupervisorJob())
    ): RunHandle {
        val runId = KSL.randomUUIDString()
        val mutableEvents = MutableSharedFlow<RunEvent>(replay = 128, extraBufferCapacity = 64)
        val result = CompletableDeferred<RunResult>()

        val job = scope.launch(SimulationDispatcher.default) {
            val beginTime = Clock.System.now()
            var pendingResult: RunResult? = null
            try {
                val totalPoints = experiment.design.designPoints().size
                val capturedSnapshots = mutableListOf<SimulationSnapshot.ExperimentCompleted?>()
                var completedIdx = 0

                experiment.simulateAll(
                    numRepsPerDesignPoint = numRepsPerDesignPoint,
                    onScenarioComplete = { _, snapshot ->
                        completedIdx++
                        capturedSnapshots.add(snapshot)
                        mutableEvents.tryEmit(
                            RunEvent.DesignPointCompleted(
                                pointId = completedIdx,
                                index = completedIdx,
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
                mutableEvents.tryEmit(
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
                )
                pendingResult = RunResult.OrchestratorCompleted(summary, successSnapshots)

            } catch (e: Exception) {
                withContext(NonCancellable) {
                    val error = KSLRuntimeError.ExecutiveError(0.0, 0, e)
                    mutableEvents.tryEmit(RunEvent.RunFailed(error))
                    pendingResult = RunResult.Failed(error)
                }
            } finally {
                result.complete(
                    pendingResult ?: RunResult.Failed(
                        KSLRuntimeError.ExecutiveError(
                            0.0, 0,
                            IllegalStateException("ExperimentOrchestrator ended without a result")
                        )
                    )
                )
            }
        }

        return RunHandleImpl(runId, mutableEvents.asSharedFlow(), result, job)
    }
}
