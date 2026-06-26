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

package ksl.service.job

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.SharedFlow

/**
 * The capability-agnostic spine of the service core.
 *
 * Every long-running, submit-and-stream capability KSL exposes — running a
 * bundled simulation model ([ksl.app.session.RunHandle]) and fitting
 * distributions to a dataset ([ksl.app.dist.session.FitHandle]) — has the same
 * structural shape: a stable id, a hot [SharedFlow] of progress events, and a
 * [Deferred] terminal result. `JobHandleView` captures exactly that shape so a
 * single `JobManager` / event-journal / transport layer serves all of them,
 * rather than re-implementing run lifecycle once per capability.
 *
 * This is the seam that keeps the server from being locked to "run a model":
 * distribution fitting (and any future job-shaped capability) plugs in as a
 * peer through [asJobView]. Capabilities whose shape is *not* submit-and-stream
 * — database analysis is open-then-query — deliberately do not implement this
 * interface; they are served by a separate query surface.
 *
 * @param E the progress-event type (e.g. `RunEvent`, `FitEvent`)
 * @param R the terminal-result type (e.g. `RunResult`, `FitResult`)
 */
interface JobHandleView<out E, out R> {

    /** Stable, capability-agnostic identifier for this job. */
    val jobId: String

    /** Hot flow of in-flight progress events. */
    val events: SharedFlow<E>

    /** Deferred terminal outcome; resolves normally (never throws on await). */
    val result: Deferred<R>

    /** Requests cancellation; the terminal result becomes the capability's
     *  cancelled variant. */
    fun cancel(reason: String = "Cancelled by user")
}
