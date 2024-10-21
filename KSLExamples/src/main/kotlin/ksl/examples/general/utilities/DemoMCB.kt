package ksl.examples.general.utilities

import ksl.utilities.io.KSL
import ksl.utilities.statistic.MultipleComparisonAnalyzer

fun main() {
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