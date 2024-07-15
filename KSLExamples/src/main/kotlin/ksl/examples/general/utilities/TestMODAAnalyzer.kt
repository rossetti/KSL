package ksl.examples.general.utilities

import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.simulation.Model
import ksl.utilities.io.dbutil.KSLDatabaseObserver

fun main(){
    val model = Model("MODA Analyzer Testing")
    model.numberOfReplications = 10
    model.experimentName = "Two Workers"
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model)

    // demonstrate capturing data to database with an observer
    val kslDatabaseObserver = KSLDatabaseObserver(model)
    val db = kslDatabaseObserver.db
    // simulate the model
    model.simulate()

    model.experimentName = "Three Workers"
    palletWorkCenter.numWorkers = 3
    model.simulate()

    val mcb = db.multipleComparisonAnalyzerFor(
        listOf("Two Workers", "Three Workers"), palletWorkCenter.totalProcessingTime.name)
    println(mcb)
}