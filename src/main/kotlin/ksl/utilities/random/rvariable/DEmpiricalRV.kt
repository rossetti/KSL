/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc

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
            val parameters: RVParameters = RVParameters.DEmpiricalRVParameters()
            parameters.changeDoubleArrayParameter("values", values)
            parameters.changeDoubleArrayParameter("cdf", cdf)
            return parameters
        }

}