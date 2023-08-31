package ksl.utilities.io.plotting

import ksl.utilities.Interval
import ksl.utilities.random.rvariable.BivariateNormalRV
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.Statistic


class PlotTesting {
}

fun main(){
//    testScatterPlot()
//    testBoxPlot()
//    testMultiBoxPlot()
    testConfidenceIntervalPlots()
//    testFrequencyPlot()
}

fun testScatterPlot(){
    val bvn = BivariateNormalRV(0.0, 1.0, 0.0, 1.0, 0.8)
    val sample = Array(100){DoubleArray(2)}

    val data = bvn.sampleByColumn(1000)

    val plot = ScatterPlot(data[0], data[1])

    plot.showInBrowser()

    plot.saveToFile("ScatterPlotTest", plotTitle = "This is a test of scatter plot")

    plot.showInBrowser(plotTitle = "This is a test of scatter plot")
}

fun testBoxPlot(){
    val x = doubleArrayOf(
        9.57386907765005,
        12.2683505035727,
        9.57737208532118,
        9.46483590382401,
        10.7426270820019,
        13.6417539779286,
        14.4009905460358,
        11.9644504015896,
        6.26967756749078,
        11.6697189446463,
        8.05817835081046,
        9.15420225990855,
        12.6661856696446,
        5.55898016788418,
        11.5509859097328,
        8.09378382643764,
        10.2800698254101,
        11.8820042371248,
        6.83122972495244,
        7.76415517242856,
        8.07037124078289,
        10.1936926483873,
        6.6056340897386,
        8.67523311054818,
        10.2860106642238,
        7.18655355368101,
        13.7326532837148,
        10.8384432167312,
        11.20127362594,
        9.10597298849603,
        13.1143167471166,
        11.461547274424,
        12.8686686397317,
        11.6123823346184,
        11.1766595994422,
        9.96640484955756,
        7.60884520541602,
        10.4027823841526,
        13.6119110527044,
        10.1927388924956,
        11.0479192016999,
        10.8335646086984,
        11.3464245020951,
        11.7370035652721,
        7.86882502350181,
        10.1677674083453,
        7.19107507247878,
        10.3219440236855,
        11.8751033160937,
        12.0507178860171,
        10.2452271541559,
        12.3574170333615,
        8.61783541196255,
        10.8759327855332,
        10.8965790925989,
        9.78508632755152,
        9.57354838522572,
        10.668697248695,
        10.4413115727436,
        11.7056055258128,
        10.6836383463882,
        9.00275936849233,
        11.1546020461964,
        11.5327569604436,
        12.6632213399552,
        9.04144921258077,
        8.34070478790018,
        8.90537066541892,
        8.9538251666728,
        10.6587406131769,
        9.46657058183544,
        11.9067728468743,
        7.31151723229678,
        10.3473820074211,
        8.51409684117935,
        15.061683701397,
        7.67016173387284,
        9.63463245914518,
        11.9544975062154,
        8.75291180980926,
        10.5902626954236,
        10.7290328701981,
        11.6103046633603,
        9.18588529341066,
        11.7832770526927,
        11.5803842329369,
        8.77282669099311,
        11.1605258465085,
        9.87370336332192,
        11.0792461569289,
        12.1457106152585,
        8.16900025019337,
        12.0963212801111,
        10.7943060404262,
        10.6648080893662,
        10.7821384837463,
        9.20756684199006,
        13.0421837951471,
        8.50476579169282,
        7.7653569673433
    )
    val boxPlotSummary = BoxPlotSummary(x, "Some Data")

    val plot = BoxPlot(boxPlotSummary)
    plot.showInBrowser()
    plot.saveToFile("The boxplot")

    println(boxPlotSummary)
}

fun testMultiBoxPlot(){
    val n = NormalRV()
    val m = mutableMapOf<String, BoxPlotSummary>()
    for (i in 1..5){
        val bps = BoxPlotSummary(n.sample(200), "BPS$i")
        m[bps.name] = bps
    }
    val plot = MultiBoxPlot(m)
    plot.showInBrowser()
    plot.saveToFile("The boxplots")
}

fun testConfidenceIntervalPlots(){
    val n = NormalRV()
    val m = mutableMapOf<String, Interval>()
    for (i in 1..5){
        val s = Statistic(n.sample(200))
        m[s.name] = s.confidenceInterval
    }
    val plot = ConfidenceIntervalsPlot(m, referencePoint = 0.0)
    plot.showInBrowser()
    plot.saveToFile("The CI Plots")
}

fun testFrequencyPlot(){
    val freq = IntegerFrequency()
    val rv = DEmpiricalRV(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.2, 0.7, 1.0))
    for (i in 1..10000) {
        freq.collect(rv.value)
    }

    val fPlot = FrequencyPlot(freq)
    fPlot.showInBrowser()
    fPlot.saveToFile("The Frequency Plot")

    val pPlot = FrequencyPlot(freq, proportions = true)
    pPlot.showInBrowser()
    pPlot.saveToFile("The Proportion Plot")

    println(freq)
}