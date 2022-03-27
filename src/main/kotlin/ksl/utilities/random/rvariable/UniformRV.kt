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
 * Generates a continuous uniform over the range
 *
 * @param min the minimum of the range, must be less than maximum
 * @param max the maximum of the range
 * @param stream     the random number stream
 */
class UniformRV (
    val min: Double = 0.0,
    val max: Double = 1.0,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {
    init {
        require(min < max) { "Lower limit must be < upper limit. lower limit = $min upper limit = $max" }
    }

    constructor(min: Double, max: Double, streamNum: Int) : this(min, max, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): UniformRV {
        return UniformRV(min, max, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rUniform(min, max, rnStream)
    }

    override fun toString(): String {
        return "UniformRV(min=$min, max=$max)"
    }

}