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
package ksl.utilities.random.rvariable

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc

abstract class MVRVariable(stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null) : MVRVariableIfc,
    IdentityIfc by Identity(name), RNStreamControlIfc by stream {
    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    override var rnStream: RNStreamIfc = stream

    /** The last (previous) randomly generated value. This value does not
     *  change until the next randomly generated value is obtained
     */
    var previous: DoubleArray = doubleArrayOf()
        private set

    /** The last (previous) randomly generated value. This value does not
     *  change until the next randomly generated value is obtained
     * @return the last randomly generated value, same as using property previous
     */
    fun previous(): DoubleArray = previous

    /**
     * The randomly generated values. Each access to value will
     * result in a new sample
     */
    val value: DoubleArray
        get() = sample()

    /**
     * The randomly generated values.  Each call to value() will
     * result in a new sample
     * @return the randomly generated values, same as using property value
     */
    fun value(): DoubleArray = value

    override fun sample(array: DoubleArray) {
        generate(array)
        previous = array
    }

    /**
     *
     * @return the randomly generated variates
     */
    protected abstract fun generate(array: DoubleArray)
}