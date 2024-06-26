package ksl.examples.book.chapter5

import ksl.simulation.Model
import ksl.utilities.io.dbutil.KSLDatabaseObserver

/**
 * Example 5.7
 * This code illustrates how to perform an MCB comparison of three
 * system configurations.
 */
fun main() {
    val model = Model("Pallet Model MCB")

    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model)
    // use a database to capture the response data
    val kslDatabaseObserver = KSLDatabaseObserver(model)
    // simulate the model
    model.experimentName = "One Worker"
    palletWorkCenter.numWorkers = 1
    model.resetStartStreamOption = true
    model.numberOfReplications = 30
    model.simulate()

    model.experimentName = "Two Workers"
    palletWorkCenter.numWorkers = 2
    model.simulate()

    model.experimentName = "Three Workers"
    palletWorkCenter.numWorkers = 3
    model.simulate()

    val responseName = palletWorkCenter.totalProcessingTime.name
    val db = kslDatabaseObserver.db

    val expNames = listOf("One Worker","Two Workers", "Three Workers")
    val comparisonAnalyzer = db.multipleComparisonAnalyzerFor(expNames, responseName)
    println(comparisonAnalyzer)
}
