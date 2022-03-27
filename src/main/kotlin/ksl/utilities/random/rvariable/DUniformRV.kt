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
 * Discrete uniform(min, max) random variable
 * @param min the lower limit of the range, must be strictly less than max
 * @param max the upper limit of the range, must be strictly greater than min
 * @param stream the random number stream
 */
class DUniformRV(val min: Int, val max: Int, stream: RNStreamIfc = KSLRandom.nextRNStream()) :
    RVariable(stream) {
    init {
        require(min < max) { "Lower limit must be < upper limit. lower limit = $min upper limit = $max" }
    }

    /**
     * Discrete uniform(min, max) random variable
     * @param min the lower limit of the range, must be strictly less than max
     * @param max the upper limit of the range, must be strictly greater than min
     * @param streamNum the stream number for the associated random number stream
     */
    constructor(min: Int, max: Int, streamNum: Int) : this(min, max, KSLRandom.rnStream(streamNum))

    /**
     * Discrete uniform(min, max) random variable
     * @param range the range of integers
     * @param streamNum the stream number for the associated random number stream
     */
    constructor(range: IntRange, streamNum: Int) : this(range.first, range.last, KSLRandom.rnStream(streamNum))

    /**
     * Discrete uniform(min, max) random variable
     * @param range the range of integers
     * @param stream the random number stream
     */
    constructor(range: IntRange, stream: RNStreamIfc = KSLRandom.nextRNStream()) : this(range.first, range.last, stream)

    override fun instance(stream: RNStreamIfc): DUniformRV {
        return DUniformRV(min, max, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rDUniform(min, max, rnStream).toDouble()
    }

    override fun toString(): String {
        return "DUniformRV(min=$min, max=$max)"
    }

}