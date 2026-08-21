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
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
abstract class RVariable(
    streamNum: Int = 0,
    final override val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariableIfc, IdentityIfc by Identity(name), DoubleEmitterIfc by DoubleEmitter() {

    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    protected open val rnStream: RNStreamIfc = streamProvider.rnStream(streamNum)

    /*
     * The number this variable was built with. A provider serves an antithetic stream as a
     * derived copy that it does not itself hold, so asking the provider to name that stream
     * cannot work -- it reports "not mine" as -1. Since the number is carried across whenever a
     * variable is copied onto another provider (a model rebinding a supplied source does exactly
     * that), a -1 was consumed as a request and landed on antithetic stream 1 instead of the one
     * asked for. Remembering what was requested makes the number round-trip.
     */
    private val myRequestedStreamNumber: Int = streamNum

    override val streamNumber: Int
        get() = if (myRequestedStreamNumber != 0) {
            myRequestedStreamNumber
        } else {
            // zero means "the next stream", so the number is only known once one has been served
            streamProvider.streamNumber(rnStream)
        }

    /**
     *  An instance of the random variable with the stream provided
     *  by the same underlying stream provider
     */
    override fun instance(streamNum: Int) : RVariableIfc {
        return instance(streamNum, streamProvider)
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