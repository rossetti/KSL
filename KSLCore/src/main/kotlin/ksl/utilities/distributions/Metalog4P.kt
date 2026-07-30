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
 *  A four-term metalog distribution.
 *
 *  The fourth coefficient primarily controls kurtosis. Raising it from zero makes the
 *  distribution fatter through its midrange with correspondingly lighter tails, closer to a
 *  normal or a symmetric beta than to a logistic; lowering it below zero narrows the midrange
 *  and thickens the tails, closer to a Student t with few degrees of freedom.
 *
 *  Two special cases are exact rather than approximate. With the second and third coefficients
 *  zero and the fourth positive, the distribution is uniform on the interval of width equal to
 *  the fourth coefficient centered on the first. With the third coefficient zero and both the
 *  second and fourth positive, it is a mixture of a logistic and a uniform sharing that mean.
 *
 *  @param a1 the location coefficient
 *  @param a2 the scale coefficient, which must remain strictly positive
 *  @param a3 the skewness coefficient
 *  @param a4 the kurtosis coefficient
 *  @param lowerBound the lower bound, or negative infinity when unbounded below
 *  @param upperBound the upper bound, or positive infinity when unbounded above
 *  @param name an optional name
 */
class Metalog4P(
    a1: Double = 0.0,
    a2: Double = 1.0,
    a3: Double = 0.0,
    a4: Double = 0.0,
    lowerBound: Double = Double.NEGATIVE_INFINITY,
    upperBound: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : MetalogDistribution(doubleArrayOf(a1, a2, a3, a4), lowerBound, upperBound, name) {

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

    override fun instance(): Metalog4P {
        return Metalog4P(a1, a2, a3, a4, lowerBound, upperBound, name)
    }
}
