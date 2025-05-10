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

import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 * Shifts the generated value of the supplied random variable by the shift amount.
 * The shift amount must be positive.
 * @param shift a non-negative value
 * @param rv    the random variable to shift
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class ShiftedRV(
    val shift: Double,
    private val rv: RVariableIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariable(streamNum, streamProvider, name) {

    init {
        require(shift >= 0.0) { "The shift should not be < 0.0" }
    }

    private val myRV: RVariableIfc = rv.instance(streamNum, streamProvider)

    override fun generate(): Double {
        return shift + myRV.value
    }

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): ShiftedRV {
        return ShiftedRV(shift, myRV, streamNumber, rnStreamProvider, name)
    }

    override fun toString(): String {
        return "ShiftedRV(shift=$shift, myRV=$myRV)"
    }

}