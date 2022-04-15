/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Models exponentially distributed random variables
 * This distribution is commonly use to model the time between events
 * @param theMean The mean of the distribution, , must be &gt; 0.0
 * @param name an optional label/name
 */
class Exponential(theMean: Double = 1.0, name: String? = null) : Distribution<Exponential>(name),
    LossFunctionDistributionIfc, ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {

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
        return if (x >= 0) {
            1 - exp(-x / mean)
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
}