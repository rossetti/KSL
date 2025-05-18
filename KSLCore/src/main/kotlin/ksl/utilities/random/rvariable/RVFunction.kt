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
 *  This represents a bi-variate functional.
 *
 * @param theFirst the first random variable in the function mapping
 * @param theSecond the second random variable in the function mapping
 * @param theTransform the functional transformation using (first, second) to produce a double
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 */
class RVFunction private constructor (
    theFirst: RVariableIfc,
    theSecond: RVariableIfc,
    theTransform: ((f: Double, s: Double) -> Double) = { f: Double, s: Double -> f + s },
    streamNum: Int,
    streamProvider: RNStreamProviderIfc
) : RVariable(streamNum, streamProvider, null) {

    /**
     *  This represents a bi-variate functional. The stream number and provider
     *  are determined by the first random variable.
     *
     * @param theFirst the first random variable in the function mapping
     * @param theSecond the second random variable in the function mapping
     * @param theTransform the functional transformation using (first, second) to produce a double
     */
    constructor(
        theFirst: RVariableIfc,
        theSecond: RVariableIfc,
        theTransform: ((f: Double, s: Double) -> Double) = { f: Double, s: Double -> f + s },
    ) : this(theFirst, theSecond, theTransform, theFirst.streamNumber, theFirst.streamProvider)

    /**
     *  This represents a bi-variate functional. The stream number and provider
     *  are determined by the non-constant random variable.
     *
     * @param theFirst the first random variable in the function mapping
     * @param theSecond the second random variable in the function mapping
     * @param theTransform the functional transformation using (first, second) to produce a double
     */
    constructor(
        theFirst: ConstantRV,
        theSecond: RVariableIfc,
        theTransform: ((f: Double, s: Double) -> Double) = { f: Double, s: Double -> f + s },
    ) : this(theFirst, theSecond, theTransform, theSecond.streamNumber, theSecond.streamProvider)

    private val first : RVariableIfc
    private val second : RVariableIfc

    init {
        first = if (theFirst is ConstantRV){
            theFirst
        } else {
            theFirst.instance(streamNum, streamProvider)
        }
        second = if (theSecond is ConstantRV){
            theSecond
        } else {
            theSecond.instance(streamNum, streamProvider)
        }
    }

    private val transform = theTransform

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): RVariableIfc {
        return RVFunction(first, second, transform, streamNum, rnStreamProvider)
    }

    override fun generate(): Double {
        return transform(first.value, second.value)
    }

}

