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

package ksl.examples.general.running

import ksl.controls.experiments.Factor
import ksl.controls.experiments.FactorialDOE
import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.KSLDatabaseObserver

fun main(){

 //   printControlsAndRVParameters()
    simulateFactorialDesign()
}

fun buildModel() : Model {
    val sim = Model("MM1 Test")
    sim.numberOfReplications = 15
    sim.lengthOfReplication = 10000.0
    sim.lengthOfReplicationWarmUp = 5000.0
    val ggc = GIGcQueue(sim, 1, name = "MM1Q")
    return sim
}

fun printControlsAndRVParameters(){

    val m = buildModel()

    val controls  = m.controls()
    val rvp = m.rvParameterSetter

    println("Controls")
    println(controls)
    println()
    println("RV Parameters")
    println(rvp)
}

fun simulateFactorialDesign(){
    val fA = Factor("Server", doubleArrayOf(1.0, 2.0, 3.0))
    val fB = Factor("MeanST", doubleArrayOf(0.6, 0.7))
    val fC = Factor("MeanTBA", doubleArrayOf(1.0, 5.0))
    val factors = mapOf(
        fA to "MM1Q.numServers",
        fB to "MM1_Test:ServiceTime_PARAM_mean",
        fC to "MM1_Test:TBA_PARAM_mean"
    )
    val m = buildModel()
    val kslDatabaseObserver = KSLDatabaseObserver(m)
    val fd = FactorialDOE(m, factors, 3)
    fd.simulateDesign()

    for(sr in fd.simulationRuns){
        KSL.out.println(sr.toJson())
        KSL.out.println()
        val r = sr.statisticalReporter()
        println(r.halfWidthSummaryReport(title = sr.name))
        println()
    }
}