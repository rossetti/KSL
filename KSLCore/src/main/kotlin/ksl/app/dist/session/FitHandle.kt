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

package ksl.app.dist.session

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking

/**
 * Live reference to a fitting job submitted through a
 * `DistributionModelingSession`, parallel to `ksl.app.session.RunHandle`.
 *
 * The job runs asynchronously on a background coroutine; observe `events`
 * for lifecycle progress and `await` `result` for the terminal outcome.
 * `result.await()` never throws — it resolves to a `FitResult` variant for
 * every outcome including cancellation and failure.
 */
interface FitHandle {

    /** Unique identifier for this fit, assigned by the session. */
    val fitId: String

    /**
     * Hot `SharedFlow` of lifecycle events. Replays a bounded recent
     * history, so a subscriber that attaches shortly after `submit`
     * returns can initialise its UI without missing the start event on
     * very fast fits. The flow is never closed; it simply stops emitting
     * after the terminal event (`FitCompleted`, `FitFailed`, or
     * `FitCancelled`).
     */
    val events: SharedFlow<FitEvent>

    /**
     * Deferred terminal result. Resolves normally with one of
     * `FitResult.Completed`, `FitResult.Failed`, or `FitResult.Cancelled`.
     */
    val result: Deferred<FitResult>

    /**
     * Requests cooperative cancellation. The public lifecycle resolves
     * immediately with a `Cancelled` result and `FitCancelled` event as
     * soon as this handle wins the terminal state; the worker is then
     * cancelled. Calling cancel more than once or after the fit has
     * already ended is safe and has no effect.
     */
    fun cancel(reason: String = "Cancelled by user")

    /**
     * Synchronously waits for `result` and returns it via `runBlocking`.
     * Do not call from inside a coroutine running on the session's own
     * scope, or from a UI thread — those callers should observe `result`
     * asynchronously instead.
     */
    fun awaitResultBlocking(): FitResult = runBlocking { result.await() }
}

/**
 * Package-internal implementation returned by the session and executor.
 */
internal class FitHandleImpl(
    private val lifecycle: FitLifecycle,
    private val job: Job
) : FitHandle {

    init {
        lifecycle.attachJobFallback(job)
    }

    override val fitId: String get() = lifecycle.fitId
    override val events: SharedFlow<FitEvent> get() = lifecycle.events
    override val result: Deferred<FitResult> get() = lifecycle.result

    override fun cancel(reason: String) {
        if (lifecycle.completeCancelled(reason)) {
            job.cancel(kotlinx.coroutines.CancellationException(reason))
        }
    }
}
