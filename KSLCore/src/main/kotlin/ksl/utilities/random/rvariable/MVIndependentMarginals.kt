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
 * Represents a multi-variate distribution with the specified marginals
 * The sampling of each marginal random variable is independent. That is the resulting
 * distribution has independent marginals. The supplied marginals may be the same
 * distribution or not.  If they are all the same, then use MVIndependentRV instead.
 * All the random variables will share the same stream. The sampling ensures that
 * is the sampling is consecutive within the stream and thus independent.
 * @param marginals must have at least 2 supplied marginals
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class MVIndependentMarginals(
    marginals: List<RVariableIfc>,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : MVRVariable(streamNum, streamProvider, name) {

    private val myRVs: MutableList<RVariableIfc> = ArrayList()

    init {
        require(marginals.size > 1) { "The number of supplied marginals must be at least 2" }
        for (rv in marginals) {
            myRVs.add(rv.instance(streamNum, streamProvider))
        }
    }

    override val dimension: Int
        get() = myRVs.size

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): MVIndependentMarginals {
        return MVIndependentMarginals(myRVs, streamNumber, rnStreamProvider)
    }

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The size of the array to fill does not match the sampling dimension!" }
        for ((i, rv) in myRVs.withIndex()) {
            array[i] = rv.sample()
        }
    }

}