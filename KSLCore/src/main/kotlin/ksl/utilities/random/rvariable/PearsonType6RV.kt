/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.parameters.PearsonType6RVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Pearson Type 6(alpha1, alpha2, beta) random variable
 * @param alpha1 first shape parameter, must be greater than 0.0
 * @param alpha2 2nd shape parameter, must be greater than 0.0
 * @param beta first scale parameter, must be greater than 0.0
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class PearsonType6RV (
    val alpha1: Double,
    val alpha2: Double,
    val beta: Double,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNum, streamProvider, name)  {

    init {
        require(alpha1 > 0.0) { "The 1st shape parameter must be > 0.0" }
        require(alpha2 > 0.0) { "The 2nd shape parameter must be > 0.0" }
        require(beta > 0.0) { "The scale parameter must be > 0.0" }
    }

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): PearsonType6RV {
        return PearsonType6RV(alpha1, alpha2, beta, streamNum, rnStreamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rPearsonType6(alpha1, alpha2, beta, rnStream)
    }

    override fun toString(): String {
        return "PearsonType6RV(alpha1=$alpha1, alpha2=$alpha2, beta=$beta)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = PearsonType6RVParameters()
            parameters.changeDoubleParameter("alpha1", alpha1)
            parameters.changeDoubleParameter("alpha2", alpha2)
            parameters.changeDoubleParameter("beta", beta)
            return parameters
        }

}