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

/**
 * Pearson Type 6(alpha1, alpha2, beta) random variable
 * @param alpha1 first shape parameter, must be greater than 0.0
 * @param alpha2 2nd shape parameter, must be greater than 0.0
 * @param beta first scale parameter, must be greater than 0.0
 * @param stream the random number stream
 */
class PearsonType6RV (
    val alpha1: Double,
    val alpha2: Double,
    val beta: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name)  {
    init {
        require(alpha1 > 0.0) { "The 1st shape parameter must be > 0.0" }
        require(alpha2 > 0.0) { "The 2nd shape parameter must be > 0.0" }
        require(beta > 0.0) { "The scale parameter must be > 0.0" }
    }

    constructor(alpha1: Double, alpha2: Double, beta: Double, streamNum: Int) : this(
        alpha1, alpha2, beta, KSLRandom.rnStream(streamNum))

    override fun instance(stream: RNStreamIfc): PearsonType6RV {
        return PearsonType6RV(alpha1, alpha2, beta, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rPearsonType6(alpha1, alpha2, beta, rnStream)
    }

    override fun toString(): String {
        return "PearsonType6RV(alpha1=$alpha1, alpha2=$alpha2, beta=$beta)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.PearsonType6RVParameters()
            parameters.changeDoubleParameter("alpha1", alpha1)
            parameters.changeDoubleParameter("alpha2", alpha2)
            parameters.changeDoubleParameter("beta", beta)
            return parameters
        }

}