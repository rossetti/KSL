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
package ksl.utilities.statistic

import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.UniformRV

/**
 * Collects a basic weighted statistical summary.  If the observation or the weight is
 * infinite or NaN, then the observation is not recorded and the number of missing observations
 * is incremented. If the observed weight is negative or 0.0, then the observation is not recorded and
 * the number of missing observations is incremented.
 *
 * @author rossetti
 */
class WeightedStatistic(name: String? = null) : WeightedCollector(name), WeightedStatisticIfc,
    Comparable<WeightedStatistic> {

    /**
     * Used to count the number of missing data points presented When a data
     * point having the value of (Double.NaN, Double.POSITIVE_INFINITY,
     * Double.NEGATIVE_INFINITY) are presented it is excluded from the summary
     * statistics and the number of missing points is noted. Implementers of
     * subclasses are responsible for properly collecting this value and
     * resetting this value.
     *
     */
    private var myNumMissing = 0.0

    /**
     * Holds the minimum of the observed data.
     */
    private var myMin = Double.POSITIVE_INFINITY

    /**
     * Holds the maximum of the observed data
     */
    private var myMax = Double.NEGATIVE_INFINITY

    /**
     * Holds the number of observations observed
     */
    private var num = 0.0

    /**
     * Holds the weighted sum of the data.
     */
    private var wsum = 0.0

    /**
     * Holds the unweighted sum of the data
     */
    private var uwsum = 0.0

    /**
     * Holds the weighted sum of squares of the data.
     */
    private var wsumsq = 0.0

    /**
     * Holds the sum of the weights observed.
     */
    private var sumw = 0.0

    /**
     * Holds the last value observed
     */
    private var myValue = Double.NaN

    /**
     * Holds the last weight observed
     */
    private var myWeight = Double.NaN

    override val count: Double
        get() = count()
    override val weightedSum: Double
        get() = weightedSum()
    override val weightedAverage: Double
        get() = average()
    override val max: Double
        get() = max()
    override val min: Double
        get() = min()
    override val sumOfWeights: Double
        get() = sumOfWeights()
    override val weightedSumOfSquares: Double
        get() = weightedSumOfSquares()
//    override val lastValue: Double
//        get() = lastValue()
//    override val lastWeight: Double
//        get() = lastWeight()
    override val numberMissing: Double
        get() = numberMissing()
    override val unWeightedSum: Double
        get() = unWeightedSum()

    override var value: Double
        get() = lastValue
        set(value) {
            collect(value, 1.0)
        }
    /**
     * @param obs      the value to collect
     * @param weight the weight associated with the value
     */
    override fun collect(obs: Double, weight: Double) {
        if (obs.isMissing() || weight.isMissing() || weight <= 0.0) {
            myNumMissing++
            return
        }
        super.collect(obs, weight)

        // update moments
        num = num + 1.0
        sumw = sumw + weight
        uwsum = uwsum + obs
        wsum = wsum + obs * weight
        wsumsq = wsumsq + obs * obs * weight

        // update min, max, current value, current weight
        if (obs > myMax) {
            myMax = obs
        }
        if (obs < myMin) {
            myMin = obs
        }
        myValue = obs
        myWeight = weight
    }

    /**
     * Creates an instance of Statistic that is a copy of this Statistic All
     * internal state is the same except for the id of the returned Statistic
     *
     * @return a new instance based on the current state of this instance
     */
    fun instance(): WeightedStatistic {
        val s = WeightedStatistic(name)
        s.myMax = myMax
        s.myMin = myMin
        s.num = num
        s.sumw = sumw
        s.wsum = wsum
        s.wsumsq = wsumsq
        s.myValue = myValue
        s.myWeight = myWeight
        s.uwsum = uwsum
        s.lastWeight = lastWeight
        s.lastValue = lastValue
        return s
    }

    override fun reset() {
        myValue = Double.NaN
        myWeight = Double.NaN
        num = 0.0
        wsum = 0.0
        sumw = 0.0
        wsumsq = 0.0
        uwsum = 0.0
        myMin = Double.POSITIVE_INFINITY
        myMax = Double.NEGATIVE_INFINITY
        myNumMissing = 0.0
    }

    fun average(): Double {
        return if (sumw <= 0.0) {
            Double.NaN
        } else wsum / sumw
    }

    fun count(): Double {
        return num
    }

    fun weightedSum(): Double {
        return wsum
    }

    fun sumOfWeights(): Double {
        return sumw
    }

    fun weightedSumOfSquares(): Double {
        return wsumsq
    }

    fun min(): Double {
        return myMin
    }

    fun max(): Double {
        return myMax
    }

    fun numberMissing(): Double {
        return myNumMissing
    }

    fun unWeightedSum(): Double {
        return uwsum
    }

    /**
     * Returns a String representation of the WeightedStatistic
     *
     * @return A String with basic summary statistics
     */
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("ID ")
        sb.append(id)
        sb.appendLine()
        sb.append("Name ")
        sb.append(name)
        sb.appendLine()
        sb.append("Number ")
        sb.append(count)
        sb.appendLine()
        sb.append("Minimum ")
        sb.append(min)
        sb.appendLine()
        sb.append("Maximum ")
        sb.append(max)
        sb.appendLine()
        sb.append("Weighted Average ")
        sb.append(weightedAverage)
        sb.appendLine()
        sb.append("Weighted Sum ")
        sb.append(weightedSum)
        sb.appendLine()
        sb.append("Weighted Sum of Squares ")
        sb.append(weightedSumOfSquares)
        sb.appendLine()
        sb.append("Sum of Weights ")
        sb.append(sumOfWeights)
        sb.appendLine()
        sb.append("Unweighted Sum ")
        sb.append(unWeightedSum)
        sb.appendLine()
        sb.append("Unweighted Average ")
        sb.append(unWeightedAverage)
        sb.appendLine()
        sb.append("Number Missing ")
        sb.append(numberMissing)
        sb.appendLine()
        sb.append("Last Value ")
        sb.append(lastValue)
        sb.appendLine()
        sb.append("Last Weight ")
        sb.append(lastWeight)
        sb.appendLine()
        return sb.toString()
    }

    /**
     * Fills up the supplied array with the statistics defined by index =
     * statistic
     *
     * statistics[0] = getCount();
     * statistics[1] = getAverage();
     * statistics[2] = getMin();
     * statistics[3] = getMax();
     * statistics[4] = getWeightedSum();
     * statistics[5] = getSumOfWeights();
     * statistics[6] = getWeightedSumOfSquares();
     * statistics[7] = getLastValue();
     * statistics[8] = getLastWeight();
     * statistics[9] = getUnWeightedSum();
     * statistics[10] = getUnWeightedAverage();
     *
     * The array must be of size 9 or an exception will be thrown
     *
     * @param statistics the array to fill
     */
    fun statistics(statistics: DoubleArray) {
        require(statistics.size == 11) { "The supplied array was not of size 11" }
        statistics[0] = count
        statistics[1] = weightedAverage
        statistics[2] = min
        statistics[3] = max
        statistics[4] = weightedSum
        statistics[5] = sumOfWeights
        statistics[6] = weightedSumOfSquares
        statistics[7] = lastValue
        statistics[8] = lastWeight
        statistics[9] = unWeightedSum
        statistics[10] = unWeightedAverage
    }

    /**
     * Returns an array with the statistics defined by index = statistic
     *
     * statistics[0] = getCount();
     * statistics[1] = getAverage();
     * statistics[2] = getMin();
     * statistics[3] = getMax();
     * statistics[4] = getSum();
     * statistics[5] = getSumOfWeights();
     * statistics[6] = getWeightedSumOfSquares();
     * statistics[7] = getLastValue();
     * statistics[8] = getLastWeight();
     * statistics[9] = getUnWeightedSum();
     * statistics[10] = getUnWeightedAverage();
     *
     * @return the array of statistics
     */
    val statistics: DoubleArray
        get() {
            val x = DoubleArray(11)
            statistics(x)
            return x
        }

    /**
     * s[0] = "Count"; s[1] = "Average"; s[2] = "Minimum"; s[3] = "Maximum";
     * s[4] = "Weighted Sum"; s[5] = "Sum of Weights"; s[6] = "Weighted sum of
     * squares"; s[7] = "Last Value"; s[8] = "Last Weight"; s[9] = "Unweighted Sum"; s[10] = "Unweighted Average";
     *
     * @return the headers
     */
    val statisticsHeader: Array<String>
        get() {
            val s = arrayOf("Count", "Average", "Minimum", "Maximum", "Weighted Sum", "Sum of Weights",
            "Weighted sum of squares", "Last Value", "Last Weight", "Unweighted Sum", "Unweighted Average")
            return s
        }

    override val csvStatistic: String
        get() {
            val sb = StringBuilder()
            sb.append(name)
            sb.append(",")
            val stats = statistics
            for (i in stats.indices) {
                if (stats[i].isMissing()) {
                    sb.append("")
                } else {
                    sb.append(stats[i])
                }
                if (i < stats.size - 1) {
                    sb.append(",")
                }
            }
            return sb.toString()
        }

    override val csvStatisticHeader: String
        get() {
            val sb = StringBuilder()
            sb.append("Statistic Name,")
            sb.append("Count,")
            sb.append("Average,")
            sb.append("Minimum,")
            sb.append("Maximum,")
            sb.append("Weighted Sum,")
            sb.append("Sum of Weights,")
            sb.append("Weighted sum of squares,")
            sb.append("Last Value,")
            sb.append("Last Weight,")
            sb.append("Unweighted Sum,")
            sb.append("Unweighted Average")
            return sb.toString()
        }

    companion object {
        /**
         * Returns a statistic that summarizes the passed in arrays. The lengths of
         * the arrays must be the same.
         *
         * @param x the values
         * @param w the weights
         * @return a weighted statistic based on the arrays
         */
        fun collectStatistics(x: DoubleArray, w: DoubleArray): WeightedStatistic {
            require(x.size == w.size) { "The supplied arrays are not of equal length" }
            val s = WeightedStatistic()
            s.collect(x, w)
            return s
        }

        /**
         * Creates a instance of Statistic that is a copy of the supplied Statistic
         * All internal state is the same except for the id of the returned
         * Statistic
         *
         * @param stat the instance that needs to be copied
         * @return the copy
         */
        fun instance(stat: WeightedStatistic): WeightedStatistic {
            val s = WeightedStatistic(stat.name)
            s.myMax = stat.myMax
            s.myMin = stat.myMin
            s.num = stat.num
            s.sumw = stat.sumw
            s.wsum = stat.wsum
            s.wsumsq = stat.wsumsq
            s.myValue = stat.myValue
            s.myWeight = stat.myWeight
            s.uwsum = stat.uwsum
            return s
        }
    }

    /**
     * Returns a negative integer, zero, or a positive integer if this object is
     * less than, equal to, or greater than the specified object.
     *
     * The natural ordering is based on the weighted average
     *
     * @param other The statistic to compare this statistic to
     * @return Returns a negative integer, zero, or a positive integer if this
     * object is less than, equal to, or greater than the specified object based on the average
     */
    override operator fun compareTo(other: WeightedStatistic): Int {
        return weightedAverage.compareTo(other.weightedAverage)
    }
}

fun main() {
    val n = NormalRV(10.0, 2.0)
    val ws = WeightedStatistic("ws test 8")
    val saver = DoublePairArraySaver()
    ws.attachObserver(saver)
    for (i in 1..100) {
        ws.collect(n.value, 1.0)
    }
    println(ws)
    println()
    saver.clearData()
    val u = UniformRV()
    n.resetStartStream()
    ws.reset()
    for (i in 1..100) {
        ws.collect(n.value, u.value)
    }
    println(ws)
    saver.write(KSL.out)
}