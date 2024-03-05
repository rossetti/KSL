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
import ksl.utilities.random.rvariable.*
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Models exponentially distributed random variables
 * This distribution is commonly use to model the time between events
 * @param theMean The mean of the distribution, must be &gt; 0.0
 * @param name an optional label/name
 */
class Exponential(theMean: Double = 1.0, name: String? = null) : Distribution(name),
    LossFunctionDistributionIfc, ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc, RVParametersTypeIfc by RVType.Exponential {

    /** Constructs an exponential distribution where parameter[0] is the
     * mean of the distribution
     * @param parameters A array containing the mean of the distribution, must be &gt; 0.0
     */
    constructor(parameters: DoubleArray) : this(parameters[0], null)

    init {
        require(theMean > 0.0) { "Exponential mean must be > 0.0" }
    }

    /**
     * mean of the distribution, must be &gt; 0.0
     */
    var mean = theMean
        set(value) {
            require(value > 0.0) { "Exponential mean must be > 0.0" }
            field = value
        }

    override fun instance(): Exponential {
        return Exponential(mean)
    }

    override fun domain(): Interval {
        return Interval(0.0, Double.POSITIVE_INFINITY)
    }

    override fun mean(): Double {
        return mean
    }

    val moment3: Double = mean.pow(3.0) * exp(Gamma.logGammaFunction(4.0))

    val moment4: Double = mean.pow(4.0) * exp(Gamma.logGammaFunction(5.0))

    override fun cdf(x: Double): Double {
        if ((x == Double.POSITIVE_INFINITY) || (x == Double.MAX_VALUE)) {
            return 1.0
        }
        return if (x >= 0.0) {
            1.0 - exp(-x / mean)
        } else {
            0.0
        }
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be [0,1]" }
        if (p <= 0.0) {
            return 0.0
        }
        return if (p >= 1.0) {
            Double.POSITIVE_INFINITY
        } else -mean * ln(1.0 - p)
    }

    override fun pdf(x: Double): Double {
        return if (x >= 0) {
            exp(-x / mean) / mean
        } else {
            0.0
        }
    }

    override fun variance(): Double {
        return mean * mean
    }

    val kurtosis: Double = 6.0

    val skewness: Double = 2.0

    override fun parameters(params: DoubleArray) {
        mean = params[0]
    }

    override fun parameters(): DoubleArray {
        return doubleArrayOf(mean)
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return ExponentialRV(mean, stream)
    }

    override fun firstOrderLossFunction(x: Double): Double {
        return exp(-mean * x) / mean
    }

    override fun secondOrderLossFunction(x: Double): Double {
        return firstOrderLossFunction(x) / mean
    }

    override fun toString(): String {
        return "Exponential(mean=$mean)"
    }
}