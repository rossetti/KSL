/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.book.appendixD

import ksl.controls.experiments.*
import ksl.examples.book.chapter7.RQInventorySystem
import ksl.simulation.Model
import ksl.utilities.io.addColumnsFor
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.jetbrains.kotlinx.dataframe.api.print

/**
 *  This file illustrates the construction of experimental designs and
 *  how to run designed experiments.
 */
fun main() {
//    demoFactorialDesignCreation()

//    demoTwoLevelFactorialDesignCreation()

//    demoFractionalDesignCreation()

//    demoCentralCompositeDesignCreation()

//    demoSimulationOfDesignedExperiment()

    demoResponseSurfaceExperiment()
}

/**
 *  This function illustrates how to create a factorial design
 *  and how to view the design.  It does not run the design.
 */
fun demoFactorialDesignCreation(){
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
}

/**
 *  This function demonstrates how to make a factorial design
 *  that has 4 factors each with 2 levels. The design can
 *  be realized in uncoded and coded form.
 */
fun demoTwoLevelFactorialDesignCreation(){
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
}

/**
 *   This function illustrates how to create a central composite
 *   design for a situation with 3 factors, each have 2 levels.
 *   First the two-level factorial design is constructed,
 *   then the central composite design is made from the two-level
 *   factorial design by augmenting with the axial spacing.
 */
fun demoCentralCompositeDesignCreation(){
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

/**
 *  This function illustrates how to create fractional factorial designs.
 *  First create the full factorial design and then get iterators based
 *  on the fraction of design points to simulate. The halfFractionIterator()
 *  allows for the specification of the positive or negative half-fraction of
 *  the design points. In addition, the user can specify the design
 *  relation associated with the fractional design and get an iterator
 *  that will iterate through the related design points.
 */
fun demoFractionalDesignCreation(){
    // create the full design
    val design = TwoLevelFactorialDesign(
        setOf(
            TwoLevelFactor("A", 5.0, 15.0),
            TwoLevelFactor("B", 2.0, 11.0),
            TwoLevelFactor("C", 6.0, 10.0),
            TwoLevelFactor("D", 3.0, 9.0),
            TwoLevelFactor("E", 4.0, 16.0)
        )
    )
    println("Positive half-fraction")
    val hitr = design.halfFractionIterator()
    // convert iterator to data frame for display
    hitr.asSequence().toList().toDataFrame(coded = true).print(rowsLimit = 36)
    println()
    // This is a resolution III 2^(5-2) design
    // This design can be found here: https://www.itl.nist.gov/div898/handbook/pri/section3/eqns/2to5m2.txt
    // Specify a design relation as a set of sets.
    // The sets are the columns of the design that define the associated generator
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

/**
 * This function illustrates the creation of a 3 factor experimental
 * design.  The factors are mapped to controls and random variable
 * parameters. After the factorial design and the model are created
 * the DesignedExperiment class is used to simulate all the design points.
 * The example then illustrates various approaches to accessing the design
 * and the results associated with the design
 */
fun demoSimulationOfDesignedExperiment(){
    val fA = Factor("Server", doubleArrayOf(1.0, 2.0, 3.0))
    val fB = Factor("MeanST", doubleArrayOf(0.6, 0.7))
    val fC = Factor("MeanTBA", doubleArrayOf(1.0, 5.0))
    val factors = mapOf(
        fA to "MM1Q.numServers",
        fB to "MM1_Test:ServiceTime.mean",
        fC to "MM1_Test:TBA.mean"
    )
    val m = Model("DesignedExperimentDemo")
    m.numberOfReplications = 15
    m.lengthOfReplication = 10000.0
    m.lengthOfReplicationWarmUp = 5000.0
    val ggc = GIGcQueue(m, 1, name = "MM1Q")

    val fd = FactorialDesign(factors.keys)
    val de = DesignedExperiment("FactorDesignTest", m, factors, fd)

    println("Design points being simulated")
    fd.designPointsAsDataframe().print(rowsLimit = 36)
    println()
    de.simulateAll(numRepsPerDesignPoint = 3)
    println("Simulation of the design is completed")
    de.resultsToCSV()

    println()
    println("Replicated design points")
    de.replicatedDesignPointsAsDataFrame().print(rowsLimit = 36)
    println()
    println("Responses as a data frame")
    de.responseAsDataFrame("System Time").print(rowsLimit = 36)
    println()
    de.replicatedDesignPointsWithResponse("System Time").print(rowsLimit = 36)
    println()
    de.replicatedDesignPointsWithResponses().print(rowsLimit = 36)
    println()
    de.replicatedDesignPointsWithResponses(coded = true).print(rowsLimit = 36)
}

fun demoResponseSurfaceExperiment(){
    val m = Model("ResponseSurfaceDemo")
    val rqModel = RQInventorySystem(m, name = "RQInventory")
    rqModel.costPerOrder = 0.15 //$ per order
    rqModel.unitHoldingCost = 0.25 //$ per unit per month
    rqModel.unitBackorderCost = 1.75 //$ per unit per month
    rqModel.initialReorderPoint = 2
    rqModel.initialReorderQty = 3
    rqModel.initialOnHand = rqModel.initialReorderPoint + rqModel.initialReorderQty
    rqModel.demandGenerator.initialTimeBtwEvents = ExponentialRV(1.0 / 3.6)
    rqModel.leadTime.initialRandomSource = ConstantRV(0.5)
    m.lengthOfReplication = 72.0
    m.lengthOfReplicationWarmUp = 12.0
    m.numberOfReplications = 30

    val r = TwoLevelFactor("ReorderLevel", low = 1.0, high = 5.0)
    println(r)
    val q = TwoLevelFactor("ReorderQty", low = 1.0, high = 7.0)
    println(q)
    println()
    val design = TwoLevelFactorialDesign(setOf(r, q))
    println("Design points being simulated")
    val df = design.designPointsAsDataframe()
    df.print(rowsLimit = 36)
    val settings = mapOf(
        r to "RQInventory:Item.initialReorderPoint",
        q to "RQInventory:Item.initialReorderQty",
    )
    val de = DesignedExperiment("R-Q Inventory Experiment", m, settings, design)
    de.simulateAll(numRepsPerDesignPoint = 20)
    println("Simulation of the design is completed")
    println()
    val resultsDf = de.replicatedDesignPointsWithResponse("RQInventory:Item:TotalCost", coded = true)
    resultsDf.print(rowsLimit = 80)
    println()
    val lm = design.linearModel(type = LinearModel.Type.AllTerms)
    println(lm.asString())
    println()
    val lmDF = resultsDf.addColumnsFor(lm)
    lmDF.print(rowsLimit = 80)
    val regressionResults = de.regressionResults("RQInventory:Item:TotalCost", lm)
    println()
    println(regressionResults)
    regressionResults.showResultsInBrowser()
}