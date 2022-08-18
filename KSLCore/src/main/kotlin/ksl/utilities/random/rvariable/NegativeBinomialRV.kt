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
 * NegativeBinomial(probability of success, number of trials until rth success)
 * @param probOfSuccess       the probability of success, must be in (0,1)
 * @param numSuccess number of trials until rth success
 * @param stream     the stream from the stream provider to use
 */
class NegativeBinomialRV(
    val probOfSuccess: Double,
    val numSuccess: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(!(probOfSuccess <= 0.0 || probOfSuccess >= 1.0)) { "Success Probability must be (0,1)" }
        require(numSuccess > 0) { "Number of trials until rth success must be > 0" }
    }

    /**
     * @param prob       the probability of success, must be in (0,1)
     * @param numSuccess number of trials until rth success
     * @param streamNum  the stream number from the stream provider to use
     */
    constructor(prob: Double, numSuccess: Double, streamNum: Int) : this(
        prob, numSuccess, KSLRandom.rnStream(streamNum)
    )

    override fun instance(stream: RNStreamIfc): NegativeBinomialRV {
        return NegativeBinomialRV(probOfSuccess, numSuccess, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rNegBinomial(probOfSuccess, numSuccess, rnStream).toDouble()
    }

    override fun toString(): String {
        return "NegativeBinomialRV(probOfSuccess=$probOfSuccess, numSuccess=$numSuccess)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.NegativeBinomialRVParameters()
            parameters.changeDoubleParameter("probOfSuccess", probOfSuccess)
            parameters.changeIntegerParameter("numSuccesses", numSuccess.toInt())
            return parameters
        }
}