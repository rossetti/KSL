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
 * @param mean must be greater than 0.0
 */
class ExponentialRV(
    val mean: Double = 1.0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamProvider, name) {

    /**
     * @param mean      must be greater than 0.0
     * @param streamNum the stream number
     */
    constructor(
        mean: Double,
        streamNum: Int,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(mean, streamProvider, name){
        rnStream = streamProvider.rnStream(streamNum)
    }

    init {
        require(mean > 0.0) { "Exponential mean must be > 0.0" }
    }

    override fun instance(streamNum: Int): ExponentialRV {
        return ExponentialRV(mean, streamNum, streamProvider, name)
    }

    override fun instance(): ExponentialRV {
        return ExponentialRV(mean, streamProvider, name)
    }

    override fun antitheticInstance(): RVariableIfc {
        // use the same stream number
       // val e = ExponentialRV(mean, st, name)
        val antitheticStream = rnStream.antitheticInstance()
        TODO("Not implemented yet")
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