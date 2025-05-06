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
package ksl.utilities.random.rvariable

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.*
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 * An abstract base class for building random variables.  Implement
 * the random generation procedure in the method generate().
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
abstract class RVariable(
    streamNumber: Int = 0,
    protected val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariableIfc, IdentityIfc by Identity(name), DoubleEmitterIfc by DoubleEmitter(), NewAntitheticInstanceIfc {

    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    protected val rnStream: RNStreamIfc = streamProvider.rnStream(streamNumber)

    override val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    /**
     *  An instance of the random variable with the stream provided
     *  by the same underlying stream provider
     */
    override fun instance(streamNumber: Int) : RVariableIfc {
        return instance(streamNumber, streamProvider)
    }

    override fun antitheticInstance(): RVariableIfc {
        return instance(streamNumber = -streamNumber, streamProvider)
    }

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

    /** The last (previous) randomly generated value. This value does not
     *  change until the next randomly generated value is obtained
     */
    final override var previousValue: Double = Double.NaN
        private set

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