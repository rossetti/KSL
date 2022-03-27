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
 * Triangular(min, mode, max) random variable
 * @param min  the min, must be less than or equal to mode
 * @param mode the mode, must be less than or equal to max
 * @param max  the max
 * @param stream  the random number stream
 */
class TriangularRV(
    val min: Double,
    val mode: Double,
    val max: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {
    init {
        require(min <= mode) { "min must be <= mode" }
        require(min < max) { "min must be < max" }
        require(mode <= max) { "mode must be <= max" }
    }

    constructor(min: Double, mode: Double, max: Double, streamNum: Int) :
            this(min, mode, max, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): TriangularRV {
        return TriangularRV(min, mode, max, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rTriangular(min, mode, max, rnStream)
    }

    override fun toString(): String {
        return "TriangularRV(min=$min, mode=$mode, max=$max)"
    }

}