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