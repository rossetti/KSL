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

package ksl.app.session

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock
import ksl.simulation.IterativeProcessIfc.EndingStatus.*
import ksl.simulation.SimulationDispatcher
import ksl.utilities.io.KSL
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Bounded recent-history replay for [RunHandle.events].
 *
 * This is intentionally small, but larger than one event so an immediate
 * subscriber that starts just after a very fast run begins can still observe
 * the Phase 1 acceptance sequence for a modest replication count.
 */
private const val RUN_EVENT_REPLAY = 128

/**
 * Entry point for asynchronous simulation execution.
 *
 * [Runner] wraps [ksl.simulation.Model.simulate]'s synchronous replication
 * loop in a coroutine, exposes lifecycle progress as a [RunEvent] flow, and
 * ensures that [RunAttachmentIfc] instances are attached before the experiment
 * begins and detached after it ends regardless of outcome.
 *
 * ## Basic usage
 *
 * ```kotlin
 * val model = Model("MM1")
 * // ... configure model ...
 * model.numberOfReplications = 30
 * model.lengthOfReplication = 20_000.0
 *
 * val runner = Runner()
 * val handle = runner.submit(RunRequest.SingleRun(model))
 *
 * launch { handle.events.collect { println(it) } }
 *
 * when (val r = handle.result.await()) {
 *     is RunResult.Completed -> println("Done: ${r.summary}")
 *     is RunResult.Cancelled -> println("Cancelled: ${r.reason}")
 *     is RunResult.Failed    -> println("Error: ${r.error}")
 * }
 * ```
 *
 * ## How the replication loop works
 *
 * Rather than calling `model.simulate()` (which blocks the calling thread for
 * the entire experiment), [Runner] drives the replication loop manually using
 * the public API:
 *
 * ```
 * model.initializeReplications()   // sets beginExecutionTime, triggers BEFORE_EXPERIMENT
 * while (hasNextReplication && !isDone):
 *     ensureActive()               // cooperative cancellation check
 *     emit ReplicationStarted
 *     model.runNextReplication()   // one full replication, synchronous
 *     emit ReplicationEnded
 * model.endSimulation()            // triggers endIterations → AFTER_EXPERIMENT
 * emit RunCompleted
 * ```
 *
 * This approach lets [Runner]:
 * - emit [RunEvent.ReplicationStarted] / [RunEvent.ReplicationEnded] between steps
 * - check for cooperative cancellation between replications
 * - honour `model.autoCSVReports` (mirrored from `simulate()`)
 *
 * [RunAttachmentIfc] instances attached via [RunRequest.SingleRun.attachments]
 * call `model.attachModelElementObserver(...)` in their [RunAttachmentIfc.onAttach]
 * implementations.  Those observers then fire synchronously on the simulation
 * thread at fine-grained lifecycle boundaries inside each `runNextReplication()`
 * call (`beforeReplication`, `warmUp`, `afterReplication`, etc.), giving
 * attachments full access to within-replication lifecycle events.
 *
 * ## Cancellation
 *
 * [RunHandle.cancel] cancels the coroutine's [Job].  Cancellation is
 * **cooperative between replications**: the replication currently executing
 * will complete; the loop then checks `ensureActive()` before starting the
 * next replication, finds the job cancelled, and emits [RunEvent.RunCancelled].
 *
 * ## stopReplication() vs endSimulation()
 *
 * These two methods operate at different levels of the KSL execution hierarchy
 * and must not be confused:
 *
 * - `ModelElement.stopReplication()` — signals the **Executive's inner event
 *   loop** to halt the current replication.  Call this from within model code
 *   (event handlers, process steps) when you want to end a replication early.
 *
 * - `Model.endSimulation()` — signals the **outer iterative-process loop** to
 *   stop scheduling further replications.  This flag is only checked between
 *   replications, never during one.  Calling it from inside an event handler
 *   while the Executive is running has no effect on the current replication
 *   and will cause an infinite-horizon model to hang indefinitely.
 *
 * [Runner] calls `endSimulation()` in two places where it is safe: after the
 * normal replication loop exits (no replication is running) and in the
 * cancellation handler (cancellation is cooperative and only fires between
 * replications at `ensureActive()`).
 *
 * ## Thread safety
 *
 * All simulation work runs on a single thread within [SimulationDispatcher.default].
 * [RunEvent] emissions use `tryEmit` from the same thread (never from a
 * concurrent thread), so there are no data races on the [MutableSharedFlow].
 */
class Runner {

    /**
     * Submits [request] for asynchronous execution and returns a [RunHandle]
     * immediately.
     *
     * The simulation runs on [SimulationDispatcher.default] in a coroutine
     * launched within [scope].  If [scope] is cancelled externally (e.g.
     * application shutdown), the simulation coroutine is also cancelled and
     * [RunEvent.RunCancelled] is emitted.
     *
     * @param request the [RunRequest.SingleRun] to execute
     * @param scope the coroutine scope that owns the simulation coroutine;
     *        defaults to a new [SupervisorJob]-backed scope on
     *        [SimulationDispatcher.default]
     * @return a [RunHandle] for observing progress and obtaining the result
     */
    fun submit(
        request: RunRequest.SingleRun,
        scope: CoroutineScope = CoroutineScope(SimulationDispatcher.default + SupervisorJob())
    ): RunHandle {
        val runId = KSL.randomUUIDString()
        val mutableEvents = MutableSharedFlow<RunEvent>(replay = RUN_EVENT_REPLAY, extraBufferCapacity = 64)
        val result = CompletableDeferred<RunResult>()

        val job = scope.launch(SimulationDispatcher.default) {
            val model = request.model

            // Give each attachment access to the model and this coroutine's scope
            // so it can register observers and acquire resources before the
            // experiment begins.
            for (att in request.attachments) {
                att.onAttach(model, this)
            }

            // Holds the terminal result until after onDetach() has run for every
            // attachment.  Completing the Deferred here (rather than inside the
            // catch blocks) guarantees that handle.result.await() never returns
            // before cleanup has finished.
            var pendingResult: RunResult? = null

            try {
                // Detect the infinite-horizon / no-timeout condition before the
                // experiment starts and surface it as a warning event so a GUI
                // can alert the user without waiting to see if the run hangs.
                if (model.lengthOfReplication.isInfinite() &&
                    model.maximumAllowedExecutionTimePerReplication == Duration.ZERO
                ) {
                    mutableEvents.tryEmit(
                        RunEvent.RunWarning(RunWarningType.InfiniteHorizonNoTimeout(model.modelIdentifier))
                    )
                    logger.warn {
                        "Model '${model.modelIdentifier}': infinite replication length with no " +
                        "execution-time limit — run will not stop unless the model calls endSimulation() " +
                        "or RunHandle.cancel() is called."
                    }
                }

                // Mirror simulate()'s CSV setup so model.autoCSVReports is honoured.
                if (model.autoCSVReports) model.turnOnCSVStatisticalReports()
                else model.turnOffCSVStatisticalReports()

                val startTime = Clock.System.now()

                // initializeReplications() sets model.beginExecutionTime and
                // triggers BEFORE_EXPERIMENT on all attached ModelElementObservers.
                model.initializeReplications()

                mutableEvents.tryEmit(
                    RunEvent.RunStarted(
                        runId = runId,
                        modelIdentifier = model.modelIdentifier,
                        totalReplications = model.numberOfReplications,
                        startTime = startTime
                    )
                )

                var nextRepNum = 0
                // model.isDone is set by stoppingConditionCheck() inside each
                // runNextReplication() call.  Checking both guards against
                // termination due to execution-time exceeded or a model-internal
                // stop condition, not just exhaustion of planned replications.
                while (model.hasNextReplication() && !model.isDone) {
                    ensureActive()   // throws CancellationException if handle.cancel() was called
                    nextRepNum++
                    mutableEvents.tryEmit(RunEvent.ReplicationStarted(nextRepNum, model.numberOfReplications))

                    // Blocking call — one full replication executes here.
                    // ModelElementObserver callbacks fire synchronously during this call.
                    model.runNextReplication()

                    mutableEvents.tryEmit(RunEvent.ReplicationEnded(model.currentReplicationNumber, model.numberOfReplications))
                }

                // Capture ending status before endSimulation() so we read the
                // value set by stoppingConditionCheck() inside the loop.
                val endingStatus = deriveEndingStatus(model)

                // endSimulation() triggers endIterations() → afterExperimentActions()
                // on all model elements, and sets model.endExecutionTime.
                model.endSimulation()

                val summary = RunSummary(
                    runId = runId,
                    modelIdentifier = model.modelIdentifier,
                    experimentName = model.experimentName,
                    requestedReplications = model.numberOfReplications,
                    completedReplications = model.currentReplicationNumber,
                    endingStatus = endingStatus,
                    beginTime = model.beginExecutionTime,
                    endTime = model.endExecutionTime
                )
                mutableEvents.tryEmit(RunEvent.RunCompleted(summary))
                pendingResult = RunResult.Completed(summary)

            } catch (e: CancellationException) {
                // The coroutine Job was cancelled via handle.cancel().
                // Use NonCancellable so we can safely emit the terminal event
                // and stage the result despite the cancelled state.
                withContext(NonCancellable) {
                    val reason = e.message ?: "Cancelled by user"
                    // Cancellation is cooperative: ensureActive() only fires
                    // between replications (before runNextReplication()), so no
                    // replication is executing here.  endSimulation() is therefore
                    // safe — it triggers post-experiment cleanup (endIterations →
                    // afterExperimentActions) without interfering with a running
                    // Executive.  Best-effort: ignore failures if the model never
                    // reached initializeReplications().
                    runCatching { model.endSimulation(reason) }
                    val completedReps = model.currentReplicationNumber
                    mutableEvents.tryEmit(RunEvent.RunCancelled(reason))
                    pendingResult = RunResult.Cancelled(reason)
                    logger.info { "Run '$runId' cancelled: $reason (completed $completedReps of ${model.numberOfReplications} reps)" }
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    val error = KSLRuntimeError.ExecutiveError(
                        simTime = model.time(),
                        replicationNumber = model.currentReplicationNumber,
                        cause = e
                    )
                    mutableEvents.tryEmit(RunEvent.RunFailed(error))
                    pendingResult = RunResult.Failed(error)
                    logger.error(e) { "Run '$runId' failed at simTime=${error.simTime}, rep=${error.replicationNumber}" }
                }
            } finally {
                // onDetach() runs before the Deferred is resolved so that
                // handle.result.await() never returns before cleanup is complete.
                for (att in request.attachments) {
                    runCatching { att.onDetach() }
                        .onFailure { logger.warn(it) { "RunAttachmentIfc.onDetach() threw for attachment $att" } }
                }
                result.complete(
                    pendingResult ?: RunResult.Failed(
                        KSLRuntimeError.ExecutiveError(0.0, 0, IllegalStateException("Run ended without a result"))
                    )
                )
            }
        }

        return RunHandleImpl(runId, mutableEvents.asSharedFlow(), result, job)
    }

    /**
     * Derives the [ksl.simulation.IterativeProcessIfc.EndingStatus] from the
     * model's public boolean status properties after the replication loop exits.
     *
     * These properties delegate to `myReplicationProcess` (private inside
     * [ksl.simulation.Model]) so we infer the enum value from the booleans
     * rather than reading it directly.  The precedence order matches the
     * `stoppingConditionCheck()` logic in [ksl.simulation.IterativeProcess].
     */
    private fun deriveEndingStatus(model: ksl.simulation.Model): ksl.simulation.IterativeProcessIfc.EndingStatus =
        when {
            model.allReplicationsCompleted -> COMPLETED_ALL_STEPS
            model.stoppedByCondition       -> MET_STOPPING_CONDITION
            model.isExecutionTimeExceeded  -> EXCEEDED_EXECUTION_TIME
            model.numberReplicationsCompleted == 0 -> NO_STEPS_EXECUTED
            else                           -> UNFINISHED
        }
}
