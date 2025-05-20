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

import ksl.utilities.distributions.Normal
import ksl.utilities.random.rng.RNStreamProviderIfc
import kotlin.math.sqrt

/**
 * Allows for the generation of bi-variate normal random variables
 * Constructs a bi-variate normal with the provided parameters
 *
 * @param mean1 mean of first coordinate
 * @param v1  variance of first coordinate
 * @param mean2 mean of 2nd coordinate
 * @param v2  variance of 2nd coordinate
 * @param corr   correlation between X1 and X2
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class BivariateNormalRV @JvmOverloads constructor(
    val mean1: Double = 0.0,
    val v1: Double = 1.0,
    val mean2: Double = 0.0,
    val v2: Double = 1.0,
    val corr: Double = 0.0,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : MVRVariable(streamNum, streamProvider, name) {

    init {
        require(v1 > 0) { "The first variance was <=0" }
        require(v2 > 0) { "The second variance was <=0" }
        require(!(corr < -1.0 || corr > 1.0)) { "The correlation must be [-1,1]" }
    }

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The size of the array to fill does not match the sampling dimension!" }
        val z0 = Normal.stdNormalInvCDF(rnStream.randU01())
        val z1 = Normal.stdNormalInvCDF(rnStream.randU01())
        val s1 = sqrt(v1)
        val s2 = sqrt(v2)
        array[0] = mean1 + s1 * z0
        array[1] = mean2 + s2 * (corr * z0 + sqrt(1.0 - corr * corr) * z1)
    }

    override fun toString(): String {
        return "BivariateNormalRV(mean1=$mean1, v1=$v1, mean2=$mean2, v2=$v2, corr=$corr)"
    }

    override val dimension: Int
        get() = 2

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): MVRVariableIfc {
        return BivariateNormalRV(mean1, v1, mean2, v2, corr, streamNumber, rnStreamProvider)
    }

}