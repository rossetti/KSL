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
 * Pearson Type 5(shape, scale) random variable
 * @param shape the shape parameter, must be greater than 0.0
 * @param scale the scale parameter, must be greater than 0.0
 * @param stream the random number stream
 */
class PearsonType5RV (
    val shape: Double,
    val scale: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(shape > 0) { "Shape parameter must be positive" }
        require(scale > 0) { "Scale parameter must be positive" }
    }

    constructor(shape: Double, scale: Double, streamNum: Int) : this(shape, scale, KSLRandom.rnStream(streamNum)) {}

    override fun instance(stream: RNStreamIfc): PearsonType5RV {
        return PearsonType5RV(shape, scale, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rPearsonType5(shape, scale, rnStream)
    }

    override fun toString(): String {
        return "PearsonType5RV(shape=$shape, scale=$scale)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.PearsonType5RVParameters()
            parameters.changeDoubleParameter("shape", shape)
            parameters.changeDoubleParameter("scale", scale)
            return parameters
        }

}