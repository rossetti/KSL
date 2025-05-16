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
 *  This represents a uni-variate function of a random variable.
 *
 * @param rv the random variable in the function mapping
 * @param theTransform the functional transformation using (first) to produce a double
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 */
class RVUFunction private constructor (
    rv: RVariableIfc,
    theTransform: ((f: Double) -> Double) = { f: Double -> f },
    streamNum: Int,
    streamProvider: RNStreamProviderIfc
) : RVariable(streamNum, streamProvider) {

    /**
     *  This represents a uni-variate function of a random variable. The function will
     *  have the same stream and same underlying provider as the supplied random variable
     *
     * @param rv the random variable in the function mapping
     * @param theTransform the functional transformation using (first) to produce a double
     */
    constructor(
        rv: RVariableIfc,
        theTransform: ((f: Double) -> Double) = { f: Double -> f }
    ) : this(rv, theTransform, rv.streamNumber, rv.streamProvider)

    //TODO how to handle the case of the rv being a ConstantRV?
    init {
        require(rv !is ConstantRV ) {"A constant random variable cannot be transform"}
    }
    private val first = rv.instance(streamNum, streamProvider)
    private val transform = theTransform

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): RVariableIfc {
        return RVUFunction(first, transform, streamNum, rnStreamProvider)
    }

    override fun generate(): Double {
        return transform(first.value)
    }

}
