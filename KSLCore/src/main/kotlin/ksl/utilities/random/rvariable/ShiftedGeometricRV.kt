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

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.random.rvariable.parameters.ShiftedGeometricRVParameters

/**
 * Shifted Geometric(probability of success) random variable, range 1, 2, 3, etc.
 * @param probOfSuccess   probability of success, must be in range (0,1)
 * @param stream the random number stream to use

 */
class ShiftedGeometricRV(
    val probOfSuccess: Double, stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(!(probOfSuccess <= 0.0 || probOfSuccess >= 1.0)) { "Probability must be (0,1)" }
    }

    /**
     * @param probOfSuccess      probability of success, must be in range (0,1)
     * @param streamNum the stream number to use
     */
    constructor(probOfSuccess: Double, streamNum: Int) : this(probOfSuccess, KSLRandom.rnStream(streamNum)) {}

    /**
     * @param stream the RNStreamIfc to use
     * @return a new instance with same parameter value
     */
    override fun instance(stream: RNStreamIfc): ShiftedGeometricRV {
        return ShiftedGeometricRV(probOfSuccess, stream)
    }

    override fun generate(): Double {
        return 1.0 + KSLRandom.rGeometric(probOfSuccess, rnStream)
    }

    override fun toString(): String {
        return "ShiftedGeometricRV(probOfSuccess=$probOfSuccess)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = ShiftedGeometricRVParameters()
            parameters.changeDoubleParameter("probOfSuccess", probOfSuccess)
            return parameters
        }

}