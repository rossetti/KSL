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
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import ksl.utilities.KSLArrays
import ksl.utilities.io.*
import org.jetbrains.kotlinx.dataframe.api.print

fun main() {

       printControlsAndRVParameters()

//    test2LevelDesign()

//    testFractionalDesign()

    simulateFactorialDesign()

//    testCCD()

//    testDataFrameWork()
}

fun buildModel(): Model {
    val sim = Model("MM1 Test")
    sim.numberOfReplications = 15
    sim.lengthOfReplication = 10000.0
    sim.lengthOfReplicationWarmUp = 5000.0
    val ggc = GIGcQueue(sim, 1, name = "MM1Q")
    return sim
}

fun printControlsAndRVParameters() {

    val m = buildModel()

    val controls = m.controls()
    val rvp = m.rvParameterSetter
    println("Control keys:")
    for (controlKey in controls.controlKeys()) {
        println(controlKey)
    }
    println()
    println("RV Parameters")
    println("Standard Map Representation:")
    for ((key, value) in rvp.rvParameters) {
        println("$key -> $value")
    }
    println()
    println("Flat Map Representation:")
    for ((key, value) in rvp.flatParametersAsDoubles) {
        println("$key -> $value")
    }
    println()
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
    println(fd.designPointsAsDataframe(true))
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

fun simulateFactorialDesign() {
    val fA = Factor("Server", doubleArrayOf(1.0, 2.0, 3.0))
    val fB = Factor("MeanST", doubleArrayOf(0.6, 0.7))
    val fC = Factor("MeanTBA", doubleArrayOf(1.0, 5.0))
    val factors = mapOf(
        fA to "MM1Q.numServers",
        fB to "MM1_Test:ServiceTime.mean",
        fC to "MM1_Test:TBA.mean"
    )
    val m = buildModel()

    val fd = FactorialDesign(factors.keys)
    val de = DesignedExperiment("FactorDesignTest", m, factors, fd)

    println("Design points being simulated")
    val df = fd.designPointsAsDataframe()

    df.print(rowsLimit = 36)
    println()
    de.simulateAll(numRepsPerDesignPoint = 3)
    println("Simulation of the design is completed")

//    println("Design point info")
//    val dpi = de.replicatedDesignPointInfo()
//    dpi.print(rowsLimit = 36)

    println()
    println("Replicated design points")
    val df2 = de.replicatedDesignPointsAsDataFrame()

    df2.print(rowsLimit = 36)
    println()

    println("Responses as a data frame")
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

fun test2LevelDesign() {
    val design = TwoLevelFactorialDesign(
        setOf(
            TwoLevelFactor("A", 5.0, 15.0),
            TwoLevelFactor("B", 2.0, 11.0),
            TwoLevelFactor("C", 6.0, 10.0),
            TwoLevelFactor("D", 3.0, 9.0),
        )
    )
    val fdf = design.designPointsAsDataframe()
    println("Full design points")
    fdf.print(rowsLimit = 36)
    println("Coded full design points")
    design.designPointsAsDataframe(true).print(rowsLimit = 36)
    println()

    // This is a resolution IV 2^(4-1) design
    // This design can be found here: https://www.itl.nist.gov/div898/handbook/pri/section3/eqns/2to4m1.txt
    val itr = design.halfFractionIterator()
    val dPoints = itr.asSequence().toList()
    val df = dPoints.toDataFrame(coded = true)
    println("Positive half-fraction")
    df.print(rowsLimit = 36)

    println("The same design using a fractional iterator")
    design.fractionalIterator(setOf(setOf(1, 2, 3, 4)))
        .asSequence().toList()
        .toDataFrame(true).print(rowsLimit = 36)

}

fun testFractionalDesign() {
    val design = TwoLevelFactorialDesign(
        setOf(
            TwoLevelFactor("A", 5.0, 15.0),
            TwoLevelFactor("B", 2.0, 11.0),
            TwoLevelFactor("C", 6.0, 10.0),
            TwoLevelFactor("D", 3.0, 9.0),
            TwoLevelFactor("E", 4.0, 16.0)
        )
    )
    val fdf = design.designPointsAsDataframe()
    println("Full design points")
    fdf.print(rowsLimit = 36)
    println("Coded full design points")
    design.designPointsAsDataframe(true).print(rowsLimit = 36)
    println()
    // half-fraction
    val hitr = design.halfFractionIterator()
    println("Positive half-fraction")
    hitr.asSequence().toList().toDataFrame(coded = true).print(rowsLimit = 36)
    println()

    // This is a resolution III 2^(5-2) design
    // This design can be found here: https://www.itl.nist.gov/div898/handbook/pri/section3/eqns/2to5m2.txt
    val relation = setOf(setOf(1, 2, 4), setOf(1, 3, 5), setOf(2, 3, 4, 5))
    val itr = design.fractionalIterator(relation)
    println("number of factors = ${itr.numFactors}")
    println("number of points = ${itr.numPoints}")
    println("fraction (p) = ${itr.fraction}")
    val dPoints = itr.asSequence().toList()
    val df = dPoints.toDataFrame(coded = true)
    println("Fractional design points")
    df.print(rowsLimit = 36)
}

fun testCCD(){
    val factors = setOf(
        TwoLevelFactor("A", 5.0, 15.0),
        TwoLevelFactor("B", 2.0, 11.0),
        TwoLevelFactor("C", 6.0, 10.0),
    )
    val fd = TwoLevelFactorialDesign(factors)
    val itr = fd.designIterator()
    val ccd = CentralCompositeDesign(itr, axialSpacing = 1.682 )
    val ucdf = ccd.designPointsAsDataframe()
    ucdf.print(rowsLimit = 36)
    println()
    val cdf = ccd.designPointsAsDataframe(coded = true)
    cdf.print(rowsLimit = 36)
}

fun testDataFrameWork(){
    val design = TwoLevelFactorialDesign(
        setOf(
            TwoLevelFactor("A", 5.0, 15.0),
            TwoLevelFactor("B", 2.0, 11.0),
            TwoLevelFactor("C", 6.0, 10.0),
//            TwoLevelFactor("D", 3.0, 9.0),
        )
    )
    val fd = design.designPointsAsDataframe(coded = true)
    fd.print(rowsLimit = 36)

//    val cA = fd.getColumn("A")
//    val cB = fd.getColumn("B")
//    val cC = fd.getColumn("C")
//    val mfd = fd.multiply(setOf(cA, cB, cC))
//    val mfd = fd.multiplyColumns(listOf("A", "B", "C"))
   // val mfd = fd.multiply("A", "B")
//    mfd.print(rowsLimit = 36)

//    val lm = design.linearModel.specifyAllTerms()
    val lm = design.linearModel(type = LinearModel.Type.AllTerms)
    val lmDF = fd.addColumnsFor(lm)
    lmDF.print(rowsLimit = 36)
}

