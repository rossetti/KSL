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

import kotlin.math.floor

/**
 * Represents the probability mass function for 1-d discrete distributions
 *
 * @author rossetti
 */
fun interface PMFIfc {

    /** If x is not an integer value, then the probability must be zero
     * otherwise pmf(int x) is used to determine the probability
     *
     * @param x the value to evaluate
     * @return the probability
     */
    fun pmf(x: Double): Double {
        return if (floor(x) == x) {
            pmf(x.toInt())
        } else {
            0.0
        }
    }

    /**
     * Returns the f(i) where f represents the probability mass function for the
     * distribution.
     *
     * @param i an integer representing the value to be evaluated
     * @return f(i) the P(X=i)
     */
    fun pmf(i: Int) : Double

    /**
     *  Computes the probabilities associated with the [range]
     *  and returns the value and the probability as a map
     *  with the integer value as the key and the probability
     *  as the related value.
     */
    fun pmf(range: IntRange) : Map<Int, Double>{
        val map = mutableMapOf<Int, Double>()
        for(i in range){
            map[i] = pmf(i)
        }
        return map
    }
}