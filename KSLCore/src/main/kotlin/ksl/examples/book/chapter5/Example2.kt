package ksl.examples.book.chapter5

import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.Statistic

fun main() {
    val rv = NormalRV(10.0, 4.0)
    val estimateX = Statistic("Estimated X")
    val estOfProb = Statistic("Pr(X>8)")
    val r = StatisticReporter(mutableListOf(estOfProb, estimateX))
    val n = 20 // sample size
    for (i in 1..n) {
        val x = rv.value
        estimateX.collect(x)
        estOfProb.collect(x > 8)
    }
    println(r.halfWidthSummaryReport())
}
