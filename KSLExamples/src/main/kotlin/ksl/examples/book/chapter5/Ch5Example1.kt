package ksl.examples.book.chapter5

import ksl.observers.ExperimentDataCollector
import ksl.observers.ReplicationDataCollector
import ksl.observers.ResponseTrace
import ksl.observers.ResponseTraceCSV
import ksl.simulation.Model
import ksl.utilities.io.asMarkDownTable
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import org.jetbrains.kotlinx.dataframe.api.describe

/**
 *  Example 5.1
 *  This example models the unloading of pallets for processing at a workcenter.
 *  The key aspects of this example include
 *   - automatically capturing results to csv files
 *   - capturing replication data via the ReplicationDataCollector class
 *   - tracing a response variable via the ResponseTrace class
 *   - using a SimulationReporter class to output markdown formatted output
 *   - capturing the simulation results within a database and illustrating how to extract data
 */
fun main() {
    val model = Model("Pallet Processing", autoCSVReports = true)
    model.numberOfReplications = 10
    model.experimentName = "Two Workers"
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model)

    // demonstrate how to capture a trace of a response variable
    val trace = ResponseTrace(palletWorkCenter.numInSystem)

    // demonstrate capture of replication data for specific response variables
    val repData = ReplicationDataCollector(model)
    repData.addResponse(palletWorkCenter.totalProcessingTime)
    repData.addResponse(palletWorkCenter.probOfOverTime)

    val expDataCollector = ExperimentDataCollector(model)

    // demonstrate capturing data to database with an observer
    val kslDatabaseObserver = KSLDatabaseObserver(model)
//    val derby = KSLDatabaseObserver.createDerbyKSLDatabaseObserver(model)

    // simulate the model
    model.simulate()

    // demonstrate that reports can have specified confidence level
    val sr = model.simulationReporter
    sr.printHalfWidthSummaryReport(confLevel = .99)

    // show that report can be written to MarkDown as a table in the output directory
    var out = model.outputDirectory.createPrintWriter("hwSummary.md")
    sr.writeHalfWidthSummaryReportAsMarkDown(out)
    println()

    //output the collected replication data to prove it was captured
    println(repData.toDataFrame().describe())
    println(repData)
    println()

    // use the database to create a Kotlin DataFrame
    val dataFrame = kslDatabaseObserver.db.acrossReplicationStatistics

    println(dataFrame)
    println()
    println(dataFrame.asMarkDownTable())

    model.experimentName = "Three Workers"
    palletWorkCenter.numWorkers = 3
    model.simulate()

    out = model.outputDirectory.createPrintWriter("AcrossExperimentResults.md")
    kslDatabaseObserver.db.writeTableAsMarkdown("ACROSS_REP_VIEW", out)

//    println("Exporting database to Excel")
//    kslDatabaseObserver.db.exportToExcel()
//    derby.db.exportToExcel()

    val mcb = expDataCollector.multipleComparisonAnalyzerFor(
        listOf("Two Workers", "Three Workers"), palletWorkCenter.totalProcessingTime.name)

    println(mcb)
}