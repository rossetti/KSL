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
package ksl.modeling.nhpp

/**
 * @param crLower represents the cumulative rate at the beginning of the segment, must be &gt;=0
 * @param tLower represents the time at the beginning of the segment, must be &gt;=0
 * @param duration represents the time duration of the segment, must be &gt;=0
 * @param rate represents the rate for the time segment, must be 0 &lt; rate &lt;= infinity
 * @author rossetti
 */
class ConstantRateSegment(crLower: Double, tLower: Double, duration: Double, rate: Double) : RateSegmentIfc {
    init {
        require(tLower >= 0.0) { "The lower time limit must be >= 0" }
        require(duration > 0.0) { "The duration must be > 0" }
        require(crLower >= 0.0) { "The lower rate limit must be >= 0" }
        require(rate >= 0.0) { "The rate must be > 0" }
        require(rate < Double.POSITIVE_INFINITY) { "The rate must be < infinity" }
    }

    /** the lower limit of the interval on the timescale
     *
     */
    override val timeRangeLowerLimit = tLower

    /** the width of the interval on the timescale (tWidth = tUL - tLL)
     *
     */
    override val timeRangeWidth = duration

    /** the upper limit of the interval on the timescale
     *
     */
    override val timeRangeUpperLimit
        get() = timeRangeLowerLimit + timeRangeWidth

    /**
     * the rate for the interval
     *
     */
    override val rateAtLowerTimeLimit = rate

    /** the lower limit of the interval on cumulative rate scale
     *
     */
    override val cumulativeRateLowerLimit = crLower

    /** the upper limit of the interval on the cumulative rate scale
     *
     */
    override val cumulativeRateUpperLimit
        get() = cumulativeRateLowerLimit + rateAtLowerTimeLimit * timeRangeWidth

    /** the width of the interval on the cumulative rate scale (crWidth = crUL - crLL)
     *
     */
    override val cumulativeRateIntervalWidth
        get() = cumulativeRateUpperLimit - cumulativeRateLowerLimit

    override fun instance(): ConstantRateSegment {
        return ConstantRateSegment(cumulativeRateLowerLimit, timeRangeLowerLimit, timeRangeWidth, rateAtLowerTimeLimit)
    }

    /** Returns the rate for the interval
     *
     * @return the rate
     */
    fun rate(): Double {
        return rateAtLowerTimeLimit
    }

    /** Gets the rate for the time within the interval
     *
     */
    override fun rate(time: Double): Double {
        return rateAtLowerTimeLimit
    }

    /** The rate at the upper time limit is undefined Double.NaN.  The rate for the
     * segment is constant throughout the interval but undefined at the end of the interval
     *
     */
    override val rateAtUpperTimeLimit: Double
        get() = Double.NaN

    /** Returns the value of the cumulative rate function for the interval
     * given a value of time within that interval
     *
     * @param time the time to be evaluated
     * @return cumulative rate at time t
     */
    override fun cumulativeRate(time: Double): Double {
        require(contains(time)) { "The time $time was not in [$timeRangeLowerLimit, $timeRangeUpperLimit)" }
        val t = time - timeRangeLowerLimit
        return cumulativeRateLowerLimit + rateAtLowerTimeLimit * t
    }

    /** Returns the inverse of the cumulative rate function given the interval
     * and a cumulative rate value within that interval.  Returns a time
     *
     * @param cumRate the rate to be evaluated
     * @return the inverse at the rate
     */
    override fun inverseCumulativeRate(cumRate: Double): Double {
        if (rateAtLowerTimeLimit == 0.0) {
            return Double.NaN
        }
        val t = cumRate - cumulativeRateLowerLimit
        return timeRangeLowerLimit + t / rateAtLowerTimeLimit
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("rate = ")
        sb.append(rateAtLowerTimeLimit)
        sb.append(" [")
        sb.append(timeRangeLowerLimit)
        sb.append(",")
        sb.append(timeRangeUpperLimit)
        sb.append(") width = ")
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