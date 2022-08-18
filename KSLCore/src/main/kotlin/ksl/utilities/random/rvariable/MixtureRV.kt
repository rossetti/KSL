/*
 * Copyright (c) 2019. Manuel D. Rossetti, rossetti@uark.edu
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
 * @param list   a list holding the random variables to select from
 * @param cdf    the cumulative probability associated with each element of the list
 * @param stream the source of the randomness
 */
class MixtureRV(
    list: List<RVariableIfc>,
    cdf: DoubleArray,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {

    val cdf = cdf.copyOf()
        get() = field.copyOf()

    private val myRVList = list

    init {
        require(KSLRandom.isValidCDF(cdf)) { "The cdf was not a valid CDF" }
    }

    /**
     * @param list      a list holding the random variables to select from
     * @param cdf       the cumulative probability associated with each element of the list
     * @param streamNum the stream number
     */
    constructor(list: List<RVariableIfc>, cdf: DoubleArray, streamNum: Int) :
            this(list, cdf, KSLRandom.rnStream(streamNum))

    override fun generate(): Double {
        return KSLRandom.randomlySelect(myRVList, cdf, rnStream).value
    }

    override fun instance(stream: RNStreamIfc): MixtureRV {
        val list: List<RVariableIfc> = ArrayList(myRVList)
        return MixtureRV(list, cdf, stream)
    }
}