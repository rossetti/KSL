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
import ksl.app.dist.result.BatchFitResultData
import ksl.app.dist.result.FitResultData

/**
 * Lifecycle event emitted by a `FitHandle` during a fitting (or batch) job.
 *
 * The set comprises the start signals, lightweight progress events, and the
 * terminal events. Progress events carry no result payload — the full result
 * arrives once, on the terminal event (`FitCompleted` / `BatchFitCompleted`),
 * mirroring `ksl.app`'s lifecycle-only event convention.
 */
sealed class FitEvent {

    /**
     * Sealed parent of every "started" event, so callers that just need a
     * uniform start signal can match `is FitEvent.Started`. Common fields
     * only; each variant adds its specifics (a single dataset name, or a
     * batch dataset count).
     */
    sealed class Started : FitEvent() {
        abstract val fitId: String
        abstract val startTime: Instant
    }

    /**
     * Emitted once for a single fit, after the data source resolves and
     * before the fitter starts work. A failed import never emits this event —
     * the only event on that path is `FitFailed`.
     */
    data class FitStarted(
        override val fitId: String,
        val datasetName: String,
        override val startTime: Instant
    ) : Started()

    /** Emitted once at the start of a batch, before the first dataset is fit. */
    data class BatchFitStarted(
        override val fitId: String,
        val datasetCount: Int,
        override val startTime: Instant
    ) : Started()

    /**
     * Progress: one dataset of a batch has finished. Carries no result
     * payload — only position and outcome. `success` is true when the dataset
     * produced a result, false when it became a `BatchFailure`.
     */
    data class DatasetCompleted(
        val fitId: String,
        val datasetName: String,
        val index: Int,
        val total: Int,
        val success: Boolean
    ) : FitEvent()

    /** Terminal: the fit produced a `FitResultData` normally. */
    data class FitCompleted(
        val fitId: String,
        val report: FitResultData,
        val endTime: Instant
    ) : FitEvent()

    /** Terminal: the batch produced a `BatchFitResultData` normally. */
    data class BatchFitCompleted(
        val fitId: String,
        val report: BatchFitResultData,
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
