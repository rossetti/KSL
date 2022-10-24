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

package ksl.examples.book.chapter5

import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.statistic.Statistic


fun main() {
    val q = 30.0 // order qty
    val s = 0.25 //sales price
    val c = 0.15 // unit cost
    val u = 0.02 //salvage value
    val values = doubleArrayOf(5.0, 10.0, 40.0, 45.0, 50.0, 55.0, 60.0)
    val cdf = doubleArrayOf(0.1, 0.3, 0.6, 0.8, 0.9, 0.95, 1.0)
    val dCDF = DEmpiricalRV(values, cdf)
    val stat = Statistic("Profit")
    val n = 100.0 // sample size
    var i = 1
    while (i <= n) {
        val d = dCDF.value
        val amtSold = minOf(d, q)
        val amtLeft = maxOf(0.0, q - d)
        val g = s * amtSold + u * amtLeft - c * q
        stat.collect(g)
        i++
    }
    System.out.printf("%s \t %f %n", "Count = ", stat.count)
    System.out.printf("%s \t %f %n", "Average = ", stat.average)
    System.out.printf("%s \t %f %n", "Std. Dev. = ", stat.standardDeviation)
    System.out.printf("%s \t %f %n", "Half-width = ", stat.halfWidth)
    println((stat.confidenceLevel * 100).toString() + "% CI = " + stat.confidenceInterval)
}
