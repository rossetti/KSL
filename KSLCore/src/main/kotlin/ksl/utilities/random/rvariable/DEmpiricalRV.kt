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

import ksl.utilities.distributions.ProbPoint
import ksl.utilities.distributions.cdf
import ksl.utilities.distributions.values
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.parameters.DEmpiricalRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.HistogramIfc

/**
 * Discrete Empirical Random Variable. Randomly selects from the supplied
 * values in the value array according to the supplied CDF array. The CDF array
 * must have valid probability elements and last element equal to 1.
 * Every element must be greater than or equal to the previous element in the CDF array.
 * That is, monotonically increasing.
 * @param values array to select from
 * @param cdf the cumulative probability associated with each element of array
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class DEmpiricalRV(
    values: DoubleArray,
    cdf: DoubleArray,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNum, streamProvider, name) {

    init {
        require(values.size == cdf.size) { "The arrays did not have the same length." }
        require(KSLRandom.isValidCDF(cdf)) { "The supplied cdf was not valid." }
    }

    val probabilityPoints: List<ProbPoint>
        get() {
            val list = mutableListOf<ProbPoint>()
            var cp = 0.0
            for ((i, v) in myValues.withIndex()) {
                val pp = ProbPoint(v, myCDF[i] - cp, myCDF[i])
                cp = myCDF[i]
                list.add(pp)
            }
            return list
        }

    private val myValues: DoubleArray = values.copyOf()

    val values: DoubleArray
        get() = myValues.copyOf()

    private val myCDF: DoubleArray = cdf.copyOf()

    val cdf: DoubleArray
        get() = myCDF.copyOf()

    constructor(
        probData: List<ProbPoint>,
        streamNumber: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(probData.values(), probData.cdf(), streamNumber, streamProvider, name)

    /**
     *
     * @param histogram a histogram specifying the midpoints and bin fractions
     * @param streamNum the stream number
     */
    constructor(
        histogram: HistogramIfc,
        streamNumber: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(histogram.midPoints, KSLRandom.makeCDF(histogram.binFractions), streamNumber, streamProvider, name)

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): DEmpiricalRV {
        return DEmpiricalRV(myValues, myCDF, streamNum, rnStreamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.discreteInverseCDF(myValues, myCDF, rnStream)
    }

    override fun toString(): String {
        return "DEmpiricalRV(values=${myValues.contentToString()}, cdf=${myCDF.contentToString()})"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = DEmpiricalRVParameters()
            parameters.changeDoubleArrayParameter("values", myValues)
            parameters.changeDoubleArrayParameter("cdf", myCDF)
            return parameters
        }

}