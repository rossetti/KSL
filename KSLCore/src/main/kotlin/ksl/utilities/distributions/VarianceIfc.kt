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

package ksl.utilities.distributions

import kotlin.math.sqrt

/** Defines an interface for getting the variance of a
 *  distribution
 *
 * @author rossetti
 */
fun interface VarianceIfc {

    /** Returns the variance of the distribution if defined
     * @return the variance of the distribution
     */
    fun variance(): Double

    /**
     * Returns the standard deviation for the distribution
     * as the square root of the variance if it exists
     *
     * @return sqrt(variance())
     */
    fun standardDeviation(): Double = sqrt(variance())
}