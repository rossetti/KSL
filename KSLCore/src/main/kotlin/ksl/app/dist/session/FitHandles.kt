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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock

/**
 * Builds a pre-failed handle that resolves immediately with the supplied
 * error. Used by the session when validation rejects a submission or the
 * session is already closed: callers see the same handle/event/result
 * shape on the failure path as on the success path, without dispatching
 * any coroutine work.
 */
internal fun failedFitHandle(fitId: String, error: FittingError): FitHandle {
    val replay = 1
    val events = MutableSharedFlow<FitEvent>(
        replay = replay,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val now = Clock.System.now()
    events.tryEmit(FitEvent.FitFailed(fitId, error, now))
    val deferred = CompletableDeferred<FitResult>().apply { complete(FitResult.Failed(error)) }
    return PreResolvedFitHandle(fitId, events.asSharedFlow(), deferred)
}

private class PreResolvedFitHandle(
    override val fitId: String,
    override val events: SharedFlow<FitEvent>,
    override val result: Deferred<FitResult>
) : FitHandle {
    override fun cancel(reason: String) { /* already resolved; no-op */ }
}
