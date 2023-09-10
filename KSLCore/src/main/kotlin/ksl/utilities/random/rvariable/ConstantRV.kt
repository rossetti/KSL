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

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.parameters.ConstantRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Allows a constant value to pretend to be a random variable
 */
open class ConstantRV(var constVal: Double, name: String? = null) : ParameterizedRV(KSLRandom.defaultRNStream(), name){

    override fun instance(stream: RNStreamIfc): ConstantRV {
        return ConstantRV(constVal)
    }

    override fun instance(): ConstantRV {
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
        set(flag) {}

    override fun antitheticInstance(): RVariableIfc {
        return ConstantRV(constVal)
    }

    companion object {
        /**
         * A constant to represent zero for sharing
         */
        val ZERO = ConstantRV(0.0)

        /**
         * A constant to represent one for sharing
         */
        val ONE = ConstantRV(1.0)

        /**
         * A constant to represent two for sharing
         */
        val TWO = ConstantRV(2.0)

        /**
         * A constant to represent positive infinity for sharing
         */
        val POSITIVE_INFINITY = ConstantRV(Double.POSITIVE_INFINITY)

    }


}