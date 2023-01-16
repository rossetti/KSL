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
 * Beta(alpha1, alpha2) random variable, range (0,1)
 * @param alpha1 the first shape parameter
 * @param alpha2 the second shape parameter
 * @param stream the random number stream
 */
class BetaRV(
    val alpha1: Double = 1.0,
    val alpha2: Double = 1.0,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(alpha1 > 0) { "The 1st shape parameter must be > 0" }
        require(alpha2 > 0) { "The 2nd shape parameter must be > 0" }
    }

    constructor(alpha1: Double, alpha2: Double, streamNum: Int) : this(alpha1, alpha2, KSLRandom.rnStream(streamNum)) {}

    override fun instance(stream: RNStreamIfc): BetaRV {
        return BetaRV(alpha1, alpha2, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rBeta(alpha1, alpha2, rnStream)
    }

    override fun toString(): String {
        return "BetaRV(alpha1=$alpha1, alpha2=$alpha2)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.BetaRVParameters()
            parameters.changeDoubleParameter("alpha1", alpha1)
            parameters.changeDoubleParameter("alpha2", alpha2)
            return parameters
        }

}