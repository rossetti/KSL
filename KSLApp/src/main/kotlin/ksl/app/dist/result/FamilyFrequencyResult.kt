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
 * Wire-safe result of the family-frequency bootstrap — a standalone (continuous)
 * analysis that resamples a dataset [numSamples] times, re-runs the full fit +
 * evaluation on each resample, and tallies how often each family is recommended.
 *
 * This is intentionally separate from `FitResultData`: it is its own analysis,
 * not part of a fit. The [frequency] cells carry the family name in `cellLabel`
 * with `count`/`proportion` giving the tally.
 */
@Serializable
data class FamilyFrequencyResult(
    val datasetName: String,
    val numSamples: Int,
    val frequency: IntegerFrequencyDTO
)
