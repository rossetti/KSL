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
class DEmpiricalRV @JvmOverloads constructor(
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

    /**
     * Represents a list of `ProbPoint` objects that encapsulates the value,
     * probability, and cumulative probability information for a discrete
     * empirical random variable.
     *
     * This property computes the list based on the set of values
     * for the random variable and the corresponding cumulative
     * distribution function values. Each `ProbPoint` contains:
     * - `value`: A particular value of the random variable.
     * - `prob`: The probability of the value occurring within the range defined
     *           by the cumulative distribution function.
     * - `cumProb`: The cumulative probability up to and including the value.
     *
     * The property is read-only and is computed dynamically upon access.
     *
     * The probabilities and cumulative probabilities are validated to ensure
     * they fall within the range [0, 1].
     *
     * @return A list of `ProbPoint` objects that define the probability
     *         distribution for the random variable.
     */
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

    /**
     * Retrieves a copy of the internal array of values associated with the empirical random variable.
     *
     * This property provides access to the underlying array of values in a safe manner, ensuring that
     * modifications to the returned array do not affect the internal state of the class. Each call
     * returns a new copy of the array containing the values.
     *
     * @return A copy of the array holding the empirical random variable's values.
     */
    val values: DoubleArray
        get() = myValues.copyOf()

    private val myCDF: DoubleArray = cdf.copyOf()

    /**
     * Provides a copy of the cumulative distribution function (CDF) array associated with the discrete empirical
     * random variable. The CDF represents the cumulative probabilities corresponding to the defined probability points
     * and values of the random variable.
     */
    val cdf: DoubleArray
        get() = myCDF.copyOf()

    /**
     * Secondary constructor for the DEmpiricalRV class. This constructor initializes the
     * object using a list of `ProbPoint` instances, representing the possible values and
     * their associated probabilities, as well as their cumulative probabilities.
     *
     * @param probData A list of `ProbPoint` objects that encapsulate the possible values,
     * their probabilities, and cumulative probabilities.
     * @param streamNumber The number identifying the random number stream to be used. Defaults to 0.
     * @param streamProvider A provider interface for random number streams, defaults to
     * `KSLRandom.DefaultRNStreamProvider`.
     * @param name Optional name for the instance, providing a descriptive identifier.
     */
    @JvmOverloads
    constructor(
        probData: List<ProbPoint>,
        streamNumber: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(probData.values(), probData.cdf(), streamNumber, streamProvider, name)


    /**
     * Secondary constructor for the DEmpiricalRV class, allowing initialization using a histogram and optional parameters.
     *
     * @param histogram An instance of HistogramIfc providing midpoints and bin fractions for initialization.
     * @param streamNumber An optional stream number used for identifying the random number stream, defaulting to 0.
     * @param streamProvider A random number stream provider of type RNStreamProviderIfc, with a default value of KSLRandom.DefaultRNStreamProvider.
     * @param name An optional name for this random variable instance, defaulting to null.
     */
    @JvmOverloads
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