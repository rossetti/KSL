/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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
 * A random variable that samples from the provided data. Each value is
 * equally likely to occur.
 * @param data the data to sample from
 * @param stream the random number stream to use
 */
class EmpiricalRV (data: DoubleArray, stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null) :
    ParameterizedRV(stream, name){
    init {
        require(data.isNotEmpty()) { "The supplied data array had no elements." }
    }

    /**
     * A copy of the data array is returned
     */
    val values = data.copyOf()
        get() = field.copyOf()

    /**
     * A random variable that samples from the provided data. Each value is
     * equally likely to occur.
     * @param data the data to sample from
     * @param streamNum the random number stream to use
     */
    constructor(data: DoubleArray, streamNum: Int) : this(data, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return EmpiricalRV(values, rnStream)
    }

    override fun generate(): Double {
        return KSLRandom.randomlySelect(values, rnStream)
    }

    override fun toString(): String {
        return "EmpiricalRV(data=${values.contentToString()})"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.EmpiricalRVParameters()
            parameters.changeDoubleArrayParameter("population", values)
            return parameters

        }

}