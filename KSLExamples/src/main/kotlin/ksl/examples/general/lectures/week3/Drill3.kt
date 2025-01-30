package ksl.examples.general.lectures.week3

import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.GeometricRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import org.jetbrains.letsPlot.core.spec.asMutable

fun main(){
    val statList = mutableListOf<StatisticIfc>()
    for (i in 200..325 step 5){
        val stat = simulateProfit(i.toDouble(), 1000)
        statList.addAll(stat)
    }
    val sr = StatisticReporter(listOfStats = statList)
    println(sr.halfWidthSummaryReport())
    println()
    val stats = simulateProfit(315.0, 1000)
    val s = stats[1]
    println(s)
    println()
    val ss = s.estimateSampleSize(6000.0)
    println("The recommended sample size is $ss")
    val stats2 = simulateProfit(315.0, ss.toInt())
    println()
    val sr1 = StatisticReporter(listOfStats = stats2.asMutable())
    println(sr1.halfWidthSummaryReport())
}

fun simulateProfit(unitPrice: Double, sampleSize: Int) : List<Statistic> {
    val mu = 4.0
    val p = 1.0 / (mu + 1)
    val salesRV = GeometricRV(p, 1)
    val unitCostRV = DEmpiricalRV(
        doubleArrayOf(80.0, 90.0, 100.0, 110.0),
        doubleArrayOf(0.2, 0.6, 0.9, 1.0), 2
    )
    val xRV = TriangularRV(-1.0, 0.0, 1.0, 3)
    val profitStat = Statistic("Profit for unit price = $unitPrice")
    val profitProb = Statistic("Profit > 150K for unit price = $unitPrice")
    for (i in 1..sampleSize) {
        val fixedCost = 65000.0 + 10000.0 * xRV.value
        val sales = salesRV.value * 1000.0
        val profit = (unitPrice - unitCostRV.value) * sales - fixedCost
        profitStat.collect(profit)
        profitProb.collect(profit > 150000.0)
    }
    return listOf(profitProb, profitStat)
}