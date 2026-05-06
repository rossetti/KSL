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

import kotlinx.coroutines.Job
import ksl.utilities.io.KSL

private const val FAILED_HANDLE_REPLAY = 8

/**
 * Creates a [RunHandle] that has already reached a failed terminal state.
 *
 * Used by higher-level facades when pre-flight validation or configuration
 * checks fail before there is any worker coroutine to launch.
 */
internal fun failedRunHandle(
    error: KSLRuntimeError,
    runId: String = KSL.randomUUIDString()
): RunHandle {
    val lifecycle = RunLifecycle(runId, replay = FAILED_HANDLE_REPLAY, extraBufferCapacity = 0)
    lifecycle.completeFailed(error)
    val job = Job()
    job.complete()
    return RunHandleImpl(lifecycle, job)
}
