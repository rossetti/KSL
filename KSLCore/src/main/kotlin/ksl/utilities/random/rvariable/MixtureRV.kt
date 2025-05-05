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
 * @param list   a list holding the random variables to select from
 * @param cdf    the cumulative probability associated with each element of the list
 * @param stream the source of the randomness
 */
class MixtureRV(
    list: List<RVariableIfc>,
    cdf: DoubleArray,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariable(streamProvider, name) {

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
    constructor(
        list: List<RVariableIfc>,
        cdf: DoubleArray,
        streamNum: Int,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(list, cdf, streamProvider, name){
        rnStream = streamProvider.rnStream(streamNum)
    }

    override fun generate(): Double {
        return KSLRandom.randomlySelect(myRVList, cdf, rnStream).value
    }

    override fun instance(streamNum: Int): MixtureRV {
        val list: List<RVariableIfc> = ArrayList(myRVList)
        return MixtureRV(list, cdf, streamNum, streamProvider, name)
    }
}