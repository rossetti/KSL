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

/**
 * GeneralizeBetaRV(alpha1, alpha2, min, max) random variable
 * @param alpha1 the first shape parameter, must be greater than 0
 * @param alpha2 the second shape parameter, must be greater than 0
 * @param minimum the minimum of the range, must be less than maximum
 * @param maximum the maximum of the range
 * @param stream the random number stream
 */
class GeneralizedBetaRV(
    val alpha1: Double,
    val alpha2: Double,
    val minimum: Double,
    val maximum: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(maximum > minimum) { "the min must be < than the max" }
    }

    private val myBeta: BetaRV = BetaRV(alpha1, alpha2, stream)

    /**
     * GeneralizeBetaRV(alpha1, alpha2, min, max) random variable
     * @param alpha1 the first shape parameter
     * @param alpha2 the second shape parameter
     * @param minimum the minimum of the range
     * @param maximum the maximum of the range
     * @param streamNum the random number stream number
     */
    constructor(alpha1: Double, alpha2: Double, min: Double, max: Double, streamNum: Int) :
            this(alpha1, alpha2, min, max, KSLRandom.rnStream(streamNum))

    /**
     * @param stream the RNStreamIfc to use
     * @return a new instance with same parameter value
     */
    override fun instance(stream: RNStreamIfc): GeneralizedBetaRV {
        return GeneralizedBetaRV(alpha1, alpha2, minimum, maximum, stream)
    }

    override fun generate(): Double {
        return minimum + (maximum - minimum) * myBeta.value
    }

    override fun toString(): String {
        return "GeneralizedBetaRV(alpha1=$alpha1, alpha2=$alpha2, minimum=$minimum, maximum=$maximum)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.GeneralizedBetaRVParameters()
            parameters.changeDoubleParameter("alpha1", alpha1)
            parameters.changeDoubleParameter("alpha2", alpha2)
            parameters.changeDoubleParameter("min", minimum)
            parameters.changeDoubleParameter("max", maximum)
            return parameters
        }

}