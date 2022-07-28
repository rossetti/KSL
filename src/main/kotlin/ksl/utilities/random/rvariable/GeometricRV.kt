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
 * Geometric(probability of success) random variable, range 0, 1, 2, etc.
 * @param probOfSuccess   probability of success, must be in range (0,1)
 * @param stream the random number stream to use
 */
class GeometricRV (val probOfSuccess: Double, stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null) :
    ParameterizedRV(stream, name) {
    init {
        require(!(probOfSuccess <= 0.0 || probOfSuccess >= 1.0)) { "Probability must be (0,1)" }
    }

    /**
     * @param prob      probability of success, must be in range (0,1)
     * @param streamNum the stream number to use
     */
    constructor(prob: Double, streamNum: Int) : this(prob, KSLRandom.rnStream(streamNum))

    /**
     * @param stream the random number stream to use
     * @return a new instance with same parameter value
     */
    override fun instance(stream: RNStreamIfc): GeometricRV {
        return GeometricRV(probOfSuccess, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rGeometric(probOfSuccess, rnStream).toDouble()
    }

    override fun toString(): String {
        return "GeometricRV(probOfSuccess=$probOfSuccess)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.GeometricRVParameters()
            parameters.changeDoubleParameter("ProbOfSuccess", probOfSuccess)
            return parameters
        }

}