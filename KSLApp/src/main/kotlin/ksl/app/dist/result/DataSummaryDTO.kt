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
 * Wire-safe, field-for-field mirror of `ksl.utilities.statistic.StatisticData`
 * — the full `StatisticIfc` summary at a chosen confidence level, exactly as
 * produced by `StatisticIfc.statisticData(level)`. Keeping the DTO consistent
 * with `StatisticData` means a client receives the engine's complete statistical
 * view (including the confidence interval, autocorrelation, and von Neumann
 * lag-1 statistic) rather than an ad-hoc subset.
 */
@Serializable
data class StatisticDataDTO(
    val name: String,
    val count: Double,
    val average: Double,
    val standardDeviation: Double,
    val standardError: Double,
    val halfWidth: Double,
    val confidenceLevel: Double,
    val lowerLimit: Double,
    val upperLimit: Double,
    val min: Double,
    val max: Double,
    val sum: Double,
    val variance: Double,
    val deviationSumOfSquares: Double,
    val kurtosis: Double,
    val skewness: Double,
    val lag1Covariance: Double,
    val lag1Correlation: Double,
    val vonNeumannLag1TestStatistic: Double,
    val numberMissing: Double
)

/**
 * Wire-safe summary of the data series that was fit: the full `StatisticIfc`
 * summary ([statistics]) plus the sign/zero counts the fitting EDA needs (which
 * `StatisticData` does not carry). The dataset-level left shift is reported
 * separately in `ShiftAnalysisDTO` (continuous) — it is not a statistic.
 *
 * Although a client that supplied the raw data could recompute these, the
 * summary is returned so the result reflects the engine's exact statistics
 * (and so a thin client need not recompute anything).
 */
@Serializable
data class DataSummaryDTO(
    val statistics: StatisticDataDTO,
    val zeroCount: Int,
    val negativeCount: Int,
    val positiveCount: Int
)
