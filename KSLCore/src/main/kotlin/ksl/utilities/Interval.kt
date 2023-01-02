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
        if (!xLower.isNaN() || !xUpper.isNaN()){
            require(xLower <= xUpper) { "The lower limit $xLower must be <= the upper limit $xUpper" }
        }
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
        if (!xLower.isNaN() || !xUpper.isNaN()){
            require(xLower <= xUpper) { "The lower limit $xLower must be <= the upper limit $xUpper" }
        }
//        require(xLower <= xUpper) { "The lower limit must be <= the upper limit" }
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

fun ClosedFloatingPointRange<Double>.asInterval() : Interval {
    return Interval(start, endInclusive)
}

fun IntRange.asInterval(): Interval {
    return Interval(start.toDouble(), endInclusive.toDouble())
}

fun LongRange.asInterval(): Interval {
    return Interval(start.toDouble(), endInclusive.toDouble())
}