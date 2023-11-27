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
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.parameters.EmpiricalRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.Histogram

/**
 * A random variable that samples from the provided data. Each value is
 * equally likely to occur.
 * @param data the data to sample from
 * @param stream the random number stream to use
 */
class EmpiricalRV (private val data: DoubleArray, stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null) :
    ParameterizedRV(stream, name){
    init {
        require(data.isNotEmpty()) { "The supplied data array had no elements." }
    }

    /**
     * A copy of the data array is returned
     */
    val values
        get() = data.copyOf()

    /**
     * A random variable that samples from the provided data. Each value is
     * equally likely to occur.
     * @param data the data to sample from
     * @param streamNum the random number stream to use
     */
    constructor(data: DoubleArray, streamNum: Int) : this(data, KSLRandom.rnStream(streamNum))

    /**
     *  Creates a series of [numPoints] points starting at the lower limit, each [width] units
     *  apart. Each point in the series will be equally likely to occur.
     */
    constructor(lowerLimit: Double, numPoints: Int, width: Double, stream: RNStreamIfc = KSLRandom.nextRNStream()) :
            this(Histogram.createBreakPoints(lowerLimit, numPoints, width), stream)

    /**
     *  Creates a series of [numPoints] points starting at the lower limit, each
     *  an equal distance apart based on the number of points.
     *  Each point in the series will be equally likely to occur.
     */
    constructor(interval: Interval, numPoints: Int, stream: RNStreamIfc = KSLRandom.nextRNStream()) :
            this(interval.stepPoints(numPoints), stream)

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return EmpiricalRV(values, rnStream)
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