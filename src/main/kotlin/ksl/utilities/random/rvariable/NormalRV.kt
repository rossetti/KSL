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
import kotlin.math.sqrt

/**
 * Normal(mean, variance)
 * @param mean the mean of the distribution
 * @param variance the variance of the distribution
 */
class NormalRV(
    val mean: Double = 0.0,
    val variance: Double = 1.0,
    rng: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(rng) {

    init {
        require(variance > 0) { "Variance must be positive" }
    }

    /**
     * @return the standard deviation of the random variable
     */
    val stdDeviation: Double = sqrt(variance)

    constructor(mean: Double, variance: Double, streamNum: Int) :
            this(mean, variance, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): NormalRV {
        return NormalRV(mean, variance, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rNormal(mean, variance, rnStream)
    }

    override fun toString(): String {
        return "NormalRV(mean=$mean, variance=$variance)"
    }

}