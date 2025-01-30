package ksl.examples.general.lectures.week8

import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.observers.ExperimentDataCollector
import ksl.simulation.Model
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.statistic.MultipleComparisonAnalyzer

fun main() {
    // mcbDemo()
    howToCreateMCBAnalyzer()
}

fun mcbDemo() {
    val data = mutableMapOf<String, DoubleArray>()
    val d1 = doubleArrayOf(63.72, 32.24, 40.28, 36.94, 36.29, 56.94, 34.10, 63.36, 49.29, 87.20)
    val d2 = doubleArrayOf(63.06, 31.78, 40.32, 37.71, 36.79, 57.93, 33.39, 62.92, 47.67, 80.79)
    val d3 = doubleArrayOf(57.74, 29.65, 36.52, 35.71, 33.81, 51.54, 31.39, 57.24, 42.63, 67.27)
    val d4 = doubleArrayOf(62.63, 31.56, 39.87, 37.35, 36.65, 57.15, 33.30, 62.21, 47.46, 79.60)
    data["One"] = d1
    data["Two"] = d2
    data["Three"] = d3
    data["Four"] = d4
    val mca = MultipleComparisonAnalyzer(data, "ExampleData")

    mca.writeDataAsCSVFile(KSL.createPrintWriter("MCA_Results.csv"))
    println(mca)
    println("num data sets: " + mca.numberDatasets)
    println(mca.dataNames.joinToString())

    val r = mca.secondStageSampleSizeNM(2.0, 0.95)
    println("Second stage sampling recommendation R = $r")
    // All the data and results can be saved in a database
    val db = mca.resultsAsDatabase("TestMCBResults")
    // they can be exported to Excel
    db.exportToExcel()
}

fun howToCreateMCBAnalyzer() {
    val model = Model("Pallet Processing", autoCSVReports = true)
    model.numberOfReplications = 10
    model.experimentName = "Two Workers"
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model)
    // demonstrate capture of replication data for specific response variables
    val expDataCollector = ExperimentDataCollector(model)
    // demonstrate capturing data to database with an observer
    val kslDatabaseObserver = KSLDatabaseObserver(model)

    // simulate the model
    model.simulate()
    model.experimentName = "Three Workers"
    palletWorkCenter.numWorkers = 3
    model.simulate()

    // can use ExperimentDataCollector
    val expNames = listOf("Two Workers", "Three Workers")
    val responseName = palletWorkCenter.totalProcessingTime.name
    val mcb = expDataCollector.multipleComparisonAnalyzerFor(
        expNames, responseName
    )
    println("********** Results via ExperimentDataCollector")
    println(mcb)

    // can use KSLDatabaseObserver
    val db = kslDatabaseObserver.db
    val comparisonAnalyzer = db.multipleComparisonAnalyzerFor(
        expNames, responseName
    )
    println("********** Results via KSLDatabase")
    println(comparisonAnalyzer)
}