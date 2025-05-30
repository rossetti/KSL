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

package ksl.utilities.statistic

import ksl.utilities.GetTimeIfc
import ksl.utilities.countLessThan

/**
 *  The default time value is 1.0
 */
class DefaultTimeGetter : GetTimeIfc {
    override fun time(): Double {
        return 1.0
    }
}

/**
 *  A helper class that turns an array of time values to a supplier
 *  of times
 */
class TimeArray(var timeValues: DoubleArray) : GetTimeIfc {
    init {
        require(timeValues.countLessThan(0.0) == 0) {"There were negative values in the time array"}
    }
    private var index = -1
    override fun time(): Double {
        if (index < timeValues.size - 1) {
            index = index + 1
            return timeValues[index]
        }
        return timeValues[index]
    }

    /**
     *  Resets to the start of the array for returned values
     */
    fun reset() {
        index = -1
    }
}

/**
 *   Collects time weighted statistics that are presented to the
 *   collect() method. The property, [timeGetter], must provide
 *   values for each observed value that appears in the collect
 *   method.
 *   @param initialTime assumed to be 0.0 by default
 *   @param initialValue assumed to be 0.0 by default
 */
class TimeWeightedStatistic(
    var timeGetter: GetTimeIfc = DefaultTimeGetter(),
    initialValue: Double = 0.0,
    initialTime: Double = 0.0,
) : Collector(), WeightedStatisticIfc {

    private var myLastValue: Double
    private var myLastTime: Double
    private val myWeightedStatistic: WeightedStatistic
    var updateTimeAtReset: Boolean = true

    init {
        require(initialTime >= 0.0) { "The initial time must be >= 0.0" }
        require(initialValue >= 0.0) { "The initial value must be >= 0.0" }
        myWeightedStatistic = WeightedStatistic()
        myLastTime = initialTime
        myLastValue = initialValue
    }

    constructor(
        values: DoubleArray,
        times: DoubleArray,
        initialValue: Double = 0.0,
        initialTime: Double = 0.0
    ) : this(TimeArray(times), initialValue, initialTime) {
        for (v in values) {
            collect(v)
        }
    }

    override fun collect(obs: Double) {
        collect(obs, timeGetter.time())
    }

    fun collect(obs: Double, time: Double) {
        require(time >= 0.0) { "The value of time must be >= 0" }
        val weight = time - myLastTime
        myLastTime = time
        myWeightedStatistic.collect(myLastValue, weight)
        myLastValue = obs
    }

    override val count: Double
        get() = myWeightedStatistic.count
    override val max: Double
        get() = myWeightedStatistic.max
    override val min: Double
        get() = myWeightedStatistic.min
    override val sumOfWeights: Double
        get() = myWeightedStatistic.sumOfWeights
    override val weightedAverage: Double
        get() = myWeightedStatistic.weightedAverage
    override val weightedSum: Double
        get() = myWeightedStatistic.weightedSum
    override val weightedSumOfSquares: Double
        get() = myWeightedStatistic.weightedSumOfSquares

    override fun reset() {
        myWeightedStatistic.reset()
        if (updateTimeAtReset) {
            myLastTime = timeGetter.time()
        }
    }

    override val lastWeight: Double
        get() = myWeightedStatistic.lastWeight
    override val numberMissing: Double
        get() = myWeightedStatistic.numberMissing
    override val unWeightedSum: Double
        get() = myWeightedStatistic.unWeightedSum

    val statistics: DoubleArray
        get() = myWeightedStatistic.statistics

    override fun toString(): String {
        return myWeightedStatistic.toString()
    }

    override val csvStatistic: String
        get() = myWeightedStatistic.csvStatistic

    override val csvStatisticHeader: String
        get() = myWeightedStatistic.csvStatisticHeader

}

fun main() {
    val t = doubleArrayOf(0.0, 2.0, 5.0, 11.0, 14.0, 17.0, 22.0, 26.0, 28.0, 31.0, 35.0, 36.0)
    val n = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 2.0, 3.0, 4.0, 3.0, 2.0, 1.0, 0.0, 0.0)
    val tws = TimeWeightedStatistic(TimeArray(t))
    for (x in n) {
        tws.collect(x)
    }
    println(tws)

    val tws2 = TimeWeightedStatistic()
    for ((i, x) in n.withIndex()) {
        tws2.collect(x, t[i])
    }
    println()
    println(tws2)
    println()
    val tws3 = TimeWeightedStatistic(n, t)
    println(tws3)
}

