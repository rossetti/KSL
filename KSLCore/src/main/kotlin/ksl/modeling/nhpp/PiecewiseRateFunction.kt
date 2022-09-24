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
 * @author rossetti
 */
abstract class PiecewiseRateFunction : InvertibleCumulativeRateFunctionIfc {

    protected var myRateSegments: MutableList<RateSegmentIfc> = mutableListOf()

    protected var myMaxRate = Double.NEGATIVE_INFINITY

    protected var myMinRate = Double.POSITIVE_INFINITY

    /** Adds a rate segment to the function
     *
     * @param duration the duration
     * @param rate the rate
     */
    abstract fun addRateSegment(duration: Double, rate: Double)

    /** Searches for the interval that the supplied time
     * falls within.  Returns -1 if no interval is found
     *
     * Interval indexing starts at index 0 (i.e. 0 is the first interval,
     * silly Java zero based indexing)
     *
     * @param time the time to look for
     * @return the index of the interval
     */
    abstract fun findTimeInterval(time: Double): Int

    /** Returns a copy of the piecewise  rate function
     *
     * @return a copy of the piecewise  rate function
     */
    abstract fun newInstance(): PiecewiseRateFunction

    /** Returns a copy of the piecewise  rate function
     * with each rate multiplied by the addFactor
     *
     * @param factor rate multiplied by the addFactor
     * @return a copy of the piecewise
     */
    abstract fun newInstance(factor: Double): PiecewiseRateFunction

    /** Get the rates as an array
     *
     * @return the rates as an array
     */
    abstract val rates: DoubleArray

    /** Get the durations as an array
     *
     * @return  the durations as an array
     */
    abstract val durations: DoubleArray

    override val cumulativeRateRangeLowerLimit: Double
        get() = myRateSegments[0].cumulativeRateRangeLowerLimit

    override val timeRangeLowerLimit: Double
        get() = myRateSegments[0].timeRangeLowerLimit

    // get the last interval
    override val timeRangeUpperLimit: Double
        get() {
            val k = myRateSegments.size
            // get the last interval
            val last = myRateSegments[k - 1]
            return last.upperTimeLimit
        }

    // get the last interval
    override val cumulativeRateRangeUpperLimit: Double
        get() {
            val k = myRateSegments.size
            // get the last interval
            val last = myRateSegments[k - 1]
            return last.cumulativeRateUpperLimit
        }

    /** Returns the rate for the supplied time
     *
     * @param time the time to evaluate
     * @return the rate for the supplied time
     */
    override fun getRate(time: Double): Double {
        if (time < 0.0) {
            return 0.0
        }
        val k = findTimeInterval(time)
        require(k != -1) { "The time = $time exceeds range of the function" }
        val i = myRateSegments[k]
        return i.getRate(time)
    }

    /** Returns the value of the cumulative rate function at the supplied time
     *
     * @param time the time to evaluate
     * @return the value of the cumulative rate function
     */
    override fun getCumulativeRate(time: Double): Double {
        if (time < 0.0) {
            return 0.0
        }
        val k = findTimeInterval(time)
        require(k != -1) { "The time = $time exceeds range of the function" }
        val i = myRateSegments[k]
        return i.getCumulativeRate(time)
    }

    /** Returns the value of the inverse cumulative rate function at the supplied rate
     * The value returned is interpreted as a time
     *
     * @param rate the rate
     * @return the value of the inverse cumulative rate function
     */
    override fun getInverseCumulativeRate(rate: Double): Double {
        if (rate <= 0.0) {
            return 0.0
        }
        val k = findCumulativeRateInterval(rate)
        require(k != -1) { "The rate = $rate exceeds range of the inverse function" }
        val i = myRateSegments[k]
        return i.getInverseCumulativeRate(rate)
    }

    /** Searches for the interval that the supplied cumulative rate
     * falls within.  Returns -1 if no interval is found
     *
     * Interval indexing starts at index 0 (i.e. 0 is the first interval,
     * silly Java zero based indexing)
     *
     * @param cumRate the rate
     * @return the interval that the supplied cumulative rate
     */
    fun findCumulativeRateInterval(cumRate: Double): Int {
        var k = -1
        for (i in myRateSegments) {
            k = k + 1
            if (cumRate <= i.cumulativeRateUpperLimit) {
                return k
            }
        }
        return -1
    }

    /** Returns the rate segment at index k
     * Interval indexing starts at index 0 (i.e. 0 is the first interval,
     * silly Java zero based indexing)
     * @param k the index
     * @return the rate segment at index k
     */
    fun getRateSegment(k: Int): RateSegmentIfc {
        return myRateSegments[k]
    }

    /** Returns the number of segments
     *
     * @return the number of segments
     */
    fun getNumberSegments(): Int {
        return myRateSegments.size
    }

    override val maximum: Double
        get() = myMaxRate

    override val minimum: Double
        get() = myMinRate

    override fun toString(): String {
        val sb = StringBuilder()
        for (i in myRateSegments) {
            sb.append(i.toString())
        }
        return sb.toString()
    }
}