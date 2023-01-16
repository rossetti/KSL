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
 * GeneralizeBetaRV(alpha1, alpha2, min, max) random variable
 * @param alpha1 the first shape parameter, must be greater than 0
 * @param alpha2 the second shape parameter, must be greater than 0
 * @param minimum the minimum of the range, must be less than maximum
 * @param maximum the maximum of the range
 * @param stream the random number stream
 */
class GeneralizedBetaRV(
    val alpha1: Double,
    val alpha2: Double,
    val minimum: Double,
    val maximum: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(), name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(maximum > minimum) { "the min must be < than the max" }
    }

    private val myBeta: BetaRV = BetaRV(alpha1, alpha2, stream)

    /**
     * GeneralizeBetaRV(alpha1, alpha2, min, max) random variable
     * @param alpha1 the first shape parameter
     * @param alpha2 the second shape parameter
     * @param minimum the minimum of the range
     * @param maximum the maximum of the range
     * @param streamNum the random number stream number
     */
    constructor(alpha1: Double, alpha2: Double, min: Double, max: Double, streamNum: Int) :
            this(alpha1, alpha2, min, max, KSLRandom.rnStream(streamNum))

    /**
     * @param stream the RNStreamIfc to use
     * @return a new instance with same parameter value
     */
    override fun instance(stream: RNStreamIfc): GeneralizedBetaRV {
        return GeneralizedBetaRV(alpha1, alpha2, minimum, maximum, stream)
    }

    override fun generate(): Double {
        return minimum + (maximum - minimum) * myBeta.value
    }

    override fun toString(): String {
        return "GeneralizedBetaRV(alpha1=$alpha1, alpha2=$alpha2, minimum=$minimum, maximum=$maximum)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.GeneralizedBetaRVParameters()
            parameters.changeDoubleParameter("alpha1", alpha1)
            parameters.changeDoubleParameter("alpha2", alpha2)
            parameters.changeDoubleParameter("min", minimum)
            parameters.changeDoubleParameter("max", maximum)
            return parameters
        }

}