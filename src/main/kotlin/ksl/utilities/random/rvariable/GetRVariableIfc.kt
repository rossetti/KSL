/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc


/**
 * An interface for getting random variables
 */
interface GetRVariableIfc {
    /**
     * @param stream the stream to use
     * @return a random variable
     */
    fun randomVariable(stream: RNStreamIfc): RVariableIfc

    /**
     * @param streamNum the stream number to use
     * @return a random variable
     */
    fun randomVariable(streamNum: Int): RVariableIfc {
        return randomVariable(KSLRandom.rnStream(streamNum))
    }

    /**
     * @return an instance of the random variable based on the next stream
     */
    val randomVariable: RVariableIfc
        get() = randomVariable(KSLRandom.nextRNStream())
}