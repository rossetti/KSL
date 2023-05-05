package ksl.examples.book.chapter5

import ksl.simulation.Model
import ksl.utilities.io.dbutil.KSLDatabaseObserver

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
}