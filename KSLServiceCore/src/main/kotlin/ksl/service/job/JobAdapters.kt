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
import ksl.app.dist.session.FitEvent
import ksl.app.dist.session.FitHandle
import ksl.app.dist.session.FitResult
import ksl.app.moda.ModaEvent
import ksl.app.moda.ModaHandle
import ksl.app.moda.ModaStudyOutcome
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult

/**
 * Views a model-run [RunHandle] as a capability-agnostic [JobHandleView].
 *
 * The two capabilities are structurally symmetric, so the adapter is a thin
 * delegation — proof that the run capability is one peer on the generic spine,
 * not a privileged center.
 */
fun RunHandle.asJobView(): JobHandleView<RunEvent, RunResult> {
    val handle = this
    return object : JobHandleView<RunEvent, RunResult> {
        override val jobId: String get() = handle.runId
        override val events: SharedFlow<RunEvent> get() = handle.events
        override val result: Deferred<RunResult> get() = handle.result
        override fun cancel(reason: String) = handle.cancel(reason)
    }
}

/**
 * Views a distribution-fitting [FitHandle] as a capability-agnostic
 * [JobHandleView]. Its existence is what proves the server does not lock out
 * `ksl.app.dist`: a fit job flows through the same `JobManager` and transport
 * layer as a model run.
 */
fun FitHandle.asJobView(): JobHandleView<FitEvent, FitResult> {
    val handle = this
    return object : JobHandleView<FitEvent, FitResult> {
        override val jobId: String get() = handle.fitId
        override val events: SharedFlow<FitEvent> get() = handle.events
        override val result: Deferred<FitResult> get() = handle.result
        override fun cancel(reason: String) = handle.cancel(reason)
    }
}

/**
 * Views a multi-objective decision study [ModaHandle] as a capability-agnostic
 * [JobHandleView]. Deciding between alternatives is then a job like any other:
 * it queues under the same capacity limit, is journalled, cancelled, and
 * retained the same way, and reaches a caller over the same transport as a
 * model run or a distribution fit.
 */
fun ModaHandle.asJobView(): JobHandleView<ModaEvent, ModaStudyOutcome> {
    val handle = this
    return object : JobHandleView<ModaEvent, ModaStudyOutcome> {
        override val jobId: String get() = handle.studyId
        override val events: SharedFlow<ModaEvent> get() = handle.events
        override val result: Deferred<ModaStudyOutcome> get() = handle.result
        override fun cancel(reason: String) = handle.cancel(reason)
    }
}
