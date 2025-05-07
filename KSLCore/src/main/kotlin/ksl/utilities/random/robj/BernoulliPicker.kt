/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

interface BernoulliPickerIfc<T> : RElementIfc<T> {
    val success: T
    val failure: T
}

/**
 *  Allows the picking between two alternatives according to a Bernoulli process.
 *  @param successProbability the probability associated with success
 *  @param successOption the success choice
 *  @param failureOption the failure choice
 *  @param stream the associated random number stream
 */
class BernoulliPicker<T>(
    val successProbability: Double,
    successOption: T,
    failureOption: T,
    streamNumber: Int = 0,
    private val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
) : BernoulliPickerIfc<T> {

    init {
        require(!(successProbability <= 0.0 || successProbability >= 1.0)) { "Probability must be (0,1)" }
        require(successOption != failureOption) {"The success and failure options cannot be the same."}
    }

    override var success: T = successOption
        set(value) {
            require(value != failure) {"The success option cannot be equal to the failure option"}
            field = value
        }

    override var failure: T = failureOption
        set(value) {
            require(value != success) {"The failure option cannot be equal to the success option"}
            field = value
        }

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): RElementIfc<T> {
        return BernoulliPicker(successProbability, success, failure, streamNumber, rnStreamProvider)
    }

    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    private val rnStream: RNStreamIfc = streamProvider.rnStream(streamNumber)

    override val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    override val randomElement: T
        get() = if (rnStream.randU01() <= successProbability) success else failure

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
}