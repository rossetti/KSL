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
package ksl.utilities.random.rng

/**
 * The new instance has the same state as the underlying stream.  This is a new stream
 * but it has the same state (starting values, etc.)
 * @author rossetti
 */
interface RNStreamNewInstanceIfc {
    /** Returns a copy of the stream with
     * exactly the same state
     *
     * @return Returns a copy of the stream with
     * exactly the same state
     */
//    fun newInstance(): RNStreamIfc

    /** Returns a copy of the stream that
     * has exactly the same state
     *
     * @param name  the name of the new instance
     * @return Returns a copy of the stream with
     * exactly the same state
     */
    fun crnInstance(name: String? = null): RNStreamIfc
}