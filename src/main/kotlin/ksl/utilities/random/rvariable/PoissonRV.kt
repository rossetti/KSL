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
 * Poisson(mean) random variable
 * @param mean the mean rate, must be greater than 0.0
 * @param stream the random number stream
 */
class PoissonRV (val mean: Double, stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null) :
    ParameterizedRV(stream, name)  {
    init {
        require(mean > 0.0) { "Poisson mean must be > 0.0" }
    }
    constructor(mean: Double, streamNum: Int) : this(mean, KSLRandom.rnStream(streamNum)) {}

    override fun instance(stream: RNStreamIfc): PoissonRV {
        return PoissonRV(mean, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rPoisson(mean, rnStream).toDouble()
    }

    override fun toString(): String {
        return "PoissonRV(mean=$mean)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.PoissonRVParameters()
            parameters.changeDoubleParameter("mean", mean)
            return parameters
        }
}