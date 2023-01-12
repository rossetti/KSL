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

package ksl.utilities.distributions

/**
 * Provides the inverse cumulative distribution function interface for a CDF
 *
 * @author rossetti
 */
fun interface InverseCDFIfc {

    /**
     * Provides the inverse cumulative distribution function for the
     * distribution
     *
     * While closed form solutions for the inverse cdf may not exist, numerical
     * search methods can be used to solve F(X) = p.
     *
     * @param p The probability to be evaluated for the inverse, p must be [0,1]
     * or an IllegalArgumentException is thrown
     * @return The inverse cdf evaluated at the supplied probability
     */
    fun invCDF(p: Double): Double
}