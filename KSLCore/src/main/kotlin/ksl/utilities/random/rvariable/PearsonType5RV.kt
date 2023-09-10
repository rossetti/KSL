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
import ksl.utilities.random.rvariable.parameters.PearsonType5RVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Pearson Type 5(shape, scale) random variable
 * @param shape the shape parameter, must be greater than 0.0
 * @param scale the scale parameter, must be greater than 0.0
 * @param stream the random number stream
 */
class PearsonType5RV (
    val shape: Double,
    val scale: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(shape > 0) { "Shape parameter must be positive" }
        require(scale > 0) { "Scale parameter must be positive" }
    }

    constructor(shape: Double, scale: Double, streamNum: Int) : this(shape, scale, KSLRandom.rnStream(streamNum)) {}

    override fun instance(stream: RNStreamIfc): PearsonType5RV {
        return PearsonType5RV(shape, scale, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rPearsonType5(shape, scale, rnStream)
    }

    override fun toString(): String {
        return "PearsonType5RV(shape=$shape, scale=$scale)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = PearsonType5RVParameters()
            parameters.changeDoubleParameter("shape", shape)
            parameters.changeDoubleParameter("scale", scale)
            return parameters
        }

}