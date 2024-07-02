package ksl.examples.general.misc

import ksl.utilities.distributions.Weibull
import ksl.utilities.random.rvariable.PearsonType5RV
import ksl.utilities.random.rvariable.WeibullRV
import ksl.utilities.statistic.CachedHistogram
import org.jetbrains.letsPlot.commons.intern.math.ipow
import org.jetbrains.letsPlot.commons.testing.assertContentEquals

fun main(){
//    val twos = IntArray(10){ (2).ipow(it+3).toInt() }
//    println(twos.joinToString())
//    testPearson()
//    testWeibull()

    val example = "House     Of The Dragon"
    val withOutSpaces = example.replace("\\p{Zs}+".toRegex(), "_")
    println(withOutSpaces)

}

fun chunky(){
    val r = 1..40
    val chunks: List<List<Int>> = r.chunked(39)
    println(chunks)

    println(1..39)
}

fun testPearson(){
    val rv = PearsonType5RV(shape=3.0, scale=8.0)
    val data = rv.sample(2000)
    val ch = CachedHistogram(data)
    val plot = ch.histogramPlot()
    plot.showInBrowser()
}

fun testWeibull(){
    val rv = WeibullRV(shape=3.0, scale=8.0)
    val data = rv.sample(2000)
    val  shape1 = Weibull.initialShapeEstimate(data)
    println("1 shape = $shape1")
    val  shape2 = Weibull.approximateInitialShapeEstimate(data)
    println("2 shape = $shape2")
//    val ch = CachedHistogram(data)
//    val plot = ch.histogramPlot()
//    plot.showInBrowser()

}