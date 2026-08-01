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
package ksl.utilities.random.rvariable.metalog

import ksl.utilities.distributions.metalog.MetalogBoundedness
import ksl.utilities.distributions.metalog.MetalogFeasibilityChecker
import ksl.utilities.distributions.metalog.MetalogFunctions
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.ParameterizedRV
import ksl.utilities.random.rvariable.parameters.Metalog6PRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * A six-term metalog random variable.
 *
 * Six terms is the largest arity with a registered random variable type.
 *
 * Because the metalog quantile function is closed form, each variate costs one evaluation of it,
 * with no root finding and no rejection. Which member of the family this represents follows from
 * which bounds are finite rather than from a separate setting.
 *
 * The coefficients are validated once here and are immutable thereafter, so generation performs no
 * feasibility checking at all. That is safe because the parameters of a random variable are never
 * mutated in place: a parameter change in a running model is applied by constructing a fresh
 * instance, which revalidates. It is also necessary, since an invalid coefficient vector does not
 * fail during generation but instead silently produces values that are not a sample from any
 * distribution.
 *
 * @param a1 the location, which is the median in fitting space
 * @param a2 the scale, which must be strictly positive
 * @param a3 the skewness coefficient
 * @param a4 the kurtosis coefficient
 * @param a5 the fifth coefficient, refining the location series of the quantile function
 * @param a6 the sixth coefficient, refining the scale series of the quantile function
 * @param lowerBound the lower bound, or negative infinity when unbounded below
 * @param upperBound the upper bound, or positive infinity when unbounded above
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams
 * @param name an optional name
 */
class Metalog6PRV @JvmOverloads constructor(
    val a1: Double = 0.0,
    val a2: Double = 1.0,
    val a3: Double = 0.0,
    val a4: Double = 0.0,
    val a5: Double = 0.0,
    val a6: Double = 0.0,
    val lowerBound: Double = Double.NEGATIVE_INFINITY,
    val upperBound: Double = Double.POSITIVE_INFINITY,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNum, streamProvider, name) {

    private val myCoefficients: DoubleArray = doubleArrayOf(a1, a2, a3, a4, a5, a6)
    private val myBoundedness: MetalogBoundedness = MetalogBoundedness.of(lowerBound, upperBound)

    init {
        MetalogFeasibilityChecker.requireFeasible(myCoefficients)
    }

    /**
     *  Which member of the metalog family this random variable draws from, derived from its bounds.
     */
    val boundedness: MetalogBoundedness
        get() = myBoundedness

    /**
     *  A defensive copy of the coefficients, in the order in which they multiply the basis terms.
     */
    fun coefficients(): DoubleArray = myCoefficients.copyOf()

    override fun generate(): Double {
        val z = MetalogFunctions.quantile(myCoefficients, rnStream.randU01())
        return myBoundedness.fromFittingSpace(z, lowerBound, upperBound)
    }

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): Metalog6PRV {
        return Metalog6PRV(
            a1, a2, a3, a4, a5, a6, lowerBound, upperBound, streamNum, rnStreamProvider, name
        )
    }

    override fun toString(): String {
        return "Metalog6PRV(a1=${a1}, a2=${a2}, a3=${a3}, a4=${a4}, a5=${a5}, a6=${a6}, lowerBound=$lowerBound, upperBound=$upperBound)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = Metalog6PRVParameters()
            parameters.changeDoubleParameter("a1", a1)
            parameters.changeDoubleParameter("a2", a2)
            parameters.changeDoubleParameter("a3", a3)
            parameters.changeDoubleParameter("a4", a4)
            parameters.changeDoubleParameter("a5", a5)
            parameters.changeDoubleParameter("a6", a6)
            parameters.changeDoubleParameter("lowerBound", lowerBound)
            parameters.changeDoubleParameter("upperBound", upperBound)
            return parameters
        }
}
