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
 * Two exponential random variables mixed to get a hyper-exponential. For higher
 * order hyper-exponential use MixtureRV.  The mixing probability is the
 * probability of getting the first exponential distribution with mean1
 * @param mixingProb   probability of selecting the first exponential distribution
 * @param mean1 the mean of the first exponential distribution
 * @param mean2 the mean of the second exponential distribution
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class Hyper2ExponentialRV(
    val mixingProb: Double,
    val mean1: Double,
    val mean2: Double,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariable(streamNum, streamProvider, name) {

    init {
        require(!(mixingProb < 0.0 || mixingProb > 1.0)) { "Mixing Probability must be [0,1]" }
        require(mean1 > 0.0) { "Exponential mean1 must be > 0.0" }
        require(mean2 > 0.0) { "Exponential mean2 must be > 0.0" }
    }

    override fun generate(): Double {
        val v = KSLRandom.rBernoulli(mixingProb, rnStream)
        return if (v >= 1.0) {
            KSLRandom.rExponential(mean1, rnStream)
        } else {
            KSLRandom.rExponential(mean2, rnStream)
        }
    }

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): RVariableIfc {
        return Hyper2ExponentialRV(mixingProb, mean1, mean2, streamNum, rnStreamProvider, name)
    }

    override fun toString(): String {
        return "Hyper2ExponentialRV(mixingProb=$mixingProb, mean1=$mean1, mean2=$mean2)"
    }
}