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
import ksl.utilities.random.rvariable.parameters.PoissonRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Poisson(mean) random variable
 * @param mean the mean rate, must be greater than 0.0
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class PoissonRV (
    val mean: Double,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNum, streamProvider, name)  {

    init {
        require(mean > 0.0) { "Poisson mean must be > 0.0" }
    }

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): PoissonRV {
        return PoissonRV(mean, streamNum, rnStreamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rPoisson(mean, rnStream).toDouble()
    }

    override fun toString(): String {
        return "PoissonRV(mean=$mean)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = PoissonRVParameters()
            parameters.changeDoubleParameter("mean", mean)
            return parameters
        }
}