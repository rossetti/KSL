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
/*
 * Created on Nov 4, 2006
 *
 */
package ksl.utilities

/** Can be used to represent confidence intervals.  Intervals between two real
 * numbers where the lower limit must be less than or equal to the upper limit.
 * The interval is inclusive of both end points.
 *
 * @param xLower the lower limit, must be less than or equal to xUpper
 * @param xUpper  the upper limit
 * @author rossetti
 */
class Interval(xLower: Double = Double.NEGATIVE_INFINITY, xUpper: Double = Double.POSITIVE_INFINITY) :
    NewInstanceIfc<Interval> {
    init {
        require(xLower <= xUpper) { "The lower limit must be <= the upper limit" }
    }

    /**
     *
     * @return the lower limit of the interval
     */
    var lowerLimit = xLower
        private set

    /**
     *
     * @return The upper limit of the interval
     */
    var upperLimit = xUpper
        private set

    /** The width of the interval
     *
     * @return
     */
    val width: Double
        get() = upperLimit - lowerLimit

    /** Half of the width of the interval
     *
     * @return
     */
    val halfWidth: Double
        get() = width / 2.0

    /** Sets the interval
     * Throws IllegalArgumentException if the lower limit is &gt;= upper limit
     *
     * @param xLower the lower limit
     * @param xUpper the upper limit
     */
    fun setInterval(xLower: Double, xUpper: Double) {
        require(xLower <= xUpper) { "The lower limit must be <= the upper limit" }
        lowerLimit = xLower
        upperLimit = xUpper
    }

    /** A new instance with the same interval settings.
     *
     * @return A new instance with the same interval settings.
     */
    override fun instance(): Interval {
        return Interval(lowerLimit, upperLimit)
    }

    fun asRange(): ClosedFloatingPointRange<Double> = lowerLimit..upperLimit

    override fun toString(): String {
        return "[$lowerLimit, $upperLimit]"
    }

    /**
     *
     * @param x the value to check
     * @return true if x is in the interval (includes end points)
     */
    operator fun contains(x: Double): Boolean {
        return x in lowerLimit..upperLimit
    }

    /** Checks if the supplied interval is contained within
     * this interval
     *
     * @param interval the interval to check
     * @return true only if both lower and upper limits of supplied interval
     * are within this interval
     */
    operator fun contains(interval: Interval): Boolean {
        return contains(interval.lowerLimit) && contains(interval.upperLimit)
    }

}