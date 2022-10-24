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

/** Models a cumulative rate function for the non-homogeneous Poisson Process
 * @author rossetti
 */
interface CumulativeRateFunctionIfc : RateFunctionIfc {
    /** Gets the cumulative rate from time 0 to the supplied time
     * for the rate function
     *
     * @param time the time to evaluate
     * @return Gets the cumulative rate from time 0 to the supplied time
     */
    fun cumulativeRate(time: Double): Double

    /** The function's lower limit on the cumulative rate range
     *
     * @return The function's lower limit on the cumulative rate range
     */
    val cumulativeRateRangeLowerLimit: Double

    /** The function's upper limit on the cumulative rate range
     *
     * @return The function's upper limit on the cumulative rate range
     */
    val cumulativeRateRangeUpperLimit: Double
}