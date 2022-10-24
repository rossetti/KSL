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

package ksl.examples.book.chapter4

import ksl.utilities.random.rvariable.BinomialRV
import ksl.utilities.statistic.IntegerFrequency

/**
 * This example illustrates how to create an instance of an IntegerFrequency
 * class in order to tabulate the frequency of occurrence of integers within
 * a sample.
 */
fun main() {
    val f = IntegerFrequency("Frequency Demo")
    val bn = BinomialRV(0.5, 100)
    val sample = bn.sample(10000)
    f.collect(sample)
    println(f)
}
