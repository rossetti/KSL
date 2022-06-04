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
package ksl.utilities.random.robj

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

/** Randomly selects the elements in the list according to a supplied CDF across the items
 *
 * @param <T> the type of elements in the list
 * @param elements the list of elements, must not be null
 * @param theCDF an array holding the cumulative probabilities across the elements in the list
 * @param stream the underlying random number stream to use for randomness
 */
class DEmpiricalList<T>(
    elements: List<T>,
    theCDF: DoubleArray,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RElementIfc<T> {
    init {
        require(KSLRandom.isValidCDF(theCDF)) { "The supplied cdf array is not a valid cdf" }
        require(elements.size >= theCDF.size) { "The number of objects was less than the number of probabilities." }
    }

    override var rnStream: RNStreamIfc = stream

    val elements: List<T> = ArrayList(elements)

    val cdf: DoubleArray = theCDF.copyOf()

    override val randomElement: T
        get() = KSLRandom.randomlySelect(elements, cdf, rnStream)
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