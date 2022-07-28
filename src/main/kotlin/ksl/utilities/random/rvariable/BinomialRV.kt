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
 * BinomialRV(probability of success, number of trials)
 * @param pSuccess  the probability of success, must be in (0,1)
 * @param numTrials the number of trials, must be greater than 0
 * @param stream    the stream from the stream provider to use
 * @param name an optional name
 */
class BinomialRV constructor(
    val pSuccess: Double,
    val numTrials: Int,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(!(pSuccess < 0.0 || pSuccess > 1.0)) { "Success Probability must be [0,1]" }
        require(numTrials > 0) { "Number of trials must be >= 1" }
    }

    /**
     * @param pSuccess  the probability of success, must be in (0,1)
     * @param numTrials the number of trials, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     */
    constructor(pSuccess: Double, numTrials: Int, streamNum: Int, name: String? = null) : this(
        pSuccess,
        numTrials,
        KSLRandom.rnStream(streamNum),
        name
    )

    override fun instance(stream: RNStreamIfc): BinomialRV {
        return BinomialRV(pSuccess, numTrials, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rBinomial(pSuccess, numTrials, rnStream).toDouble()
    }

    override fun toString(): String {
        return "BinomialRV(pSuccess=$pSuccess, numTrials=$numTrials)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.BinomialRVParameters()
            parameters.changeDoubleParameter("probOfSuccess", pSuccess)
            parameters.changeIntegerParameter("numTrials", numTrials)
            return parameters
        }

}