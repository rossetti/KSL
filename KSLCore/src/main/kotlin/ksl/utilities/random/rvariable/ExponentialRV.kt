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
import ksl.utilities.random.rvariable.parameters.ExponentialRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Exponential(mean) random variable
 * @param mean must be greater than 0.0, defaults to 1.0
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class ExponentialRV(
    val mean: Double = 1.0,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNumber, streamProvider, name) {

    init {
        require(mean > 0.0) { "Exponential mean must be > 0.0" }
    }

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): RVariableIfc {
        return ExponentialRV(mean, streamNumber, rnStreamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rExponential(mean, rnStream)
    }

    override fun toString(): String {
        return "ExponentialRV(mean=$mean)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = ExponentialRVParameters()
            parameters.changeDoubleParameter("mean", mean)
            return parameters
        }

}