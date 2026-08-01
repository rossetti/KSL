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

package ksl.utilities.distributions.metalog

import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.metalog.Metalog6PRV
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType

/**
 *  A six-term metalog distribution.
 *
 *  Six terms is the largest arity with a registered class, chosen to match the range of term
 *  counts the distribution fitting estimators search. The underlying basis functions place no
 *  limit on the number of terms, so an application needing more can extend the base class
 *  directly, forgoing the random variable type registration and the fitting pipeline.
 *
 *  No closed-form moment expressions cover this arity, so the mean and variance are obtained by
 *  numerical integration of the quantile function and cached.
 *
 *  @param a1 the location coefficient
 *  @param a2 the scale coefficient, which must remain strictly positive
 *  @param a3 the skewness coefficient
 *  @param a4 the kurtosis coefficient
 *  @param a5 the fifth coefficient, refining the location series of the quantile function
 *  @param a6 the sixth coefficient, refining the scale series of the quantile function
 *  @param lowerBound the lower bound, or negative infinity when unbounded below
 *  @param upperBound the upper bound, or positive infinity when unbounded above
 *  @param name an optional name
 */
class Metalog6P(
    a1: Double = 0.0,
    a2: Double = 1.0,
    a3: Double = 0.0,
    a4: Double = 0.0,
    a5: Double = 0.0,
    a6: Double = 0.0,
    lowerBound: Double = Double.NEGATIVE_INFINITY,
    upperBound: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : MetalogDistribution(doubleArrayOf(a1, a2, a3, a4, a5, a6), lowerBound, upperBound, name),
    RVParametersTypeIfc by RVType.Metalog6P {

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

    /**
     *  The sixth coefficient, refining the scale series of the quantile function.
     */
    var a6: Double
        get() = myCoefficients[5]
        set(value) = changeCoefficient(5, value)

    override fun instance(): Metalog6P {
        return Metalog6P(a1, a2, a3, a4, a5, a6, lowerBound, upperBound, name)
    }

    override fun randomVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): Metalog6PRV {
        return Metalog6PRV(a1, a2, a3, a4, a5, a6, lowerBound, upperBound, streamNumber, streamProvider)
    }
}
