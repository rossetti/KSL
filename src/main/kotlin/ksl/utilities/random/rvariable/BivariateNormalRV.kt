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

import ksl.utilities.distributions.Normal
import ksl.utilities.random.rng.RNStreamIfc
import kotlin.math.sqrt

/**
 * Allows for the generation of bi-variate normal
 * random variables
 * Constructs a bi-variate normal with the provided parameters
 *
 * @param mean1 mean of first coordinate
 * @param v1  variance of first coordinate
 * @param mean2 mean of 2nd coordinate
 * @param v2  variance of 2nd coordinate
 * @param corr   correlation between X1 and X2
 * @param stream   the RNStreamIfc
 * @param name an optional name
 * @author rossetti
 */
class BivariateNormalRV(
    val mean1: Double = 0.0,
    val v1: Double = 1.0,
    val mean2: Double = 0.0,
    val v2: Double = 1.0,
    val corr: Double = 0.0,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : MVRVariable(stream, name) {

    init {
        require(v1 > 0) { "The first variance was <=0" }
        require(v2 > 0) { "The second variance was <=0" }
        require(!(corr < -1.0 || corr > 1.0)) { "The correlation must be [-1,1]" }
    }

    /**
     * Constructs a bi-variate normal with the provided parameters
     *
     * @param mean1     mean of first coordinate
     * @param v1      variance of first coordinate
     * @param mean2     mean of 2nd coordinate
     * @param v2      variance of 2nd coordinate
     * @param corr       correlation between X1 and X2
     * @param streamNum the stream number
     * @param name an optional name
     */
    constructor(
        mean1: Double = 0.0,
        v1: Double = 1.0,
        mean2: Double = 0.0,
        v2: Double = 1.0,
        corr: Double = 0.0,
        streamNum: Int,
        name: String?
    ) : this(mean1, v1, mean2, v2, corr, KSLRandom.rnStream(streamNum), name)

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The size of the array to fill does not match the sampling dimension!" }
        val z0 = Normal.stdNormalInvCDF(rnStream.randU01())
        val z1 = Normal.stdNormalInvCDF(rnStream.randU01())
        val s1 = sqrt(v1)
        val s2 = sqrt(v2)
        array[0] = mean1 + s1 * z0
        array[1] = mean2 + s2 * (corr * z0 + sqrt(1.0 - corr * corr) * z1)
    }

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return BivariateNormalRV(mean1, v1, mean2, v2, corr, stream)
    }

    override fun toString(): String {
        return "BivariateNormalRV(mean1=$mean1, v1=$v1, mean2=$mean2, v2=$v2, corr=$corr)"
    }

    override val dimension: Int
        get() = 2


}