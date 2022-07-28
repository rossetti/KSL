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
 * Pearson Type 6(alpha1, alpha2, beta) random variable
 * @param alpha1 first shape parameter, must be greater than 0.0
 * @param alpha2 2nd shape parameter, must be greater than 0.0
 * @param beta first scale parameter, must be greater than 0.0
 * @param stream the random number stream
 */
class PearsonType6RV (
    val alpha1: Double,
    val alpha2: Double,
    val beta: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name)  {
    init {
        require(alpha1 > 0.0) { "The 1st shape parameter must be > 0.0" }
        require(alpha2 > 0.0) { "The 2nd shape parameter must be > 0.0" }
        require(beta > 0.0) { "The scale parameter must be > 0.0" }
    }

    constructor(alpha1: Double, alpha2: Double, beta: Double, streamNum: Int) : this(
        alpha1, alpha2, beta, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): PearsonType6RV {
        return PearsonType6RV(alpha1, alpha2, beta, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rPearsonType6(alpha1, alpha2, beta, rnStream)
    }

    override fun toString(): String {
        return "PearsonType6RV(alpha1=$alpha1, alpha2=$alpha2, beta=$beta)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.PearsonType6RVParameters()
            parameters.changeDoubleParameter("alpha1", alpha1)
            parameters.changeDoubleParameter("alpha2", alpha2)
            parameters.changeDoubleParameter("beta", beta)
            return parameters
        }

}