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
package ksl.utilities.random.robj

import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

/** Randomly selects the elements in the list according to a supplied CDF across the items
 *
 * @param <T> the type of elements in the list
 * @param elements the list of elements, must not be null
 * @param theCDF an array holding the cumulative probabilities across the elements in the list
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 */
class DEmpiricalList<T>(
    elements: List<T>,
    theCDF: DoubleArray,
    streamNumber: Int = 0,
    private val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
) : RElementIfc<T> {

    init {
        require(KSLRandom.isValidCDF(theCDF)) { "The supplied cdf array is not a valid cdf" }
        require(elements.size >= theCDF.size) { "The number of objects was less than the number of probabilities." }
    }

    private val rnStream: RNStreamIfc = streamProvider.rnStream(streamNumber)

    override val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): DEmpiricalList<T>  {
        return DEmpiricalList(elements, cdf, streamNumber, rnStreamProvider)
    }

    val elements: List<T> = ArrayList(elements)

    val cdf: DoubleArray = theCDF.copyOf()

    override val randomElement: T
        get() = KSLRandom.randomlySelect(elements, cdf, rnStream)

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(value) {
            rnStream.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(value) {
            rnStream.resetStartStreamOption = value
        }

    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
        }
}

fun main() {
    val cities = listOfNotNull("KC", "CH", "NY")
    val originSet = DEmpiricalList(cities, doubleArrayOf(0.4, 0.8, 1.0))
    for (i in 1..10) {
        println(originSet.randomElement)
    }
    println()
    val od: MutableMap<String, DEmpiricalList<String>> = HashMap()
    val kcdset = DEmpiricalList(listOfNotNull("CO", "AT", "NY"), doubleArrayOf(0.2, 0.6, 1.0))
    val chdset = DEmpiricalList(listOfNotNull("AT", "NY", "KC"), doubleArrayOf(0.2, 0.6, 1.0))
    val nydset = DEmpiricalList(listOfNotNull("AT", "KC", "CH"), doubleArrayOf(0.2, 0.6, 1.0))
    od["KC"] = kcdset
    od["CH"] = chdset
    od["NY"] = nydset
    for (i in 1..10) {
        val key = originSet.randomElement
        val rs = od[key]!!
        println(rs.randomElement)
    }
}