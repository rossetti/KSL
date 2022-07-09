/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.utilities.random.rvariable

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rng.RNStreamIfc

/**
 * An abstract base class for building random variables.  Implement
 * the random generation procedure in the method generate().
 */
abstract class RVariable(stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null) : RVariableIfc,
    IdentityIfc by Identity(name) {

    constructor(stream: RNStreamIfc = KSLRandom.nextRNStream()) : this(stream, null)

    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    final override var rnStream: RNStreamIfc = stream

    /** The last (previous) randomly generated value. This value does not
     *  change until the next randomly generated value is obtained
     */
    var previous: Double = Double.NaN
        private set

    /** The last (previous) randomly generated value. This value does not
     *  change until the next randomly generated value is obtained
     * @return the last randomly generated value, same as using property previous
     */
    final override fun previous(): Double = previous

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
        previous = x
        return x
    }

}