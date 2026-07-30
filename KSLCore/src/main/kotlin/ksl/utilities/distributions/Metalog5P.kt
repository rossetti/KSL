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

/**
 *  A five-term metalog distribution.
 *
 *  Five terms is the point at which the family can represent multimodal shapes and match moments
 *  beyond the fourth. It is also the largest arity for which Keelin (2016) gives closed-form
 *  central moments, so the mean and variance of the unbounded member are computed exactly here
 *  rather than by numerical integration.
 *
 *  @param a1 the location coefficient
 *  @param a2 the scale coefficient, which must remain strictly positive
 *  @param a3 the skewness coefficient
 *  @param a4 the kurtosis coefficient
 *  @param a5 the fifth coefficient, refining the location series of the quantile function
 *  @param lowerBound the lower bound, or negative infinity when unbounded below
 *  @param upperBound the upper bound, or positive infinity when unbounded above
 *  @param name an optional name
 */
class Metalog5P(
    a1: Double = 0.0,
    a2: Double = 1.0,
    a3: Double = 0.0,
    a4: Double = 0.0,
    a5: Double = 0.0,
    lowerBound: Double = Double.NEGATIVE_INFINITY,
    upperBound: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : MetalogDistribution(doubleArrayOf(a1, a2, a3, a4, a5), lowerBound, upperBound, name) {

    /**
     *  The location coefficient.
     */
    var a1: Double
        get() = myCoefficients[0]
        set(value) = changeCoefficient(0, value)

    /**
     *  The scale coefficient, which must remain strictly positive.
     */
    var a2: Double
        get() = myCoefficients[1]
        set(value) = changeCoefficient(1, value)

    /**
     *  The skewness coefficient.
     */
    var a3: Double
        get() = myCoefficients[2]
        set(value) = changeCoefficient(2, value)

    /**
     *  The kurtosis coefficient.
     */
    var a4: Double
        get() = myCoefficients[3]
        set(value) = changeCoefficient(3, value)

    /**
     *  The fifth coefficient, refining the location series of the quantile function.
     */
    var a5: Double
        get() = myCoefficients[4]
        set(value) = changeCoefficient(4, value)

    override fun instance(): Metalog5P {
        return Metalog5P(a1, a2, a3, a4, a5, lowerBound, upperBound, name)
    }
}
