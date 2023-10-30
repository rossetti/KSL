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
import ksl.utilities.random.rvariable.parameters.DUniformRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Discrete uniform(min, max) random variable
 * @param min the lower limit of the range, must be strictly less than max
 * @param max the upper limit of the range, must be strictly greater than min
 * @param stream the random number stream
 */
class DUniformRV(val min: Int, val max: Int, stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null) :
    ParameterizedRV(stream, name)  {
    init {
        require(min < max) { "Lower limit must be < upper limit. lower limit = $min upper limit = $max" }
    }

    /**
     * Discrete uniform(min, max) random variable
     * @param min the lower limit of the range, must be strictly less than max
     * @param max the upper limit of the range, must be strictly greater than min
     * @param streamNum the stream number for the associated random number stream
     */
    constructor(min: Int, max: Int, streamNum: Int) : this(min, max, KSLRandom.rnStream(streamNum))

    /**
     * Discrete uniform(min, max) random variable
     * @param range the range of integers
     * @param streamNum the stream number for the associated random number stream
     */
    constructor(range: IntRange, streamNum: Int) : this(range.first, range.last, KSLRandom.rnStream(streamNum))

    /**
     * Discrete uniform(min, max) random variable
     * @param range the range of integers
     * @param stream the random number stream
     */
    constructor(range: IntRange, stream: RNStreamIfc = KSLRandom.nextRNStream()) : this(range.first, range.last, stream)

    override fun instance(stream: RNStreamIfc): DUniformRV {
        return DUniformRV(min, max, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rDUniform(min, max, rnStream).toDouble()
    }

    override fun toString(): String {
        return "DUniformRV(min=$min, max=$max)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = DUniformRVParameters()
            parameters.changeIntegerParameter("min", min)
            parameters.changeIntegerParameter("max", max)
            return parameters
        }

}