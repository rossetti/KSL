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

import ksl.utilities.*
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Prepares the statistical quantities typically found on a box plot.
 * This implementation uses a full sort of the data. The original data
 * is not changed. Users may want to look for more efficient methods for use with very large data sets.
 *
 *
 *     Lower inner fence: Q1 – (1.5 * IQR)
 *     Upper inner fence: Q3 + (1.5 * IQR)
 *     Lower outer fence: Q1 – (3 * IQR)
 *     upper outer fence: Q3 + (3 * IQR)
 *
 * @param data the data to be summarized, must not be null and must not contain any Double.NaN values
 */
class BoxPlotSummary(data: DoubleArray, name: String? = null): IdentityIfc by Identity(name) {
    /**
     * @return the estimated median
     */
    var median = 0.0
        private set

    /**
     * @return the estimated 1st quartile
     */
    var firstQuartile = 0.0
        private set

    /**
     * @return the estimated 3rd quartile
     */
    var thirdQuartile = 0.0
        private set

    /**
     * @return the minimum value in the data
     */
    var min = 0.0
        private set

    /**
     * @return the maximum value of the data
     */
    var max = 0.0
        private set

    private val orderStatistics: DoubleArray
    private val statistic: Statistic

    init {
        require(!KSLArrays.checkForNaN(data)) { "There were NaN in the array" }
        if (data.size == 1) {
            median = data[0]
            firstQuartile = data[0]
            thirdQuartile = data[0]
            min = data[0]
            max = data[0]
            orderStatistics = doubleArrayOf(data[0])
        } else {
            orderStatistics = data.copyOf()
            median = Statistic.median(orderStatistics)
            min = orderStatistics[0]
            max = orderStatistics[orderStatistics.size - 1]
            firstQuartile = percentile(0.25)
            thirdQuartile = percentile(0.75)
        }
        statistic = Statistic("Summary Statistics")
        statistic.collect(data)
    }

    val average: Double
        get() = statistic.average

    val count: Double
        get() = statistic.count

    val variance: Double
        get() = statistic.variance

    val standardDeviation: Double
        get() = statistic.standardDeviation

    /**
     * A confidence interval for the mean based on the confidence level
     *
     * @param level the confidence level
     * @return the interval
     */
    fun confidenceInterval(level: Double): Interval {
        return statistic.confidenceInterval(level)
    }

    /**
     *  The statistical summary of the data
     */
    fun statistics(): Statistic {
        return statistic.instance()
    }

    /**
     *  The order statistics
     */
    fun orderStatistics(): DoubleArray {
        return orderStatistics.copyOf()
    }

    /**
     * Uses definition 7, as per R definitions
     *
     * @param p the percentile, must be within (0, 1)
     * @return the quantile
     */
    fun quantile(p: Double): Double {
        require((p <= 0.0) || (p < 1.0)) { "Percentile value must be (0,1)" }
        val n = orderStatistics.size
        if (n == 1) {
            return orderStatistics[0]
        }
        val index = 1 + (n - 1) * p
        if (index < 1.0) {
            return orderStatistics[0]
        }
        if (index >= n) {
            return orderStatistics[n - 1]
        }
        var lo = floor(index).toInt()
        var hi = ceil(index).toInt()
        val h = index - lo
        //        System.out.printf("n = %d p = %f  index = %f  lo = %d  hi = %d  h = %f %n", n, p, index, lo, hi, h);
//        System.out.printf("orderStatistics[%d] = %f  orderStatistics[%d] = %f  %n", lo, orderStatistics[lo - 1], hi, orderStatistics[hi - 1]);
        // correct for 0 based arrays
        lo = lo - 1
        hi = hi - 1
        return (1.0 - h) * orderStatistics[lo] + h * orderStatistics[hi]
    }

    /**
     * As per Apache Math commons
     *
     * @param p the percentile, must be within (0, 1)
     * @return the percentile
     */
    fun percentile(p: Double): Double {
        require((p <= 0.0) || (p < 1.0)) { "Percentile value must be (0,1)" }
        val n = orderStatistics.size
        if (n == 1) {
            return orderStatistics[0]
        }
        val pos = p * (n + 1)
        return if (pos < 1.0) {
            orderStatistics[0]
        } else if (pos >= n) {
            orderStatistics[n - 1]
        } else {
            val d = pos - floor(pos)
            val fpos = floor(pos).toInt() - 1 // correct for 0 based arrays
            val lower = orderStatistics[fpos]
            val upper = orderStatistics[fpos + 1]
            lower + d * (upper - lower)
        }
    }

    /**
     * @return the difference between 3rd quartile and 1st quartile
     */
    val interQuartileRange = thirdQuartile - firstQuartile

    /**
     * @return the difference between max and min
     */
    val range = max - min

    /**
     * @return the 1st quartile minus 1.5 times the inter-quartile range
     */
    val lowerInnerFence = firstQuartile - 1.5 * interQuartileRange

    /**
     * @return the 1st quartile minus 3 times the inter-quartile range
     */
    val lowerOuterFence = firstQuartile - 3.0 * interQuartileRange

    /**
     * @return the 3rd quartile plus 1.5 times the inter-quartile range
     */
    val upperInnerFence = thirdQuartile + 1.5 * interQuartileRange

    /**
     * @return the 3rd quartile plus 3 times the inter-quartile range
     */
    val upperOuterFence = thirdQuartile + 3.0 * interQuartileRange

    /**
     *  The largest data point that is less than or equal to the upperInnerFence
     */
    val upperWhisker: Double
        get() {
            for (i in orderStatistics.indices.reversed()) {
                if (orderStatistics[i] <= upperInnerFence) {
                    return orderStatistics[i]
                }
            }
            return max
        }

    /**
     *  The smallest data point that is greater than or equal to the lowerInnerFence
     */
    val lowerWhisker: Double
        get() {
            for (i in orderStatistics.indices) {
                if (orderStatistics[i] >= lowerInnerFence) {
                    return orderStatistics[i]
                }
            }
            return min
        }

    /**
     * @return the data points less than or equal to the lower outer fence
     */
    fun lowerOuterPoints(): DoubleArray {
        var i = 0
        val l2 = lowerOuterFence
        for (v in orderStatistics) {
            i = if (v <= l2) {
                i + 1
            } else {
                break
            }
        }
        return orderStatistics.copyOf(i)
    }

    /**
     * @return the data points greater than or equal to the upper outer fence
     */
    fun upperOuterPoints(): DoubleArray {
        var i = 0
        val n = orderStatistics.size - 1
        val u2 = upperOuterFence
        for (j in n downTo 0) {
            i = if (orderStatistics[j] >= u2) {
                i + 1
            } else {
                break
            }
        }
        return orderStatistics.copyOfRange(n - i, n)
    }

    /**
     * @return the points between the lower inner and outer fences
     */
    fun pointsBtwLowerInnerAndOuterFences(): DoubleArray {
        val i = Interval(lowerOuterFence, lowerInnerFence)
        return KSLArrays.dataInInterval(orderStatistics, i)
    }

    /**
     * @return the points between the upper inner and outer fences
     */
    fun pointsBtwUpperInnerAndOuterFences(): DoubleArray {
        val i = Interval(upperInnerFence, upperOuterFence)
        return KSLArrays.dataInInterval(orderStatistics, i)
    }

    /**
     * The summary as a map of values
     *
     *         map["lowerOuterFence"] = lowerOuterFence
     *         map["lowerInnerFence"] = lowerInnerFence
     *         map["lowerWhisker"] = lowerWhisker
     *         map["min"] = min
     *         map["firstQuartile"] = firstQuartile
     *         map["median"] = median
     *         map["max"] = max
     *         map["upperWhisker"] = upperWhisker
     *         map["thirdQuartile"] = thirdQuartile
     *         map["upperInnerFence"] = upperInnerFence
     *         map["upperOuterFence"] = upperOuterFence
     *         map["range"] = range
     *         map["interQuartileRange"] = interQuartileRange
     */
    fun asMap(): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        map["lowerOuterFence"] = lowerOuterFence
        map["lowerInnerFence"] = lowerInnerFence
        map["lowerWhisker"] = lowerWhisker
        map["min"] = min
        map["firstQuartile"] = firstQuartile
        map["median"] = median
        map["max"] = max
        map["upperWhisker"] = upperWhisker
        map["thirdQuartile"] = thirdQuartile
        map["upperInnerFence"] = upperInnerFence
        map["upperOuterFence"] = upperOuterFence
        map["range"] = range
        map["interQuartileRange"] = interQuartileRange
        return map
    }

    override fun toString(): String {
        val sb = StringBuilder("BoxPlotSummary")
        sb.appendLine()
        sb.append("lower outer fence = ").append(lowerOuterFence)
        sb.appendLine()
        sb.append("lower inner fence = ").append(lowerInnerFence)
        sb.appendLine()
        sb.append("lower whisker = ").append(lowerWhisker)
        sb.appendLine()
        sb.append("min = ").append(min)
        sb.appendLine()
        sb.append("firstQuartile = ").append(firstQuartile)
        sb.appendLine()
        sb.append("median = ").append(median)
        sb.appendLine()
        sb.append("thirdQuartile = ").append(thirdQuartile)
        sb.appendLine()
        sb.append("max = ").append(max)
        sb.appendLine()
        sb.append("upper whisker = ").append(upperWhisker)
        sb.appendLine()
        sb.append("upper inner fence = ").append(upperInnerFence)
        sb.appendLine()
        sb.append("upper outer fence = ").append(upperOuterFence)
        sb.appendLine()
        sb.append("range = ").append(range)
        sb.appendLine()
        sb.append("inter-quartile range = ").append(interQuartileRange)
        sb.appendLine()
        sb.appendLine()
        sb.append("Statistical Summary ")
        sb.appendLine()
        sb.append(statistic)
        return sb.toString()
    }
}

fun main() {
    val x = doubleArrayOf(
        9.57386907765005,
        12.2683505035727,
        9.57737208532118,
        9.46483590382401,
        10.7426270820019,
        13.6417539779286,
        14.4009905460358,
        11.9644504015896,
        6.26967756749078,
        11.6697189446463,
        8.05817835081046,
        9.15420225990855,
        12.6661856696446,
        5.55898016788418,
        11.5509859097328,
        8.09378382643764,
        10.2800698254101,
        11.8820042371248,
        6.83122972495244,
        7.76415517242856,
        8.07037124078289,
        10.1936926483873,
        6.6056340897386,
        8.67523311054818,
        10.2860106642238,
        7.18655355368101,
        13.7326532837148,
        10.8384432167312,
        11.20127362594,
        9.10597298849603,
        13.1143167471166,
        11.461547274424,
        12.8686686397317,
        11.6123823346184,
        11.1766595994422,
        9.96640484955756,
        7.60884520541602,
        10.4027823841526,
        13.6119110527044,
        10.1927388924956,
        11.0479192016999,
        10.8335646086984,
        11.3464245020951,
        11.7370035652721,
        7.86882502350181,
        10.1677674083453,
        7.19107507247878,
        10.3219440236855,
        11.8751033160937,
        12.0507178860171,
        10.2452271541559,
        12.3574170333615,
        8.61783541196255,
        10.8759327855332,
        10.8965790925989,
        9.78508632755152,
        9.57354838522572,
        10.668697248695,
        10.4413115727436,
        11.7056055258128,
        10.6836383463882,
        9.00275936849233,
        11.1546020461964,
        11.5327569604436,
        12.6632213399552,
        9.04144921258077,
        8.34070478790018,
        8.90537066541892,
        8.9538251666728,
        10.6587406131769,
        9.46657058183544,
        11.9067728468743,
        7.31151723229678,
        10.3473820074211,
        8.51409684117935,
        15.061683701397,
        7.67016173387284,
        9.63463245914518,
        11.9544975062154,
        8.75291180980926,
        10.5902626954236,
        10.7290328701981,
        11.6103046633603,
        9.18588529341066,
        11.7832770526927,
        11.5803842329369,
        8.77282669099311,
        11.1605258465085,
        9.87370336332192,
        11.0792461569289,
        12.1457106152585,
        8.16900025019337,
        12.0963212801111,
        10.7943060404262,
        10.6648080893662,
        10.7821384837463,
        9.20756684199006,
        13.0421837951471,
        8.50476579169282,
        7.7653569673433
    )
    val boxPlotSummary = BoxPlotSummary(x)
    println(boxPlotSummary)

    println()
    println(boxPlotSummary.orderStatistics().contentToString())
}