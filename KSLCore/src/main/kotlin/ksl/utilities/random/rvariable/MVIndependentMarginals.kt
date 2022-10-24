/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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
 * Represents a multi-variate distribution with the specified marginals
 * The sampling of each marginal random variable is independent. That is the resulting
 * distribution has independent marginals. The supplied marginals may be the same
 * distribution or not.  If they are all the same, then use MVIndependentRV instead.
 * All the random variables will share the same stream. The sampling ensures that
 * is the sampling is consecutive within the stream and thus independent.
 * @param marginals must have at least 2 supplied marginals
 * @param stream the stream to associate with each marginal
 */
class MVIndependentMarginals(
    marginals: List<RVariableIfc>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : MVRVariable(stream) {

    private val myRVs: MutableList<RVariableIfc> = ArrayList()

    init {
        require(marginals.size > 1) { "The number of supplied marginals must be at least 2" }
        for (rv in marginals) {
            myRVs.add(rv.instance(rnStream))
        }
    }

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return MVIndependentMarginals(myRVs, stream)
    }

    override fun antitheticInstance(): MVRVariableIfc {
        return MVIndependentMarginals(myRVs, rnStream.antitheticInstance())
    }

    override val dimension: Int
        get() = myRVs.size

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The size of the array to fill does not match the sampling dimension!" }
        for ((i, rv) in myRVs.withIndex()) {
            array[i] = rv.sample()
        }
    }

}