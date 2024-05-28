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

import ksl.utilities.random.rvariable.DUniformRV
import ksl.utilities.io.StatisticReporter
import ksl.utilities.statistic.Statistic

/**
 * Example 3.9
 * This example illustrates how to simulate the dice game "craps".
 * The example uses discrete random variables, statistics, and logic
 * to replicate the game outcomes. Statistics on the probability
 * of winning are reported.
 */
fun main() {
    val d1 = DUniformRV(1, 6)
    val d2 = DUniformRV(1, 6)
    val probOfWinning = Statistic("Prob of winning")
    val numTosses = Statistic("Number of Toss Statistics")
    val numGames = 5000
    for (k in 1..numGames) {
        var winner = false
        val point = d1.value.toInt() + d2.value.toInt()
        var numberoftoss = 1
        if (point == 7 || point == 11) {
            // automatic winner
            winner = true
        } else if (point == 2 || point == 3 || point == 12) {
            // automatic loser
            winner = false
        } else { // now must roll to get point
            var continueRolling = true
            while (continueRolling) {
                // increment number of tosses
                numberoftoss++
                // make next roll
                val nextRoll = d1.value.toInt() + d2.value.toInt()
                if (nextRoll == point) {
                    // hit the point, stop rolling
                    winner = true
                    continueRolling = false
                } else if (nextRoll == 7) {
                    // crapped out, stop rolling
                    winner = false
                    continueRolling = false
                }
            }
        }
        probOfWinning.collect(winner)
        numTosses.collect(numberoftoss.toDouble())
    }
    val reporter = StatisticReporter()
    reporter.addStatistic(probOfWinning)
    reporter.addStatistic(numTosses)
    println(reporter.halfWidthSummaryReport())
}
