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

package ksl.utilities.distributions.fitting.estimators

/**
 *  Proposes candidate bounds for the semi-bounded and bounded metalogs.
 *
 *  Candidates sit at a fraction of the observed range beyond the observed extreme, rather than at
 *  the extreme itself. That clearance matters: a bound that merely touches the sample minimum sends
 *  the transformed value of that observation to a large negative number, which then dominates the
 *  least squares fit. Measured on log-normal samples, a bound taken from the shift estimator that
 *  the rest of the fitting subsystem uses agrees with the sample minimum to four or five decimals,
 *  and the resulting fit gets steadily worse as terms are added, while a bound with clearance
 *  improves as expected.
 *
 *  Zero is offered as well when the data is positive, since a natural bound at zero is common and
 *  is exactly the case a purely proportional ladder would miss.
 *
 *  A profiled bound is chosen for the quality of the fit and is not an estimate of where the
 *  support truly ends. The bound is weakly identified: on exponential samples whose true lower
 *  bound is zero, profiling has selected bounds far below zero and still fitted well, because the
 *  coefficients absorb the difference. Supply the bound directly whenever its value is known.
 *
 *  @param clearanceFractions the offsets to try, as fractions of the observed range
 *  @param includeZero whether to offer zero as a lower bound candidate for positive data
 */
class MetalogBoundProfiler(
    val clearanceFractions: DoubleArray = DEFAULT_CLEARANCE_FRACTIONS,
    val includeZero: Boolean = true
) {

    init {
        require(clearanceFractions.isNotEmpty()) { "There must be at least one clearance fraction" }
        require(clearanceFractions.all { it > 0.0 }) { "Every clearance fraction must be positive" }
    }

    /**
     *  Lower bound candidates for the supplied data, all strictly below its minimum, in increasing
     *  order of clearance.
     */
    fun lowerBoundCandidates(data: DoubleArray): DoubleArray {
        val minimum = data.min()
        val range = spreadOf(data)
        val candidates = sortedSetOf<Double>()
        for (fraction in clearanceFractions) {
            candidates.add(minimum - fraction * range)
        }
        if (includeZero && (minimum > 0.0)) {
            candidates.add(0.0)
        }
        return candidates.toDoubleArray()
    }

    /**
     *  Upper bound candidates for the supplied data, all strictly above its maximum.
     */
    fun upperBoundCandidates(data: DoubleArray): DoubleArray {
        val maximum = data.max()
        val range = spreadOf(data)
        val candidates = sortedSetOf<Double>()
        for (fraction in clearanceFractions) {
            candidates.add(maximum + fraction * range)
        }
        if (includeZero && (maximum < 0.0)) {
            candidates.add(0.0)
        }
        return candidates.toDoubleArray()
    }

    /**
     *  The spread of the data, falling back to its magnitude when every observation is identical so
     *  that a candidate still has some clearance.
     */
    private fun spreadOf(data: DoubleArray): Double {
        require(data.size >= 2) { "There must be at least 2 observations" }
        require(data.all { it.isFinite() }) { "Every observation must be finite" }
        val range = data.max() - data.min()
        if (range > 0.0) {
            return range
        }
        val magnitude = kotlin.math.abs(data.first())
        return if (magnitude > 0.0) magnitude else 1.0
    }

    companion object {

        /**
         *  The default ladder of clearances, spanning three orders of magnitude so that both a
         *  bound close to the data and one far from it are considered.
         */
        val DEFAULT_CLEARANCE_FRACTIONS: DoubleArray
            get() = doubleArrayOf(0.001, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.4, 0.8, 1.6, 3.2)
    }
}
