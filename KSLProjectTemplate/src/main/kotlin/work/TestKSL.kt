package work;

import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.Statistic


/**
 * This example illustrates how to create instances of the Statistic
 * class and collect statistics on observations.  In addition, the basic
 * use of the StatisticReporter class is illustrated to show how to pretty
 * print the statistical results.
 */
fun main() {
    // create a normal mean = 20.0, variance = 4.0 random variable
    val n = NormalRV(20.0, 4.0)
    // create a Statistic to observe the values
    val stat = Statistic("Normal Stats")
    val pGT20 = Statistic("P(X>=20")
    // generate 1000 values
    val data = DoubleArray(1000)
    for (i in 1..1000) {
        // getValue() method returns generated values
        val x = n.value
        stat.collect(x)
        pGT20.collect(x >= 20.0)
        data[i-1] = x
    }
    println(stat)
    println(pGT20)
    val reporter = StatisticReporter(mutableListOf(stat, pGT20))
    println(reporter.halfWidthSummaryReport())
    val h1 = Histogram.create(data)
    val plot  = h1.histogramPlot()
    plot.showInBrowser()


}