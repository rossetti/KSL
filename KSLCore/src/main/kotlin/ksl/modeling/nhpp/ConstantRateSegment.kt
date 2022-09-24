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
        setInterval(tLower, duration)
        setRate(crLower, rate)
    }
    /**the rate for the interval
     *
     */
    override var rateAtLowerTimeLimit = 0.0
        protected set

    /** the width of the interval on the cumulative rate scale (crWidth = crUL - crLL)
     *
     */
    protected var crWidth = 0.0

    /** the lower limit of the interval on cumulative rate scale
     *
     */
    protected var crLL = 0.0

    /** the upper limit of the interval on the cumulative rate scale
     *
     */
    protected var crUL = 0.0

    /** the width of the interval on the time scale (tWidth = tUL - tLL)
     *
     */
    protected var tWidth = 0.0

    /** the lower limit of the interval on the time scale
     *
     */
    protected var tLL = 0.0

    /** the upper limit of the interval on the time scale
     *
     */
    protected var tUL = 0.0

    override fun newInstance(): ConstantRateSegment {
        return ConstantRateSegment(crLL, tLL, tWidth, rateAtLowerTimeLimit)
    }

    override fun contains(time: Double): Boolean {
        return tLL <= time && time < tUL
    }

    /**
     *
     * @param tLower the lower time limit of the interval, must be &gt; = 0
     * @param duration the duration of the interval, must be &gt; 0
     */
    fun setInterval(tLower: Double, duration: Double) {
        require(tLower >= 0.0) { "The lower time limit must be >= 0" }
        require(duration > 0.0) { "The duration must be > 0" }
        tLL = tLower
        tUL = tLL + duration
        tWidth = duration
    }

    /**
     * @param crLower represents the cumulative rate at the beginning of the segment, must be &gt;=0
     * @param rate represents the rate for the time segment, must be 0 &lt; rate &lt; = infinity
     */
    fun setRate(crLower: Double, rate: Double) {
        require(crLower >= 0.0) { "The lower rate limit must be >= 0" }
        require(rate >= 0.0) { "The rate must be > 0" }
        require(rate < Double.POSITIVE_INFINITY) { "The rate must be < infinity" }
        crLL = crLower
        rateAtLowerTimeLimit = rate
        crUL = crLL + rate * tWidth
        crWidth = crUL - crLL
    }

    /** Returns the rate for the interval
     *
     * @return the rate
     */
    fun getRate(): Double {
        return rateAtLowerTimeLimit
    }

    /** Gets the rate for the time within the interval
     *
     */
    override fun getRate(time: Double): Double {
        return rateAtLowerTimeLimit
    }

    /** The rate at the upper time limit is undefined Double.NaN.  The rate for the
     * segment is constant throughout the interval but undefined at the end of the interval
     *
     */
    override val rateAtUpperTimeLimit: Double
        get() = Double.NaN

    /** The lower time limit
     *
     * @return The lower time limit
     */
    override val timeRangeLowerLimit: Double
        get() = tLL

    /** The upper time limit
     *
     * @return The upper time limit
     */
    override val upperTimeLimit: Double
        get() =  tUL

    /** The width of the interval
     *
     * @return The width of the interval
     */
    override val timeWidth: Double
        get() = tWidth

    /** The lower limit on the cumulative rate axis
     *
     * @return The lower limit on the cumulative rate axis
     */
    override val cumulativeRateRangeLowerLimit: Double
        get() = crLL

    /** The upper limit on the cumulative rate axis
     *
     * @return The upper limit on the cumulative rate axis
     */
    override val cumulativeRateUpperLimit: Double
        get() = crUL

    /** The cumulative rate interval width
     *
     * @return The cumulative rate interval width
     */
    override val cumulativeRateIntervalWidth: Double
        get() = crWidth

    /** Returns the value of the cumulative rate function for the interval
     * given a value of time within that interval
     *
     * @param time the time to be evaluated
     * @return cumulative rate at time t
     */
    override fun getCumulativeRate(time: Double): Double {
        val t = time - tLL
        return crLL + rateAtLowerTimeLimit * t
    }

    /** Returns the inverse of the cumulative rate function given the interval
     * and a cumulative rate value within that interval.  Returns a time
     *
     * @param cumRate the rate to be evaluated
     * @return the inverse at the rate
     */
    override fun getInverseCumulativeRate(cumRate: Double): Double {
        if (rateAtLowerTimeLimit == 0.0) {
            return Double.NaN
        }
        val t = cumRate - crLL
        return tLL + t / rateAtLowerTimeLimit
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("rate = ")
        sb.append(rateAtLowerTimeLimit)
        sb.append(" [")
        sb.append(tLL)
        sb.append(",")
        sb.append(tUL)
        sb.append(") width = ")
        sb.append(tWidth)
        sb.append(" [")
        sb.append(crLL)
        sb.append(",")
        sb.append(crUL)
        sb.append("] cr width = ")
        sb.append(crWidth)
        sb.append("\n")
        return sb.toString()
    }
}