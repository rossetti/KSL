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
 * Exponential(mean) random variable
 * @param mean must be greater than 0.0
 */
class ExponentialRV(val mean: Double, rng: RNStreamIfc = KSLRandom.nextRNStream(), name:String? = null) :
    RVariable(rng, name) {

    /**
     * @param mean      must be greater than 0.0
     * @param streamNum the stream number
     */
    constructor(mean: Double, streamNum: Int) : this(mean, KSLRandom.rnStream(streamNum)) {}

    init {
        require(mean > 0.0) { "Exponential mean must be > 0.0" }
    }

    override fun instance(stream: RNStreamIfc): ExponentialRV {
        return ExponentialRV(mean, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rExponential(mean, rnStream)
    }

    override fun toString(): String {
        return "ExponentialRV(mean=$mean)"
    }

}