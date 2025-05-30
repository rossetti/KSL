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

/**
 * @author rossetti
 */
abstract class PiecewiseRateFunction : InvertibleCumulativeRateFunctionIfc {

    protected var myRateSegments: MutableList<RateSegmentIfc> = mutableListOf()

    override var maximumRate : Double = Double.NEGATIVE_INFINITY
        protected set

    override var minimumRate : Double = Double.POSITIVE_INFINITY
        protected set

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
    abstract fun instance(): PiecewiseRateFunction

    /** Returns a copy of the piecewise  rate function
     * with each rate multiplied by the factor
     *
     * @param factor the factor to multiply each rate by
     * @return a copy of the piecewise
     */
    abstract fun instance(factor: Double): PiecewiseRateFunction

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
        get() = myRateSegments[0].cumulativeRateLowerLimit

    override val timeRangeLowerLimit: Double
        get() = myRateSegments[0].timeRangeLowerLimit

    // get the last interval
    override val timeRangeUpperLimit: Double
        get() {
            val k = myRateSegments.size
            // get the last interval
            val last = myRateSegments[k - 1]
            return last.timeRangeUpperLimit
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
    override fun rate(time: Double): Double {
        if (time < 0.0) {
            return 0.0
        }
        val k = findTimeInterval(time)
        require(k != -1) { "The time = $time exceeds range of the function" }
        val i = myRateSegments[k]
        return i.rate(time)
    }

    /** Returns the value of the cumulative rate function at the supplied time
     *
     * @param time the time to evaluate
     * @return the value of the cumulative rate function
     */
    override fun cumulativeRate(time: Double): Double {
        if (time < 0.0) {
            return 0.0
        }
        val k = findTimeInterval(time)
        require(k != -1) { "The time = $time exceeds range of the function" }
        val i = myRateSegments[k]
        return i.cumulativeRate(time)
    }

    /** Returns the value of the inverse cumulative rate function at the supplied rate
     * The value returned is interpreted as a time
     *
     * @param rate the rate
     * @return the value of the inverse cumulative rate function
     */
    override fun inverseCumulativeRate(rate: Double): Double {
        if (rate <= 0.0) {
            return 0.0
        }
        val k = findCumulativeRateInterval(rate)
        require(k != -1) { "The rate = $rate exceeds range of the inverse function" }
        val i = myRateSegments[k]
        return i.inverseCumulativeRate(rate)
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
     * silly zero based indexing)
     * @param k the index
     * @return the rate segment at index k
     */
    fun rateSegment(k: Int): RateSegmentIfc {
        return myRateSegments[k]
    }

    /** Returns the number of segments
     *
     * @return the number of segments
     */
    fun numberSegments(): Int {
        return myRateSegments.size
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (i in myRateSegments) {
            sb.append(i.toString())
        }
        return sb.toString()
    }
}