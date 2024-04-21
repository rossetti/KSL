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

import ksl.controls.experiments.*
import ksl.simulation.Model
import ksl.utilities.KSLArrays
import ksl.utilities.io.print
import org.jetbrains.kotlinx.dataframe.api.*

fun main(){

 //   printControlsAndRVParameters()
    simulateFactorialDesign2()
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
    val fd = FactorialExperiment("FactorDesignTest", m, factors, 3)

    println("Design points being simulated")
    val df = fd.replicatedDesignPointsAsDataFrame()

    df.print(rowsLimit = 36)
    println()
    fd.simulateDesign()
    println("Simulation of the design is completed")
    val df2 = fd.replicatedDesignPointsAsDataFrame()

    df2.print(rowsLimit = 36)
    println()

    val df3 = fd.responseAsDataFrame("System Time")
    df3.print(rowsLimit = 36)

    println()
    val df4 = fd.replicatedDesignPointsWithResponse("System Time")
    df4.print(rowsLimit = 36)

    println()
    val df5 = fd.replicatedDesignPointsWithResponses()
    df5.print(rowsLimit = 36)

    println()
    val df6 = fd.replicatedCodedDesignPointsWithResponses()
    df6.print(rowsLimit = 36)

    fd.resultsToCSV()

//    fd.kslDb.withinRepViewStatistics.schema().print()
//    println()
//    val dm = fd.kslDb.withinRepViewStatistics.filter { "stat_name"<String>().equals("System Time") }

  //  val dm = fd.kslDb.withinRepViewStatistics.drop { "stat_name"<String>().equals("System Time") }
//    dm.print(rowsLimit = 50)

//    for(sr in fd.simulationRuns){
//        KSL.out.println(sr.toJson())
//        KSL.out.println()
//        val r = sr.statisticalReporter()
//        println(r.halfWidthSummaryReport(title = sr.name))
//        println()
//    }

    // This second set of runs will clear the first set from the database
 //   fd.simulateDesign()
}

fun testFD() {
    val f1 = Factor("A", doubleArrayOf(1.0, 2.0, 3.0, 4.0))
    val f2 = Factor("B", doubleArrayOf(5.0, 9.0))
    val factors = setOf(f1, f2)
    val fd = FactorialDesign(factors)
    println(fd)
    println()
    println("Factorial Design as Data Frame")
    println(fd.designPointsAsDataframe())
    println()
    println("Coded Factorial Design as Data Frame")
    println(fd.codedDesignPointsAsDataframe())
    println()
    val array = fd.designPointsTo2DArray()
    array.print()
    println()
    val kd = FactorialDesign.twoToKDesign(setOf("A", "B", "C", "D"))
    println("Factorial Design as Data Frame")
    println(kd.designPointsAsDataframe())
    println()
}

fun testFactor() {
    val f = Factor("A", doubleArrayOf(5.0, 10.0, 15.0, 20.0, 25.0))
    println(f)
    val g = Factor("G", 5..25 step 5)
    println(g)
    val x = Factor("X")
    println(x)
}

fun testCP() {
    val a = setOf(1, 2)
    val b = setOf(3, 4)
    val c = setOf(5)
    val d = setOf(6, 7, 8)

    val abcd = KSLArrays.cartesianProduct(a, b, c, d)

    println(abcd)
    println()

    val s1 = setOf(1.0, 2.0)
    val s2 = setOf(3.0, 4.0)
    val s3 = setOf(5.0)
    val s4 = setOf(6.0, 7.0, 8.0)
    val s1s2s3s4 = KSLArrays.cartesianProductOfDoubles(s1, s2, s3, s4)
    println()
    for ((i, s) in s1s2s3s4.withIndex()) {
        println("The element at index $i is: ${s1s2s3s4[i].joinToString()}")
    }
}

fun testCPRow() {
    val a = intArrayOf(1, 2)
    val b = intArrayOf(3, 4)
    val c = intArrayOf(5)
    val d = intArrayOf(6, 7, 8)
    val n = a.size * b.size * c.size * d.size
    val array = arrayOf(a, b, c, d)

    println()
    val index = 4
    val r = KSLArrays.cartesianProductRow(array, index)
    println("The element at index $index is: ${r.joinToString()}")

    println()
    println("Elements via indexed rows:")
    for (i in 0..<n) {
        val result = KSLArrays.cartesianProductRow(array, i)
        println("The element at index $i is: ${result.joinToString()}")
    }
    println()
}

fun simulateFactorialDesign2(){
    val fA = Factor("Server", doubleArrayOf(1.0, 2.0, 3.0))
    val fB = Factor("MeanST", doubleArrayOf(0.6, 0.7))
    val fC = Factor("MeanTBA", doubleArrayOf(1.0, 5.0))
    val factors = mapOf(
        fA to "MM1Q.numServers",
        fB to "MM1_Test:ServiceTime_PARAM_mean",
        fC to "MM1_Test:TBA_PARAM_mean"
    )
    val m = buildModel()

    val fd = FactorialDesignV2(factors.keys)
    val de = DesignedExperiment("FactorDesignTest", m, factors, fd)

    println("Design points being simulated")
    val df = fd.designPointsAsDataframe()

    df.print(rowsLimit = 36)
    println()
    de.simulate(numRepsPerDesignPoint = 3)
    println("Simulation of the design is completed")
    val df2 = de.replicatedDesignPointsAsDataFrame()

    df2.print(rowsLimit = 36)
    println()

    val df3 = de.responseAsDataFrame("System Time")
    df3.print(rowsLimit = 36)

    println()
    val df4 = de.replicatedDesignPointsWithResponse("System Time")
    df4.print(rowsLimit = 36)

    println()
    val df5 = de.replicatedDesignPointsWithResponses()
    df5.print(rowsLimit = 36)

    println()
    val df6 = de.replicatedDesignPointsWithResponses(coded = true)
    df6.print(rowsLimit = 36)

    de.resultsToCSV()
}
