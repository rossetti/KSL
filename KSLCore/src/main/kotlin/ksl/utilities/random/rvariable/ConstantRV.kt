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

import ksl.utilities.random.rng.RNStreamFactory
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.parameters.ConstantRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Allows a constant value to pretend to be a random variable
 */
class ConstantRV(
    var constVal: Double,
    name: String? = null
) : ParameterizedRV(
    KSLRandom.defaultStreamNumber,
    KSLRandom.DefaultRNStreamProvider,
    name
) {

    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    override val rnStream: RNStreamIfc = RNStreamFactory().nextStream()

    override val streamNumber: Int
        get() = 1

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): ConstantRV {
        return ConstantRV(constVal)
    }

    override fun generate(): Double {
        return constVal
    }

    override fun toString(): String {
        return "ConstantRV(value=$constVal)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = ConstantRVParameters()
            parameters.changeDoubleParameter("value", constVal)
            return parameters
        }

    override fun resetStartStream() {}
    override fun resetStartSubStream() {}
    override fun advanceToNextSubStream() {}

    override var antithetic: Boolean
        get() = false
        @Suppress("UNUSED_PARAMETER")
        set(flag) {
        }

    override fun antitheticInstance(): ConstantRV {
        return ConstantRV(constVal)
    }

    companion object {
        /**
         * A constant to represent zero for sharing
         */
        val ZERO: ConstantRV by lazy { ConstantRV(0.0) }

        /**
         * A constant to represent one for sharing
         */
        val ONE: ConstantRV by lazy { ConstantRV(1.0) }

        /**
         * A constant to represent positive infinity for sharing
         */
        val POSITIVE_INFINITY: ConstantRV by lazy { ConstantRV(Double.POSITIVE_INFINITY) }

    }


}