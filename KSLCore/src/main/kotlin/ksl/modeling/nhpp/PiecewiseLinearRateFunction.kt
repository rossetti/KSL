/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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
package ksl.modeling.nhpp

/** Represents a piecewise linear rate function as a sequence of segments.
 * The rate at the beginning of the segment can be different from
 * the rate at the end of the segment, with a linear rate over the duration.
 *
 * Uses the segments represented by the rate, duration pairs
 * The rate array must be larger than the duration array, not null, and have at
 * least 2 rates.  Any rates
 * rate[0] beginning rate of segment 0, duration[0] duration of segment 0
 * i &gt;=1
 * rate[i] ending rate of segment i-1, beginning rate of segment i,
 * duration[i] duration of segment i
 *
 * @param durations the durations
 * @param rates the rates
 * @author rossetti
 */
class PiecewiseLinearRateFunction(durations: DoubleArray, rates: DoubleArray) : PiecewiseRateFunction() {
    init{
        require(rates.size >= 2) { "rates length was zero" }
        require(durations.isNotEmpty()) { "durations length was zero" }
        require(!(rates.size != durations.size + 1)) { "rate length must be equal to duration length + 1" }
        addFirstSegment(rates[0], durations[0], rates[1])
        for (i in 1 until durations.size) {
            addRateSegment(durations[i], rates[i + 1])
        }
    }

    private fun addFirstSegment(firstRate: Double, duration: Double, secondRate: Double) {
        require(firstRate >= 0.0) { "The rate must be >= 0" }
        require(firstRate < Double.POSITIVE_INFINITY) { "The rate must be < infinity" }
        if (firstRate > maximumRate) {
            maximumRate = firstRate
        }
        if (firstRate < minimumRate) {
            minimumRate = firstRate
        }
        require(secondRate >= 0.0) { "The rate must be >= 0" }
        require(secondRate < Double.POSITIVE_INFINITY) { "The rate must be < infinity" }
        if (secondRate > maximumRate) {
            maximumRate = secondRate
        }
        if (secondRate < minimumRate) {
            minimumRate = secondRate
        }
        require(duration > 0.0) { "The duration must be > 0" }
        require(!java.lang.Double.isInfinite(duration)) { "The duration cannot be infinite." }
        val first = LinearRateSegment(0.0, 0.0, firstRate, duration, secondRate)
        myRateSegments.add(first)
    }

    /** Returns a copy of the piecewise linear rate function
     *
     * @return a copy of the piecewise linear rate function
     */
    override fun instance(): PiecewiseLinearRateFunction {
        return PiecewiseLinearRateFunction(durations, rates)
    }

    /** Returns a copy of the piecewise linear rate function
     * with each rate multiplied by the addFactor
     *
     * @param factor the addFactor to multiply
     * @return a copy of the piecewise linear rate function
     */
    override fun instance(factor: Double): PiecewiseLinearRateFunction {
        require(factor > 0) { "The multiplication addFactor must be > 0" }
        val rates = rates
        for (i in rates.indices) {
            rates[i] = rates[i] * factor
        }
        return PiecewiseLinearRateFunction(durations, rates)
    }

    /** Allows the construction of the piecewise linear rate function
     * The user supplies the knot points on the piecewise linear function by supplying
     * the rate at the end of the supplied duration via consecutive calls to addRateSegment().
     *
     * @param duration must be &gt; 0 and less than Double.POSITIVE_INFINITY
     * @param rate must be &gt;= 0, and less than Double.POSITIVE_INFINITY
     */
    private fun addRateSegment(duration: Double, rate: Double) {
        require(rate >= 0.0) { "The rate must be > 0" }
        require(rate < Double.POSITIVE_INFINITY) { "The rate must be < infinity" }
        require(duration > 0.0) { "The duration must be > 0" }
        require(duration.isFinite()) { "The duration cannot be infinite." }
        if (rate > maximumRate) {
            maximumRate = rate
        }
        if (rate < minimumRate) {
            minimumRate = rate
        }
        val k = myRateSegments.size
        // get the last interval
        val last = myRateSegments[k - 1]
        val next: RateSegmentIfc = LinearRateSegment(
            last.cumulativeRateUpperLimit,
            last.timeRangeUpperLimit, last.rateAtUpperTimeLimit,
            last.timeRangeUpperLimit + duration, rate
        )
        myRateSegments.add(next)
    }

    /** Get the rates as an array
     *
     * @return the rates as an array
     */
    override val rates: DoubleArray
        get() {
            val rates = DoubleArray(myRateSegments.size + 1)
            rates[0] = myRateSegments[0].rateAtLowerTimeLimit
            var i = 1
            for (s in myRateSegments) {
                rates[i] = s.rateAtUpperTimeLimit
                i++
            }
            return rates
        }

    /** Get the durations as an array
     *
     * @return the durations as an array
     */
    override val durations: DoubleArray
        get() {
            val durations = DoubleArray(myRateSegments.size)
            for ((i, s) in myRateSegments.withIndex()) {
                durations[i] = s.timeRangeWidth
            }
            return durations
        }

    override fun contains(time: Double): Boolean {
        return time in timeRangeLowerLimit..timeRangeUpperLimit
    }

    /** Searches for the interval that the supplied time
     * falls within.  Returns -1 if no interval is found
     *
     * Interval indexing starts at index 0 (i.e. 0 is the first interval,
     * silly Java zero based indexing)
     *
     * @param time the time to look up
     * @return an int representing the interval
     */
    override fun findTimeInterval(time: Double): Int {
        var k = -1
        for (i in myRateSegments) {
            k = k + 1
            if (time <= i.timeRangeUpperLimit) {
                return k
            }
        }
        return -1
    }
}