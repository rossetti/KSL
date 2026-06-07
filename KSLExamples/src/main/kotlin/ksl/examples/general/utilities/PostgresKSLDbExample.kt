package ksl.examples.general.utilities

import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.simulation.Model
import ksl.utilities.io.asMarkDownTable
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import org.jetbrains.kotlinx.dataframe.io.toStandaloneHtml

fun main() {
    val model = Model("PalletProcessing_Postgres")
    model.numberOfReplications = 10
    model.experimentName = "Two Workers"
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model)

    val kslDb = KSLDatabase.createPostgresSQLKSLDatabase(
        dbName = "test", user = "test", pWord = "test")
    val pgDbObserver = KSLDatabaseObserver(model, db = kslDb)

    // simulate the model
    model.simulate()

    // demonstrate that reports can have a specified confidence level
    val sr = model.simulationReporter
    sr.printHalfWidthSummaryReport(confLevel = .99)

    model.experimentName = "Three Workers"
    palletWorkCenter.numWorkers = 3
    model.simulate()

    val dataFrame = pgDbObserver.db.acrossReplicationViewStatistics

    println(dataFrame)
    println()
    println(dataFrame.asMarkDownTable())
    dataFrame.toStandaloneHtml().openInBrowser()

//    println("Exporting database to Excel")
//    println(pgDbObserver.db.outputDirectory)
//    pgDbObserver.db.exportToExcel()
}