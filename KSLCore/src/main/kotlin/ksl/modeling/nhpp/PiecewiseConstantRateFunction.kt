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

/** Adds the segments represented by the duration, rate pairs
 * The arrays must be the same length, not null, and have at least 1 pair
 *
 * @param durations the durations
 * @param rates the rates
 * @author rossetti
 */
class PiecewiseConstantRateFunction(durations: DoubleArray, rates: DoubleArray) : PiecewiseRateFunction() {
    init {
        require(rates.isNotEmpty()) { "rates array was empty" }
        require(durations.isNotEmpty()) { "durations array was empty" }
        require(rates.size == durations.size) { "durations and rates must have the same length" }
        addFirstSegment(durations[0], rates[0])
        for (i in 1 until rates.size) {
            addRateSegment(durations[i], rates[i])
        }
    }

    /** Returns a copy of the piecewise constance rate function
     *
     * @return the piecewise constance rate function
     */
    override fun instance(): PiecewiseConstantRateFunction {
        return PiecewiseConstantRateFunction(durations, rates)
    }

    /** Returns a copy of the piecewise constance rate function
     * with each rate multiplied by the addFactor
     *
     * @param factor multiplied by the addFactor
     * @return the piecewise constance rate function
     */
    override fun instance(factor: Double): PiecewiseConstantRateFunction {
        require(factor > 0) { "The multiplication factor must be > 0" }
        val rates = rates
        for (i in rates.indices) {
            rates[i] = rates[i] * factor
        }
        return PiecewiseConstantRateFunction(durations, rates)
    }

    private fun addFirstSegment(duration: Double, rate: Double) {
        val first = ConstantRateSegment(0.0, 0.0, duration, rate)
        myRateSegments.add(first)
        if (rate > maximumRate) {
            maximumRate = rate
        }
        if (rate < minimumRate) {
            minimumRate = rate
        }
    }

    /** Allows the construction of the piecewise rate function starting at time zero.
     * The user supplies the arrival rate and the duration for that arrival
     * rate, by consecutive calls to addRateSegment().
     *
     * @param rate must be &gt; 0, and less than Double.POSITIVE_INFINITY
     * @param duration must be &gt; 0
     */
    private fun addRateSegment(duration: Double, rate: Double) {
        val k = myRateSegments.size
        val prev = myRateSegments[k - 1]
        val next = ConstantRateSegment(prev.cumulativeRateUpperLimit, prev.timeRangeUpperLimit, duration, rate)
        myRateSegments.add(next)
        if (rate > maximumRate) {
            maximumRate = rate
        }
        if (rate < minimumRate) {
            minimumRate = rate
        }
    }

    /** Get the rates as an array
     *
     * @return the rates as an array
     */
    override val rates: DoubleArray
        get() {
            val rates = DoubleArray(myRateSegments.size)
            for ((i, s) in myRateSegments.withIndex()) {
                rates[i] = s.rateAtLowerTimeLimit
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
        return timeRangeLowerLimit <= time && time < timeRangeUpperLimit
    }

    /** Searches for the interval that the supplied time
     * falls within.  Returns -1 if no interval is found
     *
     * Interval indexing starts at index 0 (i.e. 0 is the first interval,
     * silly Java zero based indexing)
     *
     * @param time the time to look up
     * @return the index of the interval
     */
    override fun findTimeInterval(time: Double): Int {
        var k = -1
        for (i in myRateSegments) {
            k = k + 1
            if (time < i.timeRangeUpperLimit) {
                return k
            }
        }
        return -1
    }
}