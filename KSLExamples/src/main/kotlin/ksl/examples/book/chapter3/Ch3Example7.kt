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

import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.Statistic

fun main() {
    val rv = NormalRV(10.0, 16.0)
    val estimateX = Statistic("Estimated X")
    val estOfProb = Statistic("Pr(X>8)")
    val r = StatisticReporter(mutableListOf(estOfProb, estimateX))
    val n0 = 20 // sample size
    for (i in 1..n0) {
        val x = rv.value
        estimateX.collect(x)
        estOfProb.collect(x > 8)
    }
    println(r.halfWidthSummaryReport())
    val desiredHW = 0.5
    val s0 = estimateX.standardDeviation
    val level = 0.99
    val n = Statistic.estimateSampleSize(
        desiredHW = desiredHW,
        stdDev = s0,
        level = level
    )
    println("Sample Size Determination")
    println("desiredHW = $desiredHW")
    println("stdDev = $s0")
    println("Level = $level")
    println("recommended sample size = $n")
    println()
    val m = Statistic.estimateProportionSampleSize(
        desiredHW = 0.05,
        pEst = estOfProb.average,
        level = 0.95
    )
    println("Recommended sample size for proportion = $m")
}
