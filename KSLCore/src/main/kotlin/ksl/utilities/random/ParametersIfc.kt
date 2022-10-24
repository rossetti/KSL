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

package ksl.utilities.random

/**
 * Represents a general mechanism for setting and getting
 * the parameters of a function via an array of doubles
 *
 * @author rossetti
 */
interface ParametersIfc {
//TODO this should probably be an abstract property
    /**
     * Sets the parameters
     *
     * @param params an array of doubles representing the parameters
     */
    fun parameters(params: DoubleArray)

    /**
     * Gets the parameters
     *
     * @return Returns an array of the parameters
     */
    fun parameters(): DoubleArray
}