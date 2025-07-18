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

import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 * A class to randomly select elements from a list
 *
 * @param elements the elements of the list
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 */
class DUniformList<T>(
    elements: MutableList<T> = mutableListOf(),
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
) : RList<T>(elements, streamNum, streamProvider) {

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): RElementIfc<T> {
        return DUniformList(elements, streamNum, rnStreamProvider)
    }

    override val randomElement: T
        get() {
            require(this.isNotEmpty()) { "Cannot draw a random element from an empty list!" }
            return elements[rnStream.randInt(0, elements.size - 1)]
        }

}

fun main() {
    val originList: DUniformList<String> = DUniformList()

    originList.add("KC")
    originList.add("CH")
    originList.add("NY")

    for (i in 1..10) {
        println(originList.randomElement)
    }

    println()
    val od: MutableMap<String, DUniformList<String>> = HashMap()

    val kcList: DUniformList<String> = DUniformList()

    kcList.add("CO")
    kcList.add("AT")
    kcList.add("NY")

    val chList: DUniformList<String> = DUniformList()

    chList.add("AT")
    chList.add("NY")
    chList.add("KC")

    val nyList: DUniformList<String> = DUniformList()

    nyList.add("AT")
    nyList.add("KC")
    nyList.add("CH")

    od["KC"] = kcList
    od["CH"] = chList
    od["NY"] = nyList

    for (i in 1..10) {
        val key = originList.randomElement
        val rs = od[key]!!
        println(rs.randomElement)
    }
}
