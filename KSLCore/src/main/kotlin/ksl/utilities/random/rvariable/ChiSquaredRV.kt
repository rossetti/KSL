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
import ksl.utilities.random.rvariable.parameters.ChiSquaredRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Chi-Squared(degrees of freedom) random variable
 * @param degreesOfFreedom the degrees of freedom for the random variable, must be greater than 0.0
 * @param stream the random number stream
 */
class ChiSquaredRV (
    val degreesOfFreedom: Double,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamProvider, name) {
    init {
        require(degreesOfFreedom > 0.0) { "Chi-Squared degrees of freedom must be > 0.0" }
    }

    /**
     * @param degreesOfFreedom the degrees of freedom for the random variable, must be greater than 0.0
     * @param streamNum the random number stream number
     */
    constructor(
        degreesOfFreedom: Double,
        streamNum: Int,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(degreesOfFreedom, streamProvider, name) {
        rnStream = streamProvider.rnStream(streamNum)
    }

    override fun instance(streamNum: Int): ChiSquaredRV {
        return ChiSquaredRV(degreesOfFreedom, streamNum, streamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rChiSquared(degreesOfFreedom, rnStream)
    }

    override fun toString(): String {
        return "ChiSquaredRV(degreesOfFreedom=$degreesOfFreedom)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = ChiSquaredRVParameters()
            parameters.changeDoubleParameter("dof", degreesOfFreedom)
            return parameters

        }

}