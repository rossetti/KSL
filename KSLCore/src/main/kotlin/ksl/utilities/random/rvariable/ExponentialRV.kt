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
 * Exponential(mean) random variable
 * @param mean must be greater than 0.0
 */
class ExponentialRV(val mean: Double = 1.0, stream: RNStreamIfc = KSLRandom.nextRNStream(), name:String? = null) :
    ParameterizedRV(stream, name) {

    /**
     * @param mean      must be greater than 0.0
     * @param streamNum the stream number
     */
    constructor(mean: Double, streamNum: Int) : this(mean, KSLRandom.rnStream(streamNum))

    init {
        require(mean > 0.0) { "Exponential mean must be > 0.0" }
    }

    override fun instance(stream: RNStreamIfc): ExponentialRV {
        return ExponentialRV(mean, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rExponential(mean, rnStream)
    }

    override fun toString(): String {
        return "ExponentialRV(mean=$mean)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.ExponentialRVParameters()
            parameters.changeDoubleParameter("mean", mean)
            return parameters
        }

}