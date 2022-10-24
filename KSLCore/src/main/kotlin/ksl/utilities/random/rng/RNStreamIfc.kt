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
package ksl.utilities.random.rng

/**
 * Represents a random number stream with stream control
 *
 * @author rossetti
 */
interface RNStreamIfc : RandU01Ifc, RNStreamControlIfc, RNStreamNewInstanceIfc, GetAntitheticStreamIfc {

    val id: Int

    /**
     * Returns a (pseudo)random number from the discrete uniform distribution
     * over the integers {i, i + 1, . . . , j }, using this stream. Calls randU01 once.
     *
     * @param i start of range
     * @param j end of range
     * @return The integer pseudo random number
     */
    fun randInt(i: Int, j: Int): Int {
        require(i <= j) { "The lower limit must be <= the upper limit" }
        return i + (randU01() * (j - i + 1)).toInt()
    }

    /** A convenience function for allowing the range to be specified via a range
     *
     *  @param range the integer range to generate over
     */
    fun randInt(range: IntRange): Int {
        return randInt(range.first, range.last)
    }
}