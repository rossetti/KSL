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
 *  A two-term metalog distribution.
 *
 *  With both bounds infinite this is exactly a logistic distribution whose location is the first
 *  coefficient and whose scale is the second. With a finite lower bound it is the log-logistic,
 *  known in economics as the Fisk distribution. With both bounds finite it is the logit-logistic,
 *  also called the Tadikamalla and Johnson bounded distribution.
 *
 *  Two terms is the smallest metalog. It has no shape flexibility beyond location and scale, so
 *  it is chiefly useful as a baseline against which the higher-term members are compared.
 *
 *  @param a1 the location, which is the median in fitting space
 *  @param a2 the scale, which must be strictly positive
 *  @param lowerBound the lower bound, or negative infinity when unbounded below
 *  @param upperBound the upper bound, or positive infinity when unbounded above
 *  @param name an optional name
 */
class Metalog2P(
    a1: Double = 0.0,
    a2: Double = 1.0,
    lowerBound: Double = Double.NEGATIVE_INFINITY,
    upperBound: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : MetalogDistribution(doubleArrayOf(a1, a2), lowerBound, upperBound, name) {

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

    override fun instance(): Metalog2P {
        return Metalog2P(a1, a2, lowerBound, upperBound, name)
    }
}
