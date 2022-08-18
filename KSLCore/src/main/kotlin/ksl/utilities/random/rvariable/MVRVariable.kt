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