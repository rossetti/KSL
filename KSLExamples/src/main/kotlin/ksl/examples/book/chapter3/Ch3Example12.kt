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

import ksl.utilities.Interval
import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.statistic.Statistic

/**
 * Example 3.12
 * This example illustrates how to simulate a simple stochastic activity network.
 *
 */
fun main() {
    val activityRVs = mapOf<String, TriangularRV>(
        "A" to TriangularRV(2.0, 5.0, 8.0),
        "B" to TriangularRV(6.0, 9.0, 12.0),
        "C" to TriangularRV(6.0, 7.0, 8.0),
        "D" to TriangularRV(1.0, 4.0, 7.0),
        "E" to TriangularRV(7.0, 8.0, 9.0),
        "F" to TriangularRV(5.0, 14.0, 17.0),
        "G" to TriangularRV(3.0, 12.0, 21.0),
        "H" to TriangularRV(3.0, 6.0, 9.0),
        "I" to TriangularRV(5.0, 8.0, 11.0),
    )
    val paths = mutableListOf<List<String>>(
        listOf("A", "B", "D", "F", "H", "I"),
        listOf("A", "E", "F", "H", "I"),
        listOf("A", "C", "D", "F", "H", "I"),
        listOf("A", "B", "C", "G", "H", "I"),
    )

    val timeToCompleteStat = Statistic("Time to Completion")
    val probLT50Days = Statistic("P(T<=50)")
    val probWith42and48Days = Statistic("P(42<=T<=48)")
    val interval = Interval(42, 48)
    val sampleSize = 1000
    for (i in 1..sampleSize) {
        val a = generateActivityTimes(activityRVs)
        val t = timeToCompletion(a, paths)
        timeToCompleteStat.collect(t)
        probLT50Days.collect(t <= 50)
        probWith42and48Days.collect(interval.contains(t))
    }
    val sr = StatisticReporter(mutableListOf(timeToCompleteStat, probLT50Days, probWith42and48Days))
    println(sr.halfWidthSummaryReportAsMarkDown())
}

fun generateActivityTimes(activities: Map<String, TriangularRV>) : Map<String, Double> {
    val map = mutableMapOf<String, Double>()
    for ((name, rv) in activities){
        map[name] = rv.value
    }
    return map
}

fun timeToCompletion(activityTimes: Map<String, Double>, paths: List<List<String>>): Double {
    val times = mutableListOf<Double>()
    for (path in paths) {
        var pathTime = 0.0
        for (activity in path) {
            pathTime = pathTime + activityTimes[activity]!!
        }
        times.add(pathTime)
    }
    return times.max()
}


