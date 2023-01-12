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
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Constructs a StudentT distribution dof degrees of freedom
 *
 * @param degreesOfFreedom degrees of freedom, must be greater than or equal to 1.0
 * @param stream the random number generator
 */
class StudentTRV (val degreesOfFreedom: Double, stream: RNStreamIfc = KSLRandom.nextRNStream()) :
    RVariable(stream) {
    init {
        require(degreesOfFreedom >= 1) { "The degrees of freedom must be >= 1.0" }
    }
    /**
     * Constructs a StudentT distribution dof degrees of freedom
     *
     * @param dof       degrees of freedom
     * @param streamNum the stream number
     */
    constructor(dof: Double, streamNum: Int) : this(dof, KSLRandom.rnStream(streamNum)) {}

    override fun generate(): Double {
        return baileysAcceptanceRejection()
    }

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return StudentTRV(degreesOfFreedom, stream)
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