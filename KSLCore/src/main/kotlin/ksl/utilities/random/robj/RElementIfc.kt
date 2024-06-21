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
package ksl.utilities.random.robj

import ksl.utilities.random.rng.RNStreamChangeIfc
import ksl.utilities.random.rng.RNStreamControlIfc

interface RElementIfc<T> : RNStreamControlIfc, RNStreamChangeIfc {

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(value) {
            rnStream.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(value) {
            rnStream.resetStartStreamOption = value
        }

    /** Returns an element randomly selected from the list
     *
     * @return a randomly selected element from the list
     */
    val randomElement: T

    /** Returns an element randomly selected from the list
     *
     * @return a randomly selected element from the list
     */
    fun sample() : T {
        return randomElement
    }

    /** Returns sample of [size] from the list
     *
     * @return randomly selected elements as a list
     */
    fun sample(size : Int) : List<T> {
        require(size > 0) { "The size of the sample must be at least 1." }
        val list = mutableListOf<T>()
        for (i in 0 until size) {
            list.add(randomElement)
        }
        return list
    }

    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
        }
}