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

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.parameters.EmpiricalRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.Histogram

/**
 * A random variable that samples from the provided data. Each value is
 * equally likely to occur.
 * @param data the data to sample from
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class EmpiricalRV (
    private val data: DoubleArray,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNum, streamProvider, name){

    init {
        require(data.isNotEmpty()) { "The supplied data array had no elements." }
    }

    /**
     * A copy of the data array is returned
     */
    val values
        get() = data.copyOf()

    /**
     *  Creates a series of [numPoints] points starting at the lower limit, each [width] units
     *  apart. Each point in the series will be equally likely to occur.
     */
    constructor(
        lowerLimit: Double,
        numPoints: Int,
        width: Double,
        streamNumber: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(Histogram.createBreakPoints(lowerLimit, numPoints, width), streamNumber, streamProvider, name)

    /**
     *  Creates a series of [numPoints] points starting at the lower limit, each
     *  an equal distance apart based on the number of points.
     *  Each point in the series will be equally likely to occur.
     */
    constructor(
        interval: Interval,
        numPoints: Int,
        streamNumber: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(interval.stepPoints(numPoints), streamNumber, streamProvider, name)

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): EmpiricalRV {
        return EmpiricalRV(values, streamNum, rnStreamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.randomlySelect(data, rnStream)
    }

    override fun toString(): String {
        return "EmpiricalRV(data=${data.contentToString()})"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = EmpiricalRVParameters()
            parameters.changeDoubleArrayParameter("population", data)
            return parameters
        }

}