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
import ksl.utilities.random.rvariable.parameters.GeneralizedBetaRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * GeneralizeBetaRV(alpha1, alpha2, min, max) random variable
 * @param alpha the alpha shape parameter, must be greater than 0
 * @param beta the beta shape parameter, must be greater than 0
 * @param min the minimum of the range, must be less than maximum
 * @param max the maximum of the range
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class GeneralizedBetaRV(
    val alpha: Double,
    val beta: Double,
    val min: Double,
    val max: Double,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNumber, streamProvider, name) {

    init {
        require(max > min) { "the min must be < than the max" }
    }

    private val myBeta: BetaRV = BetaRV(alpha, beta, streamNumber, streamProvider)

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): GeneralizedBetaRV {
        return GeneralizedBetaRV(alpha, beta, min, max, streamNumber, rnStreamProvider, name)
    }

    override fun generate(): Double {
        return min + (max - min) * myBeta.value
    }

    override fun toString(): String {
        return "GeneralizedBetaRV(alpha=$alpha, beta=$beta, min=$min, max=$max)"
    }

    /**
     * Returns the parameters in a parameter map
     * alpha the alpha shape parameter
     * beta the beta shape parameter
     * min the minimum of the range
     * max the maximum of the range
     */
    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = GeneralizedBetaRVParameters()
            parameters.changeDoubleParameter("alpha", alpha)
            parameters.changeDoubleParameter("beta", beta)
            parameters.changeDoubleParameter("min", min)
            parameters.changeDoubleParameter("max", max)
            return parameters
        }

}