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
import ksl.utilities.random.rvariable.parameters.LognormalRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Lognormal(mean, variance). The mean and variance are for the lognormal random variables
 * @param mean the mean of the distribution must be greater than 0
 * @param variance the variance of the distribution must be greater than 0
 * @param stream the random number stream
 */
class LognormalRV(
    val mean: Double,
    val variance: Double,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamProvider, name){

    init {
        require(mean > 0) { "Mean must be positive" }
        require(variance > 0) { "Variance must be positive" }
    }

    constructor(
        mean: Double,
        variance: Double,
        streamNum: Int,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(mean, variance, streamProvider, name){
        rnStream = streamProvider.rnStream(streamNum)
    }

    override fun instance(streamNum: Int): LognormalRV {
        return LognormalRV(mean, variance, streamNum, streamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rLogNormal(mean, variance, rnStream)
    }

    override fun toString(): String {
        return "LognormalRV(mean=$mean, variance=$variance)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = LognormalRVParameters()
            parameters.changeDoubleParameter("mean", mean)
            parameters.changeDoubleParameter("variance", variance)
            return parameters
        }

}