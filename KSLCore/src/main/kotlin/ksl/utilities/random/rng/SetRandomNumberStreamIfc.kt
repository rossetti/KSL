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
package ksl.utilities.random.rng

import ksl.utilities.random.rvariable.KSLRandom

interface SetRandomNumberStreamIfc {
    /**
     * Sets the underlying random number stream
     *
     * @param stream the reference to the random number stream, must not be null
     */
    fun setRandomNumberStream(stream: RNStreamIfc)

    /** Assigns the stream associated with the supplied number from the default RNStreamProvider
     *
     * @param streamNumber a stream number, 1, 2, etc.
     */
    fun setRandomNumberStream(streamNumber: Int) {
        setRandomNumberStream(KSLRandom.rnStream(streamNumber))
    }
}