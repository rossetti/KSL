package ksl.examples.general.misc

import ksl.modeling.entity.Conveyor
import ksl.utilities.distributions.Weibull
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.*
import ksl.utilities.statistic.CachedHistogram
import ksl.utilities.statistic.Statistic
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.builtins.DoubleArraySerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import ksl.utilities.KSLArrays
import ksl.utilities.math.KSLMath

fun main(){
//    val twos = IntArray(10){ (2).ipow(it+3).toInt() }
//    println(twos.joinToString())
//    testPearson()
//    testWeibull()

//    val example = "House     Of The Dragon"
//    val withOutSpaces = example.replace("\\p{Zs}+".toRegex(), "_")
//    println(withOutSpaces)
 //   val lt1 = ConstantRV(10.0)
//    val lt2 = ShiftedGeometricRV(probOfSuccess = 0.2, streamNum = 1)
//    simulateDemandDuringLeadTime(1000, lt2)

//    testBoxMuller()
//    serializing()
    testMRound()
}

fun testMRound(){
    val x = 3.0459
    val g = 0.25
    val r = KSLMath.mround(x, g)
    println("x=$x g=$g r=$r")
}

fun serializing(){
    val array = doubleArrayOf(1.0, 5.0, -3.0)

    val Json = Json { prettyPrint = true }
    val encoding = Json.encodeToString(DoubleArraySerializer(),  array)
    println(encoding)

    val ct = Conveyor.Type.ACCUMULATING
    val ctEncoded = Json.encodeToString(serializer<Conveyor.Type>(), ct)
    println(ctEncoded)

    val list = listOf(67.0, 33.0, 111.0)
    val listEncoded = Json.encodeToString(serializer<List<Double>>(),  list)
    println(listEncoded)
}

fun testBoxMuller(n: Int = 1000){
    val u = UniformRV(streamNum = 1)
//    val h = CachedHistogram()
    val data = mutableListOf<Double>()
    for (i in 1..n) {
        val u1 = u.value
        val u2 = u.value
        val x1 = cos(2.0*PI*u2)*sqrt(-2.0*ln(u1))
//        h.collect(x1)
        data.add(x1)
    }
//    println(h)
//    h.currentHistogram.histogramPlot().showInBrowser()
    val pdf = PDFModeler(data.toDoubleArray())
    val results = pdf.showAllResultsInBrowser()
    pdf.showAllGoodnessOfFitSummariesInBrowser(results)
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

fun leadTimeDemand(leadTime: RVariableIfc, demand: RVariableIfc) : Double {
    var sum = 0.0
    val T = leadTime.value.toInt()
    for (i in 1..T){
        sum = sum + demand.value
    }
    return sum
}

fun simulateDemandDuringLeadTime(sampleSize: Int, leadTime: RVariableIfc){
    val stat = Statistic("LTD Stats")
    val probStat = Statistic("LTD >= 10")
    val d = DEmpiricalRV(doubleArrayOf(4.0, 5.0, 6.0, 7.0, 8.0),
        doubleArrayOf(0.1, 0.4, 0.75, 0.85, 1.0), 2)
    for(i in 1..sampleSize){
        val ltd = leadTimeDemand(leadTime, d)
        stat.collect(ltd)
        probStat.collect(ltd >= 65.0)
    }
    val r = StatisticReporter(mutableListOf(stat, probStat))
    println(r.halfWidthSummaryReport())
}