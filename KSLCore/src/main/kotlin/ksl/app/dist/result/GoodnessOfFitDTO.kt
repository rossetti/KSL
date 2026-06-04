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
 * Wire-safe goodness-of-fit results for one fitted distribution.
 *
 * The chi-squared test and its bin table apply to both continuous and
 * discrete fits. The continuous-only statistics (KS, Anderson-Darling,
 * Cramer-von Mises) and the discrete-only statistics (index of dispersion,
 * Poisson variance test) are nullable so a single DTO carries both kinds
 * without forcing values that a given path does not compute.
 *
 * The bin arrays (`binBreakPoints`, `binProbabilities`, `expectedCounts`,
 * `observedCounts`) describe the chi-squared binning; they also give a
 * client the exact bins the engine evaluated against.
 *
 * Populated in a later phase by the result extractor; null until then.
 */
@Serializable
data class GoodnessOfFitDTO(
    val chiSquaredStatistic: Double,
    val chiSquaredDOF: Int,
    val chiSquaredPValue: Double,
    val binBreakPoints: List<Double>,
    val binProbabilities: List<Double>,
    val expectedCounts: List<Double>,
    val observedCounts: List<Double>,
    // continuous-only (null for discrete):
    val ksStatistic: Double? = null,
    val ksPValue: Double? = null,
    val andersonDarlingStatistic: Double? = null,
    val andersonDarlingPValue: Double? = null,
    val cramerVonMisesStatistic: Double? = null,
    val cramerVonMisesPValue: Double? = null,
    // discrete-only (null for continuous):
    val indexOfDispersion: Double? = null,
    val poissonVarianceTestStatistic: Double? = null
)
