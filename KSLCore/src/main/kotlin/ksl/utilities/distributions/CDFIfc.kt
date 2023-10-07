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

package ksl.utilities.distributions

import ksl.utilities.Interval

/** Provides an interface for functions related to
 *  a cumulative distribution function CDF
 *
 * @author rossetti
 */
fun interface CDFIfc {
    /** Returns the F(x) = Pr{X &lt;= x} where F represents the
     * cumulative distribution function
     *
     * @param x a double representing the upper limit
     * @return a double representing the probability
     */
    fun cdf(x: Double): Double

    /**
     *  Returns an array of probabilities each representing
     *  F(x_i). The CDF is evaluated for each point in the input array [x]
     *  and the probabilities are returned in the returned array.
     */
    fun cdf(x: DoubleArray): DoubleArray {
        return DoubleArray(x.size) { cdf(x[it]) }
    }

    /**
     * Returns the probability of being in the interval,
     * F(upper limit) - F(lower limit)
     * Be careful, this is Pr{lower limit&lt;=X&lt;=upper limit}
     * which includes the lower limit and has implications if
     * the distribution is discrete
     */
    fun closedIntervalProbability(interval: Interval): Double {
        return closedIntervalProbability(interval.lowerLimit, interval.upperLimit)
    }

    /** Returns the Pr{x1&lt;=X&lt;=x2} for the distribution.
     * Be careful, this is Pr{x1&lt;=X&lt;=x2}
     * which includes the lower limit and has implications if
     * the distribution is discrete
     * @param x1 a double representing the lower limit
     * @param x2 a double representing the upper limit
     * @return cdf(x2)-cdf(x1)
     * @throws IllegalArgumentException if x1 &gt; x2
     */
    fun closedIntervalProbability(x1: Double, x2: Double): Double {
        require(x1 <= x2) { "x1 = $x1 > x2 = $x2 in cdf(x1,x2)" }
        return cdf(x2) - cdf(x1)
    }

    /** Computes the complementary cumulative probability
     * distribution function for given value of x.  This is P{X &gt x}
     * @param x The value to be evaluated
     * @return The probability, 1-P{X&lt;=x}
     */
    fun complementaryCDF(x: Double): Double {
        return 1.0 - cdf(x)
    }
}