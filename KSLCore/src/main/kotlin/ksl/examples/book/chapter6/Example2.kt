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

package ksl.examples.book.chapter6

import ksl.simulation.Model

/**
 * This example illustrates the simulation of a Poisson process
 * using the KSL Model class and a constructed ModelElement
 * (SimplePoissonProcess).
 */
fun main() {
    example2V1()

//    example2V2()
}

fun example2V1(){
    val s = Model("Simple PP")
    SimplePoissonProcess(s.model)
    s.lengthOfReplication = 20.0
    s.numberOfReplications = 50
    s.simulate()
    println()
    val r = s.simulationReporter
    r.printAcrossReplicationSummaryStatistics()
    println("Done!")
}

fun example2V2(){
    val s = Model("Simple PP V2")
    SimplePoissonProcessV2(s.model)
    s.lengthOfReplication = 20.0
    s.numberOfReplications = 50
    s.simulate()
    println()
    val r = s.simulationReporter
    r.printAcrossReplicationSummaryStatistics()
    println("Done!")
}