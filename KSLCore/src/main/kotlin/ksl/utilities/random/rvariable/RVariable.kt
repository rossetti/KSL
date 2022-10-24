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
import ksl.utilities.observers.*
import ksl.utilities.random.rng.RNStreamIfc

/**
 * An abstract base class for building random variables.  Implement
 * the random generation procedure in the method generate().
 */
abstract class RVariable(stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null) : RVariableIfc,
    IdentityIfc by Identity(name), DoubleEmitterIfc by DoubleEmitter() {

    constructor(stream: RNStreamIfc = KSLRandom.nextRNStream()) : this(stream, null)

    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    final override var rnStream: RNStreamIfc = stream

    /** The last (previous) randomly generated value. This value does not
     *  change until the next randomly generated value is obtained
     */
    final override var previousValue: Double = Double.NaN
        private set

    /** The last (previous) randomly generated value. This value does not
     *  change until the next randomly generated value is obtained
     * @return the last randomly generated value, same as using property previous
     */
//    fun previous(): Double = previous

    /** Makes a new instance.  False allows the new instance to keep using
     * the same underlying source of random numbers.
     *
     * @param newRNG true means use new stream. This is same as instance(). False
     * means clone uses same underlying source of randomness
     * @return a new instance configured based on current instance
     */
    fun instance(newRNG: Boolean): RVariableIfc {
        return if (newRNG) {
            instance()
        } else {
            instance(rnStream)
        }
    }

    /**
     *
     * @return the randomly generated variate
     */
    protected abstract fun generate(): Double

    final override fun sample(): Double {
        val x = generate()
        previousValue = x
        emitter.emit(x)
        return x
    }

}