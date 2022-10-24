/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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
 * JohnsonB(alpha1, alpha2, min, max) random variable
 * @param alpha1 alpha1 parameter
 * @param alpha2 alpha2 parameter, must be greater than zero
 * @param min    the min, must be less than max
 * @param max    the max
 * @param stream    the random number stream
 */
class JohnsonBRV (
    val alpha1: Double,
    val alpha2: Double,
    val min: Double,
    val max: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(alpha2 > 0) { "alpha2 must be > 0" }
        require(max > min) { "the min must be < than the max" }
    }

    constructor(alpha1: Double, alpha2: Double, min: Double, max: Double, streamNum: Int) :
            this(alpha1, alpha2, min, max, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): JohnsonBRV {
        return JohnsonBRV(alpha1, alpha2, min, max, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rJohnsonB(alpha1, alpha2, min, max, rnStream)
    }

    override fun toString(): String {
        return "JohnsonBRV(alpha1=$alpha1, alpha2=$alpha2, min=$min, max=$max)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.JohnsonBRVParameters()
            parameters.changeDoubleParameter("alpha1", alpha1)
            parameters.changeDoubleParameter("alpha2", alpha2)
            parameters.changeDoubleParameter("min", min)
            parameters.changeDoubleParameter("max", max)
            return parameters
        }

}