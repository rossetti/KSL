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

import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.Statistic

/**
 * Example 3.11.
 * This example illustrates the simulation of a simple inspection process.
 */
fun main() {
    val itemRV = BernoulliRV(probOfSuccess = 0.15, streamNum = 2)
    val itemsPerBox = 4
    val stat = Statistic("Num Until Rejection")
    val sampleSize = 100
    for (i in 1..sampleSize) {
        val countBoxes = numUntilFirstRejection(itemRV, itemsPerBox)
        stat.collect(countBoxes)
    }
    print(String.format("%s \t %f %n", "Count = ", stat.count))
    print(String.format("%s \t %f %n", "Average = ", stat.average))
    print(String.format("%s \t %f %n", "Std. Dev. = ", stat.standardDeviation))
    print(String.format("%s \t %f %n", "Half-width = ", stat.halfWidth))
    println((stat.confidenceLevel * 100).toString() + "% CI = " + stat.confidenceInterval)
}

/**
 *  This function counts the number of boxes inspected until the first
 *  box is found that has a randomly selected item that is defective.
 */
fun numUntilFirstRejection(itemRV: BernoulliRV, itemsPerBox: Int): Double {
    require(itemsPerBox >= 1) { "There must be at least 1 item per box" }
    var count = 0.0
    do {
        count++
        // randomly generate the box
        val box = itemRV.sample(itemsPerBox)
        // randomly sample from the box
        val inspection = KSLRandom.randomlySelect(box)
        // stops if bad = 1.0 is found, continues if good = 0.0 is found
    } while (inspection != 1.0)
    return count
}
