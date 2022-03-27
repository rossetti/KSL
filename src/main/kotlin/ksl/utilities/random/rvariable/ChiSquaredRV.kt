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
 * Chi-Squared(degrees of freedom) random variable
 * @param degreesOfFreedom the degrees of freedom for the random variable, must be greater than 0.0
 * @param stream the random number stream
 */
class ChiSquaredRV (val degreesOfFreedom: Double, stream: RNStreamIfc = KSLRandom.nextRNStream()) :
    RVariable(stream) {
    init {
        require(degreesOfFreedom > 0.0) { "Chi-Squared degrees of freedom must be > 0.0" }
    }

    /**
     * @param degreesOfFreedom the degrees of freedom for the random variable, must be greater than 0.0
     * @param streamNum the random number stream number
     */
    constructor(degreesOfFreedom: Double, streamNum: Int) : this(degreesOfFreedom, KSLRandom.rnStream(streamNum)) {}

    override fun instance(stream: RNStreamIfc): ChiSquaredRV {
        return ChiSquaredRV(degreesOfFreedom, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rChiSquared(degreesOfFreedom, rnStream)
    }

    override fun toString(): String {
        return "ChiSquaredRV(degreesOfFreedom=$degreesOfFreedom)"
    }

}