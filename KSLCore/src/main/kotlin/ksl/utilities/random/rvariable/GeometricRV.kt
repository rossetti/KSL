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
import ksl.utilities.random.rvariable.parameters.GeometricRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Geometric(probability of success) random variable, range 0, 1, 2, etc.
 * @param probOfSuccess   probability of success, must be in range (0,1)
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class GeometricRV (
    val probOfSuccess: Double,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNumber, streamProvider, name) {

    init {
        require(!(probOfSuccess <= 0.0 || probOfSuccess >= 1.0)) { "Probability must be (0,1)" }
    }

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): GeometricRV {
        return GeometricRV(probOfSuccess, streamNumber, rnStreamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rGeometric(probOfSuccess, rnStream).toDouble()
    }

    override fun toString(): String {
        return "GeometricRV(probOfSuccess=$probOfSuccess)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = GeometricRVParameters()
            parameters.changeDoubleParameter("ProbOfSuccess", probOfSuccess)
            return parameters
        }

}