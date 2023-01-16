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
package ksl.modeling.nhpp

import ksl.utilities.math.KSLMath
import kotlin.math.sqrt

/**
 * @author rossetti
 */
class LinearRateSegment(
    cumRateLL: Double, timeLL: Double, rateLL: Double, timeUL: Double, rateUL: Double
) : RateSegmentIfc {
    init {
        require(timeLL >= 0.0) { "The lower time limit must be >= 0" }
        require(timeUL > 0.0) { "The lower time limit must be > 0" }
        require(timeLL < timeUL) { "The lower time limit, $timeLL must be < the upper limit $timeUL" }
        require(cumRateLL >= 0.0) { "The cumulative rate at lower time limit must be >= 0" }
        require(rateLL >= 0.0) { "The rate at lower time limit must be >= 0" }
        require(rateLL < Double.POSITIVE_INFINITY) { "The rate at lower time limit must be < infinity" }
        require(rateUL >= 0.0) { "The rate at upper time limit must be >= 0" }
        require(rateUL < Double.POSITIVE_INFINITY) { "The rate at upper time limit must be < infinity" }
    }

    /** the rate at the lower limit of the interval
     *
     */
    override val rateAtLowerTimeLimit = rateLL

    /**
     * the rate at the upper limit of the interval
     */
    override val rateAtUpperTimeLimit = rateUL

    override val timeRangeLowerLimit: Double = timeLL

    override val timeRangeUpperLimit: Double = timeUL

    override val timeRangeWidth: Double
        get() = timeRangeUpperLimit - timeRangeLowerLimit

    override val cumulativeRateLowerLimit: Double = cumRateLL

    override val cumulativeRateUpperLimit: Double
        get() = cumulativeRateLowerLimit + 0.5 * (rateAtUpperTimeLimit + rateAtLowerTimeLimit) * timeRangeWidth

    override val cumulativeRateIntervalWidth: Double
        get() = cumulativeRateUpperLimit - cumulativeRateLowerLimit

    /**
     * the slope of the rate function for the interval
     */
    val slope
        get() = (rateAtUpperTimeLimit - rateAtLowerTimeLimit) / timeRangeWidth

    override fun instance(): LinearRateSegment {
        return LinearRateSegment(
            cumulativeRateLowerLimit,
            timeRangeLowerLimit,
            rateAtLowerTimeLimit,
            timeRangeUpperLimit,
            rateAtUpperTimeLimit
        )
    }

    override operator fun contains(time: Double): Boolean {
        return time in timeRangeLowerLimit..timeRangeUpperLimit
    }

    override fun rate(time: Double): Double {
        return if (KSLMath.equal(slope, 0.0)) {
            rateAtLowerTimeLimit
        } else {
            rateAtLowerTimeLimit + slope * (time - timeRangeLowerLimit)
        }
    }

    override fun cumulativeRate(time: Double): Double {
        return if (KSLMath.equal(slope, 0.0)) {
            cumulativeRateLowerLimit + rateAtLowerTimeLimit * (time - timeRangeLowerLimit)
        } else {
            cumulativeRateLowerLimit + rateAtLowerTimeLimit * (time - timeRangeLowerLimit) + 0.5 * slope * (time - timeRangeLowerLimit) * (time - timeRangeLowerLimit)
        }
    }

    override fun inverseCumulativeRate(cumRate: Double): Double {
        return if (KSLMath.equal(slope, 0.0)) {
            if (KSLMath.equal(rateAtLowerTimeLimit, 0.0)) {
                Double.NaN
            } else {
                timeRangeLowerLimit + (cumRate - cumulativeRateLowerLimit) / rateAtLowerTimeLimit
            }
        } else {
            val n = 2.0 * (cumRate - cumulativeRateLowerLimit)
            val d =
                rateAtLowerTimeLimit + sqrt(rateAtLowerTimeLimit * rateAtLowerTimeLimit + 2.0 * slope * (cumRate - cumulativeRateLowerLimit))
            timeRangeLowerLimit + n / d
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(" [")
        sb.append(rateAtLowerTimeLimit)
        sb.append(",")
        sb.append(rateAtUpperTimeLimit)
        sb.append("] slope = ")
        sb.append(slope)
        sb.append(" [")
        sb.append(timeRangeLowerLimit)
        sb.append(",")
        sb.append(timeRangeUpperLimit)
        sb.append("] width = ")
        sb.append(timeRangeWidth)
        sb.append(" [")
        sb.append(cumulativeRateLowerLimit)
        sb.append(",")
        sb.append(cumulativeRateUpperLimit)
        sb.append("] cr width = ")
        sb.append(cumulativeRateIntervalWidth)
        sb.appendLine()
        return sb.toString()
    }
}