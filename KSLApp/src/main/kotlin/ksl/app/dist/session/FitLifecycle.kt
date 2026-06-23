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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock

private const val DEFAULT_CANCEL_REASON = "Cancelled by user"

/**
 * Internal owner of a submitted fit's user-visible lifecycle, parallel to
 * `ksl.app.session.RunLifecycle`.
 *
 * The worker coroutine drives the fit and resource cleanup, but terminal
 * event/result ownership lives here so `FitHandle.cancel` can resolve the
 * public result deterministically without depending on the worker reaching
 * a particular finally block. All terminal paths are idempotent: the first
 * caller wins; later attempts are ignored.
 */
internal class FitLifecycle(
    val fitId: String,
    replay: Int,
    extraBufferCapacity: Int
) {

    private enum class State { Pending, Running, Terminal }

    private val lock = Any()
    private var state = State.Pending

    private val mutableEvents = MutableSharedFlow<FitEvent>(
        replay = replay,
        extraBufferCapacity = extraBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val resultDeferred = CompletableDeferred<FitResult>()

    val events: SharedFlow<FitEvent> = mutableEvents.asSharedFlow()
    val result: Deferred<FitResult> = resultDeferred

    /**
     * Claims the fit for the worker coroutine. A cancellation that wins
     * before this method is called moves the lifecycle directly to
     * Terminal, and the worker should return without doing any further
     * work or emitting any further events.
     */
    fun tryStart(): Boolean = synchronized(lock) {
        if (state == State.Pending) {
            state = State.Running
            true
        } else {
            false
        }
    }

    /**
     * Emits a non-terminal event unless a terminal result has already been
     * claimed. Prevents late progress events from appearing after a
     * cancellation has resolved the public lifecycle.
     */
    fun emitProgress(event: FitEvent): Boolean {
        require(!event.isTerminal) {
            "Terminal FitEvents must be emitted through FitLifecycle.complete(...)."
        }
        return synchronized(lock) {
            if (state == State.Terminal) false else mutableEvents.tryEmit(event)
        }
    }

    fun completeCancelled(reason: String = DEFAULT_CANCEL_REASON): Boolean =
        complete(
            FitResult.Cancelled(reason),
            FitEvent.FitCancelled(fitId, reason, Clock.System.now())
        )

    fun completeFailed(error: FittingError): Boolean =
        complete(
            FitResult.Failed(error),
            FitEvent.FitFailed(fitId, error, Clock.System.now())
        )

    fun completeWithReport(result: FitResult.Completed): Boolean =
        complete(result, FitEvent.FitCompleted(fitId, result.report, Clock.System.now()))

    fun completeWithBatch(result: FitResult.BatchCompleted): Boolean =
        complete(result, FitEvent.BatchFitCompleted(fitId, result.report, Clock.System.now()))

    /**
     * Atomically claims the terminal state, emits the terminal event, and
     * resolves the public result.
     */
    fun complete(result: FitResult, terminalEvent: FitEvent): Boolean {
        requireCompatible(result, terminalEvent)

        val wonTerminal = synchronized(lock) {
            if (state == State.Terminal) {
                false
            } else {
                state = State.Terminal
                mutableEvents.tryEmit(terminalEvent)
                true
            }
        }

        if (wonTerminal) {
            resultDeferred.complete(result)
        }
        return wonTerminal
    }

    /**
     * Fallback for cancellations or worker failures that escape the normal
     * terminal paths. Mirrors `RunLifecycle.attachJobFallback`.
     */
    fun attachJobFallback(job: Job) {
        job.invokeOnCompletion { cause ->
            when {
                cause is CancellationException -> {
                    completeCancelled(cause.message ?: DEFAULT_CANCEL_REASON)
                }
                cause != null -> {
                    completeFailed(
                        FittingError.RuntimeError(
                            message = cause.message ?: (cause::class.simpleName ?: "unknown error"),
                            cause = cause
                        )
                    )
                }
                !resultDeferred.isCompleted -> {
                    completeFailed(
                        FittingError.RuntimeError(
                            message = "Fit job completed without a terminal result",
                            cause = null
                        )
                    )
                }
            }
        }
    }

    private fun requireCompatible(result: FitResult, terminalEvent: FitEvent) {
        val compatible = when (result) {
            is FitResult.Cancelled -> terminalEvent is FitEvent.FitCancelled
            is FitResult.Failed -> terminalEvent is FitEvent.FitFailed
            is FitResult.Completed -> terminalEvent is FitEvent.FitCompleted
            is FitResult.BatchCompleted -> terminalEvent is FitEvent.BatchFitCompleted
        }
        require(compatible) {
            "FitResult ${result::class.simpleName} is not compatible with FitEvent ${terminalEvent::class.simpleName}."
        }
    }

    private val FitEvent.isTerminal: Boolean
        get() = this is FitEvent.FitCompleted ||
            this is FitEvent.BatchFitCompleted ||
            this is FitEvent.FitCancelled ||
            this is FitEvent.FitFailed
}
