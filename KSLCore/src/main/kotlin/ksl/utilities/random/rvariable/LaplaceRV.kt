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
import ksl.utilities.random.rvariable.parameters.LaplaceRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * LaplaceRV(mean, scale)
 * @param location the mean (location) of the random variable
 * @param scale must be greater than 0
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class LaplaceRV @JvmOverloads constructor(
    val location: Double,
    val scale: Double,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNum, streamProvider, name) {

    init {
        require(scale > 0) { "Scale must be positive" }
    }

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): LaplaceRV {
        return LaplaceRV(location, scale, streamNum, rnStreamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rLaplace(location, scale, rnStream)
    }

    override fun toString(): String {
        return "LaplaceRV(location=$location, scale=$scale)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = LaplaceRVParameters()
            parameters.changeDoubleParameter("location", location)
            parameters.changeDoubleParameter("scale", scale)
            return parameters
        }
}