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

/** Models a rate function for the non-stationary Poisson Process
 * @author rossetti
 */
interface RateFunctionIfc {
    /** Returns the rate the supplied time
     *
     * @param time the time to evaluate
     * @return Returns the rate the supplied time
     */
    fun rate(time: Double): Double

    /** Gets the maximum value of the rate function over its time horizon
     *
     * @return Gets the maximum value of the rate function over its time horizon
     */
    val maximumRate: Double

    /** Gets the minimum value of the rate function over its time horizon
     *
     * @return Gets the minimum value of the rate function over its time horizon
     */
    val minimumRate: Double

    /** The function's lower limit on the time range
     *
     * @return The function's lower limit on the time range
     */
    val timeRangeLowerLimit: Double

    /** The function's upper limit on the time range
     *
     * @return The function's upper limit on the time range
     */
    val timeRangeUpperLimit: Double

    /** Returns true if the supplied time is within the time range
     * of the rate function
     *
     * @param time the time to evaluate
     * @return true if the supplied time is within the time range
     */
    operator fun contains(time: Double): Boolean
}