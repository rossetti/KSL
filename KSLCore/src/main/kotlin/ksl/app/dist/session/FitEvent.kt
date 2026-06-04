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

import kotlinx.datetime.Instant
import ksl.app.dist.result.FitResultData

/**
 * Lifecycle event emitted by a `FitHandle` during a fitting job.
 *
 * The minimal set in this phase is one Started variant plus the three
 * terminal events (Completed / Failed / Cancelled). Intermediate progress
 * variants (data-resolved, per-estimator started/completed, scoring
 * completed) will be added when a front-end consumer needs them and the
 * runner is instrumented to emit them.
 */
sealed class FitEvent {

    /**
     * Sealed parent of every "the fit has started" event, so callers that
     * just need a uniform start signal can match `is FitEvent.Started`
     * without distinguishing the variant.
     */
    sealed class Started : FitEvent() {
        abstract val fitId: String
        abstract val datasetName: String
        abstract val startTime: Instant
    }

    /**
     * Emitted once, after the data source resolves successfully and before
     * the underlying fitter starts work. A failed import never emits this
     * event — the only event on that path is `FitFailed`.
     */
    data class FitStarted(
        override val fitId: String,
        override val datasetName: String,
        override val startTime: Instant
    ) : Started()

    /** Terminal: the fit produced a `FitResultData` normally. */
    data class FitCompleted(
        val fitId: String,
        val report: FitResultData,
        val endTime: Instant
    ) : FitEvent()

    /** Terminal: the fit failed with a structured error. */
    data class FitFailed(
        val fitId: String,
        val error: FittingError,
        val endTime: Instant
    ) : FitEvent()

    /** Terminal: the fit was cancelled before completion. */
    data class FitCancelled(
        val fitId: String,
        val reason: String,
        val endTime: Instant
    ) : FitEvent()
}
