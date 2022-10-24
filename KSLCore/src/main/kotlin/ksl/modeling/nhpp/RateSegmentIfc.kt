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
package ksl.modeling.nhpp

interface RateSegmentIfc {
    /** Returns a new instance of the rate segment
     *
     * @return a new instance of the rate segment
     */
    fun instance(): RateSegmentIfc

    /** Returns true if the supplied time is within this
     * rate segments time interval
     *
     * @param time the time to be evaluated
     * @return true if in the segment
     */
    operator fun contains(time: Double): Boolean {
        return timeRangeLowerLimit <= time && time < timeRangeUpperLimit
    }

    /** Returns the rate for the interval
     *
     * @param time  the time to evaluate
     * @return the rate at the time
     */
    fun rate(time: Double): Double

    /** The rate at the time that the time interval begins
     *
     * @return The rate at the time that the time interval begins
     */
    val rateAtLowerTimeLimit: Double

    /** The rate at the time that the time interval ends
     *
     * @return The rate at the time that the time interval ends
     */
    val rateAtUpperTimeLimit: Double

    /** The lower time limit
     *
     * @return The lower time limit
     */
    val timeRangeLowerLimit: Double

    /** The upper time limit
     *
     * @return The upper time limit
     */
    val timeRangeUpperLimit: Double

    /** The width of the interval
     *
     * @return The width of the interval
     */
    val timeRangeWidth: Double

    /** The lower limit on the cumulative rate axis
     *
     * @return The lower limit on the cumulative rate axis
     */
    val cumulativeRateLowerLimit: Double

    /** The upper limit on the cumulative rate axis
     *
     * @return The upper limit on the cumulative rate axis
     */
    val cumulativeRateUpperLimit: Double

    /** The cumulative rate interval width
     *
     * @return The cumulative rate interval width
     */
    val cumulativeRateIntervalWidth: Double

    /** Returns the value of the cumulative rate function for the interval
     * given a value of time within that interval
     *
     * @param time the time to be evaluated
     * @return the cumulative rate at the given time
     */
    fun cumulativeRate(time: Double): Double

    /** Returns the inverse of the cumulative rate function given the interval
     * and a cumulative rate value within that interval.  Returns a time
     *
     * @param cumRate the cumulative rate
     * @return the inverse of the cumulative rate function
     */
    fun inverseCumulativeRate(cumRate: Double): Double
}