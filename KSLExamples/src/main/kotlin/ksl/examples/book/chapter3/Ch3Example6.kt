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

import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.statistic.Statistic
import kotlin.math.sqrt

/**
 * Example 3.6
 *
 * This example illustrates how to perform simple Monte-Carlo
 * integration on the sqrt(x) over the range from 1 to 4.
 */
fun main() {
    val a = 1.0
    val b = 4.0
    val ucdf = UniformRV(a, b, streamNum = 3)
    val stat = Statistic("Area Estimator")
    val n = 100 // sample size
    for (i in 1..n) {
        val x = ucdf.value
        val gx = sqrt(x)
        val y = (b - a) * gx
        stat.collect(y)
    }
    System.out.printf("True Area = %10.3f %n", 14.0 / 3.0)
    System.out.printf("Area estimate = %10.3f %n", stat.average)
    println("Confidence Interval")
    println(stat.confidenceInterval)
}