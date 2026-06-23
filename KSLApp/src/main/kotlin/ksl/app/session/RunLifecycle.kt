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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val DEFAULT_CANCEL_REASON = "Cancelled by user"

/**
 * Internal owner of a submitted run's user-visible lifecycle.
 *
 * The worker coroutine still performs the simulation work and resource cleanup,
 * but terminal event/result ownership lives here so [RunHandle.cancel] can
 * resolve the public [RunHandle.result] without depending on the worker reaching
 * a particular `finally` block.  All terminal paths are idempotent: the first
 * caller wins and later attempts are ignored.
 */
internal class RunLifecycle(
    val runId: String,
    replay: Int,
    extraBufferCapacity: Int
) {

    private enum class State { Pending, Running, Terminal }

    private val lock = Any()
    private var state = State.Pending

    private val mutableEvents = MutableSharedFlow<RunEvent>(
        replay = replay,
        extraBufferCapacity = extraBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val resultDeferred = CompletableDeferred<RunResult>()

    val events: SharedFlow<RunEvent> = mutableEvents.asSharedFlow()
    val result: Deferred<RunResult> = resultDeferred

    /**
     * Claims the run for the worker coroutine.
     *
     * A cancellation that wins before this method is called moves the lifecycle
     * directly to Terminal, and the worker should return without attaching
     * observers, creating collectors, or initializing a model.
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
     * claimed.  This prevents late progress events from appearing after a
     * handle-side cancellation has completed the public lifecycle.
     */
    fun emitProgress(event: RunEvent): Boolean {
        require(!event.isTerminal) {
            "Terminal RunEvents must be emitted through RunLifecycle.complete(...)."
        }
        return synchronized(lock) {
            if (state == State.Terminal) false else mutableEvents.tryEmit(event)
        }
    }

    fun completeCancelled(reason: String = DEFAULT_CANCEL_REASON): Boolean =
        complete(RunResult.Cancelled(reason), RunEvent.RunCancelled(reason))

    fun completeFailed(error: KSLRuntimeError): Boolean =
        complete(RunResult.Failed(error), RunEvent.RunFailed(error))

    /**
     * Atomically claims the terminal lifecycle state, emits the terminal event,
     * and resolves the public result.
     */
    fun complete(result: RunResult, terminalEvent: RunEvent): Boolean {
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
     * Fallback for cancellations or failures that occur outside the normal
     * worker-owned terminal paths, including future launch/cancellation races.
     */
    fun attachJobFallback(job: Job) {
        job.invokeOnCompletion { cause ->
            when {
                cause is CancellationException -> {
                    completeCancelled(cause.message ?: DEFAULT_CANCEL_REASON)
                }
                cause != null -> {
                    completeFailed(
                        KSLRuntimeError.ExecutiveError(
                            simTime = 0.0,
                            replicationNumber = 0,
                            cause = cause
                        )
                    )
                }
                !resultDeferred.isCompleted -> {
                    completeFailed(
                        KSLRuntimeError.ExecutiveError(
                            simTime = 0.0,
                            replicationNumber = 0,
                            cause = IllegalStateException("Run job completed without a terminal result")
                        )
                    )
                }
            }
        }
    }

    private fun requireCompatible(result: RunResult, terminalEvent: RunEvent) {
        val compatible = when (result) {
            is RunResult.Cancelled -> terminalEvent is RunEvent.RunCancelled
            is RunResult.Failed -> terminalEvent is RunEvent.RunFailed
            is RunResult.Completed,
            is RunResult.BatchCompleted,
            is RunResult.OptimizationCompleted -> terminalEvent is RunEvent.RunCompleted
        }
        require(compatible) {
            "RunResult ${result::class.simpleName} is not compatible with RunEvent ${terminalEvent::class.simpleName}."
        }
    }

    private val RunEvent.isTerminal: Boolean
        get() = this is RunEvent.RunCompleted ||
            this is RunEvent.RunCancelled ||
            this is RunEvent.RunFailed
}
