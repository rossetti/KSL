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
     * `replay = 1` — a subscriber that attaches shortly after [Runner.submit]
     * returns will immediately receive the most recent event (typically
     * [RunEvent.RunStarted] or the latest [RunEvent.ReplicationEnded]) so it
     * can initialise a progress indicator without missing the run start.
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
     * Cancellation is cooperative and takes effect **between replications**:
     * the replication currently executing will complete, and then the run
     * loop will stop before starting the next one.  [RunEvent.RunCancelled]
     * is guaranteed to be emitted and [result] will resolve as
     * [RunResult.Cancelled].
     *
     * Calling [cancel] more than once, or after the run has already ended,
     * is safe and has no effect.
     *
     * @param reason a human-readable explanation, shown in [RunEvent.RunCancelled]
     *        and [RunResult.Cancelled]; defaults to `"Cancelled by user"`
     */
    fun cancel(reason: String = "Cancelled by user")
}

/**
 * Package-private implementation returned by [Runner].
 */
internal class RunHandleImpl(
    override val runId: String,
    override val events: SharedFlow<RunEvent>,
    override val result: Deferred<RunResult>,
    private val job: Job
) : RunHandle {

    override fun cancel(reason: String) {
        job.cancel(kotlinx.coroutines.CancellationException(reason))
    }
}
