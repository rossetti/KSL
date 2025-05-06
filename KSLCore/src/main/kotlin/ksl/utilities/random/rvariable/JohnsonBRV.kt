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
import ksl.utilities.random.rvariable.parameters.JohnsonBRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * JohnsonB(alpha1, alpha2, min, max) random variable
 * @param alpha1 alpha1 parameter
 * @param alpha2 alpha2 parameter, must be greater than zero
 * @param min    the min, must be less than max
 * @param max    the max
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class JohnsonBRV (
    val alpha1: Double,
    val alpha2: Double,
    val min: Double,
    val max: Double,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNumber, streamProvider, name) {

    init {
        require(alpha2 > 0) { "alpha2 must be > 0" }
        require(max > min) { "the min must be < than the max" }
    }

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): JohnsonBRV {
        return JohnsonBRV(alpha1, alpha2, min, max, streamNumber, rnStreamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rJohnsonB(alpha1, alpha2, min, max, rnStream)
    }

    override fun toString(): String {
        return "JohnsonBRV(alpha1=$alpha1, alpha2=$alpha2, min=$min, max=$max)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = JohnsonBRVParameters()
            parameters.changeDoubleParameter("alpha1", alpha1)
            parameters.changeDoubleParameter("alpha2", alpha2)
            parameters.changeDoubleParameter("min", min)
            parameters.changeDoubleParameter("max", max)
            return parameters
        }

}