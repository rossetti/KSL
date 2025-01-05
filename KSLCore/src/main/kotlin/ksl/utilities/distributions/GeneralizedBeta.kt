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
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GeneralizedBetaRV
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.sqrt

/**
 * Create Beta distribution with the supplied parameters
 *
 * @param alphaShape the first shape parameter
 * @param betaShape the second shape parameter
 * @param minimum the minimum of the range
 * @param maximum the maximum of the range
 */
class GeneralizedBeta(
    alphaShape: Double,
    betaShape: Double,
    minimum: Double = 0.0,
    maximum: Double = 1.0,
    name: String? = null
) : Distribution(name), ContinuousDistributionIfc, InverseCDFIfc,
    RVParametersTypeIfc by RVType.GeneralizedBeta, MomentsIfc {

    init {
        require(minimum < maximum) { "Lower limit must be < upper limit. lower limit = $minimum upper limit = $maximum" }
    }

    private val myBeta = Beta(alphaShape, betaShape)

    var min = minimum
        private set

    var max = maximum
        private set

    val range: Double
        get() = max - min

    val alpha: Double
        get() = myBeta.alpha

    val beta: Double
        get() = myBeta.beta

    /**
     * Changes the parameters to the supplied values
     *
     * @param alphaShape the alpha shape parameter
     * @param betaShape the beta shape parameter
     * @param theMinimum the minimum of the range
     * @param theMaximum the maximum of the range
     */
    fun parameters(alphaShape: Double, betaShape: Double, theMinimum: Double = min, theMaximum: Double = max) {
        require(theMinimum < theMaximum) { "Lower limit must be < upper limit. lower limit = $theMinimum upper limit = $theMaximum" }
        max = theMaximum
        min = theMinimum
        myBeta.parameters(alphaShape, betaShape)
    }

    /**
     * Changes the parameters to the supplied values
     * params[0] the alpha shape parameter
     * params[1]the beta shape parameter
     * params[2] the minimum of the range
     * params[3] the maximum of the range
     */
    override fun parameters(params: DoubleArray) {
        parameters(params[0], params[1], params[2], params[3])
    }

    /**
     * Returns the parameters as an array
     * params[0] the alpha shape parameter
     * params[1]the beta shape parameter
     * params[2] the minimum of the range
     * params[3] the maximum of the range
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(alpha, beta, min, max)
    }

    override fun instance(): GeneralizedBeta {
        return GeneralizedBeta(alpha, beta, min, max)
    }

    override fun cdf(x: Double): Double {
        // shift to 0, 1
        val y = (x - min) / range
        return myBeta.cdf(y)
    }

    override fun pdf(x: Double): Double {
        val y = (x - min) / range
        return (myBeta.pdf(y) / range) //TODO check this
    }

    override fun mean(): Double {
        return (alpha * max + beta * min) / (alpha + beta)
    }

    override fun variance(): Double {
        return myBeta.variance() * range * range
    }

    override fun domain(): Interval = Interval(min, max)

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return GeneralizedBetaRV(alpha, beta, min, max, stream)
    }

    override fun invCDF(p: Double): Double {
        val y = myBeta.invCDF(p)
        return y * range + min
    }

    override val mean: Double
        get() = mean()
    override val variance: Double
        get() = variance()
    override val skewness: Double
        get() = (2.0 * (beta - alpha) * sqrt(alpha + beta + 1)) / ((alpha + beta + 2) * sqrt(alpha * beta))

    override val kurtosis: Double
        get() = (6.0 * ((((alpha - beta) * (alpha - beta) * (alpha + beta + 1.0)) - alpha * beta * (alpha + beta + 2.0)))) / (alpha * beta * (alpha + beta + 2.0) * (alpha + beta + 3.0))

    override fun toString(): String {
        return "GeneralizedBeta(min=$min, max=$max, alpha=$alpha, beta=$beta)"
    }


}