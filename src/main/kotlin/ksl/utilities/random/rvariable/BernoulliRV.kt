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
 * Bernoulli(probability of success) random variable
 *
 * @param probOfSuccess      the probability, must be in (0,1)
 * @param stream the stream
 */
class BernoulliRV (
    val probOfSuccess: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : RVariable(stream, name) {

    /**
     * @param probOfSuccess      the probability, must be in (0,1)
     * @param streamNum the stream number
     */
    constructor(probOfSuccess: Double, streamNum: Int) : this(probOfSuccess, KSLRandom.rnStream(streamNum))

    init {
        require(!(probOfSuccess <= 0.0 || probOfSuccess >= 1.0)) { "Probability must be (0,1)" }
    }

    /**
     * @param stream the RNStreamIfc to use
     * @return a new instance with same parameter value
     */
    override fun instance(stream: RNStreamIfc): BernoulliRV {
        return BernoulliRV(probOfSuccess, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rBernoulli(probOfSuccess, rnStream)
    }

    /**
     * Returns a randomly generated boolean according to the Bernoulli distribution
     *
     * @return a randomly generated boolean
     */
    val boolValue: Boolean
        get() = (value != 0.0)

    /**
     * Returns a boolean array filled via boolSample()
     *
     * @param n the generation size, must be at least 1
     * @return the array
     */
    fun boolSample(n: Int): BooleanArray {
        require(n > 0) { "The generate size must be > 0" }
        val b = BooleanArray(n)
        for (i in 0 until n) {
            b[i] = boolValue
        }
        return b
    }

    override fun toString(): String {
        return "BernoulliRV(probOfSuccess=$probOfSuccess)"
    }

}