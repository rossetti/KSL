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
import ksl.utilities.random.rvariable.parameters.BetaRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Beta(alpha1, alpha2) random variable, range (0,1)
 * @param alpha1 the first shape parameter
 * @param alpha2 the second shape parameter
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class BetaRV @JvmOverloads constructor(
    val alpha1: Double = 1.0,
    val alpha2: Double = 1.0,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNum, streamProvider, name) {

    init {
        require(alpha1 > 0) { "The 1st shape parameter must be > 0" }
        require(alpha2 > 0) { "The 2nd shape parameter must be > 0" }
    }

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): BetaRV {
        return BetaRV(alpha1, alpha2, streamNum, rnStreamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rBeta(alpha1, alpha2, rnStream)
    }

    override fun toString(): String {
        return "BetaRV(alpha1=$alpha1, alpha2=$alpha2)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = BetaRVParameters()
            parameters.changeDoubleParameter("alpha1", alpha1)
            parameters.changeDoubleParameter("alpha2", alpha2)
            return parameters
        }

}