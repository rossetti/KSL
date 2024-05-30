package ksl.examples.book.chapter5

import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.KSLDatabaseObserver

/**
 * Example 5.6
 * This code example illustrates how to perform a common random number analysis for two systems
 * by running them within the same execution frame and setting the reset start stream option to true.
 */
fun main() {

    withoutCommonRandomNumbers()
    withCommonRandomNumbers()
}

fun withoutCommonRandomNumbers(){
    val model = Model("Pallet Model Experiments")
    model.numberOfReplications = 30
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model)
    // use a database to capture the response data
    val kslDatabaseObserver = KSLDatabaseObserver(model)

    model.experimentName = "Two Workers"
    palletWorkCenter.numWorkers = 2
    model.simulate()

    model.experimentName = "Three Workers"
    palletWorkCenter.numWorkers = 3
    model.simulate()

    val responseName = palletWorkCenter.totalProcessingTime.name
    val db = kslDatabaseObserver.db

    val expNames = listOf("Two Workers", "Three Workers")
    val comparisonAnalyzer = db.multipleComparisonAnalyzerFor(expNames, responseName)
    println(comparisonAnalyzer)
    comparisonAnalyzer.writeDataAsCSVFile(KSL.createPrintWriter("INDResults.csv"))
}

fun withCommonRandomNumbers(){
    val model = Model("Pallet Model Experiments")
    model.numberOfReplications = 30
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model)
    // use a database to capture the response data
    val kslDatabaseObserver = KSLDatabaseObserver(model)

    model.resetStartStreamOption = true
    model.experimentName = "Two Workers"
    palletWorkCenter.numWorkers = 2
    model.simulate()

    model.experimentName = "Three Workers"
    palletWorkCenter.numWorkers = 3
    model.simulate()

    val responseName = palletWorkCenter.totalProcessingTime.name
    val db = kslDatabaseObserver.db

    val expNames = listOf("Two Workers", "Three Workers")
    val comparisonAnalyzer = db.multipleComparisonAnalyzerFor(expNames, responseName)
    println(comparisonAnalyzer)
    comparisonAnalyzer.writeDataAsCSVFile(KSL.createPrintWriter("CRNResults.csv"))
}