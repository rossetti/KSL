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
 * Wire-safe, complete left-shift analysis for a continuous fit — the full
 * content of the standard report's "Shift Parameter Analysis" section. Carries
 * the estimated left shift, whether the data has zeros/negatives, the zero
 * tolerance used, and the bootstrap confidence interval for the data minimum
 * that drives the shift recommendation.
 *
 * The CI for the minimum is a bootstrap estimate computed at fit time; a live
 * canonical render recomputes it (and may differ slightly because bootstrap
 * resampling is stochastic). Discrete fits do not shift, so this is null on the
 * discrete path.
 */
@Serializable
data class ShiftAnalysisDTO(
    val leftShift: Double,
    val hasZeroes: Boolean,
    val hasNegatives: Boolean,
    val zeroTolerance: Double,
    val ciForMinimumLevel: Double,
    val ciForMinimumLower: Double,
    val ciForMinimumUpper: Double
)
