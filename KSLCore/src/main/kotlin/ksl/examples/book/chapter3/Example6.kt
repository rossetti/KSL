/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.book.chapter3

import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.randomlySelect

/**
 * This example illustrates how to use the randomlySelect() method
 * of the KSLRandom class to randomly select from a list. The extension
 * function for lists can also be used.
 */
fun main() {
    // create a list
    val strings = listOf("A", "B", "C", "D")
    // randomly pick from the list, with equal probability
    for (i in 1..5) {
        println(KSLRandom.randomlySelect(strings))
    }
    println()
    for (i in 1..5) {
        println(strings.randomlySelect())
    }
}
