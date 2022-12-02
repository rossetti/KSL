package ksl.examples.book.chapter5

import ksl.observers.ReplicationDataCollector
import ksl.observers.ResponseTrace
import ksl.simulation.Model
import ksl.utilities.io.dbutil.KSLDatabaseObserver

fun main() {
    val model = Model("Pallet Processing", autoCSVReports = true)
    model.numberOfReplications = 10
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model)

    // demonstrate how to capture a trace of a response variable
    val trace = ResponseTrace(palletWorkCenter.numInSystem)

    // demonstrate capture of replication data for specific response variables
    val repData = ReplicationDataCollector(model)
    repData.addResponse(palletWorkCenter.totalProcessingTime)
    repData.addResponse(palletWorkCenter.probOfOverTime)

    // demonstrate capturing data to database with an observer
    val kslDatabaseObserver = KSLDatabaseObserver(model)

    // simulate the model
    model.simulate()

    // demonstrate that reports can have specified confidence level
    val sr = model.simulationReporter
    sr.printHalfWidthSummaryReport(confLevel = .99)

    // show that report can be written to MarkDown as a table in the output directory
    val out = model.outputDirectory.createPrintWriter("hwSummary.md")
    sr.writeHalfWidthSummaryReportAsMarkDown(out)

    println()

    //output the collected replication data to prove it was captured
    println(repData)

    // use the database to create a Kotlin DataFrame
    val dataFrame = kslDatabaseObserver.db.acrossReplicationViewStatistics
    println(dataFrame)

}