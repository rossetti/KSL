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
import ksl.utilities.random.rng.RNStreamProviderIfc


/**
 * An interface for getting random variables
 */
interface GetRVariableIfc {

    /**
     *  Promises to return a random variable that uses the
     *  supplied stream number using the supplied stream provider
     *  @param streamNumber the number of the stream requested from the provider
     *  @param streamProvider the provider of random number streams
     */
    fun randomVariable(
        streamNumber: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
    ) : RVariableIfc

//    /**
//     * @param stream the stream to use
//     * @return a random variable
//     */
//    fun randomVariable(stream: RNStreamIfc): RVariableIfc
//
//    /**
//     * @param streamNum the stream number to use
//     * @return a random variable
//     */
//    fun randomVariable(streamNum: Int): RVariableIfc {
//        return randomVariable(KSLRandom.rnStream(streamNum))
//    }
//
//    /**
//     * @return an instance of the random variable based on the next stream
//     */
//    val randomVariable: RVariableIfc
//        get() = randomVariable(KSLRandom.nextRNStream())
}