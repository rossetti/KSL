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
 * GammaRV(shape, scale) random variable
 */
class GammaRV (val shape: Double, val scale: Double, rng: RNStreamIfc = KSLRandom.nextRNStream()) :
    RVariable(rng) {

    init {
        require(shape > 0){"The shape parameter must be > 0"}
        require(scale > 0){"The shape parameter must be > 0"}
    }
    constructor(shape: Double, scale: Double, streamNum: Int) : this(shape, scale, KSLRandom.rnStream(streamNum))

    /**
     * @param stream the RNStreamIfc to use
     * @return a new instance with same parameter value
     */
    override fun instance(stream: RNStreamIfc): GammaRV {
        return GammaRV(shape, scale, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rGamma(shape, scale, rnStream)
    }

    override fun toString(): String {
        return "GammaRV(shape=$shape, scale=$scale)"
    }

}