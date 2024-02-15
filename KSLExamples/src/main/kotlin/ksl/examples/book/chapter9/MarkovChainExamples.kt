/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.book.chapter9

import ksl.utilities.io.write
import ksl.utilities.random.markovchain.DMarkovChain
import ksl.utilities.statistic.IntegerFrequency

fun main(){

  //  mc1()
    mc2()

}
fun mc1(){

    val p = arrayOf(
        doubleArrayOf(0.3, 0.1, 0.6),
        doubleArrayOf(0.4, 0.4, 0.2),
        doubleArrayOf(0.1, 0.7, 0.2)
    )

    val mc = DMarkovChain(1, p)
    val f = IntegerFrequency()

    for (i in 1..100000) {
        f.collect(mc.nextState())
    }
    println("True Steady State Distribution")
    println("P{X=1} = " + 238.0 / 854.0)
    println("P{X=2} = " + 350.0 / 854.0)
    println("P{X=3} = " + 266.0 / 854.0)
    println()
    println("Observed Steady State Distribution")
    println(f)
}

fun mc2(){
    val p = arrayOf(
        doubleArrayOf(0.0,0.5, 0.0, 0.5),
        doubleArrayOf(0.5, 0.0, 0.5, 0.0),
        doubleArrayOf(0.0, 0.5, 0.0, 0.5),
        doubleArrayOf(0.5, 0.0, 0.5, 0.0)
    )
    p.write()

    val mc = DMarkovChain(1, p)
    println()
    mc.transCDF.write()
    val f = IntegerFrequency()

    for (i in 1..100000) {
        f.collect(mc.nextState())
    }
    println("Observed Steady State Distribution")
    println(f)
}