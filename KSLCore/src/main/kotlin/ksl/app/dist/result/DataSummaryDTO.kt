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
 * Wire-safe summary of the data series that was fit. Every field is read
 * directly off the engine's `StatisticIfc` view of the data. `shift` is the
 * left shift the engine applied during automatic shifting (zero when none).
 *
 * Although a client that supplied the raw data could recompute these, the
 * summary is returned so the result reflects the engine's exact statistics
 * (and so a thin client need not recompute anything).
 */
@Serializable
data class DataSummaryDTO(
    val n: Int,
    val min: Double,
    val max: Double,
    val average: Double,
    val variance: Double,
    val standardDeviation: Double,
    val skewness: Double,
    val kurtosis: Double,
    val zeroCount: Int,
    val negativeCount: Int,
    val positiveCount: Int,
    val shift: Double
)
