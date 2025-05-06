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
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Constructs a StudentT random variable with dof degrees of freedom
 *
 * @param degreesOfFreedom degrees of freedom, must be greater than or equal to 1.0
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class StudentTRV (
    val degreesOfFreedom: Double,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariable(streamNumber, streamProvider, name) {

    init {
        require(degreesOfFreedom >= 1) { "The degrees of freedom must be >= 1.0" }
    }

    override fun generate(): Double {
        return baileysAcceptanceRejection()
    }

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): StudentTRV {
        return StudentTRV(degreesOfFreedom, streamNumber, rnStreamProvider, name)
    }

    /**
     * Directly generate a random variate using Bailey's
     * acceptance-rejection algorithm
     *
     * @return the generated random variable
     */
    fun baileysAcceptanceRejection(): Double {
        var W: Double
        var U: Double
        do {
            val u = rnStream.randU01()
            val v = rnStream.randU01()
            U = 2.0 * u - 1.0
            val V = 2.0 * v - 1.0
            W = U * U + V * V
        } while (W > 1.0)
        val tmp =
            degreesOfFreedom * (W.pow(-2.0 / degreesOfFreedom) - 1.0) / W
        return U * sqrt(tmp)
    }

}