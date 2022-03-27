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
 * Lognormal(mean, variance). The mean and variance are for the lognormal random variables
 * @param mean the mean of the distribution must be greater than 0
 * @param variance the variance of the distribution must be greater than 0
 * @param stream the random number stream
 */
class LognormalRV(
    val mean: Double,
    val variance: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {
    init {
        require(mean > 0) { "Mean must be positive" }
        require(variance > 0) { "Variance must be positive" }
    }

    constructor(mean: Double, variance: Double, streamNum: Int) :
            this(mean, variance, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): LognormalRV {
        return LognormalRV(mean, variance, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rLogNormal(mean, variance, rnStream)
    }

    override fun toString(): String {
        return "LognormalRV(mean=$mean, variance=$variance)"
    }

}