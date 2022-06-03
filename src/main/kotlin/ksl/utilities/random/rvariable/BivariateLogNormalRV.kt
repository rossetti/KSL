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
package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Allows for the generation of bi-variate lognormal
 * random variables.  These parameters are all for the lognormal distribution
 * @param m1        mean of first coordinate
 * @param v1        variance of first coordinate
 * @param m2        mean of 2nd coordinate
 * @param v2        variance of 2nd coordinate
 * @param corr         correlation between X1 and X2
 * @param stream  the random number stream
 * @author rossetti
 */
class BivariateLogNormalRV(
    val m1: Double = 1.0,
    val v1: Double = 1.0,
    val m2: Double = 1.0,
    val v2: Double = 1.0,
    val corr: Double = 0.0,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : MVRVariable(stream, name) {

    private val myBVN: BivariateNormalRV

    init {
        require(m1 > 0) { "Mean 1 must be positive" }
        require(m2 > 0) { "Mean 1 must be positive" }
        require(v1 > 0) { "Variance 1 must be positive" }
        require(v2 > 0) { "Variance 2 must be positive" }
        require(!(corr < -1.0 || corr > 1.0)) { "The correlation must be within [-1,1]" }
        // calculate parameters of underlying bi-variate normal
        // get the means
        val mean1 = ln(m1 * m1 / sqrt(m1 * m1 + v1))
        val mean2 = ln(m2 * m2 / sqrt(m2 * m2 + v2))
        // get the variances
        val var1 = ln(1.0 + v1 / abs(m1 * m1))
        val var2 = ln(1.0 + v2 / abs(m2 * m2))
        // calculate the correlation
        val cov = ln(1.0 + corr * sqrt(v1 * v2) / abs(m1 * m2))
        val rho = cov / sqrt(var1 * var2)
        myBVN = BivariateNormalRV(mean1, var1, mean2, var2, rho, stream)
    }

    /**
     * Constructs a bi-variate lognormal with the provided parameters
     *
     * @param m1        mean of first coordinate
     * @param v1        variance of first coordinate
     * @param m2        mean of 2nd coordinate
     * @param v2        variance of 2nd coordinate
     * @param corr         correlation between X1 and X2
     * @param streamNum the stream number
     */
    constructor(
        m1: Double = 1.0,
        v1: Double = 1.0,
        m2: Double = 1.0,
        v2: Double = 1.0,
        corr: Double = 0.0,
        streamNum: Int,
        name: String?
    ) : this(m1, v1, m2, v2, corr, KSLRandom.rnStream(streamNum), name)

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The size of the array to fill does not match the sampling dimension!" }
        myBVN.sample(array)
        // transform them to bi-variate lognormal
        array[0] = exp(array[0])
        array[1] = exp(array[1])
    }

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return BivariateLogNormalRV(m1, v1, m2, v2, corr, stream)
    }

    override fun toString(): String {
        return "BivariateLogNormalRV(m1=$m1, v1=$v1, m2=$m2, v2=$v2, corr=$corr)"
    }

    override val dimension: Int
        get() = 2

    override fun antitheticInstance(): MVRVariableIfc {
        return BivariateLogNormalRV(m1, v1, m2, v2, corr, rnStream.antitheticInstance()
        )
    }
}