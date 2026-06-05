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
 * Wire-safe, dataset-level dispersion analysis for a discrete data series —
 * the content of the standard report's "Dispersion Analysis" section. The index
 * of dispersion is Var/Mean and the test statistic is T = (n−1)·Var/Mean,
 * referred to a chi-squared distribution with [degreesOfFreedom] = n−1. The
 * three p-values let a data/AI client read the dispersion conclusion without
 * the chi-squared math.
 *
 * Computed once from the data statistics via
 * `DiscretePMFGoodnessOfFit.poissonDispersionTest`. Discrete-only; null on the
 * continuous path.
 */
@Serializable
data class DispersionAnalysisDTO(
    val indexOfDispersion: Double,
    val poissonVarianceTestStatistic: Double,
    val degreesOfFreedom: Int,
    val upperPValue: Double,
    val lowerPValue: Double,
    val twoSidedPValue: Double
)
