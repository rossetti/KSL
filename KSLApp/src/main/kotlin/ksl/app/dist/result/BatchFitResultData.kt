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

package ksl.app.dist.result

import kotlinx.serialization.Serializable

/**
 * One failed entry in a batch: the entry's name and a human-readable message.
 * Captured so a batch completes even when some datasets fail (bad import,
 * non-integer discrete data, numerical failure).
 */
@Serializable
data class BatchFailure(
    val name: String,
    val message: String
)

/**
 * Wire-safe aggregate result for a `FitSpec.Batch`: the successful per-dataset
 * results in submission order, plus any per-dataset failures. Each
 * `FitResultData.datasetName` identifies its entry.
 */
@Serializable
data class BatchFitResultData(
    val results: List<FitResultData>,
    val failures: List<BatchFailure> = emptyList()
)
