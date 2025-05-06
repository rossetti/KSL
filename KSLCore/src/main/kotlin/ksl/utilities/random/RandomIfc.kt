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
package ksl.utilities.random

import ksl.utilities.GetValueIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.NewAntitheticInstanceIfc

/**
 *  A general interface to represent randomness within models. The interface
 *  permits sampling, stream control, and creation of instances.
 */
interface RandomIfc : SampleIfc, GetValueIfc, RNStreamControlIfc, NewAntitheticInstanceIfc {

    /**
     *  The underlying stream of U(0,1) pseudo-random numbers associated with the source of randomness
     */
    val rnStream: RNStreamIfc

    /**
     *
     * @return the stream number allocated to the random variable by the default stream provider. This will
     * return -1 if the random variable's underlying stream was not provided by the default stream provider
     */
    val streamNumber: Int

    /**
     * @param streamNumber the stream number to use from the underlying provider
     * @param rnStreamProvider the provider for the stream instance
     * @return a new instance with same parameter values
     */
    fun instance(
        streamNumber: Int = 0,
        rnStreamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
    ): RandomIfc

    /**
     * @param n the number of values to sum, must be 1 or more
     * @return the sum of n values of getValue()
     */
    fun sum(numInSum: Int) : Double {
        require(numInSum >= 1) {"There must be 1 or more in the requested sum"}
        var sum = 0.0
        for (i in 1..numInSum) {
            sum = sum + value
        }
        return sum
    }

//    override var advanceToNextSubStreamOption: Boolean
//        get() = rnStream.advanceToNextSubStreamOption
//        set(value) {
//            rnStream.advanceToNextSubStreamOption = value
//        }
//
//    override var resetStartStreamOption: Boolean
//        get() = rnStream.resetStartStreamOption
//        set(value) {
//            rnStream.resetStartStreamOption = value
//        }
//
//    override fun resetStartStream() {
//        rnStream.resetStartStream()
//    }
//
//    override fun resetStartSubStream() {
//        rnStream.resetStartSubStream()
//    }
//
//    override fun advanceToNextSubStream() {
//        rnStream.advanceToNextSubStream()
//    }
//
//    override var antithetic: Boolean
//        get() = rnStream.antithetic
//        set(value) {
//            rnStream.antithetic = value
//        }
}