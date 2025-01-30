package ksl.examples.general.lectures.week3

import ksl.utilities.io.KSL
import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

fun main() {
    val statList = mutableListOf<StatisticIfc>()
    for (q in 30..60 step 5) {
        val stat = airCraftStockingExample(q.toDouble(), sampleSize = 220)
        statList.add(stat)
    }
    val sr = StatisticReporter(listOfStats = statList)
    println(sr.halfWidthSummaryReport())
    val pw = KSL.createPrintWriter("Aircraft Stocking Results.md")
    pw.println(sr.halfWidthSummaryReportAsMarkDown())
}

fun airCraftStockingExample(q: Double = 30.0, sampleSize: Int = 100): StatisticIfc {
    val h = 1000.0 // refurbishing cost
    val b = 9000.0 // backorder cost
    val demandRV = GammaRV(shape = 6.599770961, scale = 4.265299533)
    val stat = Statistic("Cost for q = $q")
    for (i in 1..sampleSize){
        val d = demandRV.value
        val g = h* maxOf(0.0,q-d) + b* maxOf(0.0, d-q)
        stat.collect(g)
    }
    //    println(stat)
//    print(String.format("%s \t %f %n", "Count = ", stat.count))
//    print(String.format("%s \t %f %n", "Average = ", stat.average))
//    print(String.format("%s \t %f %n", "Std. Dev. = ", stat.standardDeviation))
//    print(String.format("%s \t %f %n", "Half-width = ", stat.halfWidth))
//    println((stat.confidenceLevel * 100).toString() + "% CI = " + stat.confidenceInterval)
    return stat
}