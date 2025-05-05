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
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.random.rvariable.parameters.UniformRVParameters

/**
 * Generates a continuous uniform over the range
 *
 * @param min the minimum of the range, must be less than maximum
 * @param max the maximum of the range
 * @param stream     the random number stream
 */
class UniformRV (
    val min: Double = 0.0,
    val max: Double = 1.0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamProvider, name) {

    init {
        require(min < max) { "Lower limit must be < upper limit. lower limit = $min upper limit = $max" }
    }

    constructor(
        min: Double = 0.0,
        max: Double = 1.0,
        streamNum: Int,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(min, max, streamProvider, name){
        rnStream = streamProvider.rnStream(streamNum)
    }

    override fun instance(streamNum: Int): UniformRV {
        return UniformRV(min, max, streamNum, streamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rUniform(min, max, rnStream)
    }

    override fun toString(): String {
        return "UniformRV(min=$min, max=$max)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = UniformRVParameters()
            parameters.changeDoubleParameter("min", min)
            parameters.changeDoubleParameter("max", max)
            return parameters
        }

}