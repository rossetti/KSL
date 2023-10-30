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
import ksl.utilities.random.rvariable.parameters.DEmpiricalRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Discrete Empirical Random Variable. Randomly selects from the supplied
 * values in the value array according to the supplied CDF array. The CDF array
 * must have valid probability elements and last element equal to 1.
 * Every element must be greater than or equal to the previous element in the CDF array.
 * That is, monotonically increasing.
 * @param values array to select from
 * @param cdf the cumulative probability associated with each element of array
 * @param stream  the source of randomness
 */
class DEmpiricalRV(
    values: DoubleArray,
    cdf: DoubleArray,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(values.size == cdf.size) { "The arrays did not have the same length." }
        require(KSLRandom.isValidCDF(cdf)) { "The supplied cdf was not valid." }
    }

    val values: DoubleArray = values.copyOf()
        get() = field.copyOf()

    val cdf: DoubleArray = cdf.copyOf()
        get() = field.copyOf()

    /**
     * Randomly selects from the array using the supplied cdf
     *
     * @param values    array to select from
     * @param cdf       the cumulative probability associated with each element of array
     * @param streamNum the stream number
     */
    constructor(values: DoubleArray, cdf: DoubleArray, streamNum: Int) : this(
        values, cdf, KSLRandom.rnStream(streamNum)
    )

    override fun instance(stream: RNStreamIfc): DEmpiricalRV {
        return DEmpiricalRV(values, cdf, stream)
    }

    override fun generate(): Double {
        return KSLRandom.discreteInverseCDF(values, cdf, rnStream)
    }

    override fun toString(): String {
        return "DEmpiricalRV(values=${values.contentToString()}, cdf=${cdf.contentToString()})"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = DEmpiricalRVParameters()
            parameters.changeDoubleArrayParameter("values", values)
            parameters.changeDoubleArrayParameter("cdf", cdf)
            return parameters
        }

}