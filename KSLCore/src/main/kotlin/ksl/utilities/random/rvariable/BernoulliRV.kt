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
import ksl.utilities.random.rvariable.parameters.BernoulliRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters


/**
 * Bernoulli(probability of success) random variable
 *
 * @param probOfSuccess      the probability, must be in (0,1)
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class BernoulliRV (
    val probOfSuccess: Double,
    streamNumber: Int,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamNumber, streamProvider, name) {


    init {
        require(!(probOfSuccess <= 0.0 || probOfSuccess >= 1.0)) { "Probability must be (0,1)" }
    }

    override fun generate(): Double {
        return KSLRandom.rBernoulli(probOfSuccess, rnStream)
    }

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): BernoulliRV {
        return BernoulliRV(probOfSuccess, streamNumber, rnStreamProvider, name)
    }

    /**
     * Returns a randomly generated boolean according to the Bernoulli distribution
     *
     * @return a randomly generated boolean
     */
    val boolValue: Boolean
        get() = (value != 0.0)

    /**
     * Returns a boolean array filled via boolSample()
     *
     * @param n the generation size, must be at least 1
     * @return the array
     */
    fun boolSample(n: Int): BooleanArray {
        require(n > 0) { "The generate size must be > 0" }
        val b = BooleanArray(n)
        for (i in 0 until n) {
            b[i] = boolValue
        }
        return b
    }

    override fun toString(): String {
        return "BernoulliRV(probOfSuccess=$probOfSuccess)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = BernoulliRVParameters()
            parameters.changeDoubleParameter("probOfSuccess", probOfSuccess)
            return parameters
        }

}