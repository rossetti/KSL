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

/**
 * Triangular(min, mode, max) random variable
 * @param min  the min, must be less than or equal to mode
 * @param mode the mode, must be less than or equal to max
 * @param max  the max
 * @param stream  the random number stream
 */
class TriangularRV(
    val min: Double,
    val mode: Double,
    val max: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name)  {
    init {
        require(min <= mode) { "min must be <= mode" }
        require(min < max) { "min must be < max" }
        require(mode <= max) { "mode must be <= max" }
    }

    constructor(min: Double, mode: Double, max: Double, streamNum: Int) :
            this(min, mode, max, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): TriangularRV {
        return TriangularRV(min, mode, max, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rTriangular(min, mode, max, rnStream)
    }

    override fun toString(): String {
        return "TriangularRV(min=$min, mode=$mode, max=$max)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.TriangularRVParameters()
            parameters.changeDoubleParameter("min", min)
            parameters.changeDoubleParameter("mode", mode)
            parameters.changeDoubleParameter("max", max)
            return parameters
        }
}