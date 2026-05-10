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

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking

/**
 * Live reference to a simulation run submitted via [Runner.submit].
 *
 * A [RunHandle] is returned immediately by [Runner.submit]; the simulation
 * executes asynchronously on a background thread.  Callers observe progress
 * by collecting [events] and obtain the terminal outcome by awaiting [result].
 *
 * ```kotlin
 * val handle = runner.submit(RunRequest.SingleRun(model))
 *
 * // Collect lifecycle events (usually in a dedicated coroutine):
 * launch { handle.events.collect { event -> updateUi(event) } }
 *
 * // Await the terminal result:
 * when (val r = handle.result.await()) {
 *     is RunResult.Completed -> showResults(r.summary)
 *     is RunResult.Cancelled -> showCancelled(r.reason)
 *     is RunResult.Failed    -> showError(r.error)
 * }
 * ```
 */
interface RunHandle {

    /** Unique identifier for this run, assigned by [Runner]. */
    val runId: String

    /**
     * Hot [SharedFlow] of lifecycle events emitted during the run.
     *
     * The underlying flow replays a bounded recent history, so a subscriber
     * that attaches shortly after [Runner.submit] returns can initialise a
     * progress indicator without missing the run start on very fast models.
     *
     * The flow is never closed; it simply stops emitting after the terminal
     * event ([RunEvent.RunCompleted], [RunEvent.RunCancelled], or
     * [RunEvent.RunFailed]).  Collectors should cancel their collection job
     * after receiving a terminal event if they do not want to block indefinitely.
     */
    val events: SharedFlow<RunEvent>

    /**
     * Deferred terminal result of the run.
     *
     * Resolves **normally** (never throws from `await()`) with one of:
     * - [RunResult.Completed] — all requested replications ran (or the model
     *   stopped itself via `endSimulation()`)
     * - [RunResult.Failed] — an unexpected exception occurred
     * - [RunResult.Cancelled] — [cancel] was called
     */
    val result: Deferred<RunResult>

    /**
     * Requests cooperative cancellation of the run.
     *
     * The public lifecycle is cancelled immediately: [RunEvent.RunCancelled]
     * is emitted and [result] resolves as [RunResult.Cancelled] as soon as this
     * handle wins the terminal lifecycle state.  The worker coroutine is then
     * cancelled cooperatively; if a replication is already executing, it may
     * finish before worker cleanup observes the cancellation.
     *
     * Calling [cancel] more than once, or after the run has already ended,
     * is safe and has no effect.
     *
     * @param reason a human-readable explanation, shown in [RunEvent.RunCancelled]
     *        and [RunResult.Cancelled]; defaults to `"Cancelled by user"`
     */
    fun cancel(reason: String = "Cancelled by user")

    /**
     * Synchronously waits for [result] and returns it.
     *
     * Bridges the [Deferred]-based [result] back into a non-coroutine caller
     * via `runBlocking`.  Use this from a non-suspend `main()` function or
     * any other synchronous entry point where introducing `suspend`/
     * `runBlocking` would be unwelcome boilerplate.
     *
     * Coroutine-aware callers should prefer `result.await()` directly so
     * they can compose the wait with their own coroutine context.
     *
     * **Caution — `runBlocking` deadlock hazard.**  Do not call this from
     * inside a coroutine running on the same dispatcher the simulation
     * uses (typically [ksl.simulation.SimulationDispatcher.default]).  The
     * calling thread blocks waiting for work that may need that thread,
     * which can deadlock.  Likewise, do not call from a UI thread (Swing
     * EDT, JavaFX FX thread); doing so freezes the UI for the duration
     * of the run.  In both cases, observe [result] asynchronously
     * instead — `result.await()` from a coroutine, or
     * `events.collect { … }` for a UI-friendly progress stream.
     *
     * @return the same [RunResult] that [result] would resolve to
     */
    fun awaitResultBlocking(): RunResult = runBlocking { result.await() }
}

/**
 * Package-private implementation returned by [Runner] and the orchestrators.
 *
 * [onCancelHook] is invoked synchronously after this handle claims the terminal
 * cancellation state and before the coroutine Job is cancelled.  Orchestrators
 * use this to signal domain-level stop mechanisms — for example,
 * [OptimizationOrchestrator] passes `solver::stopIterations` so the solver's
 * iteration loop exits at the next boundary rather than waiting for the blocking
 * call to complete.
 */
internal class RunHandleImpl(
    private val lifecycle: RunLifecycle,
    private val job: Job,
    private val onCancelHook: ((String) -> Unit)? = null
) : RunHandle {

    init {
        lifecycle.attachJobFallback(job)
    }

    override val runId: String
        get() = lifecycle.runId

    override val events: SharedFlow<RunEvent>
        get() = lifecycle.events

    override val result: Deferred<RunResult>
        get() = lifecycle.result

    override fun cancel(reason: String) {
        if (lifecycle.completeCancelled(reason)) {
            runCatching { onCancelHook?.invoke(reason) }
            job.cancel(kotlinx.coroutines.CancellationException(reason))
        }
    }
}
