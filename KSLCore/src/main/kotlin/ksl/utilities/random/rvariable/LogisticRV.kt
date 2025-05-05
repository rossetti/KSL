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
import ksl.utilities.random.rvariable.parameters.LogisticRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Logistic(location, scale) random variable
 * @param location must be a real number
 * @param scale must be greater than 0
 * @param stream the stream to use
 */
class LogisticRV(
    val location: Double = 0.0,
    val scale: Double = 1.0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamProvider, name)  {

    init {
        require(scale > 0) { "Scale parameter must be > 0" }
    }

    constructor(
        location: Double,
        scale: Double,
        streamNum: Int,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(location, scale, streamProvider, name) {
        rnStream = streamProvider.rnStream(streamNum)
    }

    override fun instance(streamNum: Int): LogisticRV {
        return LogisticRV(location, scale, streamNum, streamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rLogistic(location, scale, rnStream)
    }

    override fun toString(): String {
        return "LogisticRV(location=$location, scale=$scale)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = LogisticRVParameters()
            parameters.changeDoubleParameter("location", location)
            parameters.changeDoubleParameter("scale", scale)
            return parameters
        }

}